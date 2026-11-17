#!/usr/bin/env python3
"""Genera el informe final (HTML y PDF) a partir de los datos exportados de InfluxDB.

Uso (en el PC, desde docs/informe):
    .venv/Scripts/python.exe build.py            -> informe.html + figuras/
    playwright imprime informe.html a PDF (ver README de esta carpeta)

Los textos están en secciones/*.md y admiten marcadores que se rellenan con los datos:
    {{stat:<device>:<field>:<mean|min|max|last|count>[:decimales]}}   cifra de la ventana
    {{fig:<nombre>|<pie de figura>}}                                   figura generada aquí
    {{img:<ruta>|<pie>}}                                               imagen existente (capturas)
    {{tabla:<dispositivos|experimentos|alertas|huecos|simulacion|servicios|temas|reglas>}}
    {{ventana}} {{dias}} {{muestras}}
"""

import csv
import html
import json
import re
from datetime import datetime, timedelta
from pathlib import Path

import markdown
import matplotlib

matplotlib.use("Agg")
import matplotlib.dates as mdates  # noqa: E402
import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402

HERE = Path(__file__).resolve().parent
DATA = HERE / "datos"
FIG = HERE / "figuras"
FIG.mkdir(exist_ok=True)
TZ = "America/Bogota"
DOCS = HERE.parent

C = {"blue": "#2f6db3", "orange": "#e07b24", "green": "#3a9a4a", "red": "#c8393f", "purple": "#7b4fb3",
     "teal": "#2a9d98", "yellow": "#c9a227", "gray": "#6b7280"}
plt.rcParams.update({"font.size": 9, "axes.titlesize": 10, "axes.labelsize": 8.5, "axes.grid": True,
                     "grid.alpha": .25, "axes.spines.top": False, "axes.spines.right": False,
                     "figure.dpi": 150, "savefig.bbox": "tight", "legend.fontsize": 7.5, "legend.frameon": False})

series = pd.read_csv(DATA / "series.csv")
series["time"] = pd.to_datetime(series["time"], utc=True).dt.tz_convert(TZ)
STATS = json.loads((DATA / "stats.json").read_text())
EVENTS = json.loads((DATA / "events.json").read_text(encoding="utf-8"))
GAPS = json.loads((DATA / "gaps.json").read_text())
T0, T1 = series["time"].min(), series["time"].max()


def s(device, field):
    part = series[(series.device == device) & (series.field == field)]
    return part.set_index("time")["value"].sort_index()


def axis_dates(ax, span_days):
    if span_days > 2:
        ax.xaxis.set_major_locator(mdates.DayLocator(tz=TZ))
        ax.xaxis.set_major_formatter(mdates.DateFormatter("%d %b", tz=TZ))
        ax.xaxis.set_minor_locator(mdates.HourLocator(byhour=[6, 12, 18], tz=TZ))
    else:
        ax.xaxis.set_major_formatter(mdates.DateFormatter("%d %b %H:%M", tz=TZ))


def save(fig, name):
    fig.savefig(FIG / f"{name}.png")
    plt.close(fig)


# ------------------------------------------------------------------------------- figuras
DEVICE_PLOTS = {   # device: [(campo, etiqueta, color)] ; cada tupla es un subgráfico
    "api-clima": [("temp_c", "Temperatura (°C)", "orange"), ("hum_pct", "Humedad (%)", "blue"),
                  ("radiation_wm2", "Radiación (W/m²)", "yellow"), ("precip_mm", "Lluvia (mm/15 min)", "teal")],
    "api-aire": [("pm2_5", "PM2.5 (µg/m³)", "orange"), ("us_aqi", "US AQI", "red"), ("uv_index", "Índice UV", "purple")],
    "api-suelo": [("et0_mm", "ET0 (mm/15 min)", "orange"), ("soil_moist_m3m3", "Humedad de suelo (m³/m³)", "green"),
                  ("soil_temp_c", "Temp. del suelo (°C)", "red")],
    "api-pronostico": [("hours_to_irrigation_a", "Horas al próximo riego A", "green"),
                       ("water_need_24h_l", "Agua prevista 24 h (L)", "blue"),
                       ("thi_stress_hours_24h", "Horas de estrés del rebaño (24 h)", "red")],
    "ds-invernadero": [("temp_c", "Temperatura (°C)", "red"), ("hum_pct", "Humedad (%)", "blue"),
                       ("co2_ppm", "CO₂ (ppm)", "green"), ("par_umol", "PAR (µmol/m²s)", "yellow")],
    "ds-energia": [("power_kw", "Potencia activa (kW)", "orange"), ("voltage_v", "Tensión (V)", "blue")],
    "gw-pc-01": [("cpu_pct", "CPU (%)", "orange"), ("ram_pct", "RAM (%)", "blue"), ("link_rtt_ms", "Latencia a la nube (ms)", "purple")],
    "phone-01": [("pressure_hpa", "Presión (hPa)", "purple"), ("sound_dbfs", "Sonido (dBFS)", "orange"),
                 ("battery_pct", "Batería (%)", "green")],
    "mc-parcela-a": [("soil_pct", "Humedad del suelo (%)", "green"), ("growth_pct", "Crecimiento (%)", "yellow"),
                     ("irrigation", "Riego (0/1)", "blue")],
    "mc-parcela-b": [("soil_pct", "Humedad del suelo (%)", "green"), ("growth_pct", "Crecimiento (%)", "yellow"),
                     ("irrigation", "Riego (0/1)", "blue")],
    "mc-tanque": [("level_pct", "Nivel (%)", "blue"), ("outflow_lpm", "Salida a riego (L/min)", "orange"),
                  ("inflow_lpm", "Entrada (L/min)", "teal")],
    "mc-invernadero": [("temp_c", "Temperatura (°C)", "red"), ("hum_pct", "Humedad (%)", "blue"),
                       ("plant_health_pct", "Salud de las plantas (%)", "green"), ("vent_open", "Ventilas (0/1)", "gray")],
    "mc-silo": [("items", "Unidades almacenadas", "purple"), ("fill_pct", "Llenado (%)", "orange")],
    "mc-seguridad": [("hostiles_near", "Hostiles en la granja", "red"), ("alarm", "Alarma (0/1)", "orange"),
                     ("players_in_zone", "Personas en la zona", "blue")],
    "mc-estacion": [("raining", "Lluvia en el mundo (0/1)", "teal"), ("daylight_lvl", "Luz del cielo (0-15)", "yellow")],
    "mc-server": [("tps", "TPS", "green"), ("heap_mb", "Memoria usada (MB)", "purple")],
    "mc-corral": [("cows_total", "Vacas", "blue"), ("thi", "THI", "orange"), ("welfare_pct", "Bienestar (%)", "green"),
                  ("feed_pct", "Comedero (%)", "yellow")],
}


def device_figure(device, plots):
    fields = [p for p in plots if not s(device, p[0]).empty]
    if not fields:
        return None
    fig, axes = plt.subplots(len(fields), 1, figsize=(7.2, 1.35 * len(fields) + 0.4), sharex=True)
    axes = [axes] if len(fields) == 1 else axes
    for ax, (field, label, color) in zip(axes, fields):
        data = s(device, field)
        step = set(data.unique()) <= {0, 1}
        if step:
            ax.fill_between(data.index, data.values, step="post", color=C[color], alpha=.35, lw=0)
            ax.set_ylim(-.05, 1.1)
        else:
            ax.plot(data.index, data.values, color=C[color], lw=.9)
        ax.set_ylabel(label, rotation=0, ha="right", va="center", fontsize=7.5)
        for gap in GAPS.get(device, []):
            if gap[2] >= 30:
                ax.axvspan(pd.Timestamp(gap[0], unit="s", tz="UTC").tz_convert(TZ),
                           pd.Timestamp(gap[1], unit="s", tz="UTC").tz_convert(TZ), color=C["gray"], alpha=.12, lw=0)
    axis_dates(axes[-1], (T1 - T0).days)
    axes[0].set_title(device, loc="left", fontweight="bold")
    fig.align_ylabels(axes)
    save(fig, f"dev-{device}")
    return f"dev-{device}"


def cross_figures():
    # Temperatura: exterior real, gemelo y dataset
    fig, ax = plt.subplots(figsize=(7.2, 2.6))
    for dev, label, color in (("api-clima", "Exterior real (Open-Meteo)", "orange"),
                              ("mc-invernadero", "Invernadero gemelo (Minecraft)", "red"),
                              ("ds-invernadero", "Invernadero de referencia (Wageningen)", "purple")):
        data = s(dev, "temp_c")
        ax.plot(data.index, data.values, color=C[color], lw=.9, label=label)
    ax.axhline(32, color=C["red"], ls="--", lw=.7)
    ax.set_ylabel("°C")
    ax.legend(ncol=3, loc="upper left")
    axis_dates(ax, (T1 - T0).days)
    save(fig, "cruce-temperatura")

    # Agua: suelo, tanque y lluvia real
    fig, ax = plt.subplots(figsize=(7.2, 2.6))
    for dev, field, label, color in (("mc-parcela-a", "soil_pct", "Suelo parcela A", "green"),
                                     ("mc-parcela-b", "soil_pct", "Suelo parcela B", "yellow"),
                                     ("mc-tanque", "level_pct", "Nivel del tanque", "blue")):
        data = s(dev, field)
        ax.plot(data.index, data.values, color=C[color], lw=.9, label=label)
    ax.axhline(35, color=C["red"], ls="--", lw=.7)
    ax.set_ylabel("%")
    ax2 = ax.twinx()
    rain = s("api-clima", "precip_mm")
    ax2.bar(rain.index, rain.values, width=.006, color=C["teal"], alpha=.6, label="Lluvia real")
    ax2.set_ylabel("mm/15 min")
    ax2.spines["right"].set_visible(True)
    ax.legend(loc="upper left", ncol=3)
    axis_dates(ax, (T1 - T0).days)
    save(fig, "cruce-agua")

    # Corral: THI real y bienestar
    fig, ax = plt.subplots(figsize=(7.2, 2.4))
    thi = s("mc-corral", "thi")
    ax.plot(thi.index, thi.values, color=C["orange"], lw=.9, label="THI del corral")
    ax.axhline(72, color=C["yellow"], ls="--", lw=.7)
    ax.axhline(78, color=C["red"], ls="--", lw=.7)
    ax.set_ylabel("THI")
    ax2 = ax.twinx()
    welfare = s("mc-corral", "welfare_pct")
    ax2.plot(welfare.index, welfare.values, color=C["green"], lw=.9, label="Bienestar")
    ax2.set_ylabel("Bienestar %")
    ax2.spines["right"].set_visible(True)
    ax.legend(loc="lower left")
    ax2.legend(loc="lower right")
    axis_dates(ax, (T1 - T0).days)
    save(fig, "cruce-corral")


def zoom(name, title, items, start, end, marks=()):
    """Acercamiento a un experimento: items = [(device, field, label, color, eje 0|1)], marks = [(hora local, texto)]."""
    start = pd.Timestamp(start, tz=TZ)
    end = pd.Timestamp(end, tz=TZ)
    fig, ax = plt.subplots(figsize=(7.2, 2.3))
    axes = [ax, None]
    for dev, field, label, color, which in items:
        data = s(dev, field)
        data = data[(data.index >= start) & (data.index <= end)]
        if which == 1 and axes[1] is None:
            axes[1] = ax.twinx()
            axes[1].spines["right"].set_visible(True)
        target = axes[which]
        target.plot(data.index, data.values, color=C[color], lw=1.1, marker=".", ms=3, label=label)
    for when, text in marks:
        t = pd.Timestamp(when, tz=TZ)
        ax.axvline(t, color=C["gray"], ls=":", lw=.9)
        ax.annotate(text, (t, 1), xycoords=("data", "axes fraction"), fontsize=7, ha="left", va="top",
                    xytext=(3, -2), textcoords="offset points", color=C["gray"])
    ax.set_title(title, loc="left", fontsize=9)
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%H:%M", tz=TZ))
    ax.set_xlabel(start.strftime("%d %b %Y") + " (hora Colombia)", fontsize=7.5)
    handles = [h for a in axes if a for h in a.get_legend_handles_labels()[0]]
    labels = [lab for a in axes if a for lab in a.get_legend_handles_labels()[1]]
    ax.legend(handles, labels, loc="lower left", ncol=3)
    save(fig, name)


def availability_figure():
    devices = list(DEVICE_PLOTS)
    fig, ax = plt.subplots(figsize=(7.2, 4.2))
    for i, dev in enumerate(devices):
        data = series[series.device == dev]["time"]
        if data.empty:
            continue
        ax.scatter(data, [i] * len(data), s=1.2, color=C["blue"], marker="|")
    ax.set_yticks(range(len(devices)), devices, fontsize=7)
    ax.invert_yaxis()
    ax.set_title("Disponibilidad de datos por dispositivo (cada marca = promedio de 10 min)", loc="left", fontsize=9)
    axis_dates(ax, (T1 - T0).days)
    ax.grid(axis="y", alpha=0)
    save(fig, "disponibilidad")


# -------------------------------------------------------------------------------- tablas
DEVICE_INFO = [  # id, clase, qué es, sensor de referencia y rango de datasheet, protocolo
    ("gw-pc-01", "Real", "PC del operario como gateway de campo", "psutil / netsh (CPU 0-100 %, RAM 0-100 %, latencia TCP)", "MQTT 3.1.1 / TLS 8883"),
    ("phone-01", "Real", "iPhone del operario (Sensor Logger)", "Barómetro 300-1100 hPa · micrófono -160-0 dBFS · acelerómetro ±8 g", "HTTPS POST → MQTT"),
    ("mc-parcela-a", "Twin", "Parcela de trigo", "Sonda capacitiva v1.2: 0-100 % · BH1750: 1-65535 lx", "MQTT (red interna)"),
    ("mc-parcela-b", "Twin", "Parcela de zanahoria y papa", "Sonda capacitiva v1.2: 0-100 % · BH1750", "MQTT"),
    ("mc-tanque", "Twin", "Tanque de 2000 L con bomba", "JSN-SR04T: 20-600 cm · YF-S201: 1-30 L/min", "MQTT"),
    ("mc-invernadero", "Twin", "Invernadero de vidrio", "SHT31: -40-125 °C, 0-100 % HR · BH1750", "MQTT"),
    ("mc-silo", "Twin", "Silo de cosecha", "Celda de carga HX711 (0-100 %)", "MQTT"),
    ("mc-seguridad", "Twin", "Perímetro", "PIR HC-SR501 (3-7 m) · contacto magnético", "MQTT"),
    ("mc-estacion", "Twin", "Estación meteorológica del mundo", "Pluviómetro de balancín · piranómetro", "MQTT"),
    ("mc-corral", "Twin", "Corral automático de vacas", "RFID 134 kHz · nivel de comedero · THI (SHT31)", "MQTT"),
    ("mc-server", "Twin", "Salud del servidor del gemelo", "TPS 0-20 · memoria JVM", "MQTT"),
    ("api-clima", "API", "Clima real de Bucaramanga", "Open-Meteo Forecast (modelos ICON/GFS)", "HTTPS GET → MQTT"),
    ("api-aire", "API", "Calidad del aire", "Open-Meteo Air Quality (CAMS)", "HTTPS GET → MQTT"),
    ("api-suelo", "API", "Suelo y evapotranspiración", "Open-Meteo (ET0 FAO-56)", "HTTPS GET → MQTT"),
    ("api-pronostico", "API", "Pronóstico operativo de la granja", "Open-Meteo horario + modelo del gemelo", "HTTPS GET → MQTT"),
    ("ds-invernadero", "DataSet", "Invernadero de referencia", "Autonomous Greenhouse Challenge 2019 (5 min)", "MQTT"),
    ("ds-energia", "DataSet", "Medidor eléctrico", "UCI Household Power Consumption (1 min)", "MQTT"),
]

MAIN_FIELDS = {d: [p[0] for p in plots] for d, plots in DEVICE_PLOTS.items()}


def fmt(value, decimals=1):
    if value is None:
        return "—"
    text = f"{value:,.{decimals}f}".replace(",", " ")   # espacio fino que no se corta
    return text if abs(value) >= 10000 else text.replace(" ", "")


def table(rows, header):
    out = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    out += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return "\n".join(out)


def table_dispositivos():
    rows = []
    for dev, cls, what, sensor, proto in DEVICE_INFO:
        ranges = []
        for field in MAIN_FIELDS.get(dev, [])[:3]:
            st = STATS.get(dev, {}).get(field)
            if st and not set([st["min"], st["max"]]) <= {0.0, 1.0}:
                ranges.append(f"{field}: {fmt(st['min'])}–{fmt(st['max'])}")
        count = int(STATS.get(dev, {}).get("seq", {}).get("count", 0))
        rows.append([f"`{dev}`", cls, what, sensor, "<br>".join(ranges) or "—", fmt(count, 0), proto])
    return table(rows, ["Dispositivo", "Clase", "Qué es", "Sensor de referencia / datasheet", "Rango observado", "Muestras", "Protocolo"])


def table_experimentos():
    rows = []
    with open(DOCS / "experimentos" / "experimentos.csv", encoding="utf-8") as handle:
        for row in csv.DictReader(handle, delimiter=";"):
            if row["fase"] in ("inicio", "resultado", "reversion") and row["id"].startswith("E"):
                rows.append([row["hora"][5:16], row["id"], row["fase"], f"`{row['dispositivo']}`", row["accion"],
                             f"`{row['params']}`", row["ok"], row["motivo"][:110]])
    return table(rows, ["Hora", "Exp.", "Fase", "Dispositivo", "Acción", "Parámetros", "Ack", "Propósito"])


def table_alertas():
    rows = []
    for a in EVENTS["alerts"]:
        if a.get("alert", "").startswith("Prueba"):
            continue
        t = pd.Timestamp(a["time"]).tz_convert(TZ).strftime("%d %b %H:%M")
        summary = a.get("summary", "")
        summary = "" if "{{" in summary else summary
        rows.append([t, a.get("status"), a.get("alert"), f"`{a.get('device', '')}`", summary[:90]])
    return table(rows, ["Hora", "Estado", "Alerta", "Dispositivo", "Detalle"])


def table_huecos():
    rows = []
    for dev, gaps in sorted(GAPS.items()):
        for start, end, minutes in gaps:
            a = pd.Timestamp(start, unit="s", tz="UTC").tz_convert(TZ).strftime("%d %b %H:%M")
            rows.append([f"`{dev}`", a, fmt(minutes, 0)])
    return table(rows, ["Dispositivo", "Desde", "Minutos"])


def table_simulacion():
    runs = {}
    for name in ("sim-tests-corrida1.json", "sim-tests-corrida2.json"):
        path = DOCS / "experimentos" / name
        if path.exists():
            for r in json.loads(path.read_text(encoding="utf-8")):
                runs[r["scenario"]] = r
    rows = []
    for r in runs.values():
        passed = sum(1 for c in r["checks"] if c["ok"])
        detail = "; ".join(f"{c['check']}: {c['value']}" for c in r["checks"][1:4])
        rows.append([r["scenario"], r["hours"], f"{passed}/{len(r['checks'])}", detail[:160]])
    return table(rows, ["Escenario", "Horas simuladas", "Criterios cumplidos", "Ejemplos de lo observado"])


STATIC_TABLES = {
    "servicios": table([
        ["Caddy 2", "Entrada HTTPS, certificado Let's Encrypt, proxy inverso", "443, 80"],
        ["Eclipse Mosquitto 2.0", "Bróker MQTT: usuario y ACL por dispositivo, TLS, WebSocket", "8883, 443 (/mqtt)"],
        ["Telegraf 1.35", "Ingesta: suscriptor MQTT → InfluxDB (telemetría, eventos, simulación)", "interno"],
        ["InfluxDB 2.7", "Base de series de tiempo (bucket telemetry, sin caducidad)", "interno"],
        ["Grafana 12.1", "7 dashboards como código, 9 alertas, anotaciones", "443 (/)"],
        ["Servicio de control (Python)", "API REST, panel de retroceso, reglas, ingesta HTTPS, webhook de alertas, notificaciones, analista IA", "443 (/iot/)"],
        ["Flota (Python)", "Dispositivos de API, pronóstico y datasets", "interno"],
        ["Paper 1.21.11 + AgroCraft", "Mundo de Minecraft con los gemelos (plugin propio en Java 21) y mapa squaremap", "25565, 443 (/map/)"],
    ], ["Servicio", "Función", "Puerto público"]),
    "temas": table([
        ["`telemetry`", "dispositivo → nube", "QoS 1", "`{\"ts\": ms, \"seq\": n, \"variable\": número}`"],
        ["`status`", "dispositivo → nube", "retenido + LWT", "`online` / `offline`"],
        ["`state`", "dispositivo → nube", "retenido", "configuración y actuadores vigentes"],
        ["`event`", "dispositivo → nube", "QoS 1", "riego, cosecha, intruso, nacimiento, palanca..."],
        ["`cmd`", "nube → dispositivo", "QoS 1", "`{\"cmd_id\", \"action\", \"params\", \"source\"}`"],
        ["`ack`", "dispositivo → nube", "QoS 1", "`{\"cmd_id\", \"ok\", \"detail\"}`"],
    ], ["Canal", "Sentido", "Entrega", "Contenido"]),
    "reglas": table([
        ["Suelo seco", "`soil_pct` < 30 % por 10 min", "Devuelve el riego a automático"],
        ["Estrés térmico (invernadero)", "`temp_c` > 32 °C por 5 min", "Abre ventilas"],
        ["Tanque bajo", "`level_pct` < 20 % por 2 min", "Enciende la bomba"],
        ["Intruso", "`hostiles_near` > 0", "Notificación"],
        ["Dispositivo sin datos", "sin muestras en 30 min", "Notificación"],
        ["Gemelo degradado", "TPS < 15 por 5 min", "Notificación"],
        ["Estrés térmico del rebaño", "THI > 78 por 15 min", "Enciende aspersores"],
        ["Bienestar animal bajo", "bienestar < 40 % por 10 min", "Notificación"],
        ["Comedero vacío", "comedero < 5 % por 10 min", "Llena el comedero"],
    ], ["Alerta (Grafana)", "Condición", "Acción automática"]),
}


# ------------------------------------------------------------------------------- render
def render(text, figures):
    def stat(match):
        dev, field, fn = match.group(1), match.group(2), match.group(3)
        decimals = int(match.group(4)) if match.group(4) else 1
        value = STATS.get(dev, {}).get(field, {}).get(fn)
        return fmt(value, decimals)

    def fig(match):
        name, caption = match.group(1), match.group(2)
        figures.append(name)
        return (f'<figure><img src="figuras/{name}.png"><figcaption>Figura {len(figures)}. '
                f'{html.escape(caption)}</figcaption></figure>')

    def img(match):
        path, caption = match.group(1), match.group(2)
        figures.append(path)
        return (f'<figure><img src="{path}"><figcaption>Figura {len(figures)}. {html.escape(caption)}</figcaption></figure>')

    tables = {"dispositivos": table_dispositivos, "experimentos": table_experimentos, "alertas": table_alertas,
              "huecos": table_huecos, "simulacion": table_simulacion}
    text = re.sub(r"\{\{stat:([\w-]+):(\w+):(mean|min|max|last|count)(?::(\d))?\}\}", stat, text)
    text = re.sub(r"\{\{tabla:(\w+)\}\}", lambda m: tables[m.group(1)]() if m.group(1) in tables
                  else STATIC_TABLES[m.group(1)], text)
    text = text.replace("{{ventana}}", f"{T0.strftime('%d %b %Y %H:%M')} – {T1.strftime('%d %b %Y %H:%M')} (hora Colombia)")
    text = text.replace("{{dias}}", fmt((T1 - T0).total_seconds() / 86400, 1))
    text = text.replace("{{muestras}}", fmt(sum(int(f.get("seq", {}).get("count", 0)) for f in STATS.values()), 0))
    html_text = markdown.markdown(text, extensions=["tables", "fenced_code", "toc", "attr_list", "md_in_html"])
    html_text = re.sub(r"<p>\{\{fig:([\w-]+)\|([^}]+)\}\}</p>", fig, html_text)
    html_text = re.sub(r"<p>\{\{img:([^|]+)\|([^}]+)\}\}</p>", img, html_text)
    return html_text


def main():
    for dev, plots in DEVICE_PLOTS.items():
        device_figure(dev, plots)
    cross_figures()
    availability_figure()
    zoom("exp-e1", "E1 · api-aire apagado 60 min (set_enabled 0 → 1)",
         [("api-aire", "pm2_5", "PM2.5", "orange", 0)], "2026-11-11 12:30", "2026-11-11 17:00",
         [("2026-11-11 14:37", "apagado"), ("2026-11-11 15:37", "encendido")])
    zoom("exp-e2", "E2 · ds-invernadero descalibrado +3 °C durante 30 min (calibrate)",
         [("ds-invernadero", "temp_c", "Temperatura reportada", "red", 0)], "2026-11-11 13:30", "2026-11-11 16:00",
         [("2026-11-11 14:37", "offset +3"), ("2026-11-11 15:07", "reset")])
    zoom("exp-e5", "E5 · riego de la parcela A en manual → alerta Suelo seco → vuelve a automático",
         [("mc-parcela-a", "soil_pct", "Suelo A (%)", "green", 0), ("mc-parcela-a", "irrigation", "Riego", "blue", 1)],
         "2026-11-11 14:00", "2026-11-11 18:30",
         [("2026-11-11 14:37", "auto=0"), ("2026-11-11 16:32", "alerta → auto=1")])
    zoom("exp-calor", "Estrés térmico: ventilas por alerta y por palanca del jugador (E9)",
         [("mc-invernadero", "temp_c", "Temp. invernadero (°C)", "red", 0),
          ("mc-invernadero", "vent_open", "Ventilas", "gray", 1)], "2026-11-11 10:30", "2026-11-11 16:00",
         [("2026-11-11 11:14", "alerta: ventilas"), ("2026-11-11 14:39", "E9 palanca"), ("2026-11-11 14:59", "alerta")])

    figures = []
    body = ""
    for part in sorted((HERE / "secciones").glob("*.md")):
        body += render(part.read_text(encoding="utf-8"), figures) + "\n"
    template = (HERE / "plantilla.html").read_text(encoding="utf-8")
    months = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
              "noviembre", "diciembre"]
    today = datetime.now()
    out = (template.replace("{{contenido}}", body)
           .replace("{{fecha}}", f"{today.day} de {months[today.month - 1]} de {today.year}"))
    (HERE / "informe.html").write_text(out, encoding="utf-8", newline="\n")
    print(f"informe.html: {len(figures)} figuras, ventana {T0} – {T1}")


if __name__ == "__main__":
    main()
