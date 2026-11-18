// Catálogo de dispositivos: qué son, dónde están en el plano (coordenadas de Layout.java) y cómo se leen
// sus variables. Las posiciones de API, dataset y reales son lógicas: se dibujan cerca de lo que miden.

export const CLASSES = {
  twin: { label: "Gemelo digital", short: "Gemelo", color: "#7bc86c" },
  api: { label: "API", short: "API", color: "#5fb2ff" },
  dataset: { label: "DataSet", short: "DataSet", color: "#c08cff" },
  real: { label: "Real", short: "Real", color: "#f0b34a" },
  sim: { label: "Simulación", short: "Sim", color: "#ff8a5c" },
  platform: { label: "Plataforma", short: "Nube", color: "#9aa7b4" },
};

// zone: caja del plano [x1, z1, x2, z2] (se usa para enfocar, resaltar y elegir con el ratón)
export const DEVICES = {
  "mc-parcela-a": {
    name: "Parcela A", kind: "Trigo · riego por canal", cls: "twin", zone: [-6, 10, 2, 18],
    sensor: "Sonda capacitiva de humedad de suelo + BH1750 (luz)", protocol: "MQTT 3.1.1 · QoS 1",
    about: "72 plantas de trigo. La humedad baja con la ET0 real de Bucaramanga y sube con el riego, que toma 20 L/min del tanque. Al madurar se cosecha hacia el silo.",
    main: "soil_pct", actuators: ["irrigation", "lamp"],
  },
  "mc-parcela-b": {
    name: "Parcela B", kind: "Zanahoria y papa", cls: "twin", zone: [30, 10, 38, 18],
    sensor: "Sonda capacitiva de humedad de suelo + BH1750 (luz)", protocol: "MQTT 3.1.1 · QoS 1",
    about: "Se seca 25 % más rápido y crece 15 % más lento que la parcela A. Comparte tanque y silo.",
    main: "soil_pct", actuators: ["irrigation", "lamp"],
  },
  "mc-silo": {
    name: "Silo", kind: "Grano cosechado", cls: "twin", zone: [15, 13, 17, 15],
    sensor: "Celda de carga (peso del grano)", protocol: "MQTT 3.1.1 · QoS 1",
    about: "Recibe la cosecha de las dos parcelas y despacha trigo al comedero del corral.",
    main: "fill_pct",
  },
  "mc-tanque": {
    name: "Tanque de agua", kind: "2000 L", cls: "twin", zone: [0, -18, 4, -14],
    sensor: "Ultrasónico JSN-SR04T + caudalímetro YF-S201", protocol: "MQTT 3.1.1 · QoS 1",
    about: "Nivel visible por capas. Lo llenan la bomba (30 L/min) y la lluvia real (40 L por mm); lo vacían el riego y los aspersores.",
    main: "level_pct", actuators: ["pump"],
  },
  "mc-invernadero": {
    name: "Invernadero", kind: "Bayas bajo vidrio", cls: "twin", zone: [24, -24, 32, -16],
    sensor: "SHT31 (temperatura y humedad) + BH1750", protocol: "MQTT 3.1.1 · QoS 1",
    about: "Sigue la temperatura exterior real más la ganancia solar. El techo cambia de color con la temperatura; las ventilas la bajan y la calefacción la sube.",
    main: "temp_c", actuators: ["vent_open", "heater", "lamp"],
  },
  "mc-corral": {
    name: "Corral", kind: "Ganado y planta de proceso", cls: "twin", zone: [-38, -30, -28, -14],
    sensor: "THI con SHT31 · báscula del comedero · nivel del bebedero", protocol: "MQTT 3.1.1 · QoS 1",
    about: "Vacas que nacen, crecen y se procesan. El índice THI sale de la temperatura y humedad reales; los aspersores lo bajan.",
    main: "thi", actuators: ["cooling"],
  },
  "mc-seguridad": {
    name: "Seguridad", kind: "Perímetro y portón", cls: "twin", zone: [6, 30, 10, 34],
    sensor: "Sensor PIR + contacto magnético de puerta", protocol: "MQTT 3.1.1 · QoS 1",
    about: "Cuenta monstruos e intrusos cerca de la cerca y enciende la alarma.",
    main: "hostiles_near", actuators: ["alarm", "gate_open"],
  },
  "mc-estacion": {
    name: "Estación meteorológica", kind: "Clima del mundo", cls: "twin", zone: [-16, 28, -12, 32],
    sensor: "Pluviómetro + piranómetro", protocol: "MQTT 3.1.1 · QoS 1",
    about: "Lluvia, tormenta, luz y hora del mundo, sincronizadas con el clima y la hora reales de Bucaramanga.",
    main: "daylight_lvl",
  },
  "mc-server": {
    name: "Servidor del gemelo", kind: "Salud del mundo", cls: "twin", zone: [52, 12, 55, 16],
    sensor: "Monitoreo del gateway (TPS, memoria)", protocol: "MQTT 3.1.1 · QoS 1",
    about: "TPS, milisegundos por tick, chunks y entidades del servidor Paper que corre los gemelos.",
    main: "tps",
  },
  "api-clima": {
    name: "Clima real", kind: "Open-Meteo Forecast", cls: "api", logical: [-30, 16, 52],
    sensor: "Servicio web (HTTPS pull cada 5 min)", protocol: "HTTPS GET → MQTT",
    about: "Temperatura, humedad, lluvia, viento, radiación y nubosidad actuales de Bucaramanga. Mueven a todos los gemelos.",
    main: "temp_c",
  },
  "api-aire": {
    name: "Calidad del aire", kind: "Open-Meteo Air Quality", cls: "api", logical: [-20, 18, 54],
    sensor: "Servicio web (HTTPS pull cada 10 min)", protocol: "HTTPS GET → MQTT",
    about: "PM2.5, PM10, ozono, NO₂, índice UV y US AQI. Con AQI alto se cierran las ventilas del invernadero.",
    main: "us_aqi",
  },
  "api-suelo": {
    name: "Suelo y ET0", kind: "Open-Meteo (suelo)", cls: "api", logical: [-10, 16, 52],
    sensor: "Servicio web (HTTPS pull cada 10 min)", protocol: "HTTPS GET → MQTT",
    about: "Temperatura y humedad del suelo, evapotranspiración y déficit de presión de vapor. La ET0 seca las parcelas.",
    main: "et0_mm",
  },
  "api-pronostico": {
    name: "Pronóstico 24 h", kind: "Open-Meteo + modelo propio", cls: "api", logical: [0, 18, 54],
    sensor: "Servicio web (HTTPS pull cada 15 min)", protocol: "HTTPS GET → MQTT",
    about: "Lluvia esperada, temperatura máxima, horas de estrés térmico y horas hasta el próximo riego. Alimenta la regla riego-pronóstico.",
    main: "rain_next24_mm",
  },
  "ds-invernadero": {
    name: "Invernadero de referencia", kind: "Wageningen 2019", cls: "dataset", logical: [37, 7, -20],
    sensor: "Dataset Autonomous Greenhouse Challenge (reproducido)", protocol: "MQTT 3.1.1 · QoS 1",
    about: "Un invernadero real de Países Bajos reproducido al ritmo del reloj, para comparar con el gemelo.",
    main: "temp_c",
  },
  "ds-energia": {
    name: "Medidor de energía", kind: "UCI Household Power", cls: "dataset", logical: [-29, 6, 0],
    sensor: "Dataset de consumo eléctrico (reproducido)", protocol: "AMQP 0-9-1 (RabbitMQ)",
    about: "Potencia, voltaje y corriente de un hogar real, reproducidos minuto a minuto. Es el único que envía por AMQP.",
    main: "power_kw",
  },
  "gw-pc-01": {
    name: "PC gateway", kind: "Agente Python", cls: "real", logical: [50, 5, 19],
    sensor: "psutil: CPU, RAM, disco, batería, red", protocol: "MQTT 3.1.1 sobre TLS 8883",
    about: "El PC del operario como gateway de campo. Solo envía mientras está encendido.",
    main: "cpu_pct",
  },
  "phone-01": {
    name: "Celular", kind: "Sensor Logger (iPhone)", cls: "real", logical: [20, 5, 44],
    sensor: "Barómetro, micrófono, acelerómetro, batería", protocol: "HTTPS POST → MQTT",
    about: "El teléfono envía presión, sonido y aceleración por HTTPS con token; el servicio de control los publica por MQTT.",
    main: "pressure_hpa",
  },
};

// Hub de la plataforma en la nube (destino de los paquetes animados)
export const HUB = [14, 34, -4];

export const FIELDS = {
  soil_pct: ["Humedad del suelo", "%"], growth_pct: ["Crecimiento", "%"], mature_count: ["Plantas maduras", ""],
  light_lvl: ["Luz", "/15"], irrigation: ["Riego", "bool"], lamp: ["Lámparas", "bool"], harvests: ["Cosechas", ""],
  level_pct: ["Nivel", "%"], volume_l: ["Volumen", "L"], outflow_lpm: ["Salida", "L/min"], inflow_lpm: ["Entrada", "L/min"],
  pump: ["Bomba", "bool"], temp_c: ["Temperatura", "°C"], hum_pct: ["Humedad", "%"], vent_open: ["Ventilas", "bool"],
  heater: ["Calefacción", "bool"], plant_health_pct: ["Salud de plantas", "%"], fill_pct: ["Llenado", "%"],
  items: ["Unidades", ""], comparator_lvl: ["Comparador", "/15"], received_total: ["Recibido", ""],
  dispatched_total: ["Despachado", ""], hostiles_near: ["Monstruos cerca", ""], players_in_zone: ["Personas en zona", ""],
  gate_open: ["Portón", "bool"], alarm: ["Alarma", "bool"], intruders_total: ["Intrusiones", ""],
  raining: ["Lluvia", "bool"], thundering: ["Tormenta", "bool"], daylight_lvl: ["Luz del día", "/15"],
  time_ticks: ["Hora del mundo", "ticks"], tps: ["TPS", ""], mspt: ["ms por tick", "ms"], players_online: ["Jugadores", ""],
  heap_mb: ["Memoria", "MB"], loaded_chunks: ["Chunks", ""], entities: ["Entidades", ""],
  cows_total: ["Vacas", ""], cows_adult: ["Adultas", ""], cows_baby: ["Terneros", ""], feed_pct: ["Comedero", "%"],
  water_pct: ["Bebedero", "%"], thi: ["THI", ""], welfare_pct: ["Bienestar", "%"], cooling: ["Aspersores", "bool"],
  beef_stored: ["Carne en cámara", ""], beef_total: ["Carne producida", ""], births_total: ["Nacimientos", ""],
  deaths_total: ["Muertes", ""], processed_total: ["Procesadas", ""],
  precip_mm: ["Precipitación", "mm"], rain_mm: ["Lluvia", "mm"], wind_kmh: ["Viento", "km/h"],
  radiation_wm2: ["Radiación", "W/m²"], pressure_hpa: ["Presión", "hPa"], cloud_pct: ["Nubosidad", "%"],
  is_day: ["Es de día", "bool"], weather_code: ["Código WMO", ""], pm2_5: ["PM2.5", "µg/m³"], pm10: ["PM10", "µg/m³"],
  o3: ["Ozono", "µg/m³"], no2: ["NO₂", "µg/m³"], uv_index: ["Índice UV", ""], us_aqi: ["US AQI", ""],
  soil_temp_c: ["Temp. del suelo", "°C"], soil_moist_m3m3: ["Humedad del suelo", "m³/m³"], et0_mm: ["ET0", "mm"],
  et_mm: ["ET real", "mm"], vpd_kpa: ["VPD", "kPa"], rain_next24_mm: ["Lluvia 24 h", "mm"],
  rain_prob_max_pct: ["Prob. de lluvia", "%"], et0_next24_mm: ["ET0 24 h", "mm"], tmax_next24_c: ["Máx. 24 h", "°C"],
  tmin_next24_c: ["Mín. 24 h", "°C"], thi_max_next24: ["THI máx. 24 h", ""], thi_stress_hours_24h: ["Horas de estrés", "h"],
  heat_hours_24h: ["Horas de calor", "h"], hours_to_irrigation_a: ["Riego A en", "h"], hours_to_irrigation_b: ["Riego B en", "h"],
  water_need_24h_l: ["Agua 24 h", "L"], co2_ppm: ["CO₂", "ppm"], par_umol: ["PAR", "µmol/m²s"], vent_pct: ["Ventilas", "%"],
  pipe_temp_c: ["Tubería", "°C"], irrigation_lm2: ["Riego", "L/m²"], power_kw: ["Potencia", "kW"],
  reactive_kvar: ["Reactiva", "kVAr"], voltage_v: ["Voltaje", "V"], current_a: ["Corriente", "A"],
  cpu_pct: ["CPU", "%"], ram_pct: ["RAM", "%"], disk_pct: ["Disco", "%"], link_rtt_ms: ["Latencia", "ms"],
  battery_pct: ["Batería", "%"], plugged: ["Cargando", "bool"], net_up_kbps: ["Subida", "kbps"],
  net_down_kbps: ["Bajada", "kbps"], cpu_temp_c: ["Temp. CPU", "°C"], wifi_rssi_dbm: ["Wi-Fi", "dBm"],
  accel_ms2: ["Aceleración", "m/s²"], rel_alt_m: ["Altitud relativa", "m"], sound_dbfs: ["Sonido", "dBFS"], lux: ["Luz", "lx"],
  running: ["En marcha", "bool"], speed: ["Velocidad", "×"], scenario_code: ["Escenario", ""], sim_hours: ["Horas simuladas", "h"],
  sim_clock_h: ["Reloj simulado", "h"], in_temp_c: ["Temp. del escenario", "°C"], in_hum_pct: ["Humedad del escenario", "%"],
  in_rain_mmh: ["Lluvia del escenario", "mm/h"], in_radiation_wm2: ["Radiación del escenario", "W/m²"],
  in_et0_mmh: ["ET0 del escenario", "mm/h"], pump_failure: ["Falla de bomba", "bool"],
};

export function fieldLabel(key) {
  return (FIELDS[key] || [key.replaceAll("_", " "), ""])[0];
}

export function formatValue(key, value) {
  if (value === undefined || value === null || Number.isNaN(value)) return "—";
  const unit = (FIELDS[key] || ["", ""])[1];
  if (unit === "bool") return value >= 0.5 ? "Sí" : "No";
  if (key === "time_ticks") return ticksToClock(value);
  const abs = Math.abs(value);
  const digits = abs >= 100 ? 0 : abs >= 10 ? 1 : 2;
  const text = Number(value).toLocaleString("es-CO", { maximumFractionDigits: digits, minimumFractionDigits: 0 });
  return unit && unit !== "ticks" ? `${text} ${unit}` : text;
}

export function ticksToClock(ticks) {
  const hours = ((ticks / 1000) + 6) % 24;
  const h = Math.floor(hours);
  const m = Math.floor((hours - h) * 60);
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

// En la granja de simulación los gemelos se llaman sim-*; se dibujan sobre las mismas posiciones
export function baseId(id) {
  return id.startsWith("sim-") ? "mc-" + id.slice(4) : id;
}
