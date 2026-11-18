// Lenguaje simple: qué significa cada variable y si el valor actual está bien, en palabras.
// assess() devuelve { level: "ok" | "warn" | "bad" | "info", text } para pintar el estado con color Y texto.

export const HELP = {
  soil_pct: "Cuánta agua tiene la tierra. Entre 35 y 65 % es lo ideal; por debajo de 35 % se abre el riego.",
  growth_pct: "Avance del cultivo desde la siembra. Al llegar a 100 % se cosecha y va al silo.",
  mature_count: "Plantas listas para cosechar en este momento.",
  light_lvl: "Luz que reciben las plantas, de 0 (oscuro) a 15 (pleno sol).",
  irrigation: "Si el riego está abierto. Mientras riega, el canal se llena y gasta agua del tanque.",
  lamp: "Lámparas de apoyo: se encienden cuando hay poca luz solar.",
  harvests: "Cosechas completadas desde que empezó la captura.",
  level_pct: "Qué tan lleno está el tanque de 2000 litros.",
  volume_l: "Litros de agua que hay en el tanque.",
  outflow_lpm: "Agua que sale del tanque ahora (riego y aspersores), en litros por minuto.",
  inflow_lpm: "Agua que entra al tanque ahora (bomba y lluvia), en litros por minuto.",
  pump: "Si la bomba que llena el tanque está encendida.",
  temp_c: "Temperatura del aire en grados Celsius.",
  hum_pct: "Humedad relativa: cuánto vapor de agua tiene el aire.",
  vent_open: "Si las ventilas del techo están abiertas para sacar el calor.",
  heater: "Si la calefacción está encendida porque hace frío.",
  plant_health_pct: "Salud de las plantas del invernadero: baja si pasan mucho calor o mucho frío.",
  fill_pct: "Qué tan lleno está el silo de grano.",
  items: "Unidades de grano guardadas en el silo.",
  hostiles_near: "Monstruos detectados cerca de la cerca de la granja.",
  players_in_zone: "Personas dentro de la zona vigilada.",
  gate_open: "Si el portón de entrada está abierto.",
  alarm: "Si la alarma de seguridad está sonando.",
  intruders_total: "Intrusiones detectadas desde que empezó la captura.",
  raining: "Si está lloviendo en el mundo (sigue a la lluvia real de Bucaramanga).",
  thundering: "Si hay tormenta eléctrica.",
  daylight_lvl: "Luz del día, de 0 (noche) a 15 (mediodía).",
  time_ticks: "Hora del mundo, sincronizada con la hora real de Colombia.",
  tps: "Velocidad del servidor que mueve los gemelos: 20 es perfecto; por debajo de 15 va lento.",
  mspt: "Milisegundos que tarda cada paso del servidor (menos es mejor; el máximo sano es 50).",
  heap_mb: "Memoria que usa el servidor del gemelo.",
  cows_total: "Vacas en el corral.", cows_adult: "Vacas adultas.", cows_baby: "Terneros.",
  feed_pct: "Comida en el comedero; se rellena con trigo del silo.",
  water_pct: "Agua en el bebedero de las vacas.",
  thi: "Índice de calor para el ganado (mezcla temperatura y humedad). Menos de 72 es cómodo; más de 78 es estrés.",
  welfare_pct: "Bienestar del rebaño: baja con calor, hambre o sed.",
  cooling: "Si los aspersores que refrescan a las vacas están encendidos.",
  beef_stored: "Carne guardada en la cámara fría.",
  rain_mm: "Lluvia caída en la última hora, en milímetros.", precip_mm: "Precipitación de la última hora.",
  wind_kmh: "Velocidad del viento.", radiation_wm2: "Energía del sol que llega al suelo (0 de noche, ~1000 a pleno sol).",
  pressure_hpa: "Presión del aire. Bucaramanga está a ~960 m de altura, por eso ronda 900 hPa.",
  cloud_pct: "Porcentaje del cielo cubierto de nubes.", is_day: "Si es de día.",
  pm2_5: "Partículas finas en el aire que respiramos. La OMS recomienda menos de 15 µg/m³.",
  pm10: "Partículas gruesas (polvo).", o3: "Ozono a nivel del suelo.", no2: "Dióxido de nitrógeno (tráfico).",
  uv_index: "Fuerza de la radiación ultravioleta: más de 6 quema la piel rápido.",
  us_aqi: "Índice de calidad del aire de EE. UU.: 0-50 buena, 51-100 moderada, más de 100 dañina.",
  soil_temp_c: "Temperatura de la tierra.", soil_moist_m3m3: "Agua en el suelo real (m³ por m³).",
  et0_mm: "Evapotranspiración: agua que el sol y el viento sacan del suelo y las plantas. Seca las parcelas.",
  vpd_kpa: "Déficit de presión de vapor: qué tanto 'chupa' el aire el agua de las hojas.",
  rain_next24_mm: "Lluvia esperada en las próximas 24 horas. Con 5 mm o más se riega menos.",
  rain_prob_max_pct: "Probabilidad máxima de lluvia en las próximas 24 horas.",
  tmax_next24_c: "Temperatura máxima esperada mañana.", tmin_next24_c: "Temperatura mínima esperada.",
  thi_stress_hours_24h: "Horas en que el ganado pasará calor en las próximas 24 h.",
  hours_to_irrigation_a: "Horas hasta que la parcela A necesite riego.", hours_to_irrigation_b: "Horas hasta que la parcela B necesite riego.",
  water_need_24h_l: "Agua que se calcula que harán falta en las próximas 24 h.",
  co2_ppm: "Dióxido de carbono en el invernadero de referencia (las plantas lo usan para crecer).",
  par_umol: "Luz útil para la fotosíntesis.", vent_pct: "Apertura de las ventilas del invernadero de referencia.",
  power_kw: "Potencia eléctrica que consume el hogar del dataset en este minuto.",
  voltage_v: "Voltaje de la red eléctrica.", current_a: "Corriente eléctrica.",
  cpu_pct: "Uso del procesador del PC.", ram_pct: "Memoria del PC en uso.", disk_pct: "Disco del PC ocupado.",
  link_rtt_ms: "Tiempo que tarda un mensaje en ir y volver a la nube (latencia).",
  battery_pct: "Carga de la batería.", plugged: "Si está conectado al cargador.",
  net_up_kbps: "Datos que sube a internet.", net_down_kbps: "Datos que baja de internet.",
  accel_ms2: "Movimiento del celular (0 = quieto).", sound_dbfs: "Nivel de ruido del micrófono (más cerca de 0 = más fuerte).",
  rel_alt_m: "Cambio de altura del celular según el barómetro.",
};

const levels = (value, cuts) => {
  for (const [limit, level, text] of cuts) if (value < limit) return { level, text };
  const last = cuts[cuts.length - 1];
  return { level: last[3] || "ok", text: last[4] || "" };
};

export function assess(key, value, device = "") {
  if (value === undefined || value === null || Number.isNaN(value)) return { level: "info", text: "Sin dato" };
  const on = value >= 0.5;
  switch (key) {
    case "soil_pct": return levels(value, [[30, "bad", "Seca"], [35, "warn", "Baja: toca regar"], [66, "ok", "Ideal"], [80, "warn", "Muy húmeda", "bad", "Encharcada"]]);
    case "level_pct": return levels(value, [[15, "bad", "Casi vacío"], [25, "warn", "Bajo"], [95, "ok", "Normal", "warn", "Lleno"]]);
    case "temp_c":
      if (device === "mc-invernadero") return levels(value, [[16, "warn", "Frío"], [28.01, "ok", "Normal"], [32, "warn", "Caluroso", "bad", "Muy caliente"]]);
      return levels(value, [[18, "info", "Fresco"], [30, "info", "Templado", "info", "Caluroso"]]);
    case "thi": return levels(value, [[72, "ok", "Cómodo"], [78, "warn", "Estrés leve"], [89, "bad", "Estrés", "bad", "Peligro"]]);
    case "welfare_pct": case "plant_health_pct": return levels(value, [[50, "bad", "Malo"], [70, "warn", "Regular", "ok", "Bueno"]]);
    case "feed_pct": case "water_pct": return levels(value, [[15, "bad", "Casi vacío"], [30, "warn", "Bajo", "ok", "Suficiente"]]);
    case "fill_pct": return levels(value, [[95, "ok", "Con espacio", "warn", "Lleno"]]);
    case "pm2_5": return levels(value, [[15.01, "ok", "Buena (OMS)"], [37.6, "warn", "Moderada", "bad", "Dañina"]]);
    case "us_aqi": return levels(value, [[51, "ok", "Buena"], [101, "warn", "Moderada", "bad", "Dañina"]]);
    case "uv_index": return levels(value, [[3, "ok", "Baja"], [6, "warn", "Moderada"], [8, "bad", "Alta", "bad", "Muy alta"]]);
    case "tps": return levels(value, [[15, "bad", "Lento"], [19, "warn", "Algo lento", "ok", "Fluido"]]);
    case "cpu_pct": case "ram_pct": return levels(value, [[70, "ok", "Normal"], [90, "warn", "Alto", "bad", "Saturado"]]);
    case "disk_pct": return levels(value, [[90, "ok", "Normal", "warn", "Casi lleno"]]);
    case "battery_pct": return levels(value, [[15, "bad", "Crítica"], [30, "warn", "Baja", "ok", "Bien"]]);
    case "link_rtt_ms": return levels(value, [[150, "ok", "Rápida"], [400, "warn", "Lenta", "bad", "Muy lenta"]]);
    case "hostiles_near": return value > 0 ? { level: "warn", text: "Amenaza cerca" } : { level: "ok", text: "Despejado" };
    case "alarm": return on ? { level: "bad", text: "Sonando" } : { level: "ok", text: "En silencio" };
    case "growth_pct": return value >= 99 ? { level: "ok", text: "Lista para cosechar" } : { level: "info", text: "Creciendo" };
    case "rain_next24_mm": return value >= 5 ? { level: "info", text: "Se espera lluvia" } : { level: "info", text: "Poca lluvia" };
    case "raining": return on ? { level: "info", text: "Lloviendo" } : { level: "info", text: "Seco" };
    case "irrigation": case "pump": case "vent_open": case "heater": case "lamp": case "cooling": case "gate_open": case "plugged":
      return on ? { level: "info", text: "Encendido" } : { level: "info", text: "Apagado" };
    default: return { level: "info", text: "" };
  }
}

export const GLOSSARY = [
  ["Gemelo digital", "Copia virtual de algo real que se comporta igual porque recibe datos reales. Aquí la granja vive en un mundo de Minecraft y sigue el clima real de Bucaramanga."],
  ["Sensor", "Aparato que mide algo (temperatura, humedad, nivel) y lo convierte en un número."],
  ["Telemetría", "Las lecturas que un dispositivo envía solo, cada cierto tiempo."],
  ["MQTT", "Protocolo liviano para que los dispositivos hablen con la nube: publican en un «tema» y quien esté suscrito lo recibe al instante."],
  ["Bróker", "El cartero central (Mosquitto): recibe cada mensaje y se lo entrega a quien lo necesite."],
  ["Tema (topic)", "La dirección de un mensaje, como agro/twin/mc-parcela-a/telemetry: clase, dispositivo y tipo de mensaje."],
  ["QoS 1", "Garantía de entrega «al menos una vez»: si se pierde, se reenvía."],
  ["AMQP", "Otro protocolo de mensajería, con colas y confirmación (RabbitMQ). Lo usa el medidor de energía."],
  ["Retroceso", "Cuando la nube le manda una orden al dispositivo: apagar, calibrar, cambiar un umbral o encender un actuador."],
  ["Comando y confirmación", "La orden (cmd) baja al dispositivo y este responde con una confirmación (ack) que lleva el mismo número."],
  ["Actuador", "Lo que hace algo en el mundo: riego, bomba, ventilas, alarma, aspersores."],
  ["Umbral", "Valor que dispara una acción, como regar cuando el suelo baja de 35 %."],
  ["Anomalía (z)", "Un valor raro para esa hora del día. z dice a cuántas desviaciones está de lo normal: más de 3 es muy raro."],
  ["ET0", "Agua que el sol y el viento evaporan del suelo y las plantas. Por eso las parcelas se secan más en días soleados."],
  ["THI", "Índice de temperatura y humedad para el ganado: indica cuándo las vacas pasan calor."],
  ["API", "Servicio web al que se le piden datos (aquí, el clima real de Open-Meteo)."],
  ["DataSet", "Datos reales grabados de otro lugar que se reproducen como si llegaran ahora."],
];

// Umbrales con nombre para las gráficas del detalle: [valor, qué pasa]. Por dispositivo cuando cambia.
export function thresholds(device, key) {
  const base = device.startsWith("sim-") ? "mc-" + device.slice(4) : device;
  if (key === "soil_pct") return [[35, "riego se abre"], [65, "riego se cierra"]];
  if (key === "level_pct") return [[25, "bomba se enciende"], [90, "bomba se apaga"]];
  if (key === "temp_c" && base === "mc-invernadero") return [[28, "ventilas se abren"], [16, "calefacción"]];
  if (key === "thi" || key === "thi_max_next24") return [[72, "estrés leve"], [78, "estrés"]];
  if (key === "tps") return [[15, "lento"]];
  if (key === "us_aqi") return [[100, "ventilas se cierran"]];
  if (key === "pm2_5") return [[15, "límite OMS"]];
  if (key === "radiation_wm2") return [[150, "lámparas si es de día"]];
  if (key === "rain_next24_mm") return [[5, "riego reducido"]];
  if (key === "cpu_pct" || key === "ram_pct") return [[90, "saturado"]];
  if (key === "feed_pct" || key === "water_pct") return [[30, "bajo"]];
  if (key === "welfare_pct") return [[50, "malo"]];
  return [];
}

export const PERCENT_FIELDS = new Set(["soil_pct", "growth_pct", "level_pct", "hum_pct", "plant_health_pct", "fill_pct",
  "feed_pct", "water_pct", "welfare_pct", "cpu_pct", "ram_pct", "disk_pct", "battery_pct", "cloud_pct", "vent_pct", "rain_prob_max_pct"]);
