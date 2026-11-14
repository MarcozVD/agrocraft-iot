"""Detección de anomalías por patrón diario.

Cada sensor tiene un ciclo de 24 h (temperatura, radiación, nivel del tanque, CPU del PC...). Para cada
variable vigilada se compara el promedio de los últimos 30 min con lo que esa misma variable hizo a la
misma hora en los días anteriores:

    z = (actual - media_misma_hora) / max(desviación_misma_hora, piso)

|z| >= 3 durante dos revisiones seguidas abre una anomalía; |z| < 2 la cierra (histéresis). El piso
evita falsas alarmas en variables casi constantes. Hacen falta al menos 3 días de historia para esa
hora. Cada apertura y cierre se publica como evento en agro/platform/anomalias/event (queda en
InfluxDB, Grafana y el informe con IA) y se avisa por Telegram/Discord.

Además de la desviación estadística se detecta el sensor "pegado": varianza cero durante 2 h en una
variable que normalmente cambia.
"""

import asyncio
import json
import logging
import math
import os
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import aiohttp

from report import TZ, _flux

log = logging.getLogger("anomalias")
STATE_FILE = Path(os.environ.get("STATE_DIR", "/state")) / "anomalies.json"
EVERY_S = 600
Z_OPEN, Z_CLOSE = 3.0, 2.0
MIN_DAYS = 3

# (dispositivo, variable, piso de la desviación, unidad)
WATCHED = [
    ("api-clima", "temp_c", 1.0, "°C"),
    ("api-clima", "hum_pct", 4.0, "%"),
    ("api-clima", "radiation_wm2", 60.0, "W/m²"),
    ("api-aire", "pm2_5", 3.0, "µg/m³"),
    ("mc-invernadero", "temp_c", 1.0, "°C"),
    ("mc-parcela-a", "soil_pct", 4.0, "%"),
    ("mc-parcela-b", "soil_pct", 4.0, "%"),
    ("mc-tanque", "level_pct", 5.0, "%"),
    ("mc-corral", "thi", 1.5, ""),
    ("mc-corral", "welfare_pct", 4.0, "%"),
    ("ds-energia", "power_kw", 0.3, "kW"),
    ("gw-pc-01", "cpu_pct", 8.0, "%"),
    ("gw-pc-01", "ram_pct", 4.0, "%"),
    ("mc-server", "tps", 0.5, ""),
]


def _key(device, field):
    return f"{device}/{field}"


class Anomalies:
    def __init__(self, hub, notifier):
        self.hub = hub
        self.notifier = notifier
        self.open: dict[str, dict] = {}      # clave -> anomalía abierta
        self.pending: dict[str, int] = {}    # clave -> revisiones seguidas fuera de rango
        self.last_check: dict[str, dict] = {}
        try:
            saved = json.loads(STATE_FILE.read_text())
            self.open = saved.get("open", {})
        except (OSError, ValueError):
            pass

    def _save(self):
        STATE_FILE.write_text(json.dumps({"open": self.open}, ensure_ascii=False))

    def describe(self) -> dict:
        return {"open": list(self.open.values()), "last_check": self.last_check}

    async def run(self):
        await asyncio.sleep(90)              # deja arrancar al bróker y a InfluxDB
        while True:
            try:
                await self.check()
            except Exception as error:      # nunca tumba el servicio
                log.warning("revisión fallida: %r", error)
            await asyncio.sleep(EVERY_S)

    async def _baseline(self, http):
        """Media y desviación por hora del día de los días anteriores (sin las últimas 2 h)."""
        hour = datetime.now(TZ).hour
        rows = await _flux(http, f'''from(bucket: "telemetry")
  |> range(start: -8d, stop: -2h)
  |> filter(fn: (r) => r._measurement == "telemetry")
  |> filter(fn: (r) => {self._filter()})
  |> aggregateWindow(every: 1h, fn: mean, createEmpty: false, timeSrc: "_start")''')
        values = defaultdict(list)
        for row in rows:
            start = datetime.fromisoformat(row["_time"].replace("Z", "+00:00")).astimezone(TZ)
            if start.hour == hour:
                values[_key(row["device"], row["_field"])].append(float(row["_value"]))
        return values

    def _filter(self):
        return " or ".join(f'(r.device == "{d}" and r._field == "{f}")' for d, f, _, _ in WATCHED)

    async def _current(self, http, start, fn):
        rows = await _flux(http, f'''from(bucket: "telemetry")
  |> range(start: {start})
  |> filter(fn: (r) => r._measurement == "telemetry")
  |> filter(fn: (r) => {self._filter()})
  |> group(columns: ["device", "_field"])
  |> {fn}()''')
        return {_key(row["device"], row["_field"]): float(row["_value"]) for row in rows}

    async def check(self):
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=90)) as http:
            base = await self._baseline(http)
            now = await self._current(http, "-30m", "mean")
            spread = await self._current(http, "-2h", "stddev")
            counts = await self._current(http, "-2h", "count")
        stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
        for device, field, floor, unit in WATCHED:
            key = _key(device, field)
            history = base.get(key, [])
            if key not in now or len(history) < MIN_DAYS:
                self.last_check[key] = {"estado": "sin historia suficiente", "dias": len(history)}
                continue
            mean = sum(history) / len(history)
            std = math.sqrt(sum((v - mean) ** 2 for v in history) / (len(history) - 1))
            z = (now[key] - mean) / max(std, floor)
            stuck = counts.get(key, 0) >= 30 and spread.get(key, 1) == 0 and std > floor
            self.last_check[key] = {"actual": round(now[key], 2), "esperado": round(mean, 2),
                                    "desv": round(std, 2), "z": round(z, 2), "dias": len(history), "ts": stamp}
            if key in self.open:
                if abs(z) < Z_CLOSE and not stuck:
                    await self._close(key, now[key], mean, z, unit)
                continue
            if abs(z) >= Z_OPEN or stuck:
                self.pending[key] = self.pending.get(key, 0) + 1
                if self.pending[key] >= 2:
                    await self._open(key, device, field, now[key], mean, std, z, unit, stuck)
            else:
                self.pending.pop(key, None)

    async def _open(self, key, device, field, value, mean, std, z, unit, stuck):
        kind = "sensor_pegado" if stuck else ("alta" if z > 0 else "baja")
        item = {"device": device, "variable": field, "tipo": kind, "valor": round(value, 2),
                "esperado": round(mean, 2), "desv": round(std, 2), "z": round(z, 2),
                "desde": datetime.now(TZ).isoformat(timespec="minutes")}
        self.open[key] = item
        self.pending.pop(key, None)
        self._save()
        self.hub.platform_event("anomalias", {"type": "anomaly_open", **item})
        detail = ("sin variación en 2 h (¿sensor pegado?)" if stuck else
                  f"{value:.1f} {unit} frente a {mean:.1f} {unit} esperado a esta hora (z = {z:+.1f})")
        log.info("anomalía %s: %s", key, detail)
        await self.notifier.send(f"🔎 Anomalía en {device} · {field}: {detail}")

    async def _close(self, key, value, mean, z, unit):
        item = self.open.pop(key)
        self._save()
        self.hub.platform_event("anomalias", {"type": "anomaly_close", "device": item["device"],
                                              "variable": item["variable"], "valor": round(value, 2),
                                              "esperado": round(mean, 2), "z": round(z, 2), "desde": item["desde"]})
        log.info("anomalía %s cerrada (z = %.1f)", key, z)
        await self.notifier.send(f"✅ {item['device']} · {item['variable']} volvió a lo normal "
                                 f"({value:.1f} {unit}, esperado {mean:.1f} {unit})")
