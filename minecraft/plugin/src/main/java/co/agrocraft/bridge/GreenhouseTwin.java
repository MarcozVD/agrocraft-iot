package co.agrocraft.bridge;

import java.util.Map;

import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;

import com.google.gson.JsonObject;

/**
 * Invernadero de vidrio. Sensores: temperatura y humedad (SHT31), luz (BH1750).
 * Actuadores: ventilas del techo, calefacción (fogata) y lámparas.
 *
 * Modelo térmico de primer orden: la temperatura interior persigue a la exterior real más la
 * ganancia solar (radiación real), que las ventilas abiertas disipan.
 */
final class GreenhouseTwin extends Twin {
    private static final int DOOR_X = (Layout.GREENHOUSE.x1() + Layout.GREENHOUSE.x2()) / 2;

    private double temp = 24;
    private double hum = 70;

    private boolean vent;
    private boolean heater;
    private boolean lamp;
    private boolean auto = true;
    private double ventOpenAt = 28;
    private double ventCloseAt = 25;
    private double heatOnAt = 16;
    private double heatOffAt = 18;

    private Boolean shownVent;
    private Boolean shownHeater;
    private Boolean shownLamp;

    GreenhouseTwin(AgroCraftPlugin plugin) {
        super(plugin, "mc-invernadero", 30);
    }

    @Override
    void step(double dt) {
        RealWorld real = plugin.real;
        if (auto) {
            if (!vent && temp > ventOpenAt) {
                set("vent", true, "auto");
            } else if (vent && temp < ventCloseAt) {
                set("vent", false, "auto");
            }
            if (!heater && temp < heatOnAt) {
                set("heater", true, "auto");
            } else if (heater && temp > heatOffAt) {
                set("heater", false, "auto");
            }
        }
        // Sin dato de radiación todavía, se estima con la luz del cielo del propio mundo
        double radiation = real.radiationWm2 >= 0 ? real.radiationWm2 : Math.max(0, (light() - 4) / 11.0 * 600);
        double solarGain = 0.012 * radiation * (vent ? 0.35 : 1.0);
        double target = real.tempC + solarGain + (heater ? 6 : 0) + (lamp ? 1.5 : 0);
        temp += (target - temp) * (1 - Math.exp(-dt / (vent ? 600.0 : 1500.0)));

        double humTarget = Json.clamp(real.humPct + (vent ? 2 : 12) - 2.0 * (temp - real.tempC), 30, 100);
        hum += (humTarget - hum) * (1 - Math.exp(-dt / 1200.0));
        render();
    }

    private int light() {
        return layout.at(DOOR_X, 2, Layout.VENT_Z).getLightLevel();
    }

    private void set(String name, boolean on, String reason) {
        switch (name) {
            case "vent" -> vent = on;
            case "heater" -> heater = on;
            default -> lamp = on;
        }
        JsonObject data = new JsonObject();
        data.addProperty("name", name);
        data.addProperty("on", on ? 1 : 0);
        data.addProperty("reason", reason);
        data.addProperty("temp_c", Math.round(temp * 10) / 10.0);
        publishEvent("actuator", data);
        publishState();
    }

    private void render() {
        if (shownVent == null || shownVent != vent) {
            for (int x = Layout.GREENHOUSE.x1() + 1; x < Layout.GREENHOUSE.x2(); x++) {
                Block trapdoor = layout.at(x, Layout.GREENHOUSE_ROOF, Layout.VENT_Z);
                if (trapdoor.getBlockData() instanceof Openable data) {
                    data.setOpen(vent);
                    trapdoor.setBlockData(data, false);
                }
            }
            shownVent = vent;
        }
        if (shownHeater == null || shownHeater != heater) {
            Block campfire = layout.at(DOOR_X, 1, -12);
            if (campfire.getBlockData() instanceof Lightable data) {
                data.setLit(heater);
                campfire.setBlockData(data, false);
            }
            shownHeater = heater;
        }
        if (shownLamp == null || shownLamp != lamp) {
            layout.lamp(DOOR_X - 2, 1, -12, 0, lamp);
            layout.lamp(DOOR_X + 2, 1, -12, 0, lamp);
            shownLamp = lamp;
        }
    }

    @Override
    void sample(Map<String, Double> out) {
        out.put("temp_c", temp);
        out.put("hum_pct", hum);
        out.put("light_lvl", (double) light());
        out.put("vent_open", vent ? 1.0 : 0.0);
        out.put("heater", heater ? 1.0 : 0.0);
        out.put("lamp", lamp ? 1.0 : 0.0);
    }

    @Override
    boolean actuate(String name, boolean value, StringBuilder detail) {
        if (!name.equals("vent") && !name.equals("heater") && !name.equals("lamp")) {
            return super.actuate(name, value, detail);
        }
        if (!name.equals("lamp")) {
            auto = false;       // ventilas y calefacción manuales sacan el clima de automático
        }
        set(name, value, "comando");
        detail.append(name).append('=').append(value ? 1 : 0).append(name.equals("lamp") ? "" : " auto=0");
        return true;
    }

    @Override
    void writeConfig(JsonObject out) {
        out.addProperty("vent", vent ? 1 : 0);
        out.addProperty("heater", heater ? 1 : 0);
        out.addProperty("lamp", lamp ? 1 : 0);
        out.addProperty("auto", auto ? 1 : 0);
        out.addProperty("vent_open_at", ventOpenAt);
        out.addProperty("vent_close_at", ventCloseAt);
        out.addProperty("heat_on_at", heatOnAt);
        out.addProperty("heat_off_at", heatOffAt);
    }

    @Override
    void readConfig(JsonObject in) {
        vent = Json.flag(in, "vent", vent);
        heater = Json.flag(in, "heater", heater);
        lamp = Json.flag(in, "lamp", lamp);
        auto = Json.flag(in, "auto", auto);
        ventOpenAt = Json.clamp(Json.num(in, "vent_open_at", ventOpenAt), 10, 45);
        ventCloseAt = Json.clamp(Json.num(in, "vent_close_at", ventCloseAt), 5, ventOpenAt - 0.5);
        heatOffAt = Json.clamp(Json.num(in, "heat_off_at", heatOffAt), 5, ventCloseAt);
        heatOnAt = Json.clamp(Json.num(in, "heat_on_at", heatOnAt), 0, heatOffAt - 0.5);
    }

    @Override
    void resetConfig() {
        vent = false;
        heater = false;
        lamp = false;
        auto = true;
        ventOpenAt = 28;
        ventCloseAt = 25;
        heatOnAt = 16;
        heatOffAt = 18;
    }

    @Override
    void writeModel(JsonObject out) {
        out.addProperty("temp", temp);
        out.addProperty("hum", hum);
    }

    @Override
    void readModel(JsonObject in) {
        temp = Json.num(in, "temp", temp);
        hum = Json.num(in, "hum", hum);
    }
}
