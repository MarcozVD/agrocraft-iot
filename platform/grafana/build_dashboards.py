#!/usr/bin/env python3
"""Genera los dashboards de Grafana de AgroCraft como JSON (dashboards como código).

Uso:  python build_dashboards.py        -> escribe platform/grafana/dashboards/*.json
Grafana los carga solo (provisioning) al copiarlos a la VM; no hace falta reiniciarlo.

Todas las consultas son Flux sobre el bucket `telemetry`:
  medición telemetry  etiquetas class, device; un campo numérico por variable
  medición events     etiquetas class, device, channel (cmd|ack|event|state|status); campo value (texto)
`v.windowPeriod` ata la agregación al rango de tiempo elegido en el selector.
"""

import json
from pathlib import Path

OUT = Path(__file__).resolve().parent / "dashboards"
DS = {"type": "influxdb", "uid": "influx-agro"}
BLUE, GREEN, ORANGE, RED, PURPLE, YELLOW, TEAL = (
    "#5794F2", "#73BF69", "#FF9830", "#F2495C", "#B877D9", "#FADE2A", "#64C8C8")

_panel_id = 0


def _next_id() -> int:
    global _panel_id
    _panel_id += 1
    return _panel_id


# ------------------------------------------------------------------------------------- Flux
def flux_series(device: str, fields: list, fn: str = "mean", window: str = "v.windowPeriod") -> str:
    """Una serie por campo de un dispositivo, agregada por ventana. El nombre visible es el campo."""
    cond = " or ".join(f'r._field == "{f}"' for f in fields)
    return (
        'from(bucket: "telemetry")\n'
        "  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n"
        f'  |> filter(fn: (r) => r._measurement == "telemetry" and r.device == "{device}")\n'
        f"  |> filter(fn: (r) => {cond})\n"
        f"  |> aggregateWindow(every: {window}, fn: {fn}, createEmpty: false)\n"
        '  |> keep(columns: ["_time", "_value", "_field", "device"])'
    )


def flux_last(device: str, field: str) -> str:
    return (
        'from(bucket: "telemetry")\n'
        "  |> range(start: -1d)\n"
        f'  |> filter(fn: (r) => r._measurement == "telemetry" and r.device == "{device}" and r._field == "{field}")\n'
        "  |> last()\n"
        '  |> keep(columns: ["_time", "_value", "_field", "device"])'
    )


def flux_events(channels: list, device_filter: str = "true", limit: int = 200) -> str:
    cond = " or ".join(f'r.channel == "{c}"' for c in channels)
    return (
        'from(bucket: "telemetry")\n'
        "  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n"
        f'  |> filter(fn: (r) => r._measurement == "events" and ({cond}) and {device_filter})\n'
        "  |> group()\n"
        '  |> keep(columns: ["_time", "class", "device", "channel", "_value"])\n'
        '  |> sort(columns: ["_time"], desc: true)\n'
        f"  |> limit(n: {limit})\n"
        '  |> rename(columns: {_value: "mensaje"})'
    )


# ----------------------------------------------------------------------------------- paneles
def target(query: str, ref: str = "A") -> dict:
    return {"datasource": DS, "refId": ref, "query": query}


def timeseries(title, grid, queries, unit=None, series=None, desc="", stack=False, step=False,
               min_=None, max_=None, thresholds=None, axis_right=None, legend_calcs=True):
    """queries: lista de consultas Flux. series: {(device, field): (nombre, color, unidad)}"""
    defaults = {
        "custom": {
            "drawStyle": "line",
            "lineInterpolation": "stepAfter" if step else "smooth",
            "lineWidth": 2,
            "fillOpacity": 25 if stack else 8,
            "gradientMode": "opacity",
            "showPoints": "never",
            "spanNulls": 600000,
            "stacking": {"mode": "normal" if stack else "none"},
            "axisBorderShow": False,
        },
        "displayName": "${__field.labels._field} · ${__field.labels.device}",
    }
    if unit:
        defaults["unit"] = unit
    if min_ is not None:
        defaults["min"] = min_
    if max_ is not None:
        defaults["max"] = max_
    if thresholds:
        defaults["thresholds"] = {"mode": "absolute", "steps": thresholds}
        defaults["custom"]["thresholdsStyle"] = {"mode": "dashed"}
    overrides = []
    for (device, field), (name, color, s_unit) in (series or {}).items():
        props = [{"id": "displayName", "value": name}]
        if color:
            props.append({"id": "color", "value": {"mode": "fixed", "fixedColor": color}})
        if s_unit:
            props.append({"id": "unit", "value": s_unit})
        if axis_right and (device, field) in axis_right:
            props.append({"id": "custom.axisPlacement", "value": "right"})
        overrides.append({
            "matcher": {"id": "byFrameRefID", "options": _ref_for(queries, device, field)},
            "properties": props,
        })
    return {
        "id": _next_id(), "type": "timeseries", "title": title, "description": desc,
        "datasource": DS, "gridPos": grid,
        "targets": [target(q, chr(65 + i)) for i, q in enumerate(queries)],
        "fieldConfig": {"defaults": defaults, "overrides": overrides},
        "options": {
            "legend": {"showLegend": True, "displayMode": "table" if legend_calcs else "list",
                       "placement": "bottom",
                       "calcs": ["mean", "max", "min", "lastNotNull"] if legend_calcs else []},
            "tooltip": {"mode": "multi", "sort": "none"},
        },
    }


def _ref_for(queries, device, field):
    for i, q in enumerate(queries):
        if f'r.device == "{device}"' in q and f'r._field == "{field}"' in q:
            return chr(65 + i)
    return "A"


def ts(title, grid, items, unit=None, desc="", **kw):
    """items: lista de (device, field, nombre, color[, unidad]). Una consulta por serie, para que
    cada override (nombre, color, unidad, eje) apunte a su serie por refId."""
    queries, series = [], {}
    for item in items:
        device, field, name, color = item[:4]
        s_unit = item[4] if len(item) > 4 else None
        queries.append(flux_series(device, [field]))
        series[(device, field)] = (name, color, s_unit)
    return timeseries(title, grid, queries, unit=unit, series=series, desc=desc, **kw)


def stat(title, grid, device, field, unit=None, decimals=1, thresholds=None, color_mode="value",
         spark=True, mappings=None, desc=""):
    steps = thresholds or [{"color": "text", "value": None}]
    panel = {
        "id": _next_id(), "type": "stat", "title": title, "description": desc,
        "datasource": DS, "gridPos": grid,
        "targets": [target(flux_series(device, [field], window="v.windowPeriod") if spark else flux_last(device, field))],
        "fieldConfig": {"defaults": {
            "decimals": decimals,
            "thresholds": {"mode": "absolute", "steps": steps},
            "color": {"mode": "thresholds"},
            "displayName": title,
            "mappings": mappings or [],
        }, "overrides": []},
        "options": {
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "colorMode": color_mode, "graphMode": "area" if spark else "none",
            "textMode": "value", "justifyMode": "center", "orientation": "auto",
            "showPercentChange": False,
        },
    }
    if unit:
        panel["fieldConfig"]["defaults"]["unit"] = unit
    return panel


def gauge(title, grid, device, field, unit="percent", min_=0, max_=100, thresholds=None, desc=""):
    return {
        "id": _next_id(), "type": "gauge", "title": title, "description": desc,
        "datasource": DS, "gridPos": grid,
        "targets": [target(flux_last(device, field))],
        "fieldConfig": {"defaults": {
            "unit": unit, "min": min_, "max": max_, "decimals": 1, "displayName": title,
            "thresholds": {"mode": "absolute", "steps": thresholds or [{"color": GREEN, "value": None}]},
            "color": {"mode": "thresholds"},
        }, "overrides": []},
        "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                    "showThresholdLabels": False, "showThresholdMarkers": True},
    }


def state_timeline(title, grid, items, desc=""):
    """items: (device, field, nombre). Valores 0/1 mostrados como Apagado/Encendido."""
    queries, overrides = [], []
    for i, (device, field, name) in enumerate(items):
        q = (
            'from(bucket: "telemetry")\n'
            "  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n"
            f'  |> filter(fn: (r) => r._measurement == "telemetry" and r.device == "{device}" and r._field == "{field}")\n'
            "  |> aggregateWindow(every: v.windowPeriod, fn: max, createEmpty: false)\n"
            '  |> keep(columns: ["_time", "_value", "_field", "device"])'
        )
        queries.append(q)
        overrides.append({"matcher": {"id": "byFrameRefID", "options": chr(65 + i)},
                          "properties": [{"id": "displayName", "value": name}]})
    return {
        "id": _next_id(), "type": "state-timeline", "title": title, "description": desc,
        "datasource": DS, "gridPos": grid,
        "targets": [target(q, chr(65 + i)) for i, q in enumerate(queries)],
        "fieldConfig": {"defaults": {
            "color": {"mode": "thresholds"},
            "thresholds": {"mode": "absolute", "steps": [{"color": "#3a3f47", "value": None},
                                                         {"color": GREEN, "value": 0.5}]},
            "mappings": [{"type": "value", "options": {
                "0": {"text": "Apagado", "color": "#3a3f47", "index": 0},
                "1": {"text": "Encendido", "color": GREEN, "index": 1}}}],
            "custom": {"fillOpacity": 80, "lineWidth": 0},
        }, "overrides": overrides},
        "options": {"showValue": "never", "mergeValues": True, "rowHeight": 0.8,
                    "legend": {"showLegend": False}, "alignValue": "center"},
    }


def table(title, grid, query, desc="", overrides=None, sort=None):
    panel = {
        "id": _next_id(), "type": "table", "title": title, "description": desc,
        "datasource": DS, "gridPos": grid, "targets": [target(query)],
        "fieldConfig": {"defaults": {"custom": {"align": "auto", "cellOptions": {"type": "auto"},
                                                "inspect": True}},
                        "overrides": overrides or []},
        "options": {"showHeader": True, "cellHeight": "sm", "footer": {"show": False}},
    }
    if sort:
        panel["options"]["sortBy"] = sort
    return panel


def text(title, grid, content):
    return {"id": _next_id(), "type": "text", "title": title, "gridPos": grid,
            "options": {"mode": "markdown", "content": content}}


def row(title, y):
    return {"id": _next_id(), "type": "row", "title": title, "collapsed": False,
            "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []}


def g(x, y, w, h):
    return {"x": x, "y": y, "w": w, "h": h}


# ------------------------------------------------------------------------------- anotaciones
def annotations(device_regex: str = ".*") -> dict:
    """Comandos aceptados y eventos de los dispositivos, como marcas verticales en las gráficas."""
    def anno(name, channel, color, enabled=True):
        return {
            "name": name, "datasource": DS, "enable": enabled, "iconColor": color,
            "target": {"refId": "Anno", "query": (
                'from(bucket: "telemetry")\n'
                "  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n"
                f'  |> filter(fn: (r) => r._measurement == "events" and r.channel == "{channel}")\n'
                f'  |> filter(fn: (r) => r.device =~ /{device_regex}/)\n'
                "  |> group()\n"
                '  |> map(fn: (r) => ({_time: r._time, text: r.device + ": " + r._value, tags: r.device}))'
            )},
        }
    return {"list": [
        {"builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"}, "enable": True,
         "hide": True, "iconColor": "rgba(0, 211, 255, 1)", "name": "Annotations & Alerts",
         "type": "dashboard"},
        anno("Comandos (ack)", "ack", ORANGE),
        anno("Eventos", "event", PURPLE),
    ]}


def dashboard(uid, title, panels, tags, desc="", time_from="now-24h", refresh="1m",
              templating=None, device_regex=".*"):
    return {
        "uid": uid, "title": title, "description": desc, "tags": ["agrocraft", *tags],
        "timezone": "America/Bogota", "editable": True, "graphTooltip": 1,
        "schemaVersion": 41, "version": 1, "refresh": refresh,
        "time": {"from": time_from, "to": "now"},
        "timepicker": {"refresh_intervals": ["30s", "1m", "5m", "15m", "1h"]},
        "annotations": annotations(device_regex),
        "templating": {"list": templating or []},
        "links": [{"title": "AgroCraft", "type": "dashboards", "tags": ["agrocraft"],
                   "asDropdown": True, "includeVars": False, "keepTime": True},
                  {"title": "Panel de control (retroceso)", "type": "link", "url": "/iot/",
                   "icon": "bolt", "targetBlank": True},
                  {"title": "Informes IA", "type": "link", "url": "/iot/reportes/", "icon": "doc",
                   "targetBlank": True},
                  {"title": "Mapa del mundo", "type": "link",
                   "url": "/map/?world=minecraft_overworld&zoom=5&x=12&z=0", "icon": "external link",
                   "targetBlank": True}],
        "panels": panels,
    }


# ------------------------------------------------------------------------------ dashboards
DEVICES = [
    ("twin", "mc-parcela-a"), ("twin", "mc-parcela-b"), ("twin", "mc-tanque"),
    ("twin", "mc-invernadero"), ("twin", "mc-silo"), ("twin", "mc-seguridad"),
    ("twin", "mc-estacion"), ("twin", "mc-server"), ("twin", "mc-corral"),
    ("api", "api-clima"), ("api", "api-aire"), ("api", "api-suelo"), ("api", "api-pronostico"),
    ("dataset", "ds-invernadero"), ("dataset", "ds-energia"),
    ("real", "gw-pc-01"), ("real", "phone-01"), ("real", "esp32-01"),
]

INTERVALS = {"mc-silo": 60, "mc-corral": 30, "mc-estacion": 60, "api-clima": 300, "api-aire": 600, "api-suelo": 600, "api-pronostico": 900,
             "ds-invernadero": 300, "ds-energia": 60}


def fleet_table_query() -> str:
    """Dispositivos esperados unidos con su última muestra: los que nunca enviaron datos también aparecen."""
    rows = ",\n".join(
        f'  {{clase: "{cls}", device: "{dev}", intervalo: {INTERVALS.get(dev, 30)}}}' for cls, dev in DEVICES)
    return f"""import "array"
import "join"
devices = array.from(rows: [
{rows}
])
last = from(bucket: "telemetry")
  |> range(start: -7d)
  |> filter(fn: (r) => r._measurement == "telemetry" and r._field == "seq")
  |> group(columns: ["device"])
  |> last()
  |> group()
  |> map(fn: (r) => ({{device: r.device, ultima: r._time, muestras: r._value,
                      hace_s: (uint(v: now()) - uint(v: r._time)) / uint(v: 1000000000)}}))
join.left(
  left: devices,
  right: last,
  on: (l, r) => l.device == r.device,
  as: (l, r) => ({{clase: l.clase, dispositivo: l.device, ultima: r.ultima, hace_s: r.hace_s,
                  muestras: r.muestras,
                  estado: if not exists r.hace_s then "sin datos"
                          else if int(v: r.hace_s) > 3 * l.intervalo then "fuera de línea"
                          else "en línea"}}),
)
  |> keep(columns: ["clase", "dispositivo", "estado", "ultima", "hace_s", "muestras"])"""


def by_name(name: str, *properties) -> dict:
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": key, "value": value} for key, value in properties]}


STATE_COLORS = [
    by_name("estado",
            ("custom.cellOptions", {"type": "color-background", "mode": "basic"}),
            ("mappings", [{"type": "value", "options": {
                "en línea": {"color": GREEN, "index": 0},
                "fuera de línea": {"color": RED, "index": 1},
                "sin datos": {"color": "#3a3f47", "index": 2}}}])),
    by_name("hace_s", ("unit", "s"), ("displayName", "hace")),
    by_name("ultima", ("unit", "dateTimeAsLocal"), ("displayName", "última muestra")),
    by_name("muestras", ("decimals", 0)),
]


EVENT_COLUMNS = [
    by_name("_time", ("displayName", "hora"), ("unit", "dateTimeAsLocal"), ("custom.width", 170)),
    by_name("channel", ("displayName", "canal"), ("custom.width", 70)),
    by_name("class", ("displayName", "clase"), ("custom.width", 75)),
    by_name("device", ("displayName", "dispositivo"), ("custom.width", 135)),
]

def resumen():
    y = 0
    p = [text("AgroCraft", g(0, y, 8, 13), (
        "### Gemelo digital de una granja en Minecraft\n"
        "Los bloques del mundo son **sensores y actuadores IoT**. El clima real de Bucaramanga "
        "(Open-Meteo) gobierna lluvia, hora, secado del suelo y temperatura del gemelo.\n\n"
        "**Protocolos:** MQTT 3.1.1 (TLS 8883 · WSS 443) · HTTPS REST\n\n"
        "**Clases:** Real · Digital Twin · API · DataSet\n\n"
        "**Marcas en las gráficas:** naranja = comando de retroceso aceptado; "
        "morado = evento del dispositivo (riego, bomba, cosecha, intruso, alarma).\n\n"
        "Detalle por dispositivo con promedio, máximo y mínimo: menú *AgroCraft* → *Por dispositivo*.\n\n"
        "[Panel de control](/iot/) · [Mapa del mundo](/map/?world=minecraft_overworld&zoom=5&x=12&z=0)"))]
    p.append(table("Estado de la flota", g(8, y, 16, 13), fleet_table_query(),
                   desc="Un dispositivo está fuera de línea si no envía datos en 3 intervalos.",
                   overrides=STATE_COLORS))
    p[-1]["transformations"] = [{"id": "organize", "options": {"indexByName": {
        "clase": 0, "dispositivo": 1, "estado": 2, "hace_s": 3, "ultima": 4, "muestras": 5}}}]
    y += 13
    p += [
        stat("Exterior (real)", g(0, y, 4, 4), "api-clima", "temp_c", "celsius"),
        stat("Invernadero (gemelo)", g(4, y, 4, 4), "mc-invernadero", "temp_c", "celsius",
             thresholds=[{"color": BLUE, "value": None}, {"color": GREEN, "value": 16},
                         {"color": ORANGE, "value": 30}, {"color": RED, "value": 32}]),
        stat("Suelo parcela A", g(8, y, 4, 4), "mc-parcela-a", "soil_pct", "percent",
             thresholds=[{"color": RED, "value": None}, {"color": ORANGE, "value": 30},
                         {"color": GREEN, "value": 35}, {"color": BLUE, "value": 80}]),
        stat("Suelo parcela B", g(12, y, 4, 4), "mc-parcela-b", "soil_pct", "percent",
             thresholds=[{"color": RED, "value": None}, {"color": ORANGE, "value": 30},
                         {"color": GREEN, "value": 35}, {"color": BLUE, "value": 80}]),
        stat("Tanque", g(16, y, 4, 4), "mc-tanque", "level_pct", "percent",
             thresholds=[{"color": RED, "value": None}, {"color": ORANGE, "value": 20},
                         {"color": GREEN, "value": 30}]),
        stat("PM2.5 exterior", g(20, y, 4, 4), "api-aire", "pm2_5", "conμgm3",
             thresholds=[{"color": GREEN, "value": None}, {"color": YELLOW, "value": 12},
                         {"color": ORANGE, "value": 37}, {"color": RED, "value": 55}]),
    ]
    y += 4
    p.append(ts("Temperatura: exterior real, gemelo e invernadero de referencia", g(0, y, 12, 9), [
        ("api-clima", "temp_c", "Exterior · Bucaramanga (API)", ORANGE),
        ("mc-invernadero", "temp_c", "Invernadero · gemelo Minecraft", RED),
        ("ds-invernadero", "temp_c", "Invernadero · Wageningen (dataset)", PURPLE),
    ], unit="celsius"))
    p.append(ts("Agua: suelo de las parcelas y nivel del tanque", g(12, y, 12, 9), [
        ("mc-parcela-a", "soil_pct", "Suelo parcela A", GREEN),
        ("mc-parcela-b", "soil_pct", "Suelo parcela B", YELLOW),
        ("mc-tanque", "level_pct", "Nivel del tanque", BLUE),
    ], unit="percent", min_=0, max_=100,
        thresholds=[{"color": "transparent", "value": None}, {"color": RED, "value": 35}]))
    y += 9
    p.append(state_timeline("Actuadores", g(0, y, 24, 7), [
        ("mc-parcela-a", "irrigation", "Riego A"), ("mc-parcela-b", "irrigation", "Riego B"),
        ("mc-tanque", "pump", "Bomba"), ("mc-invernadero", "vent_open", "Ventilas"),
        ("mc-invernadero", "heater", "Calefacción"), ("mc-seguridad", "alarm", "Alarma"),
        ("mc-estacion", "raining", "Lluvia en el mundo"),
    ]))
    return dashboard("agro-resumen", "AgroCraft · Resumen", p, ["resumen"],
                     desc="Vista general de la granja y de la flota de 16 dispositivos.")


def cultivo():
    y = 0
    p = [
        gauge("Suelo A", g(0, y, 4, 6), "mc-parcela-a", "soil_pct",
              thresholds=[{"color": RED, "value": None}, {"color": GREEN, "value": 35}, {"color": BLUE, "value": 80}]),
        gauge("Suelo B", g(4, y, 4, 6), "mc-parcela-b", "soil_pct",
              thresholds=[{"color": RED, "value": None}, {"color": GREEN, "value": 35}, {"color": BLUE, "value": 80}]),
        gauge("Cultivo A", g(8, y, 4, 6), "mc-parcela-a", "growth_pct",
              thresholds=[{"color": YELLOW, "value": None}, {"color": GREEN, "value": 85}]),
        gauge("Cultivo B", g(12, y, 4, 6), "mc-parcela-b", "growth_pct",
              thresholds=[{"color": YELLOW, "value": None}, {"color": GREEN, "value": 85}]),
        gauge("Tanque", g(16, y, 4, 6), "mc-tanque", "level_pct",
              thresholds=[{"color": RED, "value": None}, {"color": ORANGE, "value": 20}, {"color": BLUE, "value": 30}]),
        gauge("Silo", g(20, y, 4, 6), "mc-silo", "fill_pct",
              thresholds=[{"color": GREEN, "value": None}, {"color": ORANGE, "value": 80}]),
    ]
    y += 6
    p.append(ts("Humedad del suelo", g(0, y, 12, 10), [
        ("mc-parcela-a", "soil_pct", "Parcela A · trigo", GREEN),
        ("mc-parcela-b", "soil_pct", "Parcela B · zanahoria y papa", YELLOW),
    ], unit="percent", min_=0, max_=100,
        thresholds=[{"color": "transparent", "value": None}, {"color": RED, "value": 35},
                    {"color": BLUE, "value": 65}],
        desc="Líneas punteadas: umbrales de riego automático (35 % enciende, 65 % apaga)."))
    p.append(ts("Desarrollo del cultivo y cosechas", g(12, y, 12, 10), [
        ("mc-parcela-a", "growth_pct", "Crecimiento A", GREEN, "percent"),
        ("mc-parcela-b", "growth_pct", "Crecimiento B", YELLOW, "percent"),
        ("mc-parcela-a", "harvests", "Cosechas A", BLUE, "none"),
        ("mc-parcela-b", "harvests", "Cosechas B", PURPLE, "none"),
    ], axis_right={("mc-parcela-a", "harvests"), ("mc-parcela-b", "harvests")}))
    y += 10
    p.append(ts("Tanque: nivel y caudales", g(0, y, 12, 9), [
        ("mc-tanque", "level_pct", "Nivel", BLUE, "percent"),
        ("mc-tanque", "outflow_lpm", "Salida a riego", ORANGE, "L/min"),
        ("mc-tanque", "inflow_lpm", "Entrada (bomba + lluvia)", TEAL, "L/min"),
    ], axis_right={("mc-tanque", "outflow_lpm"), ("mc-tanque", "inflow_lpm")}))
    p.append(ts("Clima real que gobierna el riego", g(12, y, 12, 9), [
        ("api-suelo", "et0_mm", "Evapotranspiración ET0 (mm/15 min)", ORANGE, "suffix: mm"),
        ("api-clima", "precip_mm", "Precipitación (mm/15 min)", BLUE, "suffix: mm"),
        ("api-suelo", "soil_moist_m3m3", "Humedad de suelo real (m³/m³)", GREEN, "none"),
    ], axis_right={("api-suelo", "soil_moist_m3m3")},
        desc="ET0 seca las parcelas del gemelo; la lluvia las moja y recarga el tanque."))
    y += 9
    p.append(state_timeline("Riego y bomba", g(0, y, 12, 5), [
        ("mc-parcela-a", "irrigation", "Riego A"), ("mc-parcela-b", "irrigation", "Riego B"),
        ("mc-tanque", "pump", "Bomba"),
    ]))
    p.append(ts("Silo de cosecha", g(12, y, 12, 5), [
        ("mc-silo", "fill_pct", "Llenado", GREEN, "percent"),
        ("mc-silo", "items", "Unidades", YELLOW, "none"),
    ], axis_right={("mc-silo", "items")}, legend_calcs=False))
    y += 5
    p += [
        stat("Próximo riego A (h)", g(0, y, 4, 4), "api-pronostico", "hours_to_irrigation_a", "h", decimals=0,
             desc="Horas hasta que la parcela A baje de 35 %, proyectando su suelo con la previsión de ET0 y lluvia."),
        stat("Próximo riego B (h)", g(4, y, 4, 4), "api-pronostico", "hours_to_irrigation_b", "h", decimals=0),
        stat("Agua para riego, 24 h", g(8, y, 4, 4), "api-pronostico", "water_need_24h_l", "litre", decimals=0),
        stat("Lluvia prevista, 24 h", g(12, y, 4, 4), "api-pronostico", "rain_next24_mm", "suffix: mm", decimals=1),
        stat("Máx. prevista, 24 h", g(16, y, 4, 4), "api-pronostico", "tmax_next24_c", "celsius", decimals=1),
        stat("Horas de estrés del rebaño", g(20, y, 4, 4), "api-pronostico", "thi_stress_hours_24h", "h", decimals=0,
             thresholds=[{"color": GREEN, "value": None}, {"color": ORANGE, "value": 1}, {"color": RED, "value": 6}]),
    ]
    return dashboard("agro-cultivo", "AgroCraft · Cultivo y riego", p, ["cultivo"],
                     device_regex="mc-parcela.*|mc-tanque|mc-silo")


def clima():
    y = 0
    p = [ts("Temperatura: exterior real vs gemelo vs invernadero real", g(0, y, 12, 9), [
        ("api-clima", "temp_c", "Exterior · API", ORANGE),
        ("mc-invernadero", "temp_c", "Gemelo Minecraft", RED),
        ("ds-invernadero", "temp_c", "Wageningen · dataset", PURPLE),
    ], unit="celsius",
        thresholds=[{"color": "transparent", "value": None}, {"color": RED, "value": 32}]),
        ts("Humedad relativa", g(12, y, 12, 9), [
            ("api-clima", "hum_pct", "Exterior · API", ORANGE),
            ("mc-invernadero", "hum_pct", "Gemelo Minecraft", RED),
            ("ds-invernadero", "hum_pct", "Wageningen · dataset", PURPLE),
        ], unit="percent")]
    y += 9
    p += [ts("Radiación solar real y luz en el mundo", g(0, y, 12, 8), [
        ("api-clima", "radiation_wm2", "Radiación · API", YELLOW, "W/m²"),
        ("mc-estacion", "daylight_lvl", "Luz del cielo · Minecraft (0-15)", BLUE, "none"),
        ("ds-invernadero", "par_umol", "PAR · Wageningen (µmol/m²s)", GREEN, "none"),
    ], axis_right={("mc-estacion", "daylight_lvl")}),
        state_timeline("Clima del invernadero gemelo", g(12, y, 12, 8), [
            ("mc-invernadero", "vent_open", "Ventilas"), ("mc-invernadero", "heater", "Calefacción"),
            ("mc-invernadero", "lamp", "Lámparas"), ("mc-estacion", "raining", "Lluvia"),
            ("mc-estacion", "thundering", "Tormenta"),
        ])]
    y += 8
    p += [ts("Calidad del aire exterior", g(0, y, 12, 8), [
        ("api-aire", "pm2_5", "PM2.5", ORANGE), ("api-aire", "pm10", "PM10", YELLOW),
        ("api-aire", "o3", "O₃", BLUE), ("api-aire", "no2", "NO₂", PURPLE),
    ], unit="conμgm3"),
        ts("Viento, presión y nubosidad", g(12, y, 12, 8), [
            ("api-clima", "wind_kmh", "Viento (km/h)", TEAL, "velocitykmh"),
            ("api-clima", "cloud_pct", "Nubosidad", BLUE, "percent"),
            ("api-clima", "pressure_hpa", "Presión", PURPLE, "pressurehpa"),
        ], axis_right={("api-clima", "pressure_hpa")})]
    y += 8
    p += [ts("Invernadero de referencia: CO₂ y ventilación (dataset)", g(0, y, 12, 8), [
        ("ds-invernadero", "co2_ppm", "CO₂", GREEN, "ppm"),
        ("ds-invernadero", "vent_pct", "Apertura de ventilas", BLUE, "percent"),
    ], axis_right={("ds-invernadero", "vent_pct")}),
        ts("Energía de la finca (dataset)", g(12, y, 12, 8), [
            ("ds-energia", "power_kw", "Potencia activa", ORANGE, "kwatt"),
            ("ds-energia", "voltage_v", "Tensión", BLUE, "volt"),
        ], axis_right={("ds-energia", "voltage_v")})]
    return dashboard("agro-clima", "AgroCraft · Clima, invernadero y energía", p, ["clima"],
                     device_regex="mc-invernadero|mc-estacion|api-.*|ds-.*")


def operacion():
    y = 0
    p = [ts("Gemelo: rendimiento del servidor", g(0, y, 12, 8), [
        ("mc-server", "tps", "TPS (máx. 20)", GREEN, "none"),
        ("mc-server", "mspt", "ms por tick", ORANGE, "ms"),
    ], axis_right={("mc-server", "mspt")},
        thresholds=[{"color": "transparent", "value": None}, {"color": RED, "value": 15}]),
        ts("Gateway real (gw-pc-01)", g(12, y, 12, 8), [
            ("gw-pc-01", "cpu_pct", "CPU", ORANGE, "percent"),
            ("gw-pc-01", "ram_pct", "RAM", BLUE, "percent"),
            ("gw-pc-01", "battery_pct", "Batería", GREEN, "percent"),
            ("gw-pc-01", "link_rtt_ms", "Latencia a la nube", PURPLE, "ms"),
        ], axis_right={("gw-pc-01", "link_rtt_ms")})]
    y += 8
    p += [ts("Seguridad perimetral", g(0, y, 12, 7), [
        ("mc-seguridad", "hostiles_near", "Hostiles en la granja", RED, "none"),
        ("mc-seguridad", "players_in_zone", "Personas en la zona", BLUE, "none"),
        ("mc-seguridad", "alarm", "Alarma", ORANGE, "none"),
        ("mc-seguridad", "intruders_total", "Intrusiones acumuladas", PURPLE, "none"),
    ], step=True, axis_right={("mc-seguridad", "intruders_total")}),
        ts("Mensajes perdidos por dispositivo (huecos en seq)", g(12, y, 12, 7), [], unit="none")]
    gaps = (
        'from(bucket: "telemetry")\n'
        "  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n"
        '  |> filter(fn: (r) => r._measurement == "telemetry" and r._field == "seq")\n'
        '  |> group(columns: ["device"])\n'
        "  |> difference(nonNegative: true)\n"
        "  |> map(fn: (r) => ({r with _value: if r._value > 1.0 then r._value - 1.0 else 0.0}))\n"
        "  |> aggregateWindow(every: v.windowPeriod, fn: sum, createEmpty: false)\n"
        '  |> keep(columns: ["_time", "_value", "device"])'
    )
    p[-1]["targets"] = [target(gaps)]
    p[-1]["description"] = ("Cada mensaje lleva un contador seq. Un salto mayor que 1 indica mensajes que "
                            "no llegaron (QoS 1 los reintenta; un reinicio del dispositivo reinicia el contador).")
    p[-1]["fieldConfig"]["defaults"]["displayName"] = "${__field.labels.device}"
    p[-1]["fieldConfig"]["defaults"]["custom"]["drawStyle"] = "bars"
    y += 7
    p.append(table("Comandos de retroceso y respuestas", g(0, y, 12, 10),
                   flux_events(["cmd", "ack"]), sort=[{"displayName": "_time", "desc": True}],
                   overrides=EVENT_COLUMNS))
    p.append(table("Eventos de los dispositivos", g(12, y, 12, 10),
                   flux_events(["event"]), sort=[{"displayName": "_time", "desc": True}],
                   overrides=EVENT_COLUMNS))
    y += 10
    p.append(table("Configuración vigente de cada dispositivo (state retenido)", g(0, y, 24, 9), (
        'from(bucket: "telemetry")\n'
        "  |> range(start: -30d)\n"
        '  |> filter(fn: (r) => r._measurement == "events" and r.channel == "state")\n'
        '  |> group(columns: ["device"])\n'
        "  |> last()\n"
        "  |> group()\n"
        '  |> keep(columns: ["_time", "class", "device", "_value"])\n'
        '  |> rename(columns: {_value: "state"})'
    ), overrides=[{"matcher": {"id": "byName", "options": "_time"},
                   "properties": [{"id": "displayName", "value": "desde"},
                                  {"id": "unit", "value": "dateTimeAsLocal"},
                                  {"id": "custom.width", "value": 170}]},
                  {"matcher": {"id": "byName", "options": "device"},
                   "properties": [{"id": "custom.width", "value": 150}]},
                  {"matcher": {"id": "byName", "options": "class"},
                   "properties": [{"id": "custom.width", "value": 80}]}]))
    y += 9
    p.append(ts("Celular real (phone-01): presión, sonido y movimiento", g(0, y, 12, 8), [
        ("phone-01", "pressure_hpa", "Presión barométrica", PURPLE, "pressurehpa"),
        ("phone-01", "sound_dbfs", "Nivel de sonido", ORANGE, "dB"),
    ], axis_right={("phone-01", "sound_dbfs")},
        desc="Celular del operario con Sensor Logger, enviando por HTTPS al servicio de control."))
    p.append(ts("Celular real (phone-01): batería y aceleración", g(12, y, 12, 8), [
        ("phone-01", "battery_pct", "Batería", GREEN, "percent"),
        ("phone-01", "accel_ms2", "Aceleración", BLUE, "accMS2"),
    ], axis_right={("phone-01", "accel_ms2")}))
    return dashboard("agro-operacion", "AgroCraft · Operación y salud", p, ["operacion"])


def dispositivo():
    templating = [
        {"name": "device", "label": "Dispositivo", "type": "query", "datasource": DS,
         "query": ('import "influxdata/influxdb/schema"\n'
                   'schema.tagValues(bucket: "telemetry", tag: "device", '
                   'predicate: (r) => r._measurement == "telemetry", start: -30d)'),
         "definition": "tagValues device", "refresh": 1, "sort": 1,
         "current": {"text": "mc-invernadero", "value": "mc-invernadero"}, "includeAll": False},
        {"name": "fn", "label": "Agregación", "type": "custom", "query": "mean,max,min,last",
         "current": {"text": "mean", "value": "mean"},
         "options": [{"text": t, "value": t, "selected": t == "mean"} for t in ("mean", "max", "min", "last")]},
    ]
    series = (
        'from(bucket: "telemetry")\n'
        "  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n"
        '  |> filter(fn: (r) => r._measurement == "telemetry" and r.device == "${device}" and r._field != "seq")\n'
        "  |> aggregateWindow(every: v.windowPeriod, fn: ${fn}, createEmpty: false)\n"
        '  |> keep(columns: ["_time", "_value", "_field", "device"])'
    )
    summary = (
        'data = from(bucket: "telemetry")\n'
        "  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n"
        '  |> filter(fn: (r) => r._measurement == "telemetry" and r.device == "${device}" and r._field != "seq")\n'
        '  |> group(columns: ["_field"])\n'
        'mean = data |> mean() |> set(key: "stat", value: "promedio")\n'
        'max = data |> max() |> set(key: "stat", value: "máximo")\n'
        'min = data |> min() |> set(key: "stat", value: "mínimo")\n'
        'last = data |> last() |> set(key: "stat", value: "último")\n'
        'count = data |> count() |> toFloat() |> set(key: "stat", value: "muestras")\n'
        "union(tables: [mean, max, min, last, count])\n"
        '  |> keep(columns: ["_field", "stat", "_value"])\n'
        "  |> group()\n"
        '  |> pivot(rowKey: ["_field"], columnKey: ["stat"], valueColumn: "_value")\n'
        '  |> rename(columns: {_field: "variable"})'
    )
    p = [
        table("Estadísticas del rango elegido: ${device}", g(0, 0, 24, 8), summary,
              desc="Promedio, máximo, mínimo, último valor y número de muestras en el rango de tiempo del selector.",
              overrides=[{"matcher": {"id": "byName", "options": "muestras"},
                          "properties": [{"id": "decimals", "value": 0}]}]),
        {
            "id": _next_id(), "type": "timeseries", "title": "${device}: variables (${fn} por ventana)",
            "datasource": DS, "gridPos": g(0, 8, 24, 12), "targets": [target(series)],
            "fieldConfig": {"defaults": {
                "custom": {"lineWidth": 2, "fillOpacity": 6, "showPoints": "never",
                           "lineInterpolation": "smooth", "spanNulls": 600000},
                "displayName": "${__field.labels._field}"}, "overrides": []},
            "options": {"legend": {"showLegend": True, "displayMode": "table", "placement": "right",
                                   "calcs": ["mean", "max", "min", "lastNotNull"]},
                        "tooltip": {"mode": "multi"}},
        },
        table("Comandos y eventos de ${device}", g(0, 20, 24, 8),
              flux_events(["cmd", "ack", "event"], 'r.device == "${device}"'),
              overrides=EVENT_COLUMNS),
    ]
    return dashboard("agro-dispositivo", "AgroCraft · Por dispositivo", p, ["dispositivo"],
                     templating=templating, device_regex="${device}")


def ganaderia(prefix="mc-", uid="agro-ganaderia", title="AgroCraft · Ganadería (corral automático)"):
    """Corral de vacas: rebaño, comida, agua, estrés térmico, bienestar y producción de carne asada."""
    d = prefix + "corral"
    y = 0
    p = [
        stat("Vacas", g(0, y, 4, 4), d, "cows_total", decimals=0),
        stat("Bienestar", g(4, y, 4, 4), d, "welfare_pct", "percent", decimals=0,
             thresholds=[{"color": RED, "value": None}, {"color": ORANGE, "value": 40}, {"color": GREEN, "value": 60}]),
        stat("THI (estrés térmico)", g(8, y, 4, 4), d, "thi", decimals=1,
             thresholds=[{"color": GREEN, "value": None}, {"color": YELLOW, "value": 72},
                         {"color": ORANGE, "value": 78}, {"color": RED, "value": 89}]),
        stat("Comedero", g(12, y, 4, 4), d, "feed_pct", "percent", decimals=0,
             thresholds=[{"color": RED, "value": None}, {"color": ORANGE, "value": 15}, {"color": GREEN, "value": 30}]),
        stat("Carne asada en cámara", g(16, y, 4, 4), d, "beef_stored", decimals=0),
        stat("Carne producida (total)", g(20, y, 4, 4), d, "beef_total", decimals=0),
    ]
    y += 4
    p.append(ts("Rebaño", g(0, y, 12, 9), [
        (d, "cows_adult", "Adultas", BLUE), (d, "cows_baby", "Terneros", YELLOW),
        (d, "births_total", "Nacimientos (acum.)", GREEN), (d, "processed_total", "Procesadas (acum.)", ORANGE),
        (d, "deaths_total", "Muertes (acum.)", RED),
    ], unit="none", step=True))
    p.append(ts("Estrés térmico y bienestar", g(12, y, 12, 9), [
        (d, "thi", "THI", ORANGE, "none"), (d, "welfare_pct", "Bienestar", GREEN, "percent"),
    ], axis_right={(d, "welfare_pct")},
        thresholds=[{"color": "transparent", "value": None}, {"color": YELLOW, "value": 72}, {"color": RED, "value": 78}],
        desc="THI = (1,8T+32) - (0,55 - 0,0055 HR)(1,8T - 26) con el clima exterior. < 72 confort, 72-78 alerta, > 78 peligro."))
    y += 9
    p.append(ts("Comedero, bebedero y tanque", g(0, y, 12, 8), [
        (d, "feed_pct", "Comedero", YELLOW), (d, "water_pct", "Bebedero", BLUE),
        (prefix + "tanque", "level_pct", "Tanque (agua para el corral)", TEAL),
    ], unit="percent", min_=0, max_=100))
    p.append(state_timeline("Actuadores del corral", g(12, y, 12, 8), [
        (d, "cooling", "Aspersores"), (prefix + "parcela-a", "irrigation", "Riego A (trigo para el comedero)"),
    ]))
    y += 8
    p.append(ts("Cadena de producción: trigo, silo y carne", g(0, y, 24, 8), [
        (prefix + "parcela-a", "harvests", "Cosechas de trigo", GREEN, "none"),
        (prefix + "silo", "items", "Silo (unidades)", PURPLE, "none"),
        (d, "beef_total", "Carne asada producida", ORANGE, "none"),
    ], step=True, axis_right={(prefix + "silo", "items")}))
    return dashboard(uid, title, p, ["ganaderia"], device_regex=f"{prefix}corral")


def simulacion():
    """Granja de simulación: mismos paneles, pero sobre la medición `simulation` (reloj acelerado)."""
    y = 0
    p = [text("Simulación acelerada", g(0, y, 6, 8), (
        "Copia de la granja, **80 bloques al este** en el mundo, que parte del estado real y avanza "
        "hasta **360×** más rápido bajo un escenario: como está ahora, día típico, ola de calor, sequía, "
        "tormenta, helada, falla de la bomba o intrusiones.\n\n"
        "Se maneja desde el [panel de control](/iot/). Sus datos van a la medición `simulation`: "
        "no se mezclan con la captura real ni disparan alertas.\n\n"
        "El eje X es tiempo real; *Horas simuladas* indica cuánto avanzó el reloj del modelo."))]
    p.append(ts("Entradas del escenario: temperatura y humedad", g(6, y, 9, 8), [
        ("sim-control", "in_temp_c", "Temperatura exterior simulada", ORANGE, "celsius"),
        ("sim-control", "in_hum_pct", "Humedad exterior simulada", BLUE, "percent"),
    ], axis_right={("sim-control", "in_hum_pct")}))
    p.append(ts("Entradas del escenario: radiación, lluvia y ET0", g(15, y, 9, 8), [
        ("sim-control", "in_radiation_wm2", "Radiación", YELLOW, "W/m²"),
        ("sim-control", "in_rain_mmh", "Lluvia (mm/h)", BLUE, "none"),
        ("sim-control", "in_et0_mmh", "ET0 (mm/h)", ORANGE, "none"),
    ], axis_right={("sim-control", "in_rain_mmh"), ("sim-control", "in_et0_mmh")}))
    y += 8
    p.append(ts("Agua: suelo de las parcelas y tanque", g(0, y, 12, 9), [
        ("sim-parcela-a", "soil_pct", "Suelo A", GREEN), ("sim-parcela-b", "soil_pct", "Suelo B", YELLOW),
        ("sim-tanque", "level_pct", "Tanque", BLUE),
    ], unit="percent", min_=0, max_=100,
        thresholds=[{"color": "transparent", "value": None}, {"color": RED, "value": 30}]))
    p.append(ts("Invernadero: temperatura y salud de las plantas", g(12, y, 12, 9), [
        ("sim-invernadero", "temp_c", "Temperatura interior", RED, "celsius"),
        ("sim-invernadero", "hum_pct", "Humedad interior", BLUE, "percent"),
        ("sim-invernadero", "plant_health_pct", "Salud de las plantas", GREEN, "percent"),
    ], axis_right={("sim-invernadero", "hum_pct"), ("sim-invernadero", "plant_health_pct")},
        thresholds=[{"color": "transparent", "value": None}, {"color": RED, "value": 32}]))
    y += 9
    p.append(state_timeline("Actuadores (simulación)", g(0, y, 24, 7), [
        ("sim-parcela-a", "irrigation", "Riego A"), ("sim-parcela-b", "irrigation", "Riego B"),
        ("sim-tanque", "pump", "Bomba"), ("sim-control", "pump_failure", "Bomba averiada"),
        ("sim-invernadero", "vent_open", "Ventilas"), ("sim-invernadero", "heater", "Calefacción"),
        ("sim-seguridad", "alarm", "Alarma"), ("sim-control", "running", "Simulación en marcha"),
    ]))
    y += 7
    p.append(ts("Cultivo, cosechas y silo", g(0, y, 12, 8), [
        ("sim-parcela-a", "growth_pct", "Crecimiento A", GREEN, "percent"),
        ("sim-parcela-b", "growth_pct", "Crecimiento B", YELLOW, "percent"),
        ("sim-silo", "fill_pct", "Silo", PURPLE, "percent"),
    ]))
    p.append(ts("Corral (simulación): rebaño, THI y bienestar", g(0, y + 8, 24, 8), [
        ("sim-corral", "cows_total", "Vacas", BLUE, "none"), ("sim-corral", "thi", "THI", ORANGE, "none"),
        ("sim-corral", "welfare_pct", "Bienestar", GREEN, "percent"), ("sim-corral", "beef_total", "Carne producida", RED, "none"),
    ], axis_right={("sim-corral", "welfare_pct")}))
    p.append(ts("Reloj y seguridad", g(12, y, 12, 8), [
        ("sim-control", "sim_hours", "Horas simuladas", BLUE, "h"),
        ("sim-control", "speed", "Velocidad (×)", TEAL, "none"),
        ("sim-seguridad", "hostiles_near", "Intrusos", RED, "none"),
    ], step=True, axis_right={("sim-control", "speed"), ("sim-seguridad", "hostiles_near")}))
    board = dashboard("agro-simulacion", "AgroCraft · Simulación acelerada", p, ["simulacion"],
                      time_from="now-30m", refresh="10s", device_regex="sim-.*")
    for panel in board["panels"]:
        for target in panel.get("targets", []):
            target["query"] = target["query"].replace('_measurement == "telemetry"', '_measurement == "simulation"')
    # Las anotaciones leen la medición events de sim-*. A 360× hay cientos de eventos: se ocultan por
    # defecto (interruptor "Eventos" arriba) y quedan visibles solo los comandos.
    for anno in board["annotations"]["list"]:
        if anno.get("name") == "Eventos":
            anno["enable"] = False
    return board


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for build in (resumen, cultivo, clima, operacion, dispositivo, ganaderia, simulacion):
        board = build()
        path = OUT / f"{board['uid']}.json"
        path.write_text(json.dumps(board, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"{path.name}: {len(board['panels'])} paneles")


if __name__ == "__main__":
    main()
