package co.agrocraft.bridge;

import java.util.Map;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Farmland;

import com.google.gson.JsonObject;

/**
 * Parcela de cultivo. Sensores: humedad del suelo (sonda capacitiva), etapa del cultivo y luz (BH1750).
 * Actuadores: riego (canal de agua) y lámpara de crecimiento.
 *
 * Modelo: el suelo se seca según la evapotranspiración real y se moja con riego o lluvia real; el
 * cultivo crece según humedad, luz y temperatura, y al madurar se cosecha hacia el silo y se resiembra.
 */
final class PlotTwin extends Twin {
    static final double FLOW_LPM = 20;                  // caudal que toma del tanque mientras riega
    private static final double WETTING_PCT_H = 120;    // lo que sube la humedad con el riego abierto
    private static final double FULL_CYCLE_H = 20;      // horas de siembra a cosecha en condiciones ideales
    private static final int MAX_AGE = 7;

    private final Layout.Box box;
    private final Material[] crops;
    private final Material produce;
    private final int lampX;
    private final double dryFactor;
    private final double growFactor;
    private final TankTwin tank;
    private final SiloTwin silo;

    private double soil = 55;
    private double growth = 0;
    private long harvests = 0;
    private int mature = 0;

    private boolean irrigation;
    private boolean lamp;
    private boolean auto = true;
    private double soilMin = 35;
    private double soilMax = 65;

    private int shownMoisture = -1;
    private int shownStage = -1;
    private Boolean shownIrrigation;
    private Boolean shownLamp;

    PlotTwin(AgroCraftPlugin plugin, Farm farm, String id, Layout.Box box, Material[] crops, Material produce, int lampX,
             double dryFactor, double growFactor, TankTwin tank, SiloTwin silo) {
        super(plugin, farm, id, farm.simulated() ? 5 : 30);
        this.box = box;
        this.crops = crops;
        this.produce = produce;
        this.lampX = lampX;
        this.dryFactor = dryFactor;
        this.growFactor = growFactor;
        this.tank = tank;
        this.silo = silo;
    }

    @Override
    void step(double dt) {
        RealWorld real = in;
        double hours = dt / 3600.0;

        if (auto) {     // control local con histéresis; los umbrales se ajustan por comando
            if (!irrigation && soil < soilMin && tank.volume() > 50) {
                setIrrigation(true, "auto");
            } else if (irrigation && soil >= soilMax) {
                setIrrigation(false, "auto");
            }
        }
        if (irrigation) {
            double wanted = FLOW_LPM * dt / 60.0;
            double got = tank.draw(wanted);
            soil += WETTING_PCT_H * hours * (got / wanted);
            if (got <= 0) {
                setIrrigation(false, "tanque vacío");
            }
        }
        soil -= (0.5 + 12 * real.et0RateMmH * dryFactor) * hours;
        soil += 8 * real.rainRateMmH * hours;
        soil = Json.clamp(soil, 0, 100);

        double soilFactor = soil < 20 ? 0.15 : soil < 35 ? 0.6 : soil > 90 ? 0.7 : 1.0;
        double lightFactor = Math.max(0.1, light() / 15.0);
        double t = real.tempC;
        double tempFactor = t < 12 ? 0.3 : t < 18 ? 0.7 : t > 34 ? 0.4 : t > 30 ? 0.8 : 1.0;
        growth += 100.0 / FULL_CYCLE_H * growFactor * soilFactor * lightFactor * tempFactor * hours;
        if (growth >= 100) {
            harvest();
        }
        render();
    }

    private int light() {
        return in.light(layout.at(box.x1() + 2, 1, box.z1() + 2));
    }

    private void setIrrigation(boolean on, String reason) {
        if (irrigation == on) {
            return;
        }
        irrigation = on;
        JsonObject data = new JsonObject();
        data.addProperty("on", on ? 1 : 0);
        data.addProperty("reason", reason);
        data.addProperty("soil_pct", Math.round(soil * 10) / 10.0);
        publishEvent("irrigation", data);
        publishState();
    }

    private void harvest() {
        int cells = (box.x2() - box.x1() + 1) * (box.z2() - box.z1());     // sin la fila del canal
        int yield = cells * 4;
        int stored = silo.deposit(produce, yield);
        harvests++;
        growth = 0;
        JsonObject data = new JsonObject();
        data.addProperty("yield", yield);
        data.addProperty("stored", stored);
        data.addProperty("harvests", harvests);
        publishEvent("harvest", data);
    }

    /** Dibuja el estado en los bloques; solo toca el mundo cuando cambia un valor visible. */
    private void render() {
        int moisture = (int) Math.round(soil / 100.0 * 7);
        int stage = (int) Math.min(MAX_AGE, Math.floor(growth / 100.0 * (MAX_AGE + 1)));
        if (moisture != shownMoisture || stage != shownStage) {
            int count = 0;
            for (int x = box.x1(); x <= box.x2(); x++) {
                for (int z = box.z1(); z <= box.z2(); z++) {
                    if (z == Layout.CHANNEL_Z) {
                        continue;
                    }
                    Block ground = layout.at(x, 0, z);
                    if (ground.getType() != Material.FARMLAND) {
                        ground.setType(Material.FARMLAND, false);
                    }
                    Farmland farmland = (Farmland) ground.getBlockData();
                    farmland.setMoisture(moisture);
                    ground.setBlockData(farmland, false);

                    Material crop = crops[(x - box.x1()) % crops.length];
                    Block plant = layout.at(x, 1, z);
                    if (plant.getType() != crop) {
                        plant.setType(crop, false);
                    }
                    Ageable ageable = (Ageable) plant.getBlockData();
                    int age = Math.max(0, Math.min(ageable.getMaximumAge(), stage + unevenness(x, z, stage)));
                    ageable.setAge(age);
                    plant.setBlockData(ageable, false);
                    if (age >= ageable.getMaximumAge()) {
                        count++;
                    }
                }
            }
            mature = count;
            shownMoisture = moisture;
            shownStage = stage;
        }
        if (shownIrrigation == null || shownIrrigation != irrigation) {
            for (int x = box.x1(); x <= box.x2(); x++) {
                layout.at(x, 0, Layout.CHANNEL_Z).setType(irrigation ? Material.WATER : Material.AIR, false);
            }
            shownIrrigation = irrigation;
        }
        if (shownLamp == null || shownLamp != lamp) {
            layout.lamp(lampX, 4, Layout.CHANNEL_Z, 5, lamp);
            shownLamp = lamp;
        }
    }

    /** Las plantas no crecen todas igual: una de cada cuatro va una etapa atrás y otra una adelante. */
    private static int unevenness(int x, int z, int stage) {
        if (stage == 0) {
            return 0;
        }
        int hash = (x * 31 + z * 17) & 3;
        return hash == 0 ? -1 : hash == 3 ? 1 : 0;
    }

    @Override
    void sample(Map<String, Double> out) {
        out.put("soil_pct", soil);
        out.put("growth_pct", Math.min(100, growth));
        out.put("mature_count", (double) mature);
        out.put("light_lvl", (double) light());
        out.put("irrigation", irrigation ? 1.0 : 0.0);
        out.put("lamp", lamp ? 1.0 : 0.0);
        out.put("harvests", (double) harvests);
    }

    @Override
    boolean actuate(String name, boolean value, StringBuilder detail) {
        switch (name) {
            case "irrigation" -> {
                auto = false;       // un comando manual saca el riego de automático
                setIrrigation(value, "comando");
                detail.append("irrigation=").append(value ? 1 : 0).append(" auto=0");
                return true;
            }
            case "lamp" -> {
                lamp = value;
                detail.append("lamp=").append(value ? 1 : 0);
                return true;
            }
            default -> {
                return super.actuate(name, value, detail);
            }
        }
    }

    @Override
    void writeConfig(JsonObject out) {
        out.addProperty("irrigation", irrigation ? 1 : 0);
        out.addProperty("lamp", lamp ? 1 : 0);
        out.addProperty("auto", auto ? 1 : 0);
        out.addProperty("soil_min", soilMin);
        out.addProperty("soil_max", soilMax);
    }

    @Override
    void readConfig(JsonObject in) {
        irrigation = Json.flag(in, "irrigation", irrigation);
        lamp = Json.flag(in, "lamp", lamp);
        auto = Json.flag(in, "auto", auto);
        soilMin = Json.clamp(Json.num(in, "soil_min", soilMin), 0, 95);
        soilMax = Json.clamp(Json.num(in, "soil_max", soilMax), soilMin + 1, 100);
    }

    @Override
    void resetConfig() {
        irrigation = false;
        lamp = false;
        auto = true;
        soilMin = 35;
        soilMax = 65;
    }

    @Override
    void writeModel(JsonObject out) {
        out.addProperty("soil", soil);
        out.addProperty("growth", growth);
        out.addProperty("harvests", harvests);
    }

    @Override
    void readModel(JsonObject in) {
        soil = Json.clamp(Json.num(in, "soil", soil), 0, 100);
        growth = Json.clamp(Json.num(in, "growth", growth), 0, 100);
        harvests = (long) Json.num(in, "harvests", harvests);
    }
}
