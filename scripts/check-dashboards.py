#!/usr/bin/env python3
"""Ejecuta cada consulta de los dashboards contra Grafana y reporta errores y series devueltas.

Uso (en la VM):  python3 scripts/check-dashboards.py [rango, por defecto now-6h]
Lee la clave de administrador de platform/.env. Solo hace lecturas.
"""

import base64
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENV = dict(line.split("=", 1) for line in (ROOT / "platform/.env").read_text().splitlines()
           if "=" in line and not line.startswith("#"))
URL = f"https://{ENV['SITE_HOST']}/api/ds/query"
AUTH = base64.b64encode(f"admin:{ENV['GRAFANA_ADMIN_PASS']}".encode()).decode()
RANGE = sys.argv[1] if len(sys.argv) > 1 else "now-6h"
VARS = {"${device}": "mc-invernadero", "${fn}": "mean"}


def run(query: str) -> dict:
    for key, value in VARS.items():
        query = query.replace(key, value)
    body = json.dumps({"from": RANGE, "to": "now", "queries": [{
        "refId": "A", "datasource": {"type": "influxdb", "uid": "influx-agro"},
        "query": query, "intervalMs": 60000, "maxDataPoints": 500}]}).encode()
    request = urllib.request.Request(URL, data=body, method="POST", headers={
        "Content-Type": "application/json", "Authorization": f"Basic {AUTH}"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)["results"]["A"]
    except urllib.error.HTTPError as error:
        return json.loads(error.read() or b"{}").get("results", {}).get("A", {"error": str(error)})


def describe(result: dict) -> str:
    if result.get("error"):
        return "ERROR " + result["error"].splitlines()[0][:160]
    frames = result.get("frames", [])
    if not frames:
        return "sin datos"
    names, rows = [], 0
    for frame in frames:
        fields = frame["schema"].get("fields", [])
        if not fields:
            continue
        value = fields[-1]
        names.append(value.get("config", {}).get("displayNameFromDS") or value.get("name", "?"))
        values = frame.get("data", {}).get("values", [])
        rows += len(values[0]) if values else 0
    if not names:
        return "sin datos"
    return f"{len(frames)} serie(s), {rows} filas: {', '.join(names[:4])}"


failures = 0
for path in sorted((ROOT / "platform/grafana/dashboards").glob("*.json")):
    board = json.loads(path.read_text(encoding="utf-8"))
    print(f"== {board['title']}")
    for panel in board["panels"]:
        for target in panel.get("targets", []):
            outcome = describe(run(target["query"]))
            failures += outcome.startswith("ERROR")
            print(f"  [{target['refId']}] {panel['title'][:55]:55} {outcome}")
    for anno in board["annotations"]["list"]:
        if "target" in anno:
            outcome = describe(run(anno["target"]["query"]))
            failures += outcome.startswith("ERROR")
            print(f"  [anotación] {anno['name']:48} {outcome}")
    for var in board["templating"]["list"]:
        if var["type"] == "query":
            print(f"  [variable] {var['name']:49} {describe(run(var['query']))}")
print(f"\nconsultas con error: {failures}")
sys.exit(1 if failures else 0)
