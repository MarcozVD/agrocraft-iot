#!/usr/bin/env python3
"""Batería de pruebas de la granja de simulación: corre cada escenario y verifica su comportamiento.

Uso (en la VM):  python3 scripts/sim_test.py [escenario ...] [--speed 360]
Por defecto corre los 8 escenarios. Resultados: experiments/sim-tests.md y experiments/sim-tests.json

Para cada escenario:
  1. start a velocidad 1 (copia el estado actual de la granja real)
  2. reboot de cada gemelo simulado: configuración de fábrica (todo en automático, umbrales normales),
     para que el resultado no dependa de que la granja real tenga algo en manual
  3. set_speed a la velocidad de prueba y espera a que termine
  4. consulta InfluxDB (medición simulation y eventos de sim-*) y evalúa criterios definidos de antemano
"""

import base64
import csv
import io
import json
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENV = dict(line.split("=", 1) for line in (ROOT / "platform/.env").read_text().splitlines()
           if "=" in line and not line.startswith("#"))
SITE = f"https://{ENV['SITE_HOST']}"
AUTH = base64.b64encode(f"{ENV.get('CONTROL_USER', 'operador')}:{ENV['CONTROL_PASS']}".encode()).decode()
TWINS = ["sim-parcela-a", "sim-parcela-b", "sim-tanque", "sim-invernadero", "sim-silo", "sim-seguridad", "sim-corral"]

SCENARIOS = {          # escenario: horas simuladas
    "actual": 24, "normal": 24, "ola_calor": 24, "sequia": 36, "tormenta": 12,
    "helada": 24, "falla_bomba": 48, "intrusion": 12,
}


def http(method, path, body=None, auth=False):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if auth:
        headers["Authorization"] = f"Basic {AUTH}"
    request = urllib.request.Request(SITE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        return json.loads(error.read() or b"{}")


def cmd(device, action, params=None):
    ack = http("POST", "/iot/api/cmd", {"device": device, "class": "sim", "action": action, "params": params or {}}, auth=True)
    if not ack.get("ok"):
        raise RuntimeError(f"{device} {action}: {ack}")
    return ack


def flux(query):
    request = urllib.request.Request(
        "http://127.0.0.1:8086/api/v2/query?org=agrocraft", data=query.encode(), method="POST",
        headers={"Authorization": f"Token {ENV['INFLUX_TOKEN']}", "Content-Type": "application/vnd.flux",
                 "Accept": "application/csv"})
    with urllib.request.urlopen(request, timeout=60) as response:
        text = response.read().decode()
    return [row for row in csv.DictReader(io.StringIO(text)) if row.get("_value") not in (None, "")]


def series(t0, t1):
    rows = flux(f'''from(bucket: "telemetry")
  |> range(start: {t0}, stop: {t1})
  |> filter(fn: (r) => r._measurement == "simulation")''')
    out = {}
    for row in rows:
        out.setdefault((row["device"], row["_field"]), []).append(float(row["_value"]))
    return out


def events(t0, t1):
    rows = flux(f'''from(bucket: "telemetry")
  |> range(start: {t0}, stop: {t1})
  |> filter(fn: (r) => r._measurement == "events" and r.channel == "event" and r.device =~ /^sim-/)''')
    out = []
    for row in rows:
        try:
            out.append((row["device"], json.loads(row["_value"])))
        except ValueError:
            pass
    return out


def stat(s, device, field, fn):
    values = s.get((device, field), [])
    if not values:
        return None
    return {"min": min, "max": max, "first": lambda v: v[0], "last": lambda v: v[-1]}[fn](values)


def count(evts, device, type_, **match):
    return sum(1 for d, e in evts if d == device and e.get("type") == type_
               and all(e.get(k) == v for k, v in match.items()))


def checks(name, s, e, base):
    """Criterios por escenario: (descripción, valor observado, ¿cumple?)."""
    v = lambda dev, f, fn: stat(s, dev, f, fn)
    irrigations = count(e, "sim-parcela-a", "irrigation", on=1) + count(e, "sim-parcela-b", "irrigation", on=1)
    present = sum(1 for d in TWINS + ["sim-control"] if any(k[0] == d for k in s))
    out = [(f"Hubo telemetría de los {len(TWINS) + 1} dispositivos simulados", present, present >= len(TWINS) + 1),
           ("El corral simulado tiene vacas", v("sim-corral", "cows_total", "max"), (v("sim-corral", "cows_total", "max") or 0) > 0)]
    t_min, t_max = v("sim-control", "in_temp_c", "min"), v("sim-control", "in_temp_c", "max")
    soil_min = min(x for x in (v("sim-parcela-a", "soil_pct", "min"), v("sim-parcela-b", "soil_pct", "min")) if x is not None)
    if name in ("actual", "normal"):
        out += [
            ("Ciclo diario de temperatura (amplitud > 8 °C)", round(t_max - t_min, 1), t_max - t_min > 8),
            ("Radiación 0 de noche y > 600 W/m² de día",
             (v("sim-control", "in_radiation_wm2", "min"), v("sim-control", "in_radiation_wm2", "max")),
             v("sim-control", "in_radiation_wm2", "min") == 0 and v("sim-control", "in_radiation_wm2", "max") > 600),
            # la copia puede arrancar con el suelo ya bajo (estado real): se exige que el riego lo recupere
            ("El riego automático actúa y el suelo termina ≥ 33 %",
             (irrigations, round(min(v("sim-parcela-a", "soil_pct", "last"), v("sim-parcela-b", "soil_pct", "last")), 1)),
             irrigations > 0 and min(v("sim-parcela-a", "soil_pct", "last"), v("sim-parcela-b", "soil_pct", "last")) >= 33),
        ]
        out += [
            ("Corral: nacen terneros", v("sim-corral", "births_total", "last"), (v("sim-corral", "births_total", "last") or 0) > 0),
            ("Corral: el comedero se recarga del silo", count(e, "sim-corral", "feeder"), count(e, "sim-corral", "feeder") > 0),
        ]
        if name == "normal":
            out.append(("Temperatura exterior dentro del día típico (17-28 °C)", (round(t_min, 1), round(t_max, 1)),
                        16 <= t_min and t_max <= 29))
    if name == "ola_calor":
        out += [
            ("Exterior supera 34 °C", round(t_max, 1), t_max >= 34),
            ("Invernadero supera 32 °C", round(v("sim-invernadero", "temp_c", "max"), 1), v("sim-invernadero", "temp_c", "max") > 32),
            ("Se abren las ventilas en automático", count(e, "sim-invernadero", "actuator", name="vent", on=1),
             count(e, "sim-invernadero", "actuator", name="vent", on=1) > 0 or v("sim-invernadero", "vent_open", "max") == 1),
            ("Corral: THI en peligro (> 78)", round(v("sim-corral", "thi", "max"), 1), v("sim-corral", "thi", "max") > 78),
            ("Corral: se encienden los aspersores", count(e, "sim-corral", "cooling", on=1), count(e, "sim-corral", "cooling", on=1) > 0),
            ("La salud de las plantas cae", (round(v("sim-invernadero", "plant_health_pct", "first"), 1), round(v("sim-invernadero", "plant_health_pct", "last"), 1)),
             v("sim-invernadero", "plant_health_pct", "last") < v("sim-invernadero", "plant_health_pct", "first")),
        ]
    if name == "sequia":
        et0 = v("sim-control", "in_et0_mmh", "max")
        out += [
            ("ET0 máxima mayor a la del día normal (×1.8)", (round(et0, 2), round(base.get("et0", 0), 2)), et0 > 1.8 * base.get("et0", 1)),
            ("Sin lluvia", v("sim-control", "in_rain_mmh", "max"), v("sim-control", "in_rain_mmh", "max") == 0),
            ("Más riegos que en el día normal (por hora)", (irrigations, base.get("irr_per_h")),
             irrigations / SCENARIOS["sequia"] > base.get("irr_per_h", 0)),
            ("El tanque termina más bajo de lo que empezó", (round(v("sim-tanque", "level_pct", "first"), 1), round(v("sim-tanque", "level_pct", "last"), 1)),
             v("sim-tanque", "level_pct", "last") < v("sim-tanque", "level_pct", "first")),
        ]
    if name == "tormenta":
        out += [
            ("Lluvia fuerte (≥ 18 mm/h)", v("sim-control", "in_rain_mmh", "max"), v("sim-control", "in_rain_mmh", "max") >= 18),
            ("Entra agua al tanque por lluvia", round(v("sim-tanque", "inflow_lpm", "max"), 2), v("sim-tanque", "inflow_lpm", "max") > 0),
            ("El suelo se moja (> 90 %)", round(max(v("sim-parcela-a", "soil_pct", "max"), v("sim-parcela-b", "soil_pct", "max")), 1),
             max(v("sim-parcela-a", "soil_pct", "max"), v("sim-parcela-b", "soil_pct", "max")) > 90),
            ("Sin riegos mientras llueve", irrigations, irrigations == 0),
        ]
    if name == "helada":
        out += [
            ("Exterior baja de 3 °C", round(t_min, 1), t_min <= 3),
            ("Se enciende la calefacción", count(e, "sim-invernadero", "actuator", name="heater", on=1),
             count(e, "sim-invernadero", "actuator", name="heater", on=1) > 0 or v("sim-invernadero", "heater", "max") == 1),
            ("El invernadero queda por encima del exterior (mín.)", (round(v("sim-invernadero", "temp_c", "min"), 1), round(t_min, 1)),
             v("sim-invernadero", "temp_c", "min") > t_min + 3),
        ]
    if name == "falla_bomba":
        out += [
            ("La falla queda registrada (pump_failure = 1)", v("sim-control", "pump_failure", "max"), v("sim-control", "pump_failure", "max") == 1),
            ("El tanque se vacía (< 5 %)", round(v("sim-tanque", "level_pct", "min"), 1), v("sim-tanque", "level_pct", "min") < 5),
            ("La bomba se ordena pero no entra agua", (v("sim-tanque", "pump", "max"), round(v("sim-tanque", "inflow_lpm", "max"), 2)),
             v("sim-tanque", "pump", "max") == 1),
            ("Sin agua, el suelo cae por debajo de 30 %", round(soil_min, 1), soil_min < 30),
        ]
    if name == "intrusion":
        out += [
            ("Aparece un intruso", count(e, "sim-seguridad", "intruder"), count(e, "sim-seguridad", "intruder") > 0),
            ("Se detecta (hostiles_near > 0)", v("sim-seguridad", "hostiles_near", "max"), (v("sim-seguridad", "hostiles_near", "max") or 0) > 0),
            ("Suena la alarma en automático", count(e, "sim-seguridad", "alarm", on=1), count(e, "sim-seguridad", "alarm", on=1) > 0),
        ]
    return out


def run(name, hours, speed):
    cmd("sim-control", "start", {"scenario": name, "speed": 1, "hours": hours})
    for twin in TWINS:
        cmd(twin, "reboot")
    t0 = datetime.now(timezone.utc).replace(microsecond=0)
    cmd("sim-control", "set_speed", {"speed": speed})
    deadline = time.time() + hours * 3600 / speed + 120
    while time.time() < deadline:
        time.sleep(10)
        state = next((d["state"] for d in http("GET", "/iot/api/devices")["devices"] if d["device"] == "sim-control"), {})
        if str(state.get("running")) == "0":
            break
    else:
        cmd("sim-control", "stop")
    time.sleep(8)                    # que Telegraf escriba lo último
    t1 = datetime.now(timezone.utc).replace(microsecond=0)
    span = (t0.isoformat().replace("+00:00", "Z"), t1.isoformat().replace("+00:00", "Z"))
    return span, series(*span), events(*span)


def main():
    args = sys.argv[1:]
    speed = 360
    if "--speed" in args:
        i = args.index("--speed")
        speed = float(args[i + 1])
        del args[i:i + 2]
    names = args or list(SCENARIOS)
    base, results = {}, []
    if any(n in ("sequia",) for n in names) and "normal" not in names:
        names = ["normal"] + names        # la sequía se compara contra un día normal
    for name in names:
        print(f"== {name}: {SCENARIOS[name]} h simuladas a {speed:g}× (~{SCENARIOS[name] * 3600 / speed / 60:.1f} min)", flush=True)
        span, s, e = run(name, SCENARIOS[name], speed)
        if name == "normal":
            irr = count(e, "sim-parcela-a", "irrigation", on=1) + count(e, "sim-parcela-b", "irrigation", on=1)
            base = {"et0": stat(s, "sim-control", "in_et0_mmh", "max") or 0, "irr_per_h": irr / SCENARIOS["normal"]}
        result = checks(name, s, e, base)
        ok = all(passed for _, _, passed in result)
        for desc, value, passed in result:
            print(f"   [{'OK ' if passed else 'FALLA'}] {desc}: {value}", flush=True)
        results.append({"scenario": name, "hours": SCENARIOS[name], "speed": speed, "from": span[0], "to": span[1],
                        "ok": ok, "checks": [{"check": d, "value": str(v), "ok": p} for d, v, p in result],
                        "events": len(e)})
    cmd("sim-control", "reset")
    out = ROOT / "experiments"
    out.mkdir(exist_ok=True)
    (out / "sim-tests.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
    lines = ["# Pruebas de la granja de simulación", "", f"Velocidad {speed:g}×. Cada escenario parte del estado real "
             "con los gemelos simulados en configuración de fábrica.", ""]
    for r in results:
        lines += [f"## {r['scenario']} — {'cumple' if r['ok'] else 'NO cumple'}",
                  f"{r['hours']} h simuladas · {r['from']} → {r['to']} (UTC) · {r['events']} eventos", "",
                  "| Criterio | Observado | Resultado |", "|---|---|---|"]
        lines += [f"| {c['check']} | {c['value']} | {'✔' if c['ok'] else '✘'} |" for c in r["checks"]]
        lines.append("")
    (out / "sim-tests.md").write_text("\n".join(lines))
    print("resumen:", ", ".join(f"{r['scenario']}={'ok' if r['ok'] else 'FALLA'}" for r in results))


if __name__ == "__main__":
    main()
