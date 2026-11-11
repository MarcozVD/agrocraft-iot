package co.agrocraft.bridge;

import java.util.List;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Entradas sintéticas de la granja de simulación: un día típico de Bucaramanga con reloj propio
 * acelerado, modificado por un escenario. No toca el clima ni la hora del mundo (son compartidos
 * con la granja real): la lluvia se dibuja con partículas solo sobre la granja simulada.
 *
 * Escenario "actual": parte del clima real del momento de inicio (desfase de temperatura y humedad
 * respecto al día típico) y avanza rápido: "la granja de ahora, pero acelerada".
 *
 * Día típico (calibrado con lo observado por api-clima y api-suelo el 11 nov 2026):
 *   temperatura 17-28 °C (máx. 15:00), HR 95-55 %, radiación hasta 950 W/m², ET0 hasta ~0.6 mm/h,
 *   chubasco corto a las 16:00.
 */
final class SimWorld extends RealWorld {
    static final List<String> SCENARIOS = List.of(
            "actual", "normal", "ola_calor", "sequia", "tormenta", "helada", "falla_bomba", "intrusion");
    static final double MAX_SPEED = 360;

    boolean running;
    String scenario = "actual";
    private double offsetT;     // escenario "actual": desfase del clima real frente al día típico
    private double offsetRh;
    double speed = 60;
    double durationH = 24;
    double elapsedH;        // horas simuladas desde el inicio
    double clockH = 6;      // hora del día simulada (0-24)

    /** Avanza el reloj simulado y devuelve cuántos segundos de modelo corresponden a dtReal. */
    double advance(double dtReal) {
        if (!running) {
            return 0;
        }
        double dt = dtReal * speed;
        elapsedH += dt / 3600.0;
        clockH = (clockH + dt / 3600.0) % 24;
        compute();
        if (elapsedH >= durationH) {
            running = false;
        }
        return dt;
    }

    void start(String scenario, double speed, double hours, double startHour, RealWorld real) {
        this.scenario = scenario;
        this.speed = Json.clamp(speed, 1, MAX_SPEED);
        this.durationH = Json.clamp(hours, 0.5, 240);
        this.clockH = ((startHour % 24) + 24) % 24;
        this.elapsedH = 0;
        this.running = true;
        offsetT = 0;
        offsetRh = 0;
        if (scenario.equals("actual") && real.updatedMs > 0) {
            double diurnal = Math.sin(2 * Math.PI * (clockH - 9) / 24);
            offsetT = Json.clamp(real.tempC - (22.5 + 5.5 * diurnal), -12, 12);
            offsetRh = Json.clamp(real.humPct - (75 - 20 * diurnal), -40, 40);
        }
        compute();
    }

    int scenarioCode() {
        return Math.max(0, SCENARIOS.indexOf(scenario));
    }

    /** Calcula las entradas del modelo para la hora simulada y el escenario. */
    void compute() {
        double h = clockH;
        double diurnal = Math.sin(2 * Math.PI * (h - 9) / 24);            // +1 a las 15:00, -1 a las 03:00
        double sun = Math.max(0, Math.sin(Math.PI * (h - 6) / 12));       // 0 de noche, 1 al mediodía
        double t = 22.5 + 5.5 * diurnal;
        double rh = 75 - 20 * diurnal;
        double rad = 950 * sun * 0.85;
        double rain = h >= 16 && h < 16.75 ? 2.0 : 0;
        int code = rain > 0 ? 61 : (sun > 0 ? 2 : 1);
        pumpFailure = false;
        intrusionBoost = 1;

        switch (scenario) {
            case "actual" -> {          // el día parte del clima real de este momento y sigue la forma típica
                t += offsetT;
                rh += offsetRh;
            }
            case "ola_calor" -> {
                t += 7;
                rh -= 20;
                rad = 950 * sun;
                rain = 0;
                code = sun > 0 ? 0 : 1;
            }
            case "sequia" -> {
                t += 2;
                rh -= 15;
                rad = 950 * sun;
                rain = 0;
                code = 0;
            }
            case "tormenta" -> {
                rain = elapsedH < 1 ? 18 : elapsedH < 3 ? 6 : elapsedH < 6 ? 1.5 : rain;
                if (elapsedH < 6) {
                    rad *= 0.15;
                    t -= 4;
                    rh = 97;
                    code = elapsedH < 3 ? 95 : 63;
                }
            }
            case "helada" -> {
                t = 6 + 4 * diurnal;
                rad *= 0.6;
                rh = 85 - 10 * diurnal;
            }
            case "falla_bomba" -> pumpFailure = true;
            case "intrusion" -> intrusionBoost = 40;
            default -> {
            }
        }
        tempC = t;
        humPct = Json.clamp(rh, 15, 100);
        radiationWm2 = Math.max(0, rad);
        rainRateMmH = rain;
        weatherCode = code;
        // ET0 aproximada (mm/h): proporcional a la radiación, crece con calor y aire seco
        double et0 = 0.00065 * radiationWm2 * (1 + (tempC - 20) / 30) * (1 + (60 - humPct) / 150);
        et0RateMmH = Math.max(0, scenario.equals("sequia") ? et0 * 2.2 : et0);
        updatedMs = System.currentTimeMillis();
    }

    @Override
    int light(Block block) {
        int sky = (int) Math.round(Json.clamp(radiationWm2 / 950.0 * 15, 0, 15));
        return Math.max(sky, block.getLightFromBlocks());        // las lámparas del gemelo también cuentan
    }

    @Override
    boolean night(World world) {
        return clockH < 6 || clockH >= 18.5;
    }
}
