package co.agrocraft.bridge;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import org.bukkit.World;

import com.google.gson.JsonObject;

/**
 * Estación meteorológica del mundo. Sensores: lluvia, tormenta, luz del cielo y hora.
 * Además es el punto donde el mundo real entra al gemelo: mientras la sincronización está activa,
 * en el juego llueve cuando llueve en Bucaramanga y es de noche cuando allá es de noche.
 */
final class StationTwin extends Twin {
    private final ZoneId zone;

    private boolean syncWeather = true;
    private boolean syncTime = true;
    private boolean forcedRain;
    private boolean forcedThunder;

    StationTwin(AgroCraftPlugin plugin, Farm farm, ZoneId zone) {
        super(plugin, farm, "estacion", 60);
        this.zone = zone;
    }

    @Override
    void step(double dt) {
        World world = layout.world;
        RealWorld real = in;
        if (syncTime) {
            double hours = LocalTime.now(zone).toSecondOfDay() / 3600.0;
            world.setTime(Math.floorMod(Math.round((hours - 6) * 1000), 24000L));   // tick 0 = 06:00
        }
        if (syncWeather) {
            if (real.updatedMs > 0) {
                boolean rain = real.rainRateMmH >= 0.2;
                weather(rain, rain && real.weatherCode >= 95);
            }
        } else {
            weather(forcedRain, forcedThunder);
        }
    }

    /** Fija el clima y renueva su duración para que el ciclo propio del juego no lo cambie. */
    private void weather(boolean rain, boolean thunder) {
        World world = layout.world;
        if (world.hasStorm() != rain) {
            world.setStorm(rain);
        }
        if (world.isThundering() != thunder) {
            world.setThundering(thunder);
        }
        world.setWeatherDuration(12000);
        world.setThunderDuration(12000);
    }

    @Override
    void sample(Map<String, Double> out) {
        World world = layout.world;
        out.put("raining", world.hasStorm() ? 1.0 : 0.0);
        out.put("thundering", world.isThundering() ? 1.0 : 0.0);
        out.put("daylight_lvl", (double) layout.at(0, 5, 0).getLightLevel());
        out.put("time_ticks", (double) world.getTime());
    }

    @Override
    boolean custom(String action, JsonObject params, StringBuilder detail) {
        switch (action) {
            case "set_weather" -> {
                String weather = params.get("weather").getAsString();
                if (!weather.equals("clear") && !weather.equals("rain") && !weather.equals("thunder")) {
                    detail.append("weather debe ser clear, rain o thunder");
                    return false;
                }
                syncWeather = false;
                forcedRain = !weather.equals("clear");
                forcedThunder = weather.equals("thunder");
                detail.append("weather=").append(weather).append(" sync_weather=0");
                return true;
            }
            case "set_time" -> {
                long ticks = Math.floorMod(params.get("ticks").getAsLong(), 24000L);
                syncTime = false;
                layout.world.setTime(ticks);
                detail.append("time_ticks=").append(ticks).append(" sync_time=0");
                return true;
            }
            default -> {
                return super.custom(action, params, detail);
            }
        }
    }

    @Override
    void writeConfig(JsonObject out) {
        out.addProperty("sync_weather", syncWeather ? 1 : 0);
        out.addProperty("sync_time", syncTime ? 1 : 0);
        out.addProperty("forced_rain", forcedRain ? 1 : 0);
        out.addProperty("forced_thunder", forcedThunder ? 1 : 0);
    }

    @Override
    void readConfig(JsonObject in) {
        syncWeather = Json.flag(in, "sync_weather", syncWeather);
        syncTime = Json.flag(in, "sync_time", syncTime);
        forcedRain = Json.flag(in, "forced_rain", forcedRain);
        forcedThunder = Json.flag(in, "forced_thunder", forcedThunder);
    }

    @Override
    void resetConfig() {
        syncWeather = true;
        syncTime = true;
        forcedRain = false;
        forcedThunder = false;
    }
}
