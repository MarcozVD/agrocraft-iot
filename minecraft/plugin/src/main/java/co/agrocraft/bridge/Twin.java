package co.agrocraft.bridge;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Base de un gemelo digital: un dispositivo IoT que vive en el mundo de Minecraft.
 * Tiene modelo (step), lectura (sample), telemetría periódica y los comandos de retroceso comunes.
 * Todo corre en el hilo principal del servidor.
 */
abstract class Twin {
    protected final AgroCraftPlugin plugin;
    protected final Layout layout;
    final String id;
    final String topic;
    private final int defaultIntervalS;

    boolean enabled = true;
    int intervalS;
    long seq;
    private final Map<String, double[]> calibration = new HashMap<>();   // variable -> {offset, ganancia}
    private long lastSlot = -1;

    Twin(AgroCraftPlugin plugin, String id, int defaultIntervalS) {
        this.plugin = plugin;
        this.layout = plugin.layout;
        this.id = id;
        this.topic = "agro/twin/" + id;
        this.defaultIntervalS = defaultIntervalS;
        this.intervalS = defaultIntervalS;
    }

    // ---------------------------------------------------------------- lo que define cada gemelo
    /** Avanza el modelo dt segundos y refleja el resultado en los bloques. */
    abstract void step(double dt);

    /** Lectura actual: variable -> valor numérico. */
    abstract void sample(Map<String, Double> out);

    /** Configuración y actuadores: se publican en `state` y se guardan en disco. */
    void writeConfig(JsonObject out) {
    }

    /** Aplica las claves presentes (comando set_config y carga desde disco). */
    void readConfig(JsonObject in) {
    }

    void resetConfig() {
    }

    /** Variables internas del modelo: solo se guardan en disco. */
    void writeModel(JsonObject out) {
    }

    void readModel(JsonObject in) {
    }

    boolean actuate(String name, boolean value, StringBuilder detail) {
        detail.append("actuador desconocido: ").append(name);
        return false;
    }

    boolean custom(String action, JsonObject params, StringBuilder detail) {
        detail.append("acción no soportada: ").append(action);
        return false;
    }

    // ---------------------------------------------------------------------------- telemetría
    /** Publica cuando cambia el intervalo de reloj: las muestras quedan alineadas a múltiplos del periodo. */
    final void maybePublish() {
        long slot = System.currentTimeMillis() / (intervalS * 1000L);
        if (slot == lastSlot) {
            return;
        }
        lastSlot = slot;
        if (enabled) {
            publishTelemetry();
        }
    }

    private void publishTelemetry() {
        Map<String, Double> values = new LinkedHashMap<>();
        sample(values);
        JsonObject payload = new JsonObject();
        payload.addProperty("ts", System.currentTimeMillis());
        payload.addProperty("seq", ++seq);
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            double value = entry.getValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                continue;
            }
            double[] adjust = calibration.get(entry.getKey());
            if (adjust != null) {
                value = value * adjust[1] + adjust[0];
            }
            payload.addProperty(entry.getKey(), Math.round(value * 1000.0) / 1000.0);
        }
        plugin.mqtt.publish(topic + "/telemetry", payload.toString(), 1, false);
    }

    final void publishState() {
        JsonObject state = new JsonObject();
        state.addProperty("ts", System.currentTimeMillis());
        state.addProperty("fw", AgroCraftPlugin.FW);
        state.addProperty("enabled", enabled ? 1 : 0);
        state.addProperty("interval_s", intervalS);
        state.add("calibration", calibrationJson());
        writeConfig(state);
        plugin.mqtt.publish(topic + "/state", state.toString(), 1, true);
    }

    final void publishEvent(String type, JsonObject data) {
        JsonObject event = data == null ? new JsonObject() : data;
        event.addProperty("ts", System.currentTimeMillis());
        event.addProperty("type", type);
        plugin.mqtt.publish(topic + "/event", event.toString(), 1, false);
    }

    // ------------------------------------------------------------------------------ comandos
    final void handleCommand(String payload) {
        String cmdId = null;
        String action = null;
        boolean ok = false;
        StringBuilder detail = new StringBuilder();
        try {
            JsonObject command = JsonParser.parseString(payload).getAsJsonObject();
            cmdId = Json.text(command, "cmd_id");
            action = Json.text(command, "action");
            JsonObject params = command.has("params") && command.get("params").isJsonObject()
                    ? command.getAsJsonObject("params") : new JsonObject();
            ok = apply(action, params, detail);
        } catch (RuntimeException error) {
            detail.setLength(0);
            detail.append("comando inválido: ").append(error);
        }
        JsonObject ack = new JsonObject();
        ack.addProperty("ts", System.currentTimeMillis());
        ack.addProperty("cmd_id", cmdId);
        ack.addProperty("action", action);
        ack.addProperty("ok", ok);
        ack.addProperty("detail", detail.toString());
        plugin.mqtt.publish(topic + "/ack", ack.toString(), 1, false);
        plugin.getLogger().info(id + " cmd " + action + " -> ok=" + ok + " " + detail);
        if (ok) {
            publishState();
            plugin.saveState();
        }
    }

    private boolean apply(String action, JsonObject params, StringBuilder detail) {
        if (action == null) {
            detail.append("falta action");
            return false;
        }
        switch (action) {
            case "set_enabled" -> {
                enabled = Json.bool(params.get("enabled"));
                detail.append("enabled=").append(enabled ? 1 : 0);
                return true;
            }
            case "set_interval" -> {
                int interval = params.get("interval_s").getAsInt();
                if (interval < 5 || interval > 3600) {
                    detail.append("interval_s fuera de rango [5, 3600]");
                    return false;
                }
                intervalS = interval;
                detail.append("interval_s=").append(interval);
                return true;
            }
            case "calibrate" -> {
                return calibrate(params, detail);
            }
            case "set_actuator" -> {
                return actuate(params.get("name").getAsString(), Json.bool(params.get("value")), detail);
            }
            case "set_config" -> {
                readConfig(params);
                detail.append("configuración actualizada");
                return true;
            }
            case "reboot" -> {
                enabled = true;
                intervalS = defaultIntervalS;
                calibration.clear();
                resetConfig();
                detail.append("reiniciado con la configuración de fábrica");
                return true;
            }
            default -> {
                return custom(action, params, detail);
            }
        }
    }

    private boolean calibrate(JsonObject params, StringBuilder detail) {
        if (Json.flag(params, "reset", false)) {
            calibration.clear();
            detail.append("calibración borrada");
            return true;
        }
        String variable = params.get("variable").getAsString();
        Map<String, Double> known = new LinkedHashMap<>();
        sample(known);
        if (!known.containsKey(variable)) {
            detail.append("variable desconocida: ").append(variable).append("; válidas: ").append(known.keySet());
            return false;
        }
        double offset = Json.num(params, "offset", 0);
        double gain = Json.num(params, "gain", 1);
        if (offset == 0 && gain == 1) {
            calibration.remove(variable);
        } else {
            calibration.put(variable, new double[] {offset, gain});
        }
        detail.append(variable).append(": offset=").append(offset).append(" gain=").append(gain);
        return true;
    }

    private JsonObject calibrationJson() {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, double[]> entry : calibration.entrySet()) {
            JsonObject adjust = new JsonObject();
            adjust.addProperty("offset", entry.getValue()[0]);
            adjust.addProperty("gain", entry.getValue()[1]);
            out.add(entry.getKey(), adjust);
        }
        return out;
    }

    // -------------------------------------------------------------------------- persistencia
    final JsonObject save() {
        JsonObject out = new JsonObject();
        out.addProperty("enabled", enabled);
        out.addProperty("interval_s", intervalS);
        out.addProperty("seq", seq);
        out.add("calibration", calibrationJson());
        JsonObject config = new JsonObject();
        writeConfig(config);
        out.add("config", config);
        JsonObject model = new JsonObject();
        writeModel(model);
        out.add("model", model);
        return out;
    }

    final void load(JsonObject in) {
        enabled = Json.flag(in, "enabled", true);
        intervalS = (int) Json.clamp(Json.num(in, "interval_s", defaultIntervalS), 5, 3600);
        seq = (long) Json.num(in, "seq", 0);
        if (in.has("calibration") && in.get("calibration").isJsonObject()) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : in.getAsJsonObject("calibration").entrySet()) {
                JsonObject adjust = entry.getValue().getAsJsonObject();
                calibration.put(entry.getKey(),
                        new double[] {Json.num(adjust, "offset", 0), Json.num(adjust, "gain", 1)});
            }
        }
        if (in.has("config") && in.get("config").isJsonObject()) {
            readConfig(in.getAsJsonObject("config"));
        }
        if (in.has("model") && in.get("model").isJsonObject()) {
            readModel(in.getAsJsonObject("model"));
        }
    }
}
