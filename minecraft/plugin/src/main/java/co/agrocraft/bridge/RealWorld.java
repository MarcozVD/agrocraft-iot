package co.agrocraft.bridge;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Condiciones del mundo real, leídas de la telemetría de los dispositivos de API.
 * Son la entrada del modelo: el gemelo llueve, se seca y se calienta según Bucaramanga.
 */
final class RealWorld {
    double tempC = 24;
    double humPct = 70;
    double rainRateMmH = 0;
    double radiationWm2 = -1;      // -1: todavía sin dato
    double et0RateMmH = 0.1;
    int weatherCode = 0;
    long updatedMs = 0;
    /** Última telemetría de cada dispositivo de API y real (para las pantallas del mundo). */
    final Map<String, JsonObject> latest = new HashMap<>();

    void update(String device, String payload) {
        JsonObject data;
        try {
            data = JsonParser.parseString(payload).getAsJsonObject();
        } catch (RuntimeException error) {
            return;
        }
        latest.put(device, data);
        switch (device) {
            case "api-clima" -> {
                tempC = Json.num(data, "temp_c", tempC);
                humPct = Json.num(data, "hum_pct", humPct);
                // Open-Meteo entrega acumulados del intervalo de 15 min: se pasan a tasa por hora
                rainRateMmH = Math.max(0, Json.num(data, "precip_mm", 0) * 4);
                radiationWm2 = Json.num(data, "radiation_wm2", radiationWm2);
                weatherCode = (int) Json.num(data, "weather_code", weatherCode);
                updatedMs = System.currentTimeMillis();
            }
            case "api-suelo" -> et0RateMmH = Math.max(0, Json.num(data, "et0_mm", 0) * 4);
            default -> {
            }
        }
    }
}
