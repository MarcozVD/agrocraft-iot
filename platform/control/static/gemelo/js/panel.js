// Panel de control: todos los dispositivos con su último dato y estadísticas de 6 h; al entrar a uno se ven
// sus gráficas en vivo, sus mensajes en crudo, su configuración y los controles de retroceso (requieren
// usuario de operador: POST /iot/api/cmd con autenticación básica, como el panel anterior).

import { CLASSES, DEVICES, fieldLabel, formatValue, baseId, FIELDS } from "./catalog.js";
import { assess } from "./glossary.js";
import { ChartGrid, deviceSpecs, RANGES } from "./charts.js";
import { Stream } from "./stream.js";

const API = "/iot/api";
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const ORDER = ["twin", "api", "dataset", "real"];
const SIM_IDS = ["sim-parcela-a", "sim-parcela-b", "sim-tanque", "sim-invernadero", "sim-corral", "sim-seguridad", "sim-silo"];
const ACTUATORS = {
  "parcela-a": ["irrigation", "lamp"], "parcela-b": ["irrigation", "lamp"], tanque: ["pump"],
  invernadero: ["vent", "heater", "lamp"], seguridad: ["alarm"], corral: ["cooling"],
};
const LABELS = { cooling: "Aspersores", irrigation: "Riego", lamp: "Lámparas", pump: "Bomba", vent: "Ventilas", heater: "Calefacción",
                 alarm: "Alarma", auto: "Modo automático", auto_cooling: "Aspersores automáticos", auto_process: "Proceso automático",
                 enabled: "Envío de datos", interval_s: "Envía cada (s)", soil_min: "Riego se abre bajo (%)", soil_max: "Riego se cierra en (%)",
                 level_min: "Bomba se enciende bajo (%)", level_max: "Bomba se apaga en (%)", vent_open_at: "Ventilas abren a (°C)",
                 vent_close_at: "Ventilas cierran a (°C)", heat_on_at: "Calefacción enciende a (°C)", heat_off_at: "Calefacción apaga a (°C)",
                 dispatch_at: "Despacha al llegar a (%)", target_herd: "Rebaño objetivo", auto_feed: "Comedero automático",
                 auto_water: "Bebedero automático", sync_weather: "Clima sincronizado", sync_time: "Hora sincronizada",
                 forced_rain: "Lluvia forzada", forced_thunder: "Tormenta forzada", simulate: "Intrusos simulados",
                 speed: "Velocidad (×)", latitude: "Latitud", longitude: "Longitud", running: "En marcha", scenario: "Escenario", hours: "Horas" };
const HIDDEN = new Set(["ts", "fw", "calibration", "scenarios", "anchor_wall", "anchor_data"]);
const SCENARIOS = { actual: "Como está ahora (clima real del momento)", normal: "Día típico", ola_calor: "Ola de calor",
                    sequia: "Sequía", tormenta: "Tormenta", helada: "Helada", falla_bomba: "Falla de la bomba", intrusion: "Intrusiones" };
const SECTION_TEXT = {
  twin: "Equipos de la granja dentro del mundo de Minecraft. Siguen el clima real y obedecen órdenes de la nube.",
  api: "Servicios web de clima real de Bucaramanga, consultados por HTTPS y publicados por MQTT.",
  dataset: "Datos reales grabados en otros lugares que se reproducen como si llegaran ahora.",
  real: "Aparatos físicos del equipo: el PC y el celular.",
};

export function specOf(id) {
  if (DEVICES[id]) return DEVICES[id];
  if (id === "sim-control") {
    return { name: "Control de la simulación", kind: "Escenarios acelerados", cls: "sim", main: "sim_hours", sensor: "Motor de escenarios del plugin",
             protocol: "MQTT 3.1.1 · QoS 1", about: "Arranca la granja copia desde el estado real y la acelera hasta 360 veces con un escenario: ola de calor, sequía, tormenta, helada, falla de bomba o intrusos." };
  }
  const base = DEVICES[baseId(id)];
  if (base) return { ...base, cls: "sim", name: `${base.name} (simulación)`, zone: undefined };
  return { name: id, kind: "", cls: "platform", main: "", sensor: "", protocol: "MQTT", about: "" };
}

function auth() {
  try { return sessionStorage.getItem("agro-auth") || ""; } catch { return ""; }
}

export class Panel {
  constructor(root, ctx) {
    this.root = root;
    this.ctx = ctx;                    // { live, getJSON, describeEvent, toast, openInFarm, raw }
    this.device = null;
    this.stats = new Map();            // "dev/campo" -> [[t, v], ...] (6 h, para las tarjetas)
    this.grid = null;
    this.stream = null;
    this.minutes = 60;
    this.root.addEventListener("click", (e) => this.onClick(e));
    this.root.addEventListener("submit", (e) => this.onSubmit(e));
  }

  // ------------------------------------------------------------------------------------- vista
  show(device) {
    this.grid?.destroy();
    this.grid = null;
    this.stream = null;
    this.device = device && (DEVICES[device] || device.startsWith("sim-")) ? device : null;
    if (this.device) this.renderDevice();
    else this.renderOverview();
    this.root.scrollTop = 0;
  }

  entry(id) {
    return this.ctx.live?.devices.get(id) || { device: id, status: "sin datos", state: {}, telemetry: {} };
  }

  status(id) {
    const d = this.entry(id);
    if (d.state && d.state.enabled === 0) return ["off", "Envío apagado"];
    if (d.status === "online") return ["on", "En línea"];
    return ["off", d.status === "offline" ? "Desconectado" : "Sin datos"];
  }

  ago(ms) {
    if (!ms) return "sin datos";
    const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    return s < 90 ? `hace ${s} s` : s < 5400 ? `hace ${Math.round(s / 60)} min` : `hace ${Math.round(s / 3600)} h`;
  }

  loginBox() {
    const logged = Boolean(auth());
    return `<form class="login-box" data-form="login">
      <span class="who ${logged ? "on" : ""}">${logged ? "Operador conectado: puedes enviar órdenes" : "Solo lectura: entra como operador para enviar órdenes"}</span>
      ${logged ? "" : `<input name="user" placeholder="usuario" autocomplete="username" size="9" aria-label="Usuario">
      <input name="pass" type="password" placeholder="clave" autocomplete="current-password" size="11" aria-label="Clave">`}
      <button class="btn ${logged ? "" : "primary"}">${logged ? "Salir" : "Entrar"}</button></form>`;
  }

  renderOverview() {
    const sections = ORDER.map((cls) => {
      const ids = Object.keys(DEVICES).filter((id) => DEVICES[id].cls === cls);
      return `<section class="p-section"><header><h3><i style="background:${CLASSES[cls].color}"></i>${CLASSES[cls].label} <small>${ids.length}</small></h3>
        <p>${SECTION_TEXT[cls]}</p></header><div class="p-grid">${ids.map((id) => this.card(id)).join("")}</div></section>`;
    }).join("");
    this.root.innerHTML = `
      <div class="p-head"><div><h2>Panel de control</h2>
        <p>Todos los dispositivos con su último dato y su comportamiento de las últimas 6 horas. Haz clic en uno para ver sus gráficas en vivo, sus mensajes en crudo y enviarle órdenes.</p></div>
        ${this.loginBox()}</div>
      <div class="summary" id="chSummary"></div>
      <section class="p-section"><header><h3>Reglas en la nube</h3><p>Decisiones automáticas que toma la plataforma con datos de varios dispositivos.</p></header>
        <div class="p-rules" id="pRules"></div></section>
      ${sections}
      <section class="p-section"><header><h3><i style="background:${CLASSES.sim.color}"></i>Simulación acelerada</h3>
        <p>Una copia de la granja que arranca desde el estado real y avanza hasta 360 veces más rápido. Sus datos se guardan aparte y nunca se mezclan con la captura real.</p></header>
        <div class="p-sim">${this.simControls()}</div><div class="p-grid">${SIM_IDS.map((id) => this.card(id)).join("")}</div></section>`;
    this.renderRules();
    this.loadStats();
  }

  card(id) {
    const spec = specOf(id);
    const cls = spec.cls;
    return `<button class="p-card" data-open="${id}" style="--cls:${CLASSES[cls].color}">
      <div class="p-top"><span class="p-cls"><i></i>${CLASSES[cls].label}</span><span class="p-st" data-st="${id}"></span></div>
      <b class="p-name">${esc(spec.name)}</b><span class="p-kind">${esc(spec.kind)}</span>
      <div class="p-main"><span class="p-val" data-val="${id}">—</span><span data-lvl="${id}"></span></div>
      <span class="p-main-label">${esc(fieldLabel(spec.main || ""))}</span>
      <div class="p-spark" data-spark="${id}"></div>
      <dl class="p-more" data-more="${id}"></dl>
      <span class="p-go">Ver detalle →</span></button>`;
  }

  async loadStats() {
    const ids = [...Object.keys(DEVICES), ...SIM_IDS];
    const keys = ids.map((id) => `${id}/${specOf(id).main}`).filter((k) => !k.endsWith("/"));
    try {
      const { series } = await this.ctx.getJSON(`/series?minutes=360&keys=${encodeURIComponent(keys.join(","))}`);
      this.stats = new Map(Object.entries(series));
    } catch { /* las tarjetas siguen con el valor en vivo */ }
    this.updateOverview();
  }

  updateOverview() {
    if (this.device) return;
    for (const id of [...Object.keys(DEVICES), ...SIM_IDS]) {
      const spec = specOf(id);
      const d = this.entry(id);
      const t = d.telemetry || {};
      const [st, text] = this.status(id);
      const stEl = this.root.querySelector(`[data-st="${id}"]`);
      if (!stEl) continue;
      stEl.className = `p-st ${st}`;
      stEl.textContent = d.telemetry_ts ? `${text} · ${this.ago(d.telemetry_ts)}` : text;
      this.root.querySelector(`[data-val="${id}"]`).textContent = formatValue(spec.main, t[spec.main]);
      const lvl = assess(spec.main, t[spec.main], baseId(id));
      this.root.querySelector(`[data-lvl="${id}"]`).innerHTML = lvl.text ? `<span class="lvl ${lvl.level}">${esc(lvl.text)}</span>` : "";
      const others = Object.keys(t).filter((k) => k !== spec.main && typeof t[k] === "number").slice(0, 4);
      this.root.querySelector(`[data-more="${id}"]`).innerHTML = others.map((k) => `<div><dt>${esc(fieldLabel(k))}</dt><dd>${esc(formatValue(k, t[k]))}</dd></div>`).join("");
      const pts = this.stats.get(`${id}/${spec.main}`) || [];
      this.root.querySelector(`[data-spark="${id}"]`).innerHTML = spark(pts, spec.main);
    }
    const sim = this.entry("sim-control");
    const simState = this.root.querySelector("#pSimState");
    if (simState) {
      const s = sim.state || {};
      simState.innerHTML = s.running >= 1
        ? `<span class="lvl warn">En marcha</span> ${esc(SCENARIOS[s.scenario] || s.scenario || "")} a ${esc(s.speed)}×`
        : `<span class="lvl info">En pausa</span> último escenario: ${esc(SCENARIOS[s.scenario] || s.scenario || "—")}`;
    }
  }

  renderRules() {
    const box = this.root.querySelector("#pRules");
    if (!box) return;
    const rules = this.ctx.live?.rules || [];
    box.innerHTML = rules.map((r) => `<div class="p-rule"><div><code>${esc(r.id)}</code><p>${esc(r.description)}</p>
      ${r.last ? `<small>Última decisión: ${esc(typeof r.last === "string" ? r.last : JSON.stringify(r.last))}</small>` : ""}</div>
      <button class="btn ${r.enabled ? "on" : ""}" data-rule="${esc(r.id)}" data-enabled="${r.enabled ? 1 : 0}">${r.enabled ? "Activa" : "Inactiva"}</button></div>`).join("")
      || `<p class="muted">Cargando reglas…</p>`;
  }

  simControls() {
    const s = this.entry("sim-control").state || {};
    return `<div class="ctl-row"><label>Escenario <select data-sim="scenario">${Object.entries(SCENARIOS).map(([k, v]) =>
        `<option value="${k}" ${k === (s.scenario || "actual") ? "selected" : ""}>${v}</option>`).join("")}</select></label>
      <label>Velocidad × <input data-sim="speed" type="number" min="1" max="360" value="${esc(s.speed ?? 60)}"></label>
      <label>Horas <input data-sim="hours" type="number" min="0.5" max="240" step="0.5" value="${esc(s.hours ?? 24)}"></label>
      <span id="pSimState" class="muted"></span></div>
      <div class="ctl-row"><button class="btn primary" data-simcmd="start">Iniciar desde el estado real</button>
      <button class="btn" data-simcmd="set_speed">Cambiar velocidad</button><button class="btn" data-simcmd="stop">Pausar</button>
      <button class="btn" data-simcmd="reset">Igualar a la granja real</button></div>`;
  }

  // ------------------------------------------------------------------------------------ detalle
  renderDevice() {
    const id = this.device;
    const spec = specOf(id);
    const d = this.entry(id);
    const fields = Object.keys(d.telemetry || {}).filter((k) => typeof d.telemetry[k] === "number");
    fields.sort((a, b) => (a === spec.main ? -1 : b === spec.main ? 1 : 0));
    const topicCls = id.startsWith("sim-") ? "sim" : spec.cls;
    this.root.innerHTML = `
      <div class="p-head"><div>
        <button class="btn back" data-back>← Todos los dispositivos</button>
        <span class="cls" style="background:${CLASSES[spec.cls].color}">${CLASSES[spec.cls].label}</span>
        <h2>${esc(spec.name)}</h2><p>${esc(spec.kind)}</p></div>${this.loginBox()}</div>
      <div class="p-detail-top">
        <div class="p-box"><div class="status-line" id="pStatus"></div><p class="about">${esc(spec.about)}</p>
          <dl class="facts"><dt>ID</dt><dd><code>${esc(id)}</code></dd><dt>Sensor</dt><dd>${esc(spec.sensor)}</dd>
          <dt>Protocolo</dt><dd>${esc(spec.protocol)}</dd><dt>Tema</dt><dd><code>agro/${esc(topicCls)}/${esc(id)}/telemetry</code></dd></dl>
          <div class="ctl-row">${spec.zone || DEVICES[baseId(id)]?.zone ? `<button class="btn" data-tofarm="${esc(baseId(id))}">Ver en la granja 3D</button>` : ""}
          <a class="btn" href="${id.startsWith("sim-") ? "/d/agro-simulacion" : `/d/agro-dispositivo?var-device=${encodeURIComponent(id)}`}" target="_blank" rel="noopener">Abrir en Grafana</a></div></div>
        <div class="p-box"><h3>Órdenes (retroceso)</h3><p class="muted small">Cada botón publica un comando MQTT en <code>agro/${esc(topicCls)}/${esc(id)}/cmd</code>; el dispositivo responde con una confirmación.</p>
          <div id="pControls">${this.controls(id)}</div></div>
        <div class="p-box"><h3>Configuración actual</h3><div class="config" id="pConfig"></div><h3>Última actividad</h3><div class="activity" id="pActivity"></div></div>
      </div>
      <section class="p-section"><header><h3>Variables en vivo</h3><p>Una gráfica por variable. Crecen con cada mensaje; las líneas punteadas amarillas son umbrales donde el sistema actúa solo.</p>
        <div class="segmented small" id="pRange">${RANGES.map(([label, m]) => `<button data-range="${m}" class="${m === this.minutes ? "on" : ""}">${label}</button>`).join("")}</div></header>
        <div class="ch-grid" id="pCharts">${fields.length ? "" : `<p class="muted">Aún no hay telemetría de este dispositivo.</p>`}</div></section>
      <section class="p-section"><header><h3>Mensajes en crudo</h3></header><div id="pStream"></div></section>`;
    if (fields.length) {
      this.grid = new ChartGrid(this.root.querySelector("#pCharts"), this.ctx.getJSON, deviceSpecs(id, fields));
      this.grid.load(this.minutes);
    }
    this.stream = new Stream(this.root.querySelector("#pStream"), this.ctx.describeEvent, id);
    const past = this.ctx.raw.filter((m) => m.device === id);
    if (!past.length && Object.keys(d.telemetry || {}).length) {
      this.stream.add({ cls: d.class, device: id, channel: "telemetry", data: { ts: d.telemetry_ts, ...d.telemetry }, at: d.telemetry_ts }, true);
    }
    for (const m of past.slice().reverse()) this.stream.add(m, true);
    this.stream.build();
    this.updateDevice();
  }

  updateDevice() {
    const id = this.device;
    if (!id) return;
    const d = this.entry(id);
    const [st, text] = this.status(id);
    const statusEl = this.root.querySelector("#pStatus");
    if (!statusEl) return;
    statusEl.innerHTML = `<span class="sdot ${st}"></span><b>${text}</b> · último dato ${this.ago(d.telemetry_ts)}`;
    const s = d.state || {};
    const config = Object.entries(s).filter(([k]) => !HIDDEN.has(k));
    const cal = Object.entries(s.calibration || {});
    this.root.querySelector("#pConfig").innerHTML = (config.map(([k, v]) => `<span>${esc(LABELS[k] || fieldLabel(k))} = <b>${esc(typeof v === "number" ? +v.toFixed(3) : v)}</b></span>`).join("")
      + cal.map(([k, c]) => `<span>calibración ${esc(fieldLabel(k))}: <b>${c.offset >= 0 ? "+" : ""}${esc(c.offset)} × ${esc(c.gain)}</b></span>`).join("")
      + (s.fw ? `<span>firmware <b>${esc(s.fw)}</b></span>` : "")) || `<span class="muted">Sin configuración publicada</span>`;
    const acts = [];
    if (d.last_ack) acts.push(`<div>Última orden: <code>${esc(d.last_ack.action)} → ${d.last_ack.ok ? "cumplida" : "rechazada"}</code> ${esc(d.last_ack.detail || "")}</div>`);
    if (d.last_event) acts.push(`<div>Último evento: ${esc(this.ctx.describeEvent({ device: id, channel: "event", data: d.last_event }).text)}</div>`);
    this.root.querySelector("#pActivity").innerHTML = acts.join("") || `<span class="muted">Sin actividad desde que se abrió la página.</span>`;
  }

  controls(id) {
    const d = this.entry(id);
    const s = d.state || {};
    const key = id.replace(/^(mc|sim)-/, "");
    const on = (v) => Number(v) === 1;
    const toggle = (name, current, action = "set_actuator") =>
      `<button class="btn ${on(current) ? "on" : ""}" data-cmd="${action}" data-params='${esc(JSON.stringify(action === "set_actuator"
        ? { name, value: on(current) ? 0 : 1 } : { [name]: on(current) ? 0 : 1 }))}'>${esc(LABELS[name] || name)}: ${on(current) ? "encendido" : "apagado"}</button>`;
    const btn = (label, action, params = {}, extra = "") =>
      `<button class="btn ${extra}" data-cmd="${action}" data-params='${esc(JSON.stringify(params))}'>${esc(label)}</button>`;
    const rows = [];
    const main = [`<button class="btn ${on(s.enabled ?? 1) ? "on" : ""}" data-cmd="set_enabled" data-params='{"enabled":${on(s.enabled ?? 1) ? 0 : 1}}'>Envío de datos: ${on(s.enabled ?? 1) ? "encendido" : "apagado"}</button>`];
    for (const name of ACTUATORS[key] || []) main.push(toggle(name, s[name]));
    if ("auto" in s) main.push(toggle("auto", s.auto, "set_config"));
    if (key === "silo") main.push(btn("Descargar silo", "set_actuator", { name: "discharge", value: 1 }));
    if (key === "corral") {
      main.push(btn("Llenar comedero", "set_actuator", { name: "feeder", value: 1 }), btn("Llenar bebedero", "set_actuator", { name: "water", value: 1 }),
        btn("Procesar 1 vaca", "set_actuator", { name: "process", value: 1 }), btn("Agregar 2 vacas", "repoblar", { count: 2 }));
      if ("auto_cooling" in s) main.push(toggle("auto_cooling", s.auto_cooling, "set_config"));
      if ("auto_process" in s) main.push(toggle("auto_process", s.auto_process, "set_config"));
    }
    if (key === "seguridad") main.push(btn("Abrir portón", "set_actuator", { name: "gate", value: 1 }), btn("Cerrar portón", "set_actuator", { name: "gate", value: 0 }));
    rows.push(`<div class="ctl-row">${main.join("")}</div>`);
    if (id === "mc-estacion") {
      rows.push(`<div class="ctl-row"><span class="muted">Clima del mundo:</span>${btn("Despejado", "set_weather", { weather: "clear" })}${btn("Lluvia", "set_weather", { weather: "rain" })}
        ${btn("Tormenta", "set_weather", { weather: "thunder" })}${btn("Sincronizar con el clima real", "set_config", { sync_weather: 1, sync_time: 1 }, on(s.sync_weather) ? "on" : "")}</div>`);
    }
    if (specOf(id).cls === "dataset") {
      rows.push(`<div class="ctl-row"><label>Velocidad × <input data-input="speed" type="number" min="0" max="120" step="0.5" value="${esc(s.speed ?? 1)}"></label>
        <button class="btn" data-cmd="set_speed" data-from="speed">Aplicar</button>${btn("Reiniciar reproducción", "restart")}</div>`);
    }
    const variables = Object.keys(d.telemetry || {});
    rows.push(`<details class="ctl-more"><summary>Ajustes: intervalo de envío, calibración y reinicio</summary>
      <div class="ctl-row"><label>Enviar cada <input data-input="interval_s" type="number" min="5" max="3600" value="${esc(s.interval_s ?? "")}"> s</label>
        <button class="btn" data-cmd="set_interval" data-from="interval_s">Aplicar</button></div>
      <div class="ctl-row"><label>Calibrar <select data-input="variable">${variables.map((v) => `<option value="${esc(v)}">${esc(fieldLabel(v))}</option>`).join("")}</select></label>
        <label>offset <input data-input="offset" type="number" step="any" value="0"></label><label>ganancia <input data-input="gain" type="number" step="any" value="1"></label>
        <button class="btn" data-cmd="calibrate" data-from="calibrate">Aplicar</button>${btn("Borrar calibración", "calibrate", { reset: true })}</div>
      <div class="ctl-row">${btn("Reiniciar con configuración de fábrica", "reboot", {}, "danger")}</div>
      <p class="muted small">Calibrar suma el offset y multiplica por la ganancia antes de enviar: sirve para corregir un sensor descalibrado.</p></details>`);
    return rows.join("");
  }

  // ------------------------------------------------------------------------------------ eventos
  async send(device, action, params) {
    const token = auth();
    if (!token) return this.ctx.toast("Entra como operador (arriba a la derecha) para enviar órdenes.");
    this.ctx.toast(`Enviando «${action}» a ${device}…`);
    try {
      const response = await fetch(`${API}/cmd`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: "Basic " + token },
                                                   body: JSON.stringify({ device, action, params }) });
      const ack = await response.json();
      if (response.status === 401) {
        try { sessionStorage.removeItem("agro-auth"); } catch { /* sin almacenamiento */ }
        this.show(this.device);
        return this.ctx.toast("Usuario o clave de operador incorrectos.");
      }
      this.ctx.toast(`${device} · ${action}: ${ack.ok ? "cumplida" : "rechazada"}${ack.detail ? " — " + ack.detail : ""}`);
      setTimeout(() => {
        if (this.device === device) this.root.querySelector("#pControls").innerHTML = this.controls(device);
      }, 1200);
    } catch (error) {
      this.ctx.toast(`${device}: ${error.message}`);
    }
  }

  onSubmit(e) {
    const form = e.target.closest("[data-form=login]");
    if (!form) return;
    e.preventDefault();
    try {
      if (auth()) sessionStorage.removeItem("agro-auth");
      else {
        const user = form.querySelector("[name=user]").value, pass = form.querySelector("[name=pass]").value;
        sessionStorage.setItem("agro-auth", btoa(unescape(encodeURIComponent(`${user}:${pass}`))));
        this.ctx.toast("Credenciales guardadas en esta pestaña; se validan con la primera orden.");
      }
    } catch {
      this.ctx.toast("El navegador no permite guardar la sesión en esta pestaña.");
    }
    this.show(this.device);
  }

  onClick(e) {
    const t = e.target.closest("button, [data-open]");
    if (!t || t.closest("form[data-form=login]") && t.tagName === "BUTTON") return;
    if (t.dataset.open) return this.ctx.navigate(t.dataset.open);
    if (t.hasAttribute("data-back")) return this.ctx.navigate(null);
    if (t.dataset.tofarm) return this.ctx.openInFarm(t.dataset.tofarm);
    if (t.dataset.range) {
      this.minutes = +t.dataset.range;
      this.root.querySelectorAll("[data-range]").forEach((b) => b.classList.toggle("on", b === t));
      return this.grid?.load(this.minutes);
    }
    if (t.dataset.rule) return this.toggleRule(t.dataset.rule, t.dataset.enabled !== "1");
    if (t.dataset.simcmd) {
      const val = (k) => this.root.querySelector(`[data-sim=${k}]`).value;
      const action = t.dataset.simcmd;
      const params = action === "start" ? { scenario: val("scenario"), speed: +val("speed"), hours: +val("hours") }
        : action === "set_speed" ? { speed: +val("speed") } : {};
      return this.send("sim-control", action, params);
    }
    if (t.dataset.cmd) {
      let params = JSON.parse(t.dataset.params || "{}");
      const input = (k) => this.root.querySelector(`[data-input=${k}]`)?.value;
      if (t.dataset.from === "interval_s") params = { interval_s: +input("interval_s") };
      if (t.dataset.from === "speed") params = { speed: +input("speed") };
      if (t.dataset.from === "calibrate") params = { variable: input("variable"), offset: +input("offset"), gain: +input("gain") };
      if (t.dataset.cmd === "reboot" && !window.confirm(`¿Reiniciar ${this.device} con la configuración de fábrica?`)) return;
      return this.send(this.device, t.dataset.cmd, params);
    }
  }

  async toggleRule(id, enabled) {
    const token = auth();
    if (!token) return this.ctx.toast("Entra como operador para activar o desactivar reglas.");
    const response = await fetch(`${API}/rules/${encodeURIComponent(id)}`, { method: "POST",
      headers: { "Content-Type": "application/json", Authorization: "Basic " + token }, body: JSON.stringify({ enabled }) });
    if (response.status === 401) return this.ctx.toast("Usuario o clave de operador incorrectos.");
    const data = await response.json();
    if (this.ctx.live) this.ctx.live.rules = data.rules || this.ctx.live.rules;
    this.renderRules();
  }

  // ------------------------------------------------------------------------------------ en vivo
  onMessage(m) {
    if (m.channel === "telemetry" && m.data && typeof m.data === "object") {
      const spec = specOf(m.device);
      const v = m.data[spec.main];
      if (typeof v === "number") {
        const key = `${m.device}/${spec.main}`;
        const list = this.stats.get(key) || [];
        list.push([m.data.ts || m.at, v]);
        this.stats.set(key, list);
      }
      if (this.grid && m.device === this.device) this.grid.push(m.device, m.data, m.data.ts || m.at);
    }
    if (this.stream && m.device === this.device) this.stream.add(m);
  }

  tick() {
    if (this.device) {
      this.grid?.flush();
      this.updateDevice();
      this.stream?.counters();
    } else {
      this.updateOverview();
    }
  }
}

function spark(points, field) {
  if (!points || points.length < 2) return `<span class="muted small">Sin historial de 6 h</span>`;
  const t0 = points[0][0], t1 = points[points.length - 1][0];
  let lo = Infinity, hi = -Infinity, sum = 0;
  for (const [, v] of points) { lo = Math.min(lo, v); hi = Math.max(hi, v); sum += v; }
  const span = hi - lo || 1;
  const line = points.map(([t, v]) => `${(((t - t0) / Math.max(1, t1 - t0)) * 200).toFixed(1)},${(30 - ((v - lo) / span) * 26).toFixed(1)}`).join(" ");
  const bool = (FIELDS[field] || [])[1] === "bool";
  return `<svg viewBox="0 0 200 32" preserveAspectRatio="none" aria-hidden="true"><polyline points="${line}" fill="none" stroke="var(--cls)" stroke-width="1.6" vector-effect="non-scaling-stroke"/></svg>
    <div class="p-mm">${bool ? `<span>encendido ${Math.round((sum / points.length) * 100)} % del tiempo</span>`
      : `<span>mín ${formatValue(field, lo)}</span><span>prom ${formatValue(field, sum / points.length)}</span><span>máx ${formatValue(field, hi)}</span>`}</div>`;
}

