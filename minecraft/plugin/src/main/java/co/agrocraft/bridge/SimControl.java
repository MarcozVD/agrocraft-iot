package co.agrocraft.bridge;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;

import com.google.gson.JsonObject;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * sim-control: el dispositivo que maneja la granja de simulación.
 *
 * Comandos (además de los comunes):
 *   start     {"scenario": "actual|normal|ola_calor|sequia|tormenta|helada|falla_bomba|intrusion",
 *              "speed": 1-360, "hours": horas simuladas, "from_real": true, "start_hour": 0-24}
 *             from_real (por defecto true) copia antes el estado actual de la granja real.
 *   stop      detiene el reloj simulado (la granja queda congelada como está).
 *   reset     copia otra vez el estado de la granja real.
 *   set_speed {"speed": 1-360} cambia la velocidad sin reiniciar.
 *
 * Telemetría: el estado del motor y las entradas sintéticas que está recibiendo el modelo.
 */
final class SimControl extends Twin {
    private final Farm real;
    private final SimWorld sim;
    private final ZoneId zone;
    private double rainTimer;
    private boolean wasRunning;

    SimControl(AgroCraftPlugin plugin, Farm simFarm, Farm realFarm, ZoneId zone) {
        super(plugin, simFarm, "control", 5);
        this.real = realFarm;
        this.sim = (SimWorld) simFarm.inputs;
        this.zone = zone;
    }

    @Override
    void step(double dt) {
        // dt aquí es tiempo real: dibuja la lluvia simulada solo sobre la granja simulada
        if (sim.running && sim.rainRateMmH > 0.3) {
            rainTimer += dt;
            if (rainTimer >= 0.5) {
                rainTimer = 0;
                Location center = layout.at(Layout.FARM.cx(), 12, Layout.FARM.cz()).getLocation();
                int drops = (int) Math.min(600, 40 + sim.rainRateMmH * 25);
                layout.world.spawnParticle(Particle.FALLING_WATER, center, drops * 3, 50, 4, 40, 0);
            }
        }
        if (wasRunning && !sim.running) {           // terminó por duración
            JsonObject data = new JsonObject();
            data.addProperty("scenario", sim.scenario);
            data.addProperty("sim_hours", round(sim.elapsedH));
            publishEvent("sim_end", data);
            announce("Simulación terminada: " + sim.scenario + " (" + round(sim.elapsedH) + " h simuladas)");
            publishState();
        }
        wasRunning = sim.running;
    }

    @Override
    void sample(Map<String, Double> out) {
        out.put("running", sim.running ? 1.0 : 0.0);
        out.put("speed", sim.speed);
        out.put("scenario_code", (double) sim.scenarioCode());
        out.put("sim_hours", sim.elapsedH);
        out.put("sim_clock_h", sim.clockH);
        out.put("in_temp_c", sim.tempC);
        out.put("in_hum_pct", sim.humPct);
        out.put("in_rain_mmh", sim.rainRateMmH);
        out.put("in_radiation_wm2", sim.radiationWm2);
        out.put("in_et0_mmh", sim.et0RateMmH);
        out.put("pump_failure", sim.pumpFailure ? 1.0 : 0.0);
    }

    private void copyReal() {
        for (Twin twin : farm.twins.values()) {
            if (twin == this) {
                continue;
            }
            Twin source = real.twins.get(real.prefix + twin.id.substring(farm.prefix.length()));
            if (source != null) {
                twin.copyFrom(source);
                if (twin instanceof CorralTwin corral && source instanceof CorralTwin realCorral) {
                    corral.matchHerd(realCorral.adults(), realCorral.babies());
                }
                twin.publishState();
            }
        }
    }

    @Override
    boolean custom(String action, JsonObject params, StringBuilder detail) {
        switch (action) {
            case "start" -> {
                String scenario = params.has("scenario") ? params.get("scenario").getAsString() : "actual";
                if (!SimWorld.SCENARIOS.contains(scenario)) {
                    detail.append("escenario desconocido; válidos: ").append(SimWorld.SCENARIOS);
                    return false;
                }
                if (Json.flag(params, "from_real", true)) {
                    copyReal();
                }
                double startHour = Json.num(params, "start_hour", LocalTime.now(zone).toSecondOfDay() / 3600.0);
                sim.start(scenario, Json.num(params, "speed", 60), Json.num(params, "hours", 24), startHour, real.inputs);
                detail.append("escenario=").append(scenario).append(" x").append(round(sim.speed))
                        .append(" durante ").append(round(sim.durationH)).append(" h simuladas desde las ")
                        .append(round(sim.clockH)).append(" h");
                announce("Simulación iniciada: " + detail);
                return true;
            }
            case "stop" -> {
                sim.running = false;
                wasRunning = false;
                detail.append("detenida en ").append(round(sim.elapsedH)).append(" h simuladas");
                announce("Simulación detenida");
                return true;
            }
            case "reset" -> {
                sim.running = false;
                wasRunning = false;
                copyReal();
                detail.append("granja simulada igualada a la real");
                return true;
            }
            case "set_speed" -> {
                sim.speed = Json.clamp(Json.num(params, "speed", sim.speed), 1, SimWorld.MAX_SPEED);
                detail.append("speed=").append(round(sim.speed));
                return true;
            }
            default -> {
                return super.custom(action, params, detail);
            }
        }
    }

    @Override
    void writeConfig(JsonObject out) {
        out.addProperty("running", sim.running ? 1 : 0);
        out.addProperty("scenario", sim.scenario);
        out.addProperty("speed", sim.speed);
        out.addProperty("hours", sim.durationH);
        out.addProperty("scenarios", String.join(",", SimWorld.SCENARIOS));
    }

    private static void announce(String text) {
        Bukkit.broadcast(Component.text("[Simulación] " + text, NamedTextColor.LIGHT_PURPLE));
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
