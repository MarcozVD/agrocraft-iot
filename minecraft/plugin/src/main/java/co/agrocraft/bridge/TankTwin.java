package co.agrocraft.bridge;

import java.util.Map;

import org.bukkit.Material;

import com.google.gson.JsonObject;

/**
 * Tanque de riego con bomba. Sensores: nivel (ultrasónico) y caudal (caudalímetro). Actuador: bomba.
 * El nivel se ve en el mundo como la altura de la columna de agua detrás del vidrio.
 */
final class TankTwin extends Twin {
    static final double CAPACITY_L = 2000;
    private static final double PUMP_LPM = 30;
    private static final double RAIN_L_PER_MM = 40;     // litros captados por milímetro de lluvia real

    private double volume = 1400;
    private double drawn;
    private double outflowLpm;
    private double inflowLpm;

    private boolean pump;
    private boolean auto = true;
    private double levelMin = 25;
    private double levelMax = 90;

    private int shownLayers = -1;

    TankTwin(AgroCraftPlugin plugin, Farm farm) {
        super(plugin, farm, "tanque", farm.simulated() ? 5 : 30);
    }

    double volume() {
        return volume;
    }

    /** Las parcelas piden agua; devuelve los litros que realmente había. */
    double draw(double liters) {
        double got = Math.min(liters, volume);
        volume -= got;
        drawn += got;
        return got;
    }

    private double level() {
        return volume / CAPACITY_L * 100;
    }

    @Override
    void step(double dt) {
        if (auto) {
            if (!pump && level() < levelMin) {
                setPump(true, "auto");
            } else if (pump && level() >= levelMax) {
                setPump(false, "auto");
            }
        }
        double in = (pump && !this.in.pumpFailure ? PUMP_LPM * dt / 60.0 : 0)
                + RAIN_L_PER_MM * this.in.rainRateMmH * dt / 3600.0;
        in = Math.min(in, CAPACITY_L - volume);
        volume += in;
        inflowLpm = in / dt * 60;
        outflowLpm = drawn / dt * 60;
        drawn = 0;

        int layers = (int) Math.round(level() / 100.0 * Layout.TANK_LAYERS);
        if (layers != shownLayers) {
            for (int dy = 1; dy <= Layout.TANK_LAYERS; dy++) {
                Material fill = dy <= layers ? Material.WATER : Material.AIR;
                for (int x = Layout.TANK.x1() + 1; x < Layout.TANK.x2(); x++) {
                    for (int z = Layout.TANK.z1() + 1; z < Layout.TANK.z2(); z++) {
                        layout.at(x, dy, z).setType(fill, false);
                    }
                }
            }
            shownLayers = layers;
        }
    }

    private void setPump(boolean on, String reason) {
        if (pump == on) {
            return;
        }
        pump = on;
        JsonObject data = new JsonObject();
        data.addProperty("on", on ? 1 : 0);
        data.addProperty("reason", reason);
        data.addProperty("level_pct", Math.round(level() * 10) / 10.0);
        publishEvent("pump", data);
        publishState();
    }

    @Override
    void sample(Map<String, Double> out) {
        out.put("level_pct", level());
        out.put("volume_l", volume);
        out.put("outflow_lpm", outflowLpm);
        out.put("inflow_lpm", inflowLpm);
        out.put("pump", pump ? 1.0 : 0.0);
    }

    @Override
    boolean actuate(String name, boolean value, StringBuilder detail) {
        if (!name.equals("pump")) {
            return super.actuate(name, value, detail);
        }
        auto = false;
        setPump(value, "comando");
        detail.append("pump=").append(value ? 1 : 0).append(" auto=0");
        return true;
    }

    @Override
    void writeConfig(JsonObject out) {
        out.addProperty("pump", pump ? 1 : 0);
        out.addProperty("auto", auto ? 1 : 0);
        out.addProperty("level_min", levelMin);
        out.addProperty("level_max", levelMax);
    }

    @Override
    void readConfig(JsonObject in) {
        pump = Json.flag(in, "pump", pump);
        auto = Json.flag(in, "auto", auto);
        levelMin = Json.clamp(Json.num(in, "level_min", levelMin), 0, 95);
        levelMax = Json.clamp(Json.num(in, "level_max", levelMax), levelMin + 1, 100);
    }

    @Override
    void resetConfig() {
        pump = false;
        auto = true;
        levelMin = 25;
        levelMax = 90;
    }

    @Override
    void writeModel(JsonObject out) {
        out.addProperty("volume", volume);
    }

    @Override
    void readModel(JsonObject in) {
        volume = Json.clamp(Json.num(in, "volume", volume), 0, CAPACITY_L);
    }
}
