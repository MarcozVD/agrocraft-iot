#!/usr/bin/env python3
"""Exporta de InfluxDB los datos de la ventana del informe (se ejecuta en la VM, solo biblioteca estándar).

Uso:  python3 docs/informe/export_data.py <inicio ISO UTC> <fin ISO UTC> <carpeta salida>
Ej.:  python3 docs/informe/export_data.py 2026-11-11T06:05:00Z 2026-11-17T06:05:00Z /tmp/informe-datos

Salida:
  series.csv   device,class,field,time,value   promedio cada 10 min de cada variable (medición telemetry)
  stats.json   {device: {field: {mean,min,max,last,count}}}   sobre la ventana completa
  events.json  comandos, acks, eventos por tipo y alertas
  gaps.json    {device: [[desde, hasta, minutos], ...]}  huecos mayores a 3 intervalos
"""

import csv
import io
import json
import sys
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENV = dict(line.split("=", 1) for line in (ROOT / "platform/.env").read_text().splitlines()
           if "=" in line and not line.startswith("#"))
START, STOP, OUT = sys.argv[1], sys.argv[2], Path(sys.argv[3])
OUT.mkdir(parents=True, exist_ok=True)
INTERVAL = {"mc-silo": 60, "mc-estacion": 60, "api-clima": 300, "api-aire": 600, "api-suelo": 600,
            "api-pronostico": 900, "ds-invernadero": 300, "ds-energia": 60}


def flux(query: str) -> list:
    request = urllib.request.Request(
        "http://127.0.0.1:8086/api/v2/query?org=agrocraft", data=query.encode(), method="POST",
        headers={"Authorization": f"Token {ENV['INFLUX_TOKEN']}", "Content-Type": "application/vnd.flux",
                 "Accept": "application/csv"})
    with urllib.request.urlopen(request, timeout=300) as response:
        text = response.read().decode()
    return [row for row in csv.DictReader(io.StringIO(text)) if row.get("_value") not in (None, "")]


base = f'from(bucket: "telemetry") |> range(start: {START}, stop: {STOP})'

rows = flux(base + ''' |> filter(fn: (r) => r._measurement == "telemetry" and r._field != "seq")
  |> aggregateWindow(every: 10m, fn: mean, createEmpty: false)''')
with open(OUT / "series.csv", "w", newline="") as handle:
    writer = csv.writer(handle)
    writer.writerow(["device", "class", "field", "time", "value"])
    for row in rows:
        writer.writerow([row["device"], row["class"], row["_field"], row["_time"], row["_value"]])
print(f"series.csv: {len(rows)} filas")

stats = defaultdict(lambda: defaultdict(dict))
for fn in ("mean", "min", "max", "last", "count"):
    for row in flux(base + f''' |> filter(fn: (r) => r._measurement == "telemetry")
  |> group(columns: ["class", "device", "_field"]) |> {fn}()'''):
        stats[row["device"]][row["_field"]][fn] = float(row["_value"])
(OUT / "stats.json").write_text(json.dumps(stats, indent=1))
print(f"stats.json: {len(stats)} dispositivos")

gaps = defaultdict(list)
for row in flux(base + ''' |> filter(fn: (r) => r._measurement == "telemetry" and r._field == "seq")
  |> group(columns: ["device"]) |> elapsed(unit: 1s)'''):
    seconds = float(row["elapsed"])
    limit = 3 * INTERVAL.get(row["device"], 30)
    if seconds > limit:
        end = datetime.fromisoformat(row["_time"].replace("Z", "+00:00"))
        gaps[row["device"]].append([end.timestamp() - seconds, end.timestamp(), round(seconds / 60, 1)])
(OUT / "gaps.json").write_text(json.dumps(gaps, indent=1))
print(f"gaps.json: {sum(len(v) for v in gaps.values())} huecos")

events = {"commands": [], "acks": [], "event_types": Counter(), "events": [], "alerts": []}
for row in flux(base + ''' |> filter(fn: (r) => r._measurement == "events" and r.class != "sim"
     and (r.channel == "cmd" or r.channel == "ack" or r.channel == "event"))'''):
    try:
        data = json.loads(row["_value"])
    except ValueError:
        continue
    item = {"time": row["_time"], "device": row["device"], **data}
    if row["channel"] == "cmd":
        events["commands"].append(item)
    elif row["channel"] == "ack":
        events["acks"].append(item)
    elif row["device"] == "alertas":
        events["alerts"].append(item)
    else:
        events["event_types"][f'{row["device"]}:{data.get("type")}'] += 1
        if data.get("type") not in ("irrigation", "pump", "feeder", "cooling"):
            events["events"].append(item)
(OUT / "events.json").write_text(json.dumps(events, indent=1, ensure_ascii=False))
print(f"events.json: {len(events['commands'])} comandos, {len(events['alerts'])} alertas, "
      f"{sum(events['event_types'].values())} eventos")
