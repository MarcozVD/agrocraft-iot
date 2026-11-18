// Página «Gráficas»: tablero propio (sin abrir Grafana) que se dibuja con el historial de InfluxDB y
// luego crece punto a punto con cada mensaje que llega en vivo. Cada gráfica explica en una frase qué
// muestra, marca los umbrales con su nombre y resume promedio, mínimo y máximo del rango elegido.

import { DEVICES, fieldLabel, formatValue } from "./catalog.js";
import { assess, HELP } from "./glossary.js";

const PALETTE = ["#7bc86c", "#5fb2ff", "#e8a33d", "#c08cff", "#ff7a6b", "#4fd1c5"];

// s: [dispositivo, campo, nombre de la serie]; th: umbrales [valor, texto]; step: encendido/apagado
export const SECTIONS = [
  { title: "Clima: lo real y el gemelo", text: "El clima real de Bucaramanga llega por API y mueve a los gemelos del mundo.", charts: [
    { title: "Temperatura", unit: "°C", what: "Compara el aire real de Bucaramanga con el invernadero del gemelo (que suma calor del sol) y con un invernadero real de Países Bajos.",
      s: [["api-clima", "temp_c", "Bucaramanga (real)"], ["mc-invernadero", "temp_c", "Invernadero gemelo"], ["ds-invernadero", "temp_c", "Invernadero Wageningen"]],
      th: [[28, "ventilas se abren"]] },
    { title: "Humedad del aire", unit: "%", what: "Cuánto vapor de agua tiene el aire afuera y dentro de los invernaderos.",
      s: [["api-clima", "hum_pct", "Bucaramanga"], ["mc-invernadero", "hum_pct", "Invernadero gemelo"], ["ds-invernadero", "hum_pct", "Wageningen"]] },
    { title: "Sol", unit: "W/m²", what: "Energía solar que llega al suelo: sube de mañana, pico al mediodía y 0 de noche.",
      s: [["api-clima", "radiation_wm2", "Radiación solar"]], th: [[150, "lámparas si es de día"]] },
    { title: "Lluvia", unit: "mm", what: "Lluvia real por hora y pronóstico de las próximas 24 h. El agua de lluvia también llena el tanque.",
      s: [["api-clima", "rain_mm", "Lluvia última hora"], ["api-pronostico", "rain_next24_mm", "Esperada en 24 h"]], th: [[5, "riego reducido"]] },
  ] },
  { title: "Agua y cultivos", text: "Cuándo se riega, cuánta agua queda y cómo crecen las plantas.", charts: [
    { title: "Humedad del suelo", unit: "%", what: "La tierra se seca con el sol (ET0) y se moja con el riego. Si baja de 35 % el riego se abre solo; a 65 % se cierra.",
      s: [["mc-parcela-a", "soil_pct", "Parcela A (trigo)"], ["mc-parcela-b", "soil_pct", "Parcela B (zanahoria y papa)"]],
      th: [[35, "riego se abre"], [65, "riego se cierra"]], min: 0, max: 100 },
    { title: "Tanque de agua", unit: "%", what: "Baja mientras se riega y sube con la bomba o la lluvia. Bajo 25 % la bomba se enciende sola.",
      s: [["mc-tanque", "level_pct", "Nivel del tanque"]], th: [[25, "bomba se enciende"], [90, "bomba se apaga"]], min: 0, max: 100 },
    { title: "Crecimiento", unit: "%", what: "Avance de cada cultivo. Al llegar a 100 % se cosecha (cae a 0) y el grano va al silo.",
      s: [["mc-parcela-a", "growth_pct", "Parcela A"], ["mc-parcela-b", "growth_pct", "Parcela B"], ["mc-silo", "fill_pct", "Silo lleno"]], min: 0, max: 100 },
    { title: "Riego y bomba", unit: "", what: "Arriba = encendido, abajo = apagado. Así se ve cada vez que el sistema actuó solo.",
      s: [["mc-parcela-a", "irrigation", "Riego A"], ["mc-parcela-b", "irrigation", "Riego B"], ["mc-tanque", "pump", "Bomba"]], step: true, min: -0.15, max: 1.15 },
  ] },
  { title: "Ganado", text: "Comodidad y alimentación de las vacas del corral.", charts: [
    { title: "Calor del ganado (THI)", unit: "", what: "Índice que mezcla temperatura y humedad. Menos de 72 las vacas están cómodas; sobre 78 sufren estrés.",
      s: [["mc-corral", "thi", "THI del corral"], ["api-pronostico", "thi_max_next24", "THI máximo esperado"]], th: [[72, "estrés leve"], [78, "estrés"]] },
    { title: "Bienestar y alimento", unit: "%", what: "El bienestar baja con calor, hambre o sed. El comedero se rellena con trigo del silo.",
      s: [["mc-corral", "welfare_pct", "Bienestar"], ["mc-corral", "feed_pct", "Comedero"], ["mc-corral", "water_pct", "Bebedero"]], min: 0, max: 100 },
  ] },
  { title: "Aire y energía", text: "Calidad del aire real y consumo eléctrico de un hogar (dataset por AMQP).", charts: [
    { title: "Partículas finas PM2.5", unit: "µg/m³", what: "Contaminación que se respira. La OMS recomienda menos de 15.",
      s: [["api-aire", "pm2_5", "PM2.5"], ["api-aire", "pm10", "PM10"]], th: [[15, "límite OMS"]] },
    { title: "Índice de calidad del aire", unit: "", what: "0 a 50 buena, 51 a 100 moderada. Sobre 100 se cierran las ventilas del invernadero.",
      s: [["api-aire", "us_aqi", "US AQI"]], th: [[50, "buena"], [100, "se cierran ventilas"]] },
    { title: "Consumo eléctrico", unit: "kW", what: "Potencia de un hogar real (dataset UCI) que llega cada minuto por RabbitMQ (AMQP).",
      s: [["ds-energia", "power_kw", "Potencia"]] },
  ] },
  { title: "Dispositivos reales y plataforma", text: "El PC y el celular del equipo, y la salud del servidor que corre los gemelos.", charts: [
    { title: "PC gateway", unit: "%", what: "Uso de procesador y memoria del PC que envía por MQTT sobre TLS.",
      s: [["gw-pc-01", "cpu_pct", "CPU"], ["gw-pc-01", "ram_pct", "RAM"]], th: [[90, "saturado"]], min: 0, max: 100 },
    { title: "Batería", unit: "%", what: "Carga del PC y del celular.", s: [["gw-pc-01", "battery_pct", "PC"], ["phone-01", "battery_pct", "Celular"]], min: 0, max: 100 },
    { title: "Presión del aire (celular)", unit: "hPa", what: "El barómetro del iPhone por HTTPS. Cambia con el clima y con la altura a la que esté el teléfono.",
      s: [["phone-01", "pressure_hpa", "Celular"], ["api-clima", "pressure_hpa", "Bucaramanga (API)"]] },
    { title: "Servidor del gemelo", unit: "TPS", what: "Velocidad del mundo de Minecraft: 20 es perfecto. Si baja, los gemelos se mueven más lento.",
      s: [["mc-server", "tps", "TPS"]], th: [[15, "lento"]], min: 0, max: 21 },
  ] },
];

export const RANGES = [["15 min", 15], ["1 h", 60], ["6 h", 360], ["24 h", 1440], ["6 días", 8640]];

const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

export class Charts {
  constructor(root, getJSON) {
    this.root = root;
    this.getJSON = getJSON;
    this.minutes = 60;
    this.cards = [];
    this.data = new Map();               // "dev/campo" -> [[t, v], ...]
    this.built = false;
  }

  keys() {
    const all = new Set();
    for (const section of SECTIONS) for (const chart of section.charts) for (const [d, f] of chart.s) all.add(`${d}/${f}`);
    return [...all];
  }

  build() {
    this.root.innerHTML = "";
    const bar = document.createElement("div");
    bar.className = "charts-bar";
    bar.innerHTML = `<div><h2>Gráficas en vivo</h2><p>Cada línea se dibuja con lo guardado en la base de datos y sigue creciendo con cada mensaje que llega. Pasa el mouse sobre una gráfica para ver el valor exacto.</p></div>
      <div class="segmented small" id="chRange">${RANGES.map(([label, m]) => `<button data-m="${m}" class="${m === this.minutes ? "on" : ""}">${label}</button>`).join("")}</div>`;
    this.root.append(bar);
    const summary = document.createElement("div");
    summary.className = "summary";
    summary.id = "chSummary";
    this.root.append(summary);
    bar.querySelectorAll("[data-m]").forEach((b) => b.addEventListener("click", () => {
      this.minutes = +b.dataset.m;
      bar.querySelectorAll("[data-m]").forEach((o) => o.classList.toggle("on", o === b));
      this.load();
    }));
    for (const section of SECTIONS) {
      const sec = document.createElement("section");
      sec.className = "ch-section";
      sec.innerHTML = `<header><h3>${section.title}</h3><p>${section.text}</p></header><div class="ch-grid"></div>`;
      const grid = sec.querySelector(".ch-grid");
      for (const spec of section.charts) {
        const card = document.createElement("article");
        card.className = "ch-card";
        card.innerHTML = `<header><div><h4>${spec.title}${spec.unit ? ` <small>${spec.unit}</small>` : ""}</h4><p>${spec.what}</p></div><span class="ch-live">● en vivo</span></header>
          <div class="ch-legend"></div><div class="ch-plot"></div><table class="ch-stats"><thead><tr><th>Serie</th><th>Ahora</th><th>Estado</th><th>Prom.</th><th>Mín.</th><th>Máx.</th></tr></thead><tbody></tbody></table>`;
        grid.append(card);
        this.cards.push({ spec, el: card, plot: null, dirty: false });
      }
      this.root.append(sec);
    }
    this.built = true;
    new ResizeObserver(() => this.resize()).observe(this.root);
  }

  async load() {
    if (!this.built) this.build();
    this.root.classList.add("loading-data");
    try {
      const { series } = await this.getJSON(`/series?minutes=${this.minutes}&keys=${encodeURIComponent(this.keys().join(","))}`);
      this.data = new Map(Object.entries(series));
    } catch {
      this.data = new Map();
    }
    this.root.classList.remove("loading-data");
    for (const card of this.cards) this.draw(card, true);
  }

  // Un mensaje en vivo: agrega el punto a las series que lo usan
  push(device, telemetry, t) {
    if (!this.built) return;
    const cut = Date.now() - this.minutes * 60000;
    for (const [field, value] of Object.entries(telemetry)) {
      if (typeof value !== "number") continue;
      const key = `${device}/${field}`;
      const points = this.data.get(key);
      if (!points && !this.keys().includes(key)) continue;
      const list = points || [];
      list.push([t, value]);
      while (list.length && list[0][0] < cut) list.shift();
      this.data.set(key, list);
      for (const card of this.cards) if (card.spec.s.some(([d, f]) => d === device && f === field)) card.dirty = true;
    }
  }

  flush(visible) {
    if (!visible) return;
    for (const card of this.cards) {
      if (card.dirty) {
        card.dirty = false;
        this.draw(card, false);
        card.el.querySelector(".ch-live").classList.remove("blink");
        void card.el.offsetWidth;
        card.el.querySelector(".ch-live").classList.add("blink");
      }
    }
  }

  draw(card, rebuild) {
    const { spec, el } = card;
    const tables = spec.s.map(([d, f]) => {
      const pts = this.data.get(`${d}/${f}`) || [];
      return [pts.map((p) => p[0] / 1000), pts.map((p) => p[1])];
    });
    const joined = window.uPlot.join(tables.length ? tables : [[[], []]]);
    const tbody = el.querySelector(".ch-stats tbody");
    tbody.innerHTML = spec.s.map(([d, f, name], i) => {
      const ys = tables[i][1];
      if (!ys.length) return `<tr><td><i style="background:${PALETTE[i]}"></i>${name}</td><td colspan="5" class="muted">sin datos en este rango</td></tr>`;
      const last = ys[ys.length - 1];
      const avg = ys.reduce((a, b) => a + b, 0) / ys.length;
      const st = assess(f, last, d);
      const fmt = (v) => (spec.step ? (v >= 0.5 ? "Encendido" : "Apagado") : formatValue(f, v));
      return `<tr><td><i style="background:${PALETTE[i]}"></i>${name}</td><td><b>${fmt(last)}</b></td>
        <td>${st.text ? `<span class="lvl ${st.level}">${st.text}</span>` : ""}</td>
        <td>${spec.step ? `${Math.round(avg * 100)} % del tiempo` : formatValue(f, avg)}</td><td>${spec.step ? "" : formatValue(f, Math.min(...ys))}</td><td>${spec.step ? "" : formatValue(f, Math.max(...ys))}</td></tr>`;
    }).join("");
    el.querySelector(".ch-legend").innerHTML = spec.s.map(([d, f, name], i) =>
      `<span title="${(HELP[f] || fieldLabel(f)).replace(/"/g, "&quot;")} · ${DEVICES[d]?.name || d}"><i style="background:${PALETTE[i]}"></i>${name}</span>`).join("")
      + (spec.th || []).map(([v, label]) => `<span class="th">┄ ${label} (${v}${spec.unit && spec.unit !== "TPS" ? " " + spec.unit : ""})</span>`).join("");
    const host = el.querySelector(".ch-plot");
    if (rebuild || !card.plot) {
      if (card.plot) card.plot.destroy();
      card.plot = new window.uPlot(this.options(spec, host.clientWidth || 520), joined, host);
    } else {
      card.plot.setData(joined);
    }
  }

  options(spec, width) {
    const grid = { stroke: "rgba(255,255,255,0.06)", width: 1 };
    const axis = { stroke: css("--muted") || "#9fb3a5", grid, ticks: { stroke: "rgba(255,255,255,0.1)" }, font: "11px Inter, sans-serif" };
    const thresholds = spec.th || [];
    return {
      width, height: 190, padding: [8, 8, 0, 0],
      cursor: { points: { size: 7 }, drag: { x: false, y: false } },
      legend: { show: true, live: true },
      scales: { x: { time: true }, y: spec.min !== undefined ? { range: [spec.min, spec.max] } : { range: (u, lo, hi) => {
        const values = thresholds.map((t) => t[0]).concat([lo, hi]).filter((v) => v !== null && Number.isFinite(v));
        if (!values.length) return [0, 1];
        const a = Math.min(...values), b = Math.max(...values), pad = (b - a) * 0.12 || 1;
        return [a - pad, b + pad];
      } } },
      axes: [{ ...axis, values: (u, ticks) => ticks.map((t) => new Date(t * 1000).toLocaleString("es-CO", this.minutes > 1440
        ? { day: "2-digit", hour: "2-digit", hour12: false } : { hour: "2-digit", minute: "2-digit", hour12: false })) },
             { ...axis, size: 48, ...(spec.step ? { splits: () => [0, 1] } : {}), values: (u, ticks) => ticks.map((v) => spec.step ? (Math.abs(v - 1) < 0.01 ? "ON" : Math.abs(v) < 0.01 ? "OFF" : "") : +v.toFixed(2)) }],
      series: [{ value: (u, v) => (v === null ? "—" : new Date(v * 1000).toLocaleString("es-CO", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit", hour12: false })) },
        ...spec.s.map(([d, f, name], i) => ({
          label: name, stroke: PALETTE[i], width: 2, spanGaps: true,
          fill: spec.s.length === 1 ? PALETTE[i] + "22" : undefined,
          paths: spec.step ? window.uPlot.paths.stepped({ align: 1 }) : undefined,
          value: (u, v) => (v === null || v === undefined ? "—" : spec.step ? (v >= 0.5 ? "ON" : "OFF") : formatValue(f, v)),
        }))],
      hooks: { setCursor: [(u) => this.tooltip(u, spec)], draw: [(u) => {
        const ctx = u.ctx;
        ctx.save();
        for (const [value, label] of thresholds) {
          const y = u.valToPos(value, "y", true);
          if (y < u.bbox.top || y > u.bbox.top + u.bbox.height) continue;
          ctx.strokeStyle = "rgba(255, 209, 102, 0.75)";
          ctx.setLineDash([6, 5]);
          ctx.lineWidth = 1.2 * devicePixelRatio;
          ctx.beginPath();
          ctx.moveTo(u.bbox.left, y);
          ctx.lineTo(u.bbox.left + u.bbox.width, y);
          ctx.stroke();
          ctx.setLineDash([]);
          ctx.fillStyle = "rgba(255, 209, 102, 0.9)";
          ctx.font = `${11 * devicePixelRatio}px Inter, sans-serif`;
          ctx.textAlign = "right";
          ctx.textBaseline = "bottom";
          ctx.fillText(label, u.bbox.left + u.bbox.width - 6 * devicePixelRatio, y - 3 * devicePixelRatio);
        }
        ctx.restore();
      }] },
    };
  }

  // Cuadro con la hora y el valor de cada serie bajo el cursor
  tooltip(u, spec) {
    const host = u.root.parentElement;
    let tip = host.querySelector(".ch-tip");
    if (!tip) {
      tip = document.createElement("div");
      tip.className = "ch-tip";
      host.append(tip);
    }
    const idx = u.cursor.idx;
    if (idx === null || idx === undefined || u.cursor.left < 0) {
      tip.style.display = "none";
      return;
    }
    const t = u.data[0][idx];
    const rows = spec.s.map(([d, f, name], i) => {
      let v = u.data[i + 1][idx];
      for (let k = idx; (v === null || v === undefined) && k >= 0; k--) v = u.data[i + 1][k];
      const text = v === null || v === undefined ? "—" : spec.step ? (v >= 0.5 ? "Encendido" : "Apagado") : formatValue(f, v);
      return `<div><i style="background:${PALETTE[i]}"></i>${name}: <b>${text}</b></div>`;
    }).join("");
    tip.innerHTML = `<time>${new Date(t * 1000).toLocaleString("es-CO", { weekday: "short", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false })}</time>${rows}`;
    tip.style.display = "block";
    const x = u.cursor.left + u.over.offsetLeft, w = tip.offsetWidth;
    tip.style.left = `${Math.min(Math.max(0, x + 14), host.clientWidth - w)}px`;
    tip.style.top = `${Math.max(0, u.cursor.top - 10)}px`;
  }

  resize() {
    for (const card of this.cards) {
      if (!card.plot) continue;
      const w = card.el.querySelector(".ch-plot").clientWidth;
      if (w && Math.abs(w - card.plot.width) > 4) card.plot.setSize({ width: w, height: 190 });
    }
  }
}
