package co.agrocraft.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Lectura tolerante de JSON: los comandos pueden traer 1/0, true/false o "on"/"off". */
final class Json {
    private Json() {
    }

    static double num(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean() ? 1 : 0;
        }
        try {
            return primitive.getAsDouble();
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    static boolean flag(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        return element == null ? fallback : bool(element);
    }

    static boolean bool(JsonElement element) {
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble() != 0;
        }
        String text = primitive.getAsString().trim().toLowerCase();
        return text.equals("1") || text.equals("true") || text.equals("on") || text.equals("si") || text.equals("yes");
    }

    static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
