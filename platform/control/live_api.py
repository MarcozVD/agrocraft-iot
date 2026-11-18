"""API de solo lectura para el gemelo 3D (/iot/gemelo/).

  GET /iot/api/stream                  Server-Sent Events: foto inicial de la flota y luego cada mensaje
                                       del bróker (telemetry, state, status, event, ack) en vivo
  GET /iot/api/latest                  último valor guardado de cada variable (real: 6 h; simulación: 30 d)
  GET /iot/api/history/<device>        series de un dispositivo (?hours=6, agregadas para ~150 puntos)
  GET /iot/api/series                  varias series a la vez (?keys=dev/campo,dev/campo&minutes=60)
  GET /iot/api/replay                  estado de los gemelos y del clima para reproducir (?hours=24)
  GET /iot/api/events                  últimos eventos, comandos, alertas y anomalías (?hours=24)
  GET /iot/api/reports                 informes del analista con IA (lista)
  GET /iot/api/reports/<name>          un informe: análisis en Markdown y anomalías

Todo es lectura: los comandos siguen pasando por POST /iot/api/cmd con usuario de operador.
"""

import asyncio
import json
import logging
import re
from collections import defaultdict
from datetime import datetime

import aiohttp
from aiohttp import web

import report

log = logging.getLogger("gemelo")
DEVICE_RE = re.compile(r"^[a-z0-9][a-z0-9-]{1,40}$")
FIELD_RE = re.compile(r"^[a-z][a-z0-9_]{0,40}$")
CLIENT_QUEUE = 400
KEEPALIVE_S = 15

# Variables que mueven la escena 3D al reproducir el pasado (dispositivo -> campos)
REPLAY = {
    "mc-parcela-a": ["soil_pct", "growth_pct", "irrigation", "lamp", "light_lvl"],
    "mc-parcela-b": ["soil_pct", "growth_pct", "irrigation", "lamp", "light_lvl"],
    "mc-tanque": ["level_pct", "pump", "inflow_lpm", "outflow_lpm"],
    "mc-invernadero": ["temp_c", "hum_pct", "vent_open", "heater", "lamp", "plant_health_pct"],
    "mc-seguridad": ["alarm", "hostiles_near", "players_in_zone", "gate_open"],
    "mc-corral": ["cows_adult", "cows_baby", "cooling", "thi", "welfare_pct", "feed_pct", "water_pct"],
    "mc-estacion": ["raining", "thundering", "time_ticks", "daylight_lvl"],
    "mc-silo": ["fill_pct", "items"],
    "api-clima": ["temp_c", "hum_pct", "rain_mm", "radiation_wm2", "wind_kmh", "cloud_pct", "is_day"],
}


class Broadcaster:
    """Reparte cada mensaje del Hub a los navegadores conectados por SSE."""

    def __init__(self):
        self.clients: set[asyncio.Queue] = set()

    def on_message(self, cls, device, channel, data) -> None:
        if not self.clients:
            return
        text = json.dumps({"class": cls, "device": device, "channel": channel, "data": data,
                           "at": int(datetime.now().timestamp() * 1000)}, ensure_ascii=False)
        for queue in list(self.clients):
            if queue.qsize() < CLIENT_QUEUE:
                queue.put_nowait(text)


async def stream(request: web.Request):
    hub = request.app["hub"]
    caster: Broadcaster = request.app["broadcaster"]
    response = web.StreamResponse(headers={
        "Content-Type": "text/event-stream", "Cache-Control": "no-cache, no-transform",
        "X-Accel-Buffering": "no", "Connection": "keep-alive"})
    await response.prepare(request)
    queue: asyncio.Queue = asyncio.Queue()
    caster.clients.add(queue)
    try:
        snapshot = json.dumps({"devices": hub.snapshot(), "rules": request.app["rules"].describe()["rules"]},
                              ensure_ascii=False)
        await response.write(f"retry: 3000\nevent: snapshot\ndata: {snapshot}\n\n".encode())
        while True:
            try:
                text = await asyncio.wait_for(queue.get(), KEEPALIVE_S)
                await response.write(f"data: {text}\n\n".encode())
            except asyncio.TimeoutError:
                await response.write(b": ping\n\n")
    except (ConnectionResetError, asyncio.CancelledError, RuntimeError):
        pass
    finally:
        caster.clients.discard(queue)
    return response


def _hours(request: web.Request, default: int, top: int) -> int:
    try:
        return max(1, min(int(request.query.get("hours", default)), top))
    except ValueError:
        return default


async def _query(query: str) -> list:
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=60)) as http:
        return await report._flux(http, query)


def _ms(stamp: str) -> int:
    return int(datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp() * 1000)


async def latest(request: web.Request):
    rows = await _query('''from(bucket: "telemetry")
  |> range(start: -6h)
  |> filter(fn: (r) => r._measurement == "telemetry" and r._field != "seq")
  |> last()''')
    rows += await _query('''from(bucket: "telemetry")
  |> range(start: -30d)
  |> filter(fn: (r) => r._measurement == "simulation" and r._field != "seq")
  |> last()''')
    out = defaultdict(lambda: {"telemetry": {}, "ts": 0})
    for row in rows:
        entry = out[row["device"]]
        entry["telemetry"][row["_field"]] = round(float(row["_value"]), 4)
        entry["ts"] = max(entry["ts"], _ms(row["_time"]))
    return web.json_response({"devices": out})


async def history(request: web.Request):
    device = request.match_info["device"]
    if not DEVICE_RE.match(device):
        raise web.HTTPBadRequest(text="dispositivo inválido")
    hours = _hours(request, 6, 168)
    every = max(1, round(hours * 60 / 150))
    measurement = "simulation" if device.startswith("sim-") else "telemetry"
    rows = await _query(f'''from(bucket: "telemetry")
  |> range(start: -{hours}h)
  |> filter(fn: (r) => r._measurement == "{measurement}" and r.device == "{device}" and r._field != "seq")
  |> aggregateWindow(every: {every}m, fn: mean, createEmpty: false)''')
    series = defaultdict(list)
    for row in rows:
        series[row["_field"]].append([_ms(row["_time"]), round(float(row["_value"]), 3)])
    return web.json_response({"device": device, "hours": hours, "every_min": every, "series": series})


async def series(request: web.Request):
    keys = [k for k in request.query.get("keys", "").split(",") if k][:48]
    pairs = []
    for key in keys:
        device, _, field = key.partition("/")
        if not DEVICE_RE.match(device) or not FIELD_RE.match(field):
            raise web.HTTPBadRequest(text=f"serie inválida: {key}")
        pairs.append((device, field))
    if not pairs:
        return web.json_response({"series": {}})
    try:
        minutes = max(5, min(int(request.query.get("minutes", "60")), 8 * 24 * 60))
    except ValueError:
        minutes = 60
    every = max(10, round(minutes * 60 / 240))
    real = [(d, f) for d, f in pairs if not d.startswith("sim-")]
    sim = [(d, f) for d, f in pairs if d.startswith("sim-")]
    out = defaultdict(list)
    for measurement, group in (("telemetry", real), ("simulation", sim)):
        if not group:
            continue
        cond = " or ".join(f'(r.device == "{d}" and r._field == "{f}")' for d, f in group)
        rows = await _query(f'''from(bucket: "telemetry")
  |> range(start: -{minutes}m)
  |> filter(fn: (r) => r._measurement == "{measurement}")
  |> filter(fn: (r) => {cond})
  |> aggregateWindow(every: {every}s, fn: mean, createEmpty: false)''')
        for row in rows:
            out[f'{row["device"]}/{row["_field"]}'].append([_ms(row["_time"]), round(float(row["_value"]), 3)])
    return web.json_response({"minutes": minutes, "every_s": every, "series": out})


async def replay(request: web.Request):
    hours = _hours(request, 24, 72)
    every = 5 if hours <= 24 else 15
    pairs = " or ".join(f'(r.device == "{d}" and r._field == "{f}")' for d, fields in REPLAY.items() for f in fields)
    rows = await _query(f'''from(bucket: "telemetry")
  |> range(start: -{hours}h)
  |> filter(fn: (r) => r._measurement == "telemetry")
  |> filter(fn: (r) => {pairs})
  |> aggregateWindow(every: {every}m, fn: last, createEmpty: false)''')
    series = defaultdict(lambda: defaultdict(list))
    for row in rows:
        series[row["device"]][row["_field"]].append([_ms(row["_time"]), round(float(row["_value"]), 3)])
    return web.json_response({"hours": hours, "every_min": every, "series": series})


async def events(request: web.Request):
    hours = _hours(request, 24, 168)
    rows = await _query(f'''from(bucket: "telemetry")
  |> range(start: -{hours}h)
  |> filter(fn: (r) => r._measurement == "events" and r.class != "sim")
  |> filter(fn: (r) => r.channel == "event" or r.channel == "cmd")
  |> group()
  |> sort(columns: ["_time"], desc: true)
  |> limit(n: 300)''')
    out = []
    for row in rows:
        try:
            data = json.loads(row["_value"])
        except ValueError:
            continue
        out.append({"t": _ms(row["_time"]), "device": row.get("device"), "class": row.get("class"),
                    "channel": row.get("channel"), "data": data})
    return web.json_response({"hours": hours, "events": out})


async def reports(request: web.Request):
    items = []
    for name in report.list_reports():
        try:
            data = json.loads((report.REPORT_DIR / f"{name}.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        analysis = data.get("analisis") or ""
        summary = analysis.split("## 2")[0]
        summary = " ".join(line for line in summary.splitlines() if line and not line.startswith("#"))
        items.append({"name": name, "model": data.get("modelo"), "generated": data.get("generado"),
                      "hours": data.get("ventana_horas"), "summary": summary[:400],
                      "anomalies": len(data.get("anomalias") or []), "alerts": len(data.get("alertas") or [])})
    return web.json_response({"reports": items})


async def report_detail(request: web.Request):
    name = request.match_info["name"]
    if name not in report.list_reports():
        raise web.HTTPNotFound()
    try:
        data = json.loads((report.REPORT_DIR / f"{name}.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        data = {}
    analysis = data.get("analisis")
    if analysis is None:
        text = (report.REPORT_DIR / f"{name}.md").read_text(encoding="utf-8")
        analysis = text.split("## Análisis", 1)[-1].split("\n## Estadísticas", 1)[0]
    return web.json_response({"name": name, "model": data.get("modelo"), "generated": data.get("generado"),
                              "analysis": analysis, "anomalies": data.get("anomalias") or [],
                              "alerts": data.get("alertas") or [], "commands": data.get("comandos") or [],
                              "forecast": data.get("pronostico") or {}})


def routes() -> list:
    return [
        web.get("/iot/api/stream", stream),
        web.get("/iot/api/latest", latest),
        web.get("/iot/api/history/{device}", history),
        web.get("/iot/api/series", series),
        web.get("/iot/api/replay", replay),
        web.get("/iot/api/events", events),
        web.get("/iot/api/reports", reports),
        web.get("/iot/api/reports/{name}", report_detail),
    ]
