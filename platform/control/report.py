"""Analista con IA: informe diario del comportamiento de la granja.

1. Reúne de InfluxDB las estadísticas de las últimas N horas de cada dispositivo (promedio, mínimo,
   máximo, último valor, número de muestras), los eventos (riegos, cosechas, alertas, nacimientos...),
   los comandos de retroceso y el pronóstico vigente.
2. Se los da a Claude (claude-opus-5-5) con instrucciones de analista agrónomo/IoT y obtiene un
   informe en español: comportamiento por dispositivo, relación clima real-gemelo, anomalías y
   recomendaciones.
3. Lo guarda en /state/reportes/<fecha>.md (servido en /iot/reportes/) y lo envía a Telegram/Discord.

Proveedor del modelo (variables en platform/.env):
  - Compatible con OpenAI (p. ej. TokenHarbor): ANALYST_BASE_URL, ANALYST_API_KEY, ANALYST_MODEL.
    Se llama POST {ANALYST_BASE_URL}/chat/completions.
  - Claude: ANTHROPIC_API_KEY (y opcional ANALYST_MODEL), si no hay ANALYST_BASE_URL.
Sin ninguno se genera solo la parte estadística, indicando que la IA no está configurada.
"""

import csv
import io
import json
import logging
import os
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import aiohttp
import anthropic

log = logging.getLogger("analista")
REPORT_DIR = Path(os.environ.get("STATE_DIR", "/state")) / "reportes"
INFLUX_URL = os.environ.get("INFLUX_URL", "http://influxdb:8086")
TZ = ZoneInfo(os.environ.get("TZ", "America/Bogota"))
BASE_URL = os.environ.get("ANALYST_BASE_URL", "").strip().rstrip("/")
SERIES = [("api-clima", "temp_c"), ("mc-invernadero", "temp_c"), ("ds-invernadero", "temp_c"),
          ("mc-parcela-a", "soil_pct"), ("mc-parcela-b", "soil_pct"), ("mc-tanque", "level_pct"),
          ("mc-corral", "thi"), ("mc-corral", "welfare_pct"), ("api-clima", "radiation_wm2"),
          ("api-clima", "hum_pct"), ("api-aire", "pm2_5"), ("ds-energia", "power_kw"),
          ("gw-pc-01", "cpu_pct"), ("gw-pc-01", "ram_pct")]
MODEL = os.environ.get("ANALYST_MODEL", "").strip() or ("deepseek-v4.1-flash:free" if BASE_URL else "claude-opus-5-5")

SYSTEM = """Eres el analista de datos de AgroCraft, una granja inteligente en Santander (Colombia) cuyo
gemelo digital corre en un mundo de Minecraft. Recibes estadísticas reales de una ventana de tiempo,
medidas por dispositivos de cuatro clases: reales (PC gateway, celular), gemelos digitales del mundo
(parcelas, tanque, invernadero, silo, seguridad, estación, corral de vacas, servidor), APIs de clima
real de Bucaramanga (Open-Meteo, incluido un pronóstico) y datasets públicos reproducidos (invernadero
de Wageningen, consumo eléctrico UCI).

Escribe un informe en español, en Markdown, para el operador de la granja y para un informe académico:
1. **Resumen** en 3-4 frases: cómo estuvo la granja en la ventana.
2. **Comportamiento por dispositivo**: para cada dispositivo con datos, qué hicieron sus variables
   (rango, tendencia, ciclos día/noche), citando cifras de las estadísticas.
3. **Relaciones**: cómo el clima real explica el comportamiento de los gemelos (secado del suelo por
   ET0, temperatura del invernadero frente a la exterior, estrés térmico del rebaño por THI, lluvia y tanque).
4. **Anomalías y eventos**: anomalías detectadas por patrón diario (campo `anomalias`), huecos de datos,
   descalibraciones, alertas, comandos de retroceso y su efecto.
5. **Recomendaciones** operativas concretas para las próximas 24 h, apoyadas en el pronóstico.

Reglas: usa solo las cifras recibidas; si algo no se puede afirmar con los datos, dilo. No inventes
dispositivos ni valores. Sé concreto y breve (600-900 palabras)."""


async def _flux(http: aiohttp.ClientSession, query: str) -> list:
    async with http.post(f"{INFLUX_URL}/api/v2/query?org=agrocraft", data=query, headers={
            "Authorization": f"Token {os.environ['INFLUX_TOKEN']}", "Content-Type": "application/vnd.flux",
            "Accept": "application/csv"}) as response:
        text = await response.text()
        if response.status >= 300:
            raise RuntimeError(f"InfluxDB {response.status}: {text[:300]}")
    return [row for row in csv.DictReader(io.StringIO(text)) if row.get("_value") not in (None, "")]


async def gather(hours: int) -> dict:
    """Estadísticas por dispositivo y variable, eventos, comandos y pronóstico de la ventana."""
    window = f"-{hours}h"
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=120)) as http:
        stats = defaultdict(dict)
        for fn in ("mean", "min", "max", "last", "count"):
            rows = await _flux(http, f'''from(bucket: "telemetry")
  |> range(start: {window})
  |> filter(fn: (r) => r._measurement == "telemetry" and r._field != "seq")
  |> group(columns: ["class", "device", "_field"])
  |> {fn}()''')
            for row in rows:
                key = f'{row["class"]}/{row["device"]}'
                stats[key].setdefault(row["_field"], {})[fn] = round(float(row["_value"]), 2)
        events = await _flux(http, f'''from(bucket: "telemetry")
  |> range(start: {window})
  |> filter(fn: (r) => r._measurement == "events" and (r.channel == "event" or r.channel == "cmd") and r.class != "sim")''')
    by_type = Counter()
    alerts, commands, anomalies = [], [], []
    for row in events:
        try:
            data = json.loads(row["_value"])
        except ValueError:
            continue
        if row["channel"] == "cmd":
            commands.append({"hora": row["_time"][:16], "device": row["device"], "accion": data.get("action"),
                             "params": data.get("params"), "origen": data.get("source", "cli")})
        elif row["device"] == "alertas":
            alerts.append({"hora": row["_time"][:16], "estado": data.get("status"), "alerta": data.get("alert"),
                           "device": data.get("device"), "resumen": data.get("summary")})
        else:
            by_type[f'{row["device"]}:{data.get("type")}'] += 1
            if row["device"] == "anomalias":
                anomalies.append({"hora": row["_time"][:16], "tipo": data.get("type"), "device": data.get("device"),
                                  "variable": data.get("variable"), "valor": data.get("valor"),
                                  "esperado": data.get("esperado"), "z": data.get("z")})
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=120)) as http:
        series_rows = await _flux(http, f'''from(bucket: "telemetry")
  |> range(start: {window})
  |> filter(fn: (r) => r._measurement == "telemetry")
  |> filter(fn: (r) => {" or ".join(f'(r.device == "{d}" and r._field == "{f}")' for d, f in SERIES)})
  |> aggregateWindow(every: 20m, fn: mean, createEmpty: false)''')
    series = defaultdict(lambda: {"t": [], "v": []})
    for row in series_rows:
        point = series[f'{row["device"]}/{row["_field"]}']
        stamp = datetime.fromisoformat(row["_time"].replace("Z", "+00:00")).astimezone(TZ)
        point["t"].append(stamp.strftime("%d %H:%M"))
        point["v"].append(round(float(row["_value"]), 2))
    forecast = stats.get("api/api-pronostico", {})
    return {"ventana_horas": hours, "generado": datetime.now(TZ).isoformat(timespec="minutes"),
            "estadisticas": dict(stats), "eventos_por_tipo": dict(by_type), "alertas": alerts[-40:],
            "comandos": commands[-40:], "anomalias": anomalies[-40:], "pronostico": {k: v.get("last") for k, v in forecast.items()},
            "series": dict(series)}


def stats_markdown(data: dict) -> str:
    lines = [f"## Estadísticas (últimas {data['ventana_horas']} h)", ""]
    for device in sorted(data["estadisticas"]):
        lines += [f"**{device}**", "", "| variable | promedio | mín | máx | último | muestras |", "|---|---|---|---|---|---|"]
        for field, s in sorted(data["estadisticas"][device].items()):
            lines.append(f"| {field} | {s.get('mean')} | {s.get('min')} | {s.get('max')} | {s.get('last')} | {s.get('count')} |")
        lines.append("")
    if data["eventos_por_tipo"]:
        lines += ["**Eventos**", ""] + [f"- {k}: {v}" for k, v in sorted(data["eventos_por_tipo"].items())] + [""]
    return "\n".join(lines)


async def analyze_openai_compatible(prompt: str) -> tuple:
    """Proveedor con API compatible con OpenAI (Chat Completions)."""
    key = os.environ.get("ANALYST_API_KEY", "").strip()
    if not key:
        return "_Análisis con IA no configurado: falta ANALYST_API_KEY en platform/.env._", None
    body = {"model": MODEL, "max_tokens": 16000, "temperature": 0.3,   # modelo de razonamiento: deja margen
            "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": prompt}]}
    try:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=300)) as http:
            async with http.post(f"{BASE_URL}/chat/completions", json=body,
                                 headers={"Authorization": f"Bearer {key}"}) as response:
                text = await response.text()
                if response.status >= 300:
                    return f"_Análisis no disponible: el proveedor respondió HTTP {response.status}: {text[:200]}_", None
                reply = json.loads(text)
    except (aiohttp.ClientError, TimeoutError, ValueError) as error:
        return f"_Análisis no disponible: {error!r}_", None
    choice = (reply.get("choices") or [{}])[0]
    content = (choice.get("message") or {}).get("content") or ""
    usage = reply.get("usage", {})
    log.info("análisis generado con %s (%s tokens de entrada, %s de salida)", reply.get("model", MODEL),
             usage.get("prompt_tokens"), usage.get("completion_tokens"))
    if choice.get("finish_reason") == "length" and len(content.strip()) < 400:
        return ("_El modelo agotó su límite de salida razonando y no terminó el informe "
                f"({usage.get('completion_tokens')} tokens)._"), None
    if not content.strip():
        return "_El modelo no devolvió texto._", reply.get("model", MODEL)
    return content.strip(), reply.get("model", MODEL)


async def analyze(data: dict) -> tuple:
    """Devuelve (texto del análisis, modelo usado). Sin clave de API devuelve un aviso."""
    prompt = "Datos de la ventana (JSON):\n\n" + json.dumps(data, ensure_ascii=False)
    if BASE_URL:
        return await analyze_openai_compatible(prompt)
    if not os.environ.get("ANTHROPIC_API_KEY"):
        return ("_Análisis con IA no configurado: falta ANALYST_BASE_URL/ANALYST_API_KEY "
                "(o ANTHROPIC_API_KEY) en platform/.env. "
                "Se incluyen solo las estadísticas._"), None
    client = anthropic.AsyncAnthropic()
    try:
        response = await client.beta.messages.create(
            model=MODEL,
            max_tokens=16000,
            output_config={"effort": "high"},
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            system=SYSTEM,
            messages=[{"role": "user", "content":
                       "Datos de la ventana (JSON):\n\n" + json.dumps(data, ensure_ascii=False)}],
        )
    except anthropic.RateLimitError as error:
        return f"_Análisis no disponible: límite de uso de la API ({error.message})._", None
    except anthropic.APIStatusError as error:
        return f"_Análisis no disponible: error de la API ({error.status_code}: {error.message})._", None
    except anthropic.APIConnectionError:
        return "_Análisis no disponible: sin conexión con la API._", None
    if response.stop_reason == "refusal":
        return "_El modelo declinó generar el análisis para estos datos._", response.model
    text = "".join(block.text for block in response.content if block.type == "text")
    log.info("análisis generado con %s (%s tokens de entrada, %s de salida)", response.model,
             response.usage.input_tokens, response.usage.output_tokens)
    return text.strip(), response.model


async def build_report(hours: int = 24) -> dict:
    data = await gather(hours)
    series = data.pop("series")              # no se envían al modelo: solo para las gráficas
    analysis, model = await analyze(data)
    data.update(series=series, analisis=analysis, modelo=model)
    stamp = datetime.now(TZ)
    name = stamp.strftime("%Y-%m-%d-%H%M")
    header = (f"# Informe AgroCraft · {stamp.strftime('%Y-%m-%d %H:%M')} (hora Colombia)\n\n"
              f"Ventana: últimas {hours} h · "
              + (f"Análisis generado por IA ({model}); verificar contra las gráficas.\n\n" if model else "\n\n"))
    body = header + "## Análisis\n\n" + analysis + "\n\n" + stats_markdown(data)
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    (REPORT_DIR / f"{name}.md").write_text(body, encoding="utf-8")
    (REPORT_DIR / f"{name}.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    summary = analysis.split("\n\n")[0:3]
    return {"name": name, "model": model, "summary": "\n\n".join(summary)[:1500]}


def list_reports() -> list:
    if not REPORT_DIR.exists():
        return []
    return sorted((p.stem for p in REPORT_DIR.glob("*.md")), reverse=True)
