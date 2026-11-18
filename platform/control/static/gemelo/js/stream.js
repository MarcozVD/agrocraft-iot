// Página «Mensajes»: los mensajes tal como pasan por el bróker, en el momento en que llegan, con su tema,
// protocolo, tamaño y contenido JSON, y una explicación en palabras de qué significa cada uno.

import { DEVICES, CLASSES, fieldLabel, formatValue } from "./catalog.js";
import { assess } from "./glossary.js";

const CHANNELS = {
  telemetry: ["Lectura", "sube", "El dispositivo envía sus mediciones."],
  state: ["Configuración", "sube", "El dispositivo informa cómo está configurado."],
  status: ["Conexión", "sube", "Se conectó o se desconectó del bróker."],
  event: ["Evento", "sube", "Algo pasó: riego, cosecha, alerta, anomalía…"],
  cmd: ["Orden", "baja", "La nube le manda una orden al dispositivo (retroceso)."],
  ack: ["Confirmación", "sube", "El dispositivo responde que cumplió (o no) la orden."],
};
const MAX_KEEP = 1500;
const MAX_ROWS = 300;
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const encoder = new TextEncoder();

function protocolOf(m) {
  if (m.device === "ds-energia" && m.channel === "telemetry") return ["AMQP", "amqp"];
  if (m.device === "phone-01" && m.channel === "telemetry") return ["HTTPS → MQTT", "https"];
  if (m.cls === "platform") return ["MQTT · plataforma", "mqtt"];
  return ["MQTT", "mqtt"];
}

function nameOf(device) {
  if (DEVICES[device]) return DEVICES[device].name;
  if (device.startsWith("sim-")) return `${DEVICES["mc-" + device.slice(4)]?.name || device} (simulación)`;
  return { alertas: "Alertas de Grafana", anomalias: "Detector de anomalías", reglas: "Reglas en la nube", "sim-control": "Control de la simulación" }[device] || device;
}

function highlight(json) {
  return esc(json).replace(/(&quot;[^&]*?&quot;)(\s*:)?|\b(-?\d+(?:\.\d+)?(?:e[+-]?\d+)?)\b|\b(true|false|null)\b/g, (m, str, colon, num, lit) => {
    if (str) return colon ? `<span class="j-key">${str}</span>${colon}` : `<span class="j-str">${str}</span>`;
    if (num) return `<span class="j-num">${num}</span>`;
    return `<span class="j-lit">${lit}</span>`;
  });
}

export class Stream {
  constructor(root, describeEvent) {
    this.root = root;
    this.describeEvent = describeEvent;
    this.messages = [];
    this.paused = false;
    this.pending = 0;
    this.total = 0;
    this.stamps = [];
    this.byProtocol = { mqtt: 0, amqp: 0, https: 0 };
    this.filter = { cls: "", channel: "", q: "" };
    this.open = new Set();
    this.built = false;
    this.seq = 0;
  }

  build() {
    this.root.innerHTML = `
      <div class="st-head">
        <div><h2>Mensajes en vivo</h2>
          <p>Cada fila es un mensaje real que acaba de pasar por el bróker. <b class="up">↑ Sube</b>: del dispositivo a la nube.
          <b class="down">↓ Baja</b>: de la nube al dispositivo (órdenes). Haz clic en un mensaje para verlo completo y explicado en palabras.</p></div>
        <div class="st-counters" id="stCounters"></div>
      </div>
      <div class="st-tools">
        <select id="stCls" aria-label="Filtrar por clase"><option value="">Todas las clases</option>
          ${["twin", "api", "dataset", "real", "sim", "platform"].map((c) => `<option value="${c}">${CLASSES[c].label}</option>`).join("")}</select>
        <select id="stCh" aria-label="Filtrar por tipo de mensaje"><option value="">Todos los tipos</option>
          ${Object.entries(CHANNELS).map(([k, [label]]) => `<option value="${k}">${label} (${k})</option>`).join("")}</select>
        <input id="stQ" type="search" placeholder="Buscar dispositivo, tema o valor" aria-label="Buscar en los mensajes">
        <button id="stPause">❚❚ Pausar</button>
        <button id="stClear">Limpiar</button>
      </div>
      <div class="st-cols" aria-hidden="true"><span>Hora</span><span>Sentido</span><span>Tipo</span><span>Dispositivo · tema</span><span>Contenido (JSON)</span><span>Tamaño</span></div>
      <ol class="st-list" id="stList"></ol>`;
    const $ = (s) => this.root.querySelector(s);
    $("#stCls").addEventListener("change", (e) => { this.filter.cls = e.target.value; this.render(); });
    $("#stCh").addEventListener("change", (e) => { this.filter.channel = e.target.value; this.render(); });
    $("#stQ").addEventListener("input", (e) => { this.filter.q = e.target.value.trim().toLowerCase(); this.render(); });
    $("#stPause").addEventListener("click", () => this.togglePause());
    $("#stClear").addEventListener("click", () => { this.messages = []; this.open.clear(); this.render(); });
    $("#stList").addEventListener("click", (e) => {
      const li = e.target.closest("li[data-id]");
      if (!li || e.target.closest(".st-detail")) return;
      const id = +li.dataset.id;
      if (this.open.has(id)) this.open.delete(id); else this.open.add(id);
      li.classList.toggle("open");
      const msg = this.messages.find((m) => m.id === id);
      if (msg) li.querySelector(".st-detail").innerHTML = this.open.has(id) ? this.detail(msg) : "";
    });
    this.built = true;
    this.render();
  }

  togglePause() {
    this.paused = !this.paused;
    const b = this.root.querySelector("#stPause");
    b.textContent = this.paused ? "▶ Continuar" : "❚❚ Pausar";
    b.classList.toggle("on", this.paused);
    if (!this.paused) {
      this.pending = 0;
      this.render();
    }
  }

  add({ cls, device, channel, data, at }, old = false) {
    const json = typeof data === "string" ? JSON.stringify(data) : JSON.stringify(data ?? null);
    const msg = { id: ++this.seq, cls, device, channel, data, json, t: at || Date.now(), size: encoder.encode(json).length, old };
    if (old) {                                   // lecturas previas: van al final, se dibujan con render()
      this.messages.push(msg);
      return;
    }
    this.messages.unshift(msg);
    if (this.messages.length > MAX_KEEP) this.messages.length = MAX_KEEP;
    if (!old) {
      this.total += 1;
      this.stamps.push(Date.now());
      this.byProtocol[protocolOf(msg)[1]] += 1;
    }
    if (!this.built) return;
    if (this.paused) {
      this.pending += 1;
      this.counters();
      return;
    }
    if (this.matches(msg)) {
      const list = this.root.querySelector("#stList");
      const empty = list.querySelector(".st-empty");
      if (empty) empty.remove();
      list.insertAdjacentHTML("afterbegin", this.row(msg));
      while (list.children.length > MAX_ROWS) list.lastElementChild.remove();
    }
    this.counters();
  }

  matches(m) {
    const f = this.filter;
    if (f.cls && m.cls !== f.cls) return false;
    if (f.channel && m.channel !== f.channel) return false;
    if (f.q) {
      const hay = `${m.device} ${nameOf(m.device)} agro/${m.cls}/${m.device}/${m.channel} ${m.json}`.toLowerCase();
      if (!hay.includes(f.q)) return false;
    }
    return true;
  }

  row(m) {
    const [chLabel, dir] = CHANNELS[m.channel] || [m.channel, "sube"];
    const [proto, protoCls] = protocolOf(m);
    const time = new Date(m.t).toLocaleTimeString("es-CO", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false, timeZone: "America/Bogota" });
    const color = (CLASSES[m.cls] || CLASSES.platform).color;
    return `<li data-id="${m.id}" class="${m.old ? "old" : "new"} ${this.open.has(m.id) ? "open" : ""}">
      <time>${time}${m.old ? "<small>antes</small>" : ""}</time>
      <span class="dir ${dir === "baja" ? "down" : "up"}">${dir === "baja" ? "↓ baja" : "↑ sube"}</span>
      <span class="ch ch-${m.channel}">${chLabel}</span>
      <span class="who"><b><i style="background:${color}"></i>${esc(nameOf(m.device))}</b><code>agro/${esc(m.cls)}/${esc(m.device)}/${esc(m.channel)}</code><em class="proto ${protoCls}">${proto}</em></span>
      <code class="payload">${esc(m.json.length > 220 ? m.json.slice(0, 220) + "…" : m.json)}</code>
      <span class="size">${m.size} B</span>
      <div class="st-detail">${this.open.has(m.id) ? this.detail(m) : ""}</div></li>`;
  }

  explain(m) {
    const who = nameOf(m.device);
    const d = m.data || {};
    switch (m.channel) {
      case "telemetry": {
        const items = Object.entries(d).filter(([k, v]) => !["ts", "seq"].includes(k) && typeof v === "number");
        const parts = items.map(([k, v]) => {
          const st = assess(k, v, m.device.startsWith("sim-") ? "mc-" + m.device.slice(4) : m.device);
          return `<li><b>${esc(fieldLabel(k))}</b>: ${esc(formatValue(k, v))}${st.text ? ` <span class="lvl ${st.level}">${esc(st.text)}</span>` : ""}</li>`;
        });
        const seq = d.seq !== undefined ? ` Es su mensaje número ${d.seq}; si faltara un número, se habría perdido un mensaje.` : "";
        return `<p>${esc(who)} envió ${items.length} lecturas.${seq}</p><ul>${parts.join("")}</ul>`;
      }
      case "state": {
        const bits = [];
        if (d.enabled !== undefined) bits.push(d.enabled ? "el envío de datos está encendido" : "el envío de datos está APAGADO");
        if (d.interval_s) bits.push(`envía cada ${d.interval_s} s`);
        if (d.auto !== undefined) bits.push(d.auto ? "trabaja en automático" : "está en manual");
        const cal = Object.keys(d.calibration || {});
        if (cal.length) bits.push(`tiene calibración en ${cal.map(fieldLabel).join(", ")}`);
        return `<p>${esc(who)} publicó su configuración: ${esc(bits.join(", ") || "sin cambios relevantes")}. Este mensaje queda «retenido»: quien se conecte después lo recibe de inmediato.</p>`;
      }
      case "status":
        return m.data === "online"
          ? `<p>${esc(who)} se conectó al bróker y avisó que está en línea.</p>`
          : `<p>${esc(who)} se desconectó. Si no se despidió, el bróker publica este aviso por él («última voluntad»).</p>`;
      case "cmd":
        return `<p>La nube le ordena a ${esc(who)}: <b>${esc(d.action)}</b> ${esc(JSON.stringify(d.params || {}))}. Origen: ${esc(d.source || "línea de comandos")}. El dispositivo debe responder con una confirmación que lleve el mismo número <code>${esc(d.cmd_id)}</code>.</p>`;
      case "ack":
        return `<p>${esc(who)} responde a la orden <code>${esc(d.cmd_id)}</code> (${esc(d.action)}): <b>${d.ok ? "cumplida" : "no se pudo"}</b>${d.detail ? ` — ${esc(d.detail)}` : ""}.</p>`;
      case "event":
        return `<p>${esc(this.describeEvent({ device: m.device, channel: "event", data: d }).text)}</p>`;
      default:
        return "";
    }
  }

  detail(m) {
    const pretty = typeof m.data === "string" ? JSON.stringify(m.data) : JSON.stringify(m.data, null, 2);
    const [proto] = protocolOf(m);
    const [, , what] = CHANNELS[m.channel] || ["", "", ""];
    return `<div class="st-explain"><h5>En palabras</h5>${this.explain(m)}
        <p class="muted">${esc(what)} Viajó por ${esc(proto)} en el tema <code>agro/${esc(m.cls)}/${esc(m.device)}/${esc(m.channel)}</code> y pesa ${m.size} bytes.</p></div>
      <pre class="st-json">${highlight(pretty)}</pre>`;
  }

  render() {
    if (!this.built) return;
    const list = this.root.querySelector("#stList");
    const rows = this.messages.filter((m) => this.matches(m)).slice(0, MAX_ROWS);
    list.innerHTML = rows.map((m) => this.row(m)).join("")
      || `<li class="st-empty">Esperando mensajes… Los gemelos envían cada 30 s, las API cada 5 a 15 min y el medidor de energía cada minuto.</li>`;
    this.counters();
  }

  counters() {
    const el = this.root.querySelector("#stCounters");
    if (!el) return;
    const cut = Date.now() - 60000;
    this.stamps = this.stamps.filter((t) => t > cut);
    el.innerHTML = `<span><b>${this.stamps.length}</b> por minuto</span><span><b>${this.total}</b> desde que abriste</span>
      <span class="proto mqtt">MQTT ${this.byProtocol.mqtt}</span><span class="proto amqp">AMQP ${this.byProtocol.amqp}</span><span class="proto https">HTTPS ${this.byProtocol.https}</span>
      ${this.paused ? `<span class="paused">En pausa · ${this.pending} nuevos</span>` : ""}`;
  }
}
