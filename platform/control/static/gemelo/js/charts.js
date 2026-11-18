// Gráficas en vivo (uPlot) para el detalle de un dispositivo en el Panel de control: se dibujan con el
// historial de InfluxDB y crecen punto a punto con cada mensaje que llega. Cada una dice en una frase qué
// muestra, marca los umbrales con su nombre y resume promedio, mínimo y máximo del rango elegido.

import { fieldLabel, formatValue, FIELDS } from "./catalog.js";
import { assess, HELP, thresholds, PERCENT_FIELDS } from "./glossary.js";

const PALETTE = ["#7bc86c", "#5fb2ff", "#e8a33d", "#c08cff", "#ff7a6b", "#4fd1c5"];
export const RANGES = [["15 min", 15], ["1 h", 60], ["6 h", 360], ["24 h", 1440], ["6 días", 8640]];

// Una gráfica por variable del dispositivo
export function deviceSpecs(device, fields) {
  return fields.map((field) => {
    const unit = (FIELDS[field] || ["", ""])[1];
    const step = unit === "bool";
    return {
      title: fieldLabel(field), unit: step ? "" : unit, what: HELP[field] || "", s: [[device, field, fieldLabel(field)]],
      th: step ? [] : thresholds(device, field), step,
      ...(step ? { min: -0.15, max: 1.15 } : PERCENT_FIELDS.has(field) ? { min: 0, max: 100 } : {}),
    };
  });
}

export class ChartGrid {
  constructor(host, getJSON, specs) {
    this.host = host;
    this.getJSON = getJSON;
    this.specs = specs;
    this.minutes = 60;
    this.cards = [];
    this.data = new Map();               // "dev/campo" -> [[t, v], ...]
    this.keys = new Set(specs.flatMap((s) => s.s.map(([d, f]) => `${d}/${f}`)));
    this.build();
    this.observer = new ResizeObserver(() => this.resize());
    this.observer.observe(host);
  }

  build() {
    this.host.innerHTML = "";
    for (const spec of this.specs) {
      const card = document.createElement("article");
      card.className = "ch-card";
      card.innerHTML = `<header><div><h4>${spec.title}${spec.unit ? ` <small>${spec.unit}</small>` : ""}</h4>${spec.what ? `<p>${spec.what}</p>` : ""}</div><span class="ch-live">● en vivo</span></header>
        <div class="ch-legend"></div><div class="ch-plot"></div><table class="ch-stats"><thead><tr><th>Serie</th><th>Ahora</th><th>Estado</th><th>Prom.</th><th>Mín.</th><th>Máx.</th></tr></thead><tbody></tbody></table>`;
      this.host.append(card);
      this.cards.push({ spec, el: card, plot: null, dirty: false });
    }
  }

  async load(minutes = this.minutes) {
    this.minutes = minutes;
    this.host.classList.add("loading-data");
    try {
      const { series } = await this.getJSON(`/series?minutes=${minutes}&keys=${encodeURIComponent([...this.keys].join(","))}`);
      this.data = new Map(Object.entries(series));
    } catch {
      this.data = new Map();
    }
    this.host.classList.remove("loading-data");
    for (const card of this.cards) this.draw(card, true);
  }

  // Un mensaje en vivo: agrega el punto a las series que lo usan
  push(device, telemetry, t) {
    const cut = Date.now() - this.minutes * 60000;
    for (const [field, value] of Object.entries(telemetry)) {
      const key = `${device}/${field}`;
      if (typeof value !== "number" || !this.keys.has(key)) continue;
      const list = this.data.get(key) || [];
      list.push([t, value]);
      while (list.length && list[0][0] < cut) list.shift();
      this.data.set(key, list);
      for (const card of this.cards) if (card.spec.s.some(([d, f]) => d === device && f === field)) card.dirty = true;
    }
  }

  flush() {
    for (const card of this.cards) {
      if (!card.dirty) continue;
      card.dirty = false;
      this.draw(card, false);
      const live = card.el.querySelector(".ch-live");
      live.classList.remove("blink");
      void live.offsetWidth;
      live.classList.add("blink");
    }
  }

  destroy() {
    this.observer.disconnect();
    for (const card of this.cards) card.plot?.destroy();
    this.cards = [];
  }

  draw(card, rebuild) {
    const { spec, el } = card;
    const tables = spec.s.map(([d, f]) => {
      const pts = this.data.get(`${d}/${f}`) || [];
      return [pts.map((p) => p[0] / 1000), pts.map((p) => p[1])];
    });
    const joined = window.uPlot.join(tables);
    const fmt = (f, v) => (spec.step ? (v >= 0.5 ? "Encendido" : "Apagado") : formatValue(f, v));
    el.querySelector(".ch-stats tbody").innerHTML = spec.s.map(([d, f, name], i) => {
      const ys = tables[i][1];
      if (!ys.length) return `<tr><td><i style="background:${PALETTE[i]}"></i>${name}</td><td colspan="5" class="muted">sin datos en este rango</td></tr>`;
      const last = ys[ys.length - 1];
      const avg = ys.reduce((a, b) => a + b, 0) / ys.length;
      const st = assess(f, last, d.startsWith("sim-") ? "mc-" + d.slice(4) : d);
      let lo = Infinity, hi = -Infinity;
      for (const y of ys) { lo = Math.min(lo, y); hi = Math.max(hi, y); }
      return `<tr><td><i style="background:${PALETTE[i]}"></i>${name}</td><td><b>${fmt(f, last)}</b></td>
        <td>${st.text ? `<span class="lvl ${st.level}">${st.text}</span>` : ""}</td>
        <td>${spec.step ? `${Math.round(avg * 100)} % del tiempo` : formatValue(f, avg)}</td>
        <td>${spec.step ? "" : formatValue(f, lo)}</td><td>${spec.step ? "" : formatValue(f, hi)}</td></tr>`;
    }).join("");
    el.querySelector(".ch-legend").innerHTML = (spec.th || [])
      .map(([v, label]) => `<span class="th">┄ ${label} (${formatValue(spec.s[0][1], v)})</span>`).join("");
    const host = el.querySelector(".ch-plot");
    if (rebuild || !card.plot) {
      card.plot?.destroy();
      card.plot = new window.uPlot(this.options(spec, host.clientWidth || 520), joined, host);
    } else {
      card.plot.setData(joined);
    }
  }

  options(spec, width) {
    const grid = { stroke: "rgba(255,255,255,0.06)", width: 1 };
    const axis = { stroke: "#9fb3a5", grid, ticks: { stroke: "rgba(255,255,255,0.1)" }, font: "11px Inter, sans-serif" };
    const marks = spec.th || [];
    return {
      width, height: 180, padding: [8, 8, 0, 0],
      cursor: { points: { size: 7 }, drag: { x: false, y: false } },
      legend: { show: false },
      scales: { x: { time: true }, y: spec.min !== undefined ? { range: [spec.min, spec.max] } : { range: (u, lo, hi) => {
        const values = marks.map((t) => t[0]).concat([lo, hi]).filter((v) => v !== null && Number.isFinite(v));
        if (!values.length) return [0, 1];
        const a = Math.min(...values), b = Math.max(...values), pad = (b - a) * 0.12 || 1;
        return [a - pad, b + pad];
      } } },
      axes: [{ ...axis, values: (u, ticks) => ticks.map((t) => new Date(t * 1000).toLocaleString("es-CO", this.minutes > 1440
        ? { day: "2-digit", hour: "2-digit", hour12: false } : { hour: "2-digit", minute: "2-digit", hour12: false })) },
             { ...axis, size: 52, ...(spec.step ? { splits: () => [0, 1] } : {}),
               values: (u, ticks) => ticks.map((v) => (spec.step ? (v >= 0.5 ? "ON" : "OFF") : +v.toFixed(2))) }],
      series: [{}, ...spec.s.map(([, , name], i) => ({
        label: name, stroke: PALETTE[i], width: 2, spanGaps: true,
        fill: spec.s.length === 1 && !spec.step ? PALETTE[i] + "22" : undefined,
        paths: spec.step ? window.uPlot.paths.stepped({ align: 1 }) : undefined,
      }))],
      hooks: { setCursor: [(u) => this.tooltip(u, spec)], draw: [(u) => {
        const ctx = u.ctx;
        ctx.save();
        for (const [value, label] of marks) {
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

  // Cuadro con la hora y el valor bajo el cursor
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
    const rows = spec.s.map(([, f, name], i) => {
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
      if (w && Math.abs(w - card.plot.width) > 4) card.plot.setSize({ width: w, height: 180 });
    }
  }
}
