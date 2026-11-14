"""Página HTML de un informe del analista: indicadores, gráficas de la ventana y análisis formateado.

El texto del modelo se escapa antes de convertir el Markdown (la página es pública: ningún HTML que
venga del modelo se ejecuta). Las gráficas usan Chart.js con los datos guardados en el JSON del informe.
"""

import html
import json

import markdown

CHARTS = [  # título, [(serie, etiqueta, color, eje)]
    ("Temperatura: exterior real, gemelo e invernadero de referencia",
     [("api-clima/temp_c", "Exterior (API)", "#e07b24", "y"), ("mc-invernadero/temp_c", "Invernadero gemelo", "#c8393f", "y"),
      ("ds-invernadero/temp_c", "Wageningen (dataset)", "#7b4fb3", "y")], "°C", None),
    ("Agua: humedad del suelo y nivel del tanque",
     [("mc-parcela-a/soil_pct", "Suelo A", "#3a9a4a", "y"), ("mc-parcela-b/soil_pct", "Suelo B", "#c9a227", "y"),
      ("mc-tanque/level_pct", "Tanque", "#2f6db3", "y")], "%", None),
    ("Corral: estrés térmico (THI) y bienestar del rebaño",
     [("mc-corral/thi", "THI", "#e07b24", "y"), ("mc-corral/welfare_pct", "Bienestar %", "#3a9a4a", "y1")], "THI", "%"),
    ("Clima real: radiación y humedad",
     [("api-clima/radiation_wm2", "Radiación W/m²", "#c9a227", "y"), ("api-clima/hum_pct", "Humedad %", "#2f6db3", "y1")], "W/m²", "%"),
    ("Aire exterior y energía de la finca",
     [("api-aire/pm2_5", "PM2.5 µg/m³", "#c8393f", "y"), ("ds-energia/power_kw", "Potencia kW", "#2a9d98", "y1")], "µg/m³", "kW"),
    ("Gateway real (PC del operario)",
     [("gw-pc-01/cpu_pct", "CPU %", "#e07b24", "y"), ("gw-pc-01/ram_pct", "RAM %", "#2f6db3", "y")], "%", None),
]


def _stat(data, device, field, fn):
    for key, fields in data.get("estadisticas", {}).items():
        if key.endswith("/" + device) and field in fields:
            return fields[field].get(fn)
    return None


def _num(value, decimals=1, suffix=""):
    if value is None:
        return "—"
    return f"{value:.{decimals}f}".replace(".", ",") + suffix


def _kpis(data):
    firing = sum(1 for a in data.get("alertas", []) if a.get("estado") == "firing")
    cards = [
        ("Exterior", f"{_num(_stat(data, 'api-clima', 'temp_c', 'min'))} – {_num(_stat(data, 'api-clima', 'temp_c', 'max'))} °C", "mín – máx"),
        ("Invernadero", _num(_stat(data, "mc-invernadero", "temp_c", "max"), 1, " °C"), "máxima"),
        ("Suelo A / B", f"{_num(_stat(data, 'mc-parcela-a', 'soil_pct', 'min'), 0)} / {_num(_stat(data, 'mc-parcela-b', 'soil_pct', 'min'), 0)} %", "mínimo"),
        ("Tanque", _num(_stat(data, "mc-tanque", "level_pct", "last"), 0, " %"), "nivel actual"),
        ("Rebaño", _num(_stat(data, "mc-corral", "cows_total", "last"), 0, " vacas"), f"bienestar {_num(_stat(data, 'mc-corral', 'welfare_pct', 'last'), 0, ' %')}"),
        ("Carne asada", _num(_stat(data, "mc-corral", "beef_total", "last"), 0), "producida (acum.)"),
        ("Alertas", str(firing), "disparadas"),
        ("Comandos", str(len(data.get("comandos", []))), "de retroceso"),
    ]
    return "".join(f'<div class="kpi"><span>{html.escape(t)}</span><b>{html.escape(v)}</b><small>{html.escape(n)}</small></div>'
                   for t, v, n in cards)


def _analysis_html(text):
    safe = html.escape(text or "", quote=False)
    body = markdown.markdown(safe, extensions=["tables", "sane_lists"])
    return body.replace("<h1>", "<h2>").replace("</h1>", "</h2>")


def _tables(data):
    out = []
    for device in sorted(data.get("estadisticas", {})):
        rows = "".join(
            f"<tr><td>{html.escape(f)}</td><td>{_num(s.get('mean'), 2)}</td><td>{_num(s.get('min'), 2)}</td>"
            f"<td>{_num(s.get('max'), 2)}</td><td>{_num(s.get('last'), 2)}</td><td>{int(s.get('count') or 0)}</td></tr>"
            for f, s in sorted(data["estadisticas"][device].items()))
        out.append(f"<details><summary>{html.escape(device)}</summary><table><thead><tr><th>Variable</th><th>Promedio</th>"
                   f"<th>Mín.</th><th>Máx.</th><th>Último</th><th>Muestras</th></tr></thead><tbody>{rows}</tbody></table></details>")
    return "".join(out)


def _events(data):
    alerts = "".join(
        f"<li><b>{html.escape(str(a.get('hora', ''))[11:16])}</b> {'🔴' if a.get('estado') == 'firing' else '✅'} "
        f"{html.escape(str(a.get('alerta')))} · <code>{html.escape(str(a.get('device') or ''))}</code></li>"
        for a in data.get("alertas", []) if not str(a.get("alerta", "")).startswith("Prueba"))
    commands = "".join(
        f"<li><b>{html.escape(str(c.get('hora', ''))[11:16])}</b> <code>{html.escape(str(c.get('device')))}</code> "
        f"{html.escape(str(c.get('accion')))} <span class=muted>({html.escape(str(c.get('origen')))})</span></li>"
        for c in data.get("comandos", []))
    anomalies = "".join(
        f"<li><b>{html.escape(str(a.get('hora', ''))[11:16])}</b> {'🔎' if a.get('tipo') == 'anomaly_open' else '✅'} "
        f"<code>{html.escape(str(a.get('device')))}</code> {html.escape(str(a.get('variable')))}: "
        f"{_num(a.get('valor'), 1)} (esperado {_num(a.get('esperado'), 1)}, z {_num(a.get('z'), 1)})</li>"
        for a in data.get("anomalias", []))
    return (alerts or "<li>Sin alertas.</li>", commands or "<li>Sin comandos.</li>",
            anomalies or "<li>Sin anomalías respecto al patrón diario.</li>")


def render(name: str, data: dict, analysis: str, model: str | None) -> str:
    charts = [{"title": t, "items": [{"key": k, "label": lab, "color": c, "axis": ax} for k, lab, c, ax in items],
               "y": y, "y1": y1} for t, items, y, y1 in CHARTS]
    payload = json.dumps({"series": data.get("series", {}), "charts": charts}).replace("</", "<\\/")
    alerts, commands, anomalies = _events(data)
    generated = html.escape(str(data.get("generado", ""))).replace("T", " ")
    model_note = (f"Análisis generado por <b>{html.escape(model)}</b>. Verificar cada afirmación contra las gráficas."
                  if model else "Análisis con IA no disponible para este informe.")
    has_series = bool(data.get("series"))
    return PAGE.format(
        name=html.escape(name), generated=generated, hours=int(data.get("ventana_horas", 24)), model_note=model_note,
        kpis=_kpis(data), analysis=_analysis_html(analysis), tables=_tables(data), alerts=alerts, commands=commands, anomalies=anomalies,
        charts="" if has_series else '<p class="muted">Este informe es anterior a las gráficas: no guardó series de tiempo.</p>',
        payload=payload)


PAGE = """<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Informe AgroCraft {name}</title>
<style>
:root {{ --bg:#f5f7fb; --card:#fff; --ink:#1d2433; --muted:#5b6475; --line:#e3e7ef; --accent:#2f6db3; --soft:#eef3fa; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#0f1115; --card:#181b22; --ink:#dbe0e8; --muted:#8a93a3; --line:#2a2f3a; --accent:#6ea2e6; --soft:#1f2530; }} }}
* {{ box-sizing:border-box; }}
body {{ margin:0; background:var(--bg); color:var(--ink); font:15px/1.55 "Segoe UI",Roboto,system-ui,sans-serif; }}
header {{ background:linear-gradient(120deg,#244f86,#2f6db3 60%,#3a9a4a); color:#fff; padding:28px 20px 34px; }}
header .wrap, main {{ max-width:1100px; margin:0 auto; }}
header a {{ color:#d9e7ff; text-decoration:none; font-size:13px; }}
header h1 {{ margin:8px 0 4px; font-size:28px; }}
header p {{ margin:0; opacity:.9; }}
main {{ padding:0 16px 60px; }}
.kpis {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:12px; margin-top:-22px; }}
.kpi {{ background:var(--card); border:1px solid var(--line); border-radius:12px; padding:12px 14px; box-shadow:0 2px 8px #0000000d; }}
.kpi span {{ display:block; font-size:12px; color:var(--muted); text-transform:uppercase; letter-spacing:.5px; }}
.kpi b {{ display:block; font-size:21px; margin:2px 0; font-variant-numeric:tabular-nums; }}
.kpi small {{ color:var(--muted); font-size:12px; }}
section {{ background:var(--card); border:1px solid var(--line); border-radius:12px; padding:18px 22px; margin-top:18px; }}
section > h2 {{ margin:0 0 12px; font-size:18px; color:var(--accent); }}
.grid {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(480px,1fr)); gap:16px; }}
.chart {{ border:1px solid var(--line); border-radius:10px; padding:10px 12px 6px; }}
.chart h3 {{ margin:0 0 6px; font-size:14px; }}
.chart canvas {{ width:100% !important; height:230px !important; }}
.analysis h2 {{ font-size:17px; color:var(--accent); margin:20px 0 8px; border-bottom:1px solid var(--line); padding-bottom:4px; }}
.analysis h3 {{ font-size:15px; margin:14px 0 6px; }}
.analysis p, .analysis li {{ text-align:justify; }}
.analysis strong {{ color:var(--ink); }}
.note {{ background:var(--soft); border-left:4px solid var(--accent); padding:8px 12px; border-radius:6px; font-size:13px; color:var(--muted); }}
.cols {{ display:grid; grid-template-columns:1fr 1fr; gap:16px; }}
ul.list {{ margin:0; padding-left:18px; font-size:14px; max-height:320px; overflow:auto; }}
code {{ font-family:Consolas,monospace; font-size:12.5px; background:var(--soft); padding:0 4px; border-radius:4px; }}
details {{ border-bottom:1px solid var(--line); padding:6px 0; }}
summary {{ cursor:pointer; font-weight:600; }}
table {{ width:100%; border-collapse:collapse; font-size:13px; margin:8px 0; }}
th {{ text-align:left; color:var(--muted); font-weight:600; border-bottom:1px solid var(--line); padding:4px 6px; }}
td {{ border-bottom:1px solid var(--line); padding:4px 6px; font-variant-numeric:tabular-nums; }}
.muted {{ color:var(--muted); }}
@media (max-width:640px) {{ .grid, .cols {{ grid-template-columns:1fr; }} header h1 {{ font-size:22px; }} }}
@media print {{ body {{ background:#fff; }} section {{ break-inside:avoid; }} details {{ display:block; }} }}
</style></head>
<body>
<header><div class="wrap">
  <a href="/iot/reportes/">← Informes</a> · <a href="/">Dashboards</a> · <a href="/iot/">Panel de control</a>
  <h1>Informe AgroCraft</h1>
  <p>{generated} (hora Colombia) · ventana de las últimas {hours} h</p>
</div></header>
<main>
  <div class="kpis">{kpis}</div>
  <section><h2>Gráficas de la ventana</h2>{charts}<div class="grid" id="charts"></div></section>
  <section class="analysis"><h2>Análisis</h2><p class="note">{model_note}</p>{analysis}</section>
  <section class="cols">
    <div><h2 style="color:var(--accent);font-size:17px;margin-top:0">Alertas</h2><ul class="list">{alerts}</ul></div>
    <div><h2 style="color:var(--accent);font-size:17px;margin-top:0">Comandos de retroceso</h2><ul class="list">{commands}</ul></div>
  </section>
  <section><h2>Anomalías (patrón diario)</h2><p class="muted" style="margin-top:0">Promedio de 30 min comparado con la misma hora de los días anteriores; se abre con |z| ≥ 3 y se cierra con |z| &lt; 2.</p><ul class="list">{anomalies}</ul></section>
  <section><h2>Estadísticas por dispositivo</h2><p class="muted" style="margin-top:0">Promedio, mínimo, máximo, último valor y número de muestras de la ventana.</p>{tables}</section>
</main>
<script type="application/json" id="data">{payload}</script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js"></script>
<script>
(function () {{
  const data = JSON.parse(document.getElementById("data").textContent);
  if (!window.Chart || !Object.keys(data.series).length) return;
  const dark = matchMedia("(prefers-color-scheme: dark)").matches;
  Chart.defaults.color = dark ? "#aab3c2" : "#5b6475";
  Chart.defaults.borderColor = dark ? "#2a2f3a" : "#e3e7ef";
  Chart.defaults.font.family = "Segoe UI, Roboto, system-ui, sans-serif";
  const box = document.getElementById("charts");
  for (const chart of data.charts) {{
    const items = chart.items.filter(i => data.series[i.key]);
    if (!items.length) continue;
    const labels = data.series[items[0].key].t;
    const card = document.createElement("div");
    card.className = "chart";
    card.innerHTML = "<h3></h3><canvas></canvas>";
    card.querySelector("h3").textContent = chart.title;
    box.appendChild(card);
    const scales = {{ x: {{ ticks: {{ maxTicksLimit: 8, maxRotation: 0 }} }}, y: {{ title: {{ display: true, text: chart.y }} }} }};
    if (chart.y1) scales.y1 = {{ position: "right", grid: {{ drawOnChartArea: false }}, title: {{ display: true, text: chart.y1 }} }};
    new Chart(card.querySelector("canvas"), {{
      type: "line",
      data: {{ labels, datasets: items.map(i => ({{ label: i.label, data: data.series[i.key].v, borderColor: i.color,
        backgroundColor: i.color + "22", yAxisID: i.axis, borderWidth: 1.8, pointRadius: 0, tension: .3, spanGaps: true }})) }},
      options: {{ responsive: true, maintainAspectRatio: false, interaction: {{ mode: "index", intersect: false }},
        plugins: {{ legend: {{ labels: {{ boxWidth: 12 }} }} }}, scales }}
    }});
  }}
}})();
</script>
</body></html>
"""
