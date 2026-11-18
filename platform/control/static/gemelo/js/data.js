// Datos: flota en vivo por Server-Sent Events (con respaldo por consulta periódica), historial por
// dispositivo, reproducción de las últimas horas, eventos e informes del analista con IA.

const API = "/iot/api";

export class Live {
  constructor() {
    this.devices = new Map();
    this.rules = [];
    this.handlers = [];
    this.status = "conectando";
    this.stamps = [];
    this.failures = 0;
  }

  on(handler) {
    this.handlers.push(handler);
  }

  emit(kind, payload) {
    for (const h of this.handlers) h(kind, payload);
  }

  setStatus(status) {
    if (status !== this.status) {
      this.status = status;
      this.emit("status", status);
    }
  }

  snapshot(list) {
    for (const d of list) this.devices.set(d.device, d);
    this.emit("snapshot", list);
    if (!this.seeded) this.seed();
  }

  // Tras un reinicio del servicio el Hub aún no tiene la telemetría de los dispositivos lentos (las API
  // publican cada 5-15 min): se completa con el último valor guardado en InfluxDB.
  async seed() {
    this.seeded = true;
    try {
      const { devices } = await getJSON("/latest");
      for (const [id, last] of Object.entries(devices)) {
        const entry = this.devices.get(id);
        if (entry && !Object.keys(entry.telemetry || {}).length) {
          entry.telemetry = last.telemetry;
          entry.telemetry_ts = last.ts;
        }
      }
      this.emit("snapshot", [...this.devices.values()]);
    } catch { /* sin respaldo: se llena con el próximo mensaje */ }
  }

  feed({ class: cls, device, channel, data, at }) {
    const entry = this.devices.get(device) || { device, class: cls, status: "desconocido", state: {}, telemetry: {},
                                               telemetry_ts: null, last_ack: null, last_event: null };
    if (channel === "status") entry.status = data;
    else if (channel === "state" && data && typeof data === "object") entry.state = data;
    else if (channel === "telemetry" && data && typeof data === "object") {
      const { ts, seq, ...values } = data;
      entry.telemetry = values;
      entry.telemetry_ts = ts || at;
      this.stamps.push(Date.now());
    } else if (channel === "ack") entry.last_ack = data;
    else if (channel === "event") entry.last_event = data;
    this.devices.set(device, entry);
    this.emit("message", { cls, device, channel, data, at: at || Date.now() });
  }

  rate() {
    const cut = Date.now() - 60000;
    this.stamps = this.stamps.filter((t) => t > cut);
    return this.stamps.length;
  }

  start() {
    if (!("EventSource" in window)) return this.poll();
    const source = new EventSource(`${API}/stream`);
    this.source = source;
    let opened = false;
    source.addEventListener("snapshot", (e) => {
      opened = true;
      this.failures = 0;
      const data = JSON.parse(e.data);
      this.rules = data.rules || [];
      this.snapshot(data.devices);
      this.setStatus("en vivo");
    });
    source.onmessage = (e) => this.feed(JSON.parse(e.data));
    source.onerror = () => {
      this.failures += 1;
      this.setStatus("reconectando");
      if (!opened && this.failures >= 3) {
        source.close();
        this.poll();
      }
    };
  }

  async poll() {
    this.setStatus("consulta cada 5 s");
    const tick = async () => {
      try {
        const res = await fetch(`${API}/devices`, { cache: "no-store" });
        const { devices } = await res.json();
        for (const d of devices) {
          const prev = this.devices.get(d.device);
          this.devices.set(d.device, d);
          if (!prev || prev.telemetry_ts !== d.telemetry_ts) {
            this.stamps.push(Date.now());
            this.emit("message", { cls: d.class, device: d.device, channel: "telemetry", data: d.telemetry, at: Date.now() });
          }
        }
        this.emit("snapshot", devices);
      } catch {
        this.setStatus("sin conexión");
      }
    };
    await tick();
    setInterval(tick, 5000);
  }
}

export async function getJSON(path) {
  const res = await fetch(`${API}${path}`, { cache: "no-store" });
  if (!res.ok) throw new Error(`${res.status} ${path}`);
  return res.json();
}

// Reproducción: series por dispositivo y campo; valuesAt(t) devuelve el último valor conocido en t
export class Replay {
  constructor(payload) {
    this.series = payload.series;
    let t0 = Infinity, t1 = -Infinity;
    for (const fields of Object.values(this.series)) {
      for (const points of Object.values(fields)) {
        if (points.length) {
          t0 = Math.min(t0, points[0][0]);
          t1 = Math.max(t1, points[points.length - 1][0]);
        }
      }
    }
    this.t0 = t0;
    this.t1 = t1;
  }

  static async load(hours = 24) {
    return new Replay(await getJSON(`/replay?hours=${hours}`));
  }

  valuesAt(t) {
    const out = {};
    for (const [device, fields] of Object.entries(this.series)) {
      const values = {};
      for (const [field, points] of Object.entries(fields)) {
        let lo = 0, hi = points.length - 1, best = -1;
        while (lo <= hi) {
          const mid = (lo + hi) >> 1;
          if (points[mid][0] <= t) {
            best = mid;
            lo = mid + 1;
          } else hi = mid - 1;
        }
        if (best >= 0) values[field] = points[best][1];
      }
      out[device] = values;
    }
    return out;
  }
}
