// Une la escena con los datos: lista de dispositivos, detalle con historial, actividad, línea de tiempo
// para reproducir las últimas 24 h, granja de simulación, recorrido automático e informes con IA.

import { loadFarm, buildWorld } from "./world.js";
import { Stage } from "./scene.js";
import { Twins } from "./twins.js";
import { Live, Replay, getJSON } from "./data.js";
import { CLASSES, DEVICES, fieldLabel, formatValue, ticksToClock, baseId } from "./catalog.js";

const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const IDS = Object.keys(DEVICES);
const ORDER = ["twin", "api", "dataset", "real"];
const SIM_TWINS = new Set(["mc-parcela-a", "mc-parcela-b", "mc-tanque", "mc-invernadero", "mc-corral", "mc-seguridad", "mc-silo"]);
const TOUR = ["mc-parcela-a", "mc-silo", "mc-parcela-b", "mc-tanque", "mc-invernadero", "mc-corral", "mc-estacion",
              "api-clima", "mc-seguridad", "phone-01", "mc-server", "ds-energia"];
const EVENT_NAMES = {
  actuator: "Actuador", alarm: "Alarma", birth: "Nacimiento", cooling: "Aspersores", death: "Muerte",
  dispatch: "Despacho al comedero", harvest: "Cosecha", intruder: "Intruso", intruder_gone: "Intruso se fue",
  irrigation: "Riego", overflow: "Rebose", player_actuator: "Palanca en el mundo", processing: "Proceso de carne",
  pump: "Bomba", feeder: "Comedero", sim_end: "Fin de la simulación", tour_start: "Visita guiada", rule: "Regla", rule_toggle: "Regla",
  anomaly_open: "Anomalía abierta", anomaly_close: "Anomalía cerrada", alert: "Alerta",
};
const SCENARIOS = { actual: "clima actual", normal: "día normal", ola_calor: "ola de calor", sequia: "sequía",
                    tormenta: "tormenta", helada: "helada", falla_bomba: "falla de bomba", intrusion: "intrusión" };

const ui = {
  farm: "real", mode: "live", replay: null, replayT: 0, playing: false, selected: null, range: 6,
  history: null, tour: null, events: [], anomalies: {}, dirty: true, lastApply: 0, values: {}, hover: null,
};
let stage, twins, live;

// ------------------------------------------------------------------------------------- arranque
async function boot() {
  try {
    const farm = await loadFarm("/iot/static/gemelo/farm.json");
    const world = buildWorld(farm);
    stage = new Stage($("#stage"), world);
    twins = new Twins(stage.scene, world);
    stage.showFlow = true;
    for (const id of IDS) stage.addMarker(id);
    stage.onPick = (id) => (id ? select(id) : null);
    stage.onHover = (id) => hover(id);
  } catch (error) {
    $("#loading p").textContent = "No se pudo iniciar la vista 3D: " + error.message;
    throw error;
  }
  buildList();
  bindUI();
  live = new Live();
  live.on(onLive);
  live.start();
  loadEvents();
  refreshAnomalies();
  setInterval(refreshAnomalies, 120000);
  setInterval(() => { const r = document.getElementById("rate"); if (r && live) r.textContent = `${live.rate()} msg/min`; }, 2000);
  let last = performance.now();
  const loop = (now) => {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    tick(now / 1000, dt);
    requestAnimationFrame(loop);
  };
  requestAnimationFrame(loop);
  setTimeout(() => $("#loading").classList.add("done"), 300);
}

// ---------------------------------------------------------------------------------- datos
function sourceId(id) {
  return ui.farm === "sim" && SIM_TWINS.has(id) ? "sim-" + id.slice(3) : id;
}

function merged(id) {
  const d = live?.devices.get(sourceId(id));
  if (!d) return {};
  const out = {};
  for (const [k, v] of Object.entries(d.state || {})) if (typeof v === "number" && !["ts", "interval_s"].includes(k)) out[k] = v;
  return Object.assign(out, d.telemetry || {});
}

function currentValues() {
  const values = {};
  for (const id of IDS) values[id] = merged(id);
  if (ui.mode === "replay" && ui.replay && ui.farm === "real") {
    const past = ui.replay.valuesAt(ui.replayT);
    for (const [id, fields] of Object.entries(past)) values[id] = { ...values[id], ...fields };
  }
  return values;
}

function bogotaTicks() {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: "America/Bogota", hour: "2-digit", minute: "2-digit", hour12: false })
    .formatToParts(new Date());
  const h = +parts.find((p) => p.type === "hour").value, m = +parts.find((p) => p.type === "minute").value;
  return (((h + m / 60) - 6 + 24) % 24) * 1000;
}

function weatherOf(values) {
  if (ui.farm === "sim") {
    const sc = live?.devices.get("sim-control")?.telemetry || {};
    const rain = sc.in_rain_mmh ?? 0;
    const clock = sc.sim_clock_h;
    return { raining: rain > 0.2 ? 1 : 0, thundering: rain > 10 ? 1 : 0, wind_kmh: rain > 5 ? 25 : 8,
             cloud_pct: rain > 0.2 ? 95 : 25, temp_c: sc.in_temp_c, hum_pct: sc.in_hum_pct,
             time_ticks: clock === undefined ? bogotaTicks() : ((clock - 6 + 24) % 24) * 1000, daylight_lvl: null };
  }
  const st = values["mc-estacion"] || {}, cl = values["api-clima"] || {};
  return { raining: st.raining, thundering: st.thundering, time_ticks: st.time_ticks ?? bogotaTicks(),
           daylight_lvl: st.daylight_lvl ?? null, wind_kmh: cl.wind_kmh, cloud_pct: cl.cloud_pct, temp_c: cl.temp_c,
           hum_pct: cl.hum_pct, rain_mm: cl.rain_mm };
}

function statusOf(id) {
  if (ui.mode === "replay") return "online";
  const d = live?.devices.get(sourceId(id));
  if (!d) return "sin datos";
  if (d.state && d.state.enabled === 0) return "apagado";
  return d.status === "online" ? "online" : d.status;
}

function alertOf(id, v) {
  switch (id) {
    case "mc-parcela-a": case "mc-parcela-b": return v.soil_pct !== undefined && v.soil_pct < 30;
    case "mc-tanque": return v.level_pct !== undefined && v.level_pct < 20;
    case "mc-invernadero": return v.temp_c > 32;
    case "mc-corral": return v.thi > 78 || (v.welfare_pct !== undefined && v.welfare_pct < 50);
    case "mc-seguridad": return v.alarm >= 0.5 || v.hostiles_near > 0;
    case "mc-server": return v.tps !== undefined && v.tps < 15;
    case "api-aire": return v.us_aqi > 100;
    case "gw-pc-01": return v.cpu_pct > 90;
    default: return false;
  }
}

function summary(id, v) {
  const spec = DEVICES[id];
  let text = formatValue(spec.main, v[spec.main]);
  const flags = [];
  if (v.irrigation >= 0.5) flags.push("riego");
  if (v.pump >= 0.5) flags.push("bomba");
  if ((v.vent_open ?? v.vent) >= 0.5 && id === "mc-invernadero") flags.push("ventilas");
  if (v.heater >= 0.5) flags.push("calefacción");
  if (v.alarm >= 0.5) flags.push("ALARMA");
  if (v.cooling >= 0.5) flags.push("aspersores");
  if (id === "mc-estacion") text = `${formatValue("time_ticks", v.time_ticks)}${v.raining >= 0.5 ? " · lluvia" : ""}`;
  return flags.length ? `${text} · ${flags.join(" · ")}` : text;
}

// ---------------------------------------------------------------------------- mensajes en vivo
function onLive(kind, payload) {
  if (kind === "status") return setPill();
  if (kind === "snapshot") {
    ui.dirty = true;
    setPill();
    return;
  }
  const { device, channel, data, cls, at } = payload;
  ui.dirty = true;
  const id = ui.farm === "sim" ? (device.startsWith("sim-") ? baseId(device) : null) : device;
  if (ui.mode === "live" && id && DEVICES[id]) {
    if (channel === "telemetry") {
      stage.pulse(id);
      stage.sendPacket(id, "up", CLASSES[DEVICES[id].cls].color);
      flashRow(id);
      if (ui.selected === id && ui.history) appendHistory(data, at);
    } else if (channel === "ack") {
      stage.sendPacket(id, "down", "#e8a33d");
    }
  }
  if (channel === "event" && cls !== "sim") pushEvent({ t: at, device, class: cls, channel, data }, true);
  if (channel === "ack" && cls !== "sim") pushEvent({ t: at, device, class: cls, channel: "ack", data }, true);
  if (channel === "event" && device === "anomalias") refreshAnomalies();
}

function setPill() {
  const pill = $("#live");
  let cls = "warn", text = live?.status || "conectando";
  if (ui.mode === "replay") {
    cls = "replay";
    text = ui.playing ? "Reproduciendo" : "Pasado (pausa)";
  } else if (live?.status === "en vivo") {
    cls = "ok";
    text = ui.farm === "sim" ? "En vivo · simulación" : "En vivo";
  } else if (live?.status === "sin conexión") cls = "bad";
  pill.className = `pill live ${cls}`;
  pill.querySelector("span").textContent = text[0].toUpperCase() + text.slice(1);
}

// ------------------------------------------------------------------------------------- bucle
function tick(time, dt) {
  if (ui.mode === "replay" && ui.playing && ui.replay) {
    ui.replayT += dt * 1000 * +$("#tlSpeed").value;
    if (ui.replayT >= ui.replay.t1) goLive();
    else syncSlider();
    ui.dirty = true;
  }
  if (ui.dirty && time - ui.lastApply > 0.2) {
    ui.dirty = false;
    ui.lastApply = time;
    applyAll();
  }
  twins.tick(time, dt);
  const w = ui.weather || {};
  stage.setTime(w.time_ticks ?? bogotaTicks(), w.daylight_lvl ?? null, twins.flash);
  stage.frame(time, dt);
}

function applyAll() {
  const values = currentValues();
  ui.values = values;
  const weather = weatherOf(values);
  ui.weather = weather;
  const forTwins = { ...values, weather };
  if (ui.farm === "sim") forTwins["mc-estacion"] = {};
  twins.apply(forTwins);
  for (const id of IDS) {
    const v = values[id];
    const status = statusOf(id);
    stage.setMarker(id, { value: summary(id, v), status, alert: alertOf(id, v) });
    const row = document.querySelector(`.dev[data-id="${id}"]`);
    if (row) {
      row.querySelector(".val").textContent = formatValue(DEVICES[id].main, v[DEVICES[id].main]);
      const dot = row.querySelector(".dot");
      dot.className = `dot ${status === "online" ? "online" : ""} ${alertOf(id, v) ? "alert" : ""}`;
    }
  }
  renderWeather(weather);
  renderSimBanner();
  if (ui.selected) renderDrawerValues();
}

function renderWeather(w) {
  const parts = [];
  if (w.time_ticks !== undefined) parts.push(`${ui.farm === "sim" ? "Reloj simulado" : "Mundo"} <b>${ticksToClock(w.time_ticks)}</b>`);
  if (w.temp_c !== undefined) parts.push(`${ui.farm === "sim" ? "Escenario" : "Bucaramanga"} <b>${formatValue("temp_c", w.temp_c)}</b>`);
  if (w.hum_pct !== undefined) parts.push(`<em class="wide"><b>${formatValue("hum_pct", w.hum_pct)}</b> HR</em>`);
  parts.push(w.raining >= 0.5 ? `<b>${w.thundering >= 0.5 ? "Tormenta" : "Lluvia"}</b>` : "Sin lluvia");
  $("#weather").innerHTML = parts.join("<span>·</span>");
}

function renderSimBanner() {
  const banner = $("#simBanner");
  if (ui.farm !== "sim") return (banner.hidden = true);
  const sc = live?.devices.get("sim-control");
  const st = sc?.state || {}, t = sc?.telemetry || {};
  banner.hidden = false;
  const running = st.running >= 1 || t.running >= 1;
  const scenario = SCENARIOS[st.scenario] || st.scenario || "—";
  banner.innerHTML = running
    ? `Granja de simulación · <b>${esc(scenario)}</b> a <b>${formatValue("speed", st.speed ?? t.speed)}</b> · ${formatValue("sim_hours", t.sim_hours)} simuladas`
    : `Granja de simulación <b>en pausa</b> · escenario ${esc(scenario)} · iníciala desde el <a href="/iot/" target="_blank" rel="noopener" style="color:#fff">panel de control</a>`;
}

// ------------------------------------------------------------------------------- lista lateral
function buildList() {
  const list = $("#devList");
  list.innerHTML = "";
  for (const cls of ORDER) {
    const ids = IDS.filter((id) => DEVICES[id].cls === cls);
    const title = document.createElement("div");
    title.className = "group-title";
    title.dataset.cls = cls;
    title.textContent = `${CLASSES[cls].label} · ${ids.length}`;
    list.append(title);
    for (const id of ids) {
      const row = document.createElement("button");
      row.className = "dev";
      row.dataset.id = id;
      row.innerHTML = `<span class="dot"></span><b>${esc(DEVICES[id].name)}</b><span class="val">—</span><code>${id}</code>`;
      row.addEventListener("click", () => select(id));
      row.addEventListener("mouseenter", () => hover(id, true));
      row.addEventListener("mouseleave", () => hover(null, true));
      list.append(row);
    }
  }
  $("#devCount").textContent = `${IDS.length}`;
  $("#legend").innerHTML = ORDER.map((c) => `<span><i style="background:${CLASSES[c].color}"></i>${CLASSES[c].label}</span>`).join("");
}

function filterList() {
  const q = $("#devSearch").value.trim().toLowerCase();
  for (const row of document.querySelectorAll(".dev")) {
    const id = row.dataset.id, spec = DEVICES[id];
    const hay = [id, spec.name, spec.kind, spec.sensor, ...Object.keys(ui.values[id] || {}).map(fieldLabel)].join(" ").toLowerCase();
    const simHidden = ui.farm === "sim" && !SIM_TWINS.has(id);
    row.hidden = simHidden || (q && !hay.includes(q));
  }
  for (const title of document.querySelectorAll(".group-title")) {
    const rows = [...document.querySelectorAll(`.dev`)].filter((r) => DEVICES[r.dataset.id].cls === title.dataset.cls);
    title.hidden = rows.every((r) => r.hidden);
  }
}

function flashRow(id) {
  const val = document.querySelector(`.dev[data-id="${id}"] .val`);
  if (!val) return;
  val.classList.remove("flash");
  void val.offsetWidth;
  val.classList.add("flash");
}

function hover(id, fromList = false) {
  if (ui.hover === id) return;
  ui.hover = id;
  for (const row of document.querySelectorAll(".dev")) row.classList.toggle("hover", row.dataset.id === id);
  for (const [mid, m] of stage.markers) m.label.classList.toggle("sel", mid === id || mid === ui.selected);
  if (fromList && id) stage.pulse(id);
}

// ----------------------------------------------------------------------------------- detalle
async function select(id, fromTour = false) {
  if (!fromTour) stopTour();
  if (ui.farm === "sim" && !SIM_TWINS.has(id)) return toast("Ese dispositivo no existe en la granja de simulación.");
  ui.selected = id;
  stage.select(id);
  for (const row of document.querySelectorAll(".dev")) row.classList.toggle("on", row.dataset.id === id);
  for (const [mid, m] of stage.markers) m.label.classList.toggle("sel", mid === id);
  const spec = DEVICES[id];
  const src = sourceId(id);
  const drawer = $("#drawer");
  drawer.hidden = false;
  const cls = ui.farm === "sim" && SIM_TWINS.has(id) ? "sim" : spec.cls;
  $("#dCls").textContent = CLASSES[cls].label;
  $("#dCls").style.background = CLASSES[cls].color;
  $("#dName").textContent = spec.name;
  $("#dKind").textContent = spec.kind;
  $("#dAbout").textContent = spec.about;
  const topicCls = ui.farm === "sim" && SIM_TWINS.has(id) ? "sim" : spec.cls;
  const where = spec.zone ? `x ${spec.zone[0]}…${spec.zone[2]}, z ${spec.zone[1]}…${spec.zone[3]} (bloques)`
                          : "posición lógica junto a lo que mide";
  $("#dFacts").innerHTML = `
    <dt>ID</dt><dd><code>${src}</code></dd>
    <dt>Sensor</dt><dd>${esc(spec.sensor)}</dd>
    <dt>Protocolo</dt><dd>${esc(spec.protocol)}</dd>
    <dt>Tema</dt><dd><code>agro/${topicCls}/${src}/telemetry</code></dd>
    <dt>Ubicación</dt><dd>${where}</dd>`;
  $("#dGrafana").href = ui.farm === "sim" ? "/d/agro-simulacion" : `/d/agro-dispositivo?var-device=${encodeURIComponent(id)}`;
  ui.history = null;
  renderDrawerValues();
  loadHistory();
}

async function loadHistory() {
  const id = ui.selected;
  if (!id) return;
  const src = sourceId(id);
  try {
    const data = await getJSON(`/history/${encodeURIComponent(src)}?hours=${ui.range}`);
    if (ui.selected !== id) return;
    ui.history = data.series;
    renderDrawerValues(true);
  } catch {
    ui.history = {};
    renderDrawerValues(true);
  }
}

function appendHistory(data, at) {
  const t = data.ts || at || Date.now();
  for (const [k, v] of Object.entries(data)) {
    if (k === "ts" || k === "seq" || typeof v !== "number") continue;
    (ui.history[k] ||= []).push([t, v]);
  }
}

function sparkline(points, field, cursor) {
  if (!points || points.length < 2) return `<svg viewBox="0 0 300 42" preserveAspectRatio="none"></svg>`;
  const t0 = points[0][0], t1 = points[points.length - 1][0];
  let lo = Infinity, hi = -Infinity;
  for (const [, v] of points) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
  if (hi - lo < 1e-9) { hi += 1; lo -= 1; }
  const x = (t) => ((t - t0) / Math.max(1, t1 - t0)) * 300;
  const y = (v) => 38 - ((v - lo) / (hi - lo)) * 34;
  const line = points.map(([t, v]) => `${x(t).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  const gid = `g${field}`;
  const cur = cursor && cursor >= t0 && cursor <= t1
    ? `<line x1="${x(cursor)}" x2="${x(cursor)}" y1="0" y2="42" stroke="#5fb2ff" stroke-width="1.5"/>` : "";
  return `<svg viewBox="0 0 300 42" preserveAspectRatio="none" aria-hidden="true">
    <defs><linearGradient id="${gid}" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="#7bc86c" stop-opacity=".35"/><stop offset="1" stop-color="#7bc86c" stop-opacity="0"/></linearGradient></defs>
    <polygon points="0,42 ${line} 300,42" fill="url(#${gid})"/>
    <polyline points="${line}" fill="none" stroke="#7bc86c" stroke-width="1.6" vector-effect="non-scaling-stroke"/>${cur}</svg>`;
}

let lastDrawer = 0;
function renderDrawerValues(force = false) {
  const id = ui.selected;
  if (!id) return;
  const now = performance.now();
  if (!force && now - lastDrawer < 900) return;
  lastDrawer = now;
  const spec = DEVICES[id];
  const src = sourceId(id);
  const d = live?.devices.get(src);
  const v = ui.values[id] || {};
  const status = statusOf(id);
  const ago = d?.telemetry_ts ? Math.max(0, Math.round((Date.now() - d.telemetry_ts) / 1000)) : null;
  const statusText = ui.mode === "replay"
    ? `Mostrando <b>${new Date(ui.replayT).toLocaleString("es-CO", { dateStyle: "medium", timeStyle: "short" })}</b>`
    : `<span class="dot ${status === "online" ? "online" : ""}" style="width:9px;height:9px;border-radius:50%;display:inline-block;background:${status === "online" ? "var(--leaf)" : "var(--bad)"}"></span>
       <b>${status === "online" ? "En línea" : esc(status)}</b>${ago !== null ? ` · último dato hace ${ago < 90 ? ago + " s" : Math.round(ago / 60) + " min"}` : ""}`;
  $("#dStatus").innerHTML = statusText;

  const telemetry = { ...(d?.telemetry || {}), ...v };
  const keys = Object.keys(d?.telemetry || {}).length ? Object.keys(d.telemetry) : Object.keys(v);
  keys.sort((a, b) => (a === spec.main ? -1 : b === spec.main ? 1 : 0));
  const anomalies = ui.anomalies || {};
  $("#dVars").innerHTML = keys.length ? keys.map((k) => {
    const value = telemetry[k];
    const z = anomalies[`${src}/${k}`];
    const zChip = z ? `<span class="z ${Math.abs(z.z) >= 3 ? "hot" : ""}" title="z frente a la misma hora de días anteriores">z ${z.z > 0 ? "+" : ""}${z.z}</span>` : "";
    const pts = ui.history ? ui.history[k] : null;
    let mm = "";
    if (pts && pts.length) {
      let lo = Infinity, hi = -Infinity, sum = 0;
      for (const [, x] of pts) { lo = Math.min(lo, x); hi = Math.max(hi, x); sum += x; }
      mm = `<div class="mm"><span>mín ${formatValue(k, lo)}</span><span>prom ${formatValue(k, sum / pts.length)}</span><span>máx ${formatValue(k, hi)}</span></div>`;
    }
    return `<div class="var"><span class="k">${esc(fieldLabel(k))} ${zChip}</span><span class="v">${esc(formatValue(k, value))}</span>
      ${ui.history ? sparkline(pts, k, ui.mode === "replay" ? ui.replayT : null) : ""}${mm}</div>`;
  }).join("") : `<p class="muted">${ui.farm === "sim" ? "La simulación está en pausa: aún no hay telemetría." : "Sin telemetría todavía."}</p>`;

  const st = d?.state || {};
  const config = Object.entries(st).filter(([k]) => !["ts", "fw", "calibration", "scenarios"].includes(k));
  const cal = Object.entries(st.calibration || {});
  $("#dConfig").innerHTML = (config.map(([k, val]) => `<span>${esc(k)} = <b>${esc(typeof val === "number" ? +val.toFixed(3) : val)}</b></span>`).join("")
    + cal.map(([k, c]) => `<span>calibración ${esc(k)}: <b>${c.offset >= 0 ? "+" : ""}${esc(c.offset)} × ${esc(c.gain)}</b></span>`).join(""))
    || `<span class="muted">Sin configuración publicada</span>`;
  if (st.fw) $("#dConfig").insertAdjacentHTML("beforeend", `<span>firmware <b>${esc(st.fw)}</b></span>`);

  const acts = [];
  if (d?.last_event) acts.push(`<div>Evento: <code>${esc(describeEvent({ device: src, data: d.last_event }).text)}</code></div>`);
  if (d?.last_ack) acts.push(`<div>Último comando: <code>${esc(d.last_ack.action)} → ${d.last_ack.ok ? "ok" : "falló"} · ${esc(d.last_ack.detail || "")}</code></div>`);
  const recent = ui.events.filter((e) => e.device === id).slice(0, 4);
  for (const e of recent) acts.push(`<div>${clock(e.t)} · ${esc(describeEvent(e).text)}</div>`);
  $("#dActivity").innerHTML = acts.join("") || `<span class="muted">Sin actividad reciente.</span>`;
}

function closeDrawer() {
  ui.selected = null;
  stage.select(null);
  $("#drawer").hidden = true;
  for (const row of document.querySelectorAll(".dev")) row.classList.remove("on");
  for (const m of stage.markers.values()) m.label.classList.remove("sel");
}

// -------------------------------------------------------------------------------- actividad
function clock(t) {
  return new Date(t).toLocaleTimeString("es-CO", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "America/Bogota" });
}

function describeEvent(e) {
  const d = e.data || {};
  const name = DEVICES[e.device]?.name || e.device;
  if (e.channel === "cmd") {
    return { tag: "cmd", label: "comando",
             text: `${name}: ${d.action} ${JSON.stringify(d.params || {})} (${d.source || "cli"})` };
  }
  if (e.channel === "ack") return { tag: "cmd", label: "confirmado", text: `${name}: ${d.action} → ${d.ok ? "ok" : "falló"} ${d.detail || ""}` };
  if (e.device === "alertas") {
    return { tag: "alert", label: d.status === "resolved" ? "resuelta" : "alerta",
             text: `${d.alert || "Alerta"}${d.device ? " · " + (DEVICES[d.device]?.name || d.device) : ""}${d.summary ? ": " + d.summary : ""}` };
  }
  if (e.device === "anomalias") {
    const dev = DEVICES[d.device]?.name || d.device;
    return { tag: "anom", label: d.type === "anomaly_close" ? "anomalía cerrada" : "anomalía",
             text: `${dev} · ${fieldLabel(d.variable || "")}: ${d.valor ?? "?"} (esperado ${d.esperado ?? "?"}, z ${d.z ?? "?"})` };
  }
  if (e.device === "reglas") {
    return { tag: "rule", label: "regla", text: `${d.rule}: ${d.decision || (d.enabled ? "activada" : "desactivada")}${d.reason ? " · " + d.reason : ""}` };
  }
  const type = EVENT_NAMES[d.type] || d.type || "evento";
  const extras = Object.entries(d).filter(([k]) => !["ts", "type"].includes(k)).slice(0, 3)
    .map(([k, v]) => `${k} ${typeof v === "number" ? +v.toFixed(2) : v}`).join(", ");
  return { tag: d.type === "harvest" ? "harvest" : "", label: type.toLowerCase(), text: `${name}: ${type}${extras ? " · " + extras : ""}` };
}

function pushEvent(e, isNew = false) {
  if (isNew) ui.events.unshift(e);
  ui.events = ui.events.slice(0, 300);
  renderFeed();
  if (isNew) renderMarks();
}

function renderFeed() {
  const list = $("#feedList");
  list.innerHTML = ui.events.slice(0, 50).map((e, i) => {
    const info = describeEvent(e);
    return `<li data-i="${i}"><time>${clock(e.t)}</time><span><span class="tag ${info.tag}">${esc(info.label)}</span>${esc(info.text)}</span></li>`;
  }).join("") || `<li><time></time><span class="muted">Sin actividad en las últimas 24 h.</span></li>`;
}

async function loadEvents() {
  try {
    const data = await getJSON("/events?hours=24");
    ui.events = data.events;
    renderFeed();
    renderMarks();
  } catch {
    renderFeed();
  }
}

async function refreshAnomalies() {
  try {
    const data = await getJSON("/anomalies");
    ui.anomalies = data.last_check || {};
    ui.openAnomalies = data.open || [];
  } catch { /* sin anomalías disponibles */ }
}

// ------------------------------------------------------------------------- línea de tiempo
const DAY = 24 * 3600 * 1000;
function windowStart() { return Date.now() - DAY; }

function renderMarks() {
  const t0 = windowStart();
  $("#tlMarks").innerHTML = ui.events.filter((e) => e.t >= t0).slice(0, 220).map((e) => {
    const info = describeEvent(e);
    const kind = info.tag === "alert" ? "alert" : info.tag === "cmd" ? "cmd" : info.tag === "anom" ? "anom" : info.tag === "harvest" ? "harvest" : "";
    if (!kind) return "";
    return `<i class="${kind}" style="left:${(((e.t - t0) / DAY) * 100).toFixed(2)}%" title="${esc(clock(e.t) + " · " + info.text)}"></i>`;
  }).join("");
}

async function enterReplay(t) {
  if (ui.farm === "sim") {
    syncSlider();
    return toast("La reproducción es de la granja real. Cambia a «Granja real».");
  }
  if (!ui.replay) {
    toast("Cargando las últimas 24 h…");
    try {
      ui.replay = await Replay.load(24);
    } catch {
      return toast("No se pudo cargar la reproducción.");
    }
  }
  ui.mode = "replay";
  ui.replayT = Math.max(ui.replay.t0, Math.min(t, ui.replay.t1));
  $("#tlLive").classList.remove("on");
  stage.showFlow = false;
  setPill();
  ui.dirty = true;
  if (ui.selected) renderDrawerValues(true);
}

function goLive() {
  ui.mode = "live";
  ui.playing = false;
  ui.replay = null;
  $("#tlPlay").textContent = "▶";
  $("#tlLive").classList.add("on");
  $("#tlRange").value = 1000;
  $("#tlTime").textContent = "Ahora";
  stage.showFlow = $("#t-flow").classList.contains("on");
  setPill();
  ui.dirty = true;
  if (ui.selected) renderDrawerValues(true);
}

function syncSlider() {
  if (ui.mode !== "replay") return;
  const t0 = windowStart();
  $("#tlRange").value = Math.round(((ui.replayT - t0) / DAY) * 1000);
  $("#tlTime").textContent = new Date(ui.replayT).toLocaleString("es-CO", { weekday: "short", hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "America/Bogota" });
}

// ------------------------------------------------------------------------------ recorrido
function startTour() {
  stopTour();
  let i = 0;
  const step = () => {
    const ids = TOUR.filter((id) => ui.farm === "real" || SIM_TWINS.has(id));
    select(ids[i % ids.length], true);
    i++;
  };
  step();
  ui.tour = setInterval(step, 7000);
  $("#t-tour").classList.add("on");
}

function stopTour() {
  if (ui.tour) clearInterval(ui.tour);
  ui.tour = null;
  $("#t-tour").classList.remove("on");
}

// ------------------------------------------------------------------------------- informes
async function openReports(tab = "reports") {
  $("#reports").hidden = false;
  for (const b of document.querySelectorAll("#reports [data-tab]")) b.classList.toggle("on", b.dataset.tab === tab);
  const list = $("#rList"), content = $("#rContent");
  list.hidden = tab !== "reports";
  $("#reports .modal-body").style.gridTemplateColumns = tab === "reports" ? "" : "1fr";
  if (tab === "reports") {
    list.innerHTML = `<p class="muted">Cargando…</p>`;
    try {
      const { reports } = await getJSON("/reports");
      list.innerHTML = reports.map((r, i) => `<button class="r-card ${i === 0 ? "on" : ""}" data-name="${esc(r.name)}">
        <b>${esc(r.name.slice(0, 10))} · ${esc(r.name.slice(11, 13))}:${esc(r.name.slice(13, 15))}</b>
        <p>${esc(r.summary || "Informe estadístico")}</p>
        <small>${esc(r.model || "sin IA")} · ${r.hours || 24} h · ${r.anomalies} anomalías · ${r.alerts} alertas</small></button>`).join("")
        || `<p class="muted">Aún no hay informes.</p>`;
      for (const card of list.querySelectorAll(".r-card")) card.addEventListener("click", () => showReport(card.dataset.name));
      if (reports[0]) showReport(reports[0].name);
    } catch {
      list.innerHTML = `<p class="muted">No se pudieron cargar los informes.</p>`;
    }
  } else if (tab === "anomalies") {
    await refreshAnomalies();
    const rows = Object.entries(ui.anomalies).sort((a, b) => Math.abs(b[1].z) - Math.abs(a[1].z));
    const open = ui.openAnomalies || [];
    content.innerHTML = `<h2>Anomalías por patrón diario</h2>
      <p class="muted">Cada 10 minutos se compara el promedio de 30 minutos de cada variable con la media y la desviación de esa misma hora en los días anteriores. Con |z| ≥ 3 dos veces seguidas se abre una anomalía; con |z| &lt; 2 se cierra.</p>
      <h3>Abiertas ahora: ${open.length}</h3>
      ${open.map((a) => `<div class="anom-row"><code>${esc(a.device)}/${esc(a.variable)}</code><span>${esc(a.valor)}</span><span>esperado ${esc(a.esperado)}</span><span class="z hot">z ${esc(a.z)}</span></div>`).join("") || `<p class="muted">Ninguna: todo dentro de su patrón diario.</p>`}
      <h3>Última revisión por variable</h3>
      ${rows.map(([key, a]) => {
        const w = Math.min(50, Math.abs(a.z) / 4 * 50);
        return `<div class="anom-row"><code>${esc(key)}</code><span>${esc(a.actual)} (esperado ${esc(a.esperado)})</span>
          <div class="bar"><i class="${Math.abs(a.z) >= 3 ? "hot" : ""}" style="${a.z >= 0 ? `left:50%;width:${w}%` : `left:${50 - w}%;width:${w}%`}"></i></div>
          <span class="z ${Math.abs(a.z) >= 3 ? "hot" : ""}">z ${a.z > 0 ? "+" : ""}${esc(a.z)}</span></div>`;
      }).join("")}`;
  } else {
    let rules = live?.rules || [];
    try { rules = (await getJSON("/rules")).rules; } catch { /* usa las del snapshot */ }
    content.innerHTML = `<h2>Reglas en la nube</h2><p class="muted">Corren en el servicio de control y envían comandos por MQTT sin intervención humana.</p>
      ${rules.map((r) => `<div class="anom-row" style="grid-template-columns:1fr auto"><div><code>${esc(r.id)}</code><p style="margin:4px 0 0">${esc(r.description)}</p>
        ${r.last ? `<p class="muted" style="margin:2px 0 0">Última decisión: ${esc(typeof r.last === "string" ? r.last : JSON.stringify(r.last))}</p>` : ""}</div>
        <span class="z ${r.enabled ? "" : "hot"}">${r.enabled ? "activa" : "inactiva"}</span></div>`).join("")}`;
  }
}

async function showReport(name) {
  for (const c of document.querySelectorAll(".r-card")) c.classList.toggle("on", c.dataset.name === name);
  const content = $("#rContent");
  content.innerHTML = `<p class="muted">Cargando…</p>`;
  try {
    const r = await getJSON(`/reports/${encodeURIComponent(name)}`);
    const md = window.marked ? window.marked.parse(r.analysis || "") : `<pre>${esc(r.analysis)}</pre>`;
    const safe = window.DOMPurify ? window.DOMPurify.sanitize(md) : esc(r.analysis);
    content.innerHTML = `<p class="meta">${esc(r.generated || name)} · ${esc(r.model || "sin IA")} · <a href="/iot/reportes/${encodeURIComponent(name)}" target="_blank" rel="noopener" style="color:var(--leaf)">ver con gráficas</a></p>${safe}`;
  } catch {
    content.innerHTML = `<p class="muted">No se pudo abrir el informe.</p>`;
  }
}

// ---------------------------------------------------------------------------------- controles
function setView(mode) {
  stopTour();
  stage.setView(mode);
  twins.setView(mode);
  for (const b of document.querySelectorAll("[data-view]")) b.classList.toggle("on", b.dataset.view === mode);
  $("#rotate").hidden = mode !== "25d";
  layoutTop();
  ui.dirty = true;
}

function setFarm(farm) {
  if (farm === ui.farm) return;
  if (farm === "sim" && ui.mode === "replay") goLive();
  ui.farm = farm;
  for (const b of document.querySelectorAll("[data-farm]")) b.classList.toggle("on", b.dataset.farm === farm);
  for (const [id, m] of stage.markers) m.group.visible = farm === "real" || SIM_TWINS.has(id);
  stage.hub.visible = true;
  if (ui.selected && farm === "sim" && !SIM_TWINS.has(ui.selected)) closeDrawer();
  else if (ui.selected) select(ui.selected);
  filterList();
  setPill();
  toast(farm === "sim" ? "Granja de simulación: mismos equipos, datos de los escenarios." : "Granja real: datos en vivo de la captura.");
  ui.dirty = true;
}

let toastTimer;
function toast(text) {
  const el = $("#toast");
  el.textContent = text;
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 2600);
}

function toggle(button, on) {
  const state = on ?? !button.classList.contains("on");
  button.classList.toggle("on", state);
  return state;
}

// Los paneles empiezan debajo de la barra superior, aunque esta ocupe dos líneas
function layoutTop() {
  const bar = document.querySelector(".topbar").getBoundingClientRect();
  document.documentElement.style.setProperty("--top", `${Math.round(bar.bottom + 10)}px`);
}

function bindUI() {
  layoutTop();
  window.addEventListener("resize", layoutTop);
  for (const b of document.querySelectorAll("[data-view]")) b.addEventListener("click", () => setView(b.dataset.view));
  for (const b of document.querySelectorAll("[data-rot]")) b.addEventListener("click", () => stage.rotateIso(+b.dataset.rot));
  for (const b of document.querySelectorAll("[data-farm]")) b.addEventListener("click", () => setFarm(b.dataset.farm));
  $("#t-labels").addEventListener("click", (e) => document.body.classList.toggle("hide-labels", !toggle(e.currentTarget)));
  $("#t-flow").addEventListener("click", (e) => { stage.showFlow = toggle(e.currentTarget) && ui.mode === "live"; });
  $("#t-tour").addEventListener("click", () => (ui.tour ? stopTour() : startTour()));
  $("#t-home").addEventListener("click", () => { stopTour(); closeDrawer(); stage.home(); });
  $("#t-reports").addEventListener("click", () => openReports());
  $("#rClose").addEventListener("click", () => ($("#reports").hidden = true));
  $("#reports").addEventListener("click", (e) => { if (e.target.id === "reports") $("#reports").hidden = true; });
  for (const b of document.querySelectorAll("#reports [data-tab]")) b.addEventListener("click", () => openReports(b.dataset.tab));
  $("#dClose").addEventListener("click", closeDrawer);
  for (const b of document.querySelectorAll("#dRange button")) {
    b.addEventListener("click", () => {
      ui.range = +b.dataset.h;
      for (const o of document.querySelectorAll("#dRange button")) o.classList.toggle("on", o === b);
      loadHistory();
    });
  }
  $("#devSearch").addEventListener("input", filterList);
  $("#devToggle").addEventListener("click", () => $("#devices").classList.toggle("collapsed"));
  $("#feedToggle").addEventListener("click", () => $("#feed").classList.toggle("collapsed"));
  $("#feedList").addEventListener("click", (e) => {
    const li = e.target.closest("li[data-i]");
    if (!li) return;
    const ev = ui.events[+li.dataset.i];
    const target = DEVICES[ev.device] ? ev.device : DEVICES[ev.data?.device] ? ev.data.device : null;
    if (target) select(target);
    if (ev.t < Date.now() - 60000) enterReplay(ev.t);
  });
  $("#tlRange").addEventListener("input", (e) => {
    const value = +e.target.value;
    if (value >= 999) return goLive();
    enterReplay(windowStart() + (value / 1000) * DAY).then(syncSlider);
  });
  $("#tlLive").addEventListener("click", goLive);
  $("#tlPlay").addEventListener("click", togglePlay);
  $("#stage").addEventListener("pointerdown", () => stopTour());
  window.addEventListener("keydown", (e) => {
    if (e.target.matches("input, select, textarea")) return;
    const k = e.key.toLowerCase();
    if (k === "1") setView("3d");
    else if (k === "2") setView("25d");
    else if (k === "3") setView("2d");
    else if (k === "l") $("#t-labels").click();
    else if (k === "f") $("#t-flow").click();
    else if (k === "t") $("#t-tour").click();
    else if (k === "h") $("#t-home").click();
    else if (k === "r") openReports();
    else if (k === " ") { e.preventDefault(); togglePlay(); }
    else if (k === "escape") { if (!$("#reports").hidden) $("#reports").hidden = true; else closeDrawer(); }
  });
}

async function togglePlay() {
  if (ui.mode !== "replay") await enterReplay(windowStart());
  if (ui.mode !== "replay") return;
  ui.playing = !ui.playing;
  $("#tlPlay").textContent = ui.playing ? "❚❚" : "▶";
  setPill();
  syncSlider();
}

boot();
