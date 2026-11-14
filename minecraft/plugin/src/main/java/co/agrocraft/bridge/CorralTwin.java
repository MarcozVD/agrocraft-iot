package co.agrocraft.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Cow;
import org.bukkit.inventory.ItemStack;

import com.google.gson.JsonObject;

/**
 * Corral automático de vacas: ganadería de precisión dentro del gemelo.
 *
 * Sensores: rebaño (adultas y terneros, como un lector RFID), nivel del comedero (compostador),
 * nivel del bebedero (caldero), índice temperatura-humedad THI (con el clima real o el escenario),
 * bienestar del rebaño y producción de la planta de proceso (carne asada en la cámara).
 * Actuadores: comedero (trigo del silo), bebedero (agua del tanque), aspersores (enfrían, gastan agua)
 * y proceso automático.
 *
 * Cadena: parcela A (trigo) -> silo -> comedero -> vacas -> planta de proceso -> carne asada.
 */
final class CorralTwin extends Twin {
    static final Layout.Box CORRAL = Layout.CORRAL;
    static final int[] FEEDER = {CORRAL.x1() + 2, CORRAL.z1() + 2};
    static final int[] TROUGH = {CORRAL.x1() + 4, CORRAL.z1() + 2};
    static final int[] SMOKER = {Layout.PLANT.x1() + 2, Layout.PLANT.z1() + 1};
    static final int[] COLD_ROOM = {Layout.PLANT.x1() + 4, Layout.PLANT.z1() + 1};

    private static final double FEED_CAPACITY = 120;       // unidades de trigo en el comedero
    private static final double WATER_CAPACITY_L = 200;
    private static final double SPRINKLER_LPM = 8;
    private static final int HERD_MAX = 12;
    private static final double BABY_TO_ADULT_H = 12;
    private static final double HOURS_PER_BIRTH_PAIR = 10;
    private static final double PROCESS_EVERY_H = 2;
    private static final int BEEF_PER_COW = 3;

    private final TankTwin tank;
    private final SiloTwin silo;
    private final String tag;

    private double feed = 60;            // %
    private double water = 80;           // %
    private double welfare = 80;         // %
    private double thi;
    private long births;
    private long deaths;
    private long processed;
    private long beefTotal;
    private double sinceProcessH;
    private boolean feederEmpty;
    private double feederRetryH;
    private boolean seeded;
    private final Map<UUID, Double> babyAgeH = new HashMap<>();

    private boolean autoFeed = true;
    private boolean autoWater = true;
    private boolean cooling;
    private boolean autoCooling = true;
    private boolean autoProcess = true;
    private int targetHerd = 8;

    private int adults;
    private int babies;
    private double smokeTimer;
    private double sprinkleTimer;
    private double smokerLitFor;
    private int shownFeedLevel = -1;
    private int shownWaterLevel = -1;

    CorralTwin(AgroCraftPlugin plugin, Farm farm, TankTwin tank, SiloTwin silo) {
        super(plugin, farm, "corral", farm.simulated() ? 5 : 30);
        this.tank = tank;
        this.silo = silo;
        this.tag = "agro-" + farm.prefix + "vaca";
    }

    // ------------------------------------------------------------------------------ rebaño
    private List<Cow> herd() {
        List<Cow> out = new ArrayList<>();
        for (Cow cow : layout.world.getEntitiesByClass(Cow.class)) {
            if (cow.getScoreboardTags().contains(tag) && !cow.isDead()) {
                Location at = cow.getLocation();
                if (CORRAL.has(at.getBlockX() - layout.ox, at.getBlockZ() - layout.oz)) {
                    out.add(cow);
                } else {                                    // se escapó: vuelve al centro del corral
                    cow.teleport(center().add(0, 0.2, 0));
                    out.add(cow);
                }
            }
        }
        return out;
    }

    private Location center() {
        return layout.at((CORRAL.x1() + CORRAL.x2()) / 2, 1, (CORRAL.z1() + CORRAL.z2()) / 2).getLocation().add(0.5, 0, 0.5);
    }

    private Cow spawn(boolean baby) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = layout.at(random.nextInt(CORRAL.x1() + 1, CORRAL.x2()), 1,
                random.nextInt(CORRAL.z1() + 3, CORRAL.z2())).getLocation().add(0.5, 0, 0.5);
        Cow cow = layout.world.spawn(at, Cow.class, c -> {
            c.addScoreboardTag(tag);
            c.setRemoveWhenFarAway(false);
            c.setPersistent(true);
            if (baby) {
                c.setBaby();
            } else {
                c.setAdult();
            }
            c.setAgeLock(true);              // el modelo decide cuándo crece
        });
        if (baby) {
            babyAgeH.put(cow.getUniqueId(), 0.0);
        }
        return cow;
    }

    int adults() {
        return adults;
    }

    int babies() {
        return babies;
    }

    /** Iguala el rebaño físico de este corral al de otro (la simulación parte del rebaño real). */
    void matchHerd(int wantAdults, int wantBabies) {
        List<Cow> herd = herd();
        babyAgeH.clear();
        int haveAdults = 0;
        int haveBabies = 0;
        for (Cow cow : herd) {
            boolean keep = cow.isAdult() ? haveAdults++ < wantAdults : haveBabies++ < wantBabies;
            if (!keep) {
                cow.remove();
            } else if (!cow.isAdult()) {
                babyAgeH.put(cow.getUniqueId(), 0.0);
            }
        }
        for (int i = haveAdults; i < wantAdults; i++) {
            spawn(false);
        }
        for (int i = haveBabies; i < wantBabies; i++) {
            spawn(true);
        }
        adults = wantAdults;
        babies = wantBabies;
        seeded = true;
    }

    /** Índice temperatura-humedad para bovinos (NRC 1971). < 72 confort, 72-78 alerta, 78-89 peligro. */
    static double thi(double tempC, double humPct) {
        return (1.8 * tempC + 32) - (0.55 - 0.0055 * humPct) * (1.8 * tempC - 26);
    }

    // ------------------------------------------------------------------------------- modelo
    @Override
    void step(double dt) {
        double hours = dt / 3600.0;
        List<Cow> herd = herd();
        if (!seeded) {
            if (herd.isEmpty()) {
                for (int i = 0; i < 4; i++) {
                    herd.add(spawn(false));
                }
            }
            seeded = true;
        }
        adults = 0;
        babies = 0;
        for (Cow cow : herd) {
            if (cow.isAdult()) {
                adults++;
            } else {
                babies++;
            }
        }

        thi = thi(in.tempC, in.humPct) - (cooling ? 4 : 0);

        // consumo
        double eat = (adults * 1.5 + babies * 0.7) * hours;
        double drinkL = (adults * 4 + babies * 1.5) * (thi > 72 ? 1.5 : 1.0) * hours;
        feed = Math.max(0, feed - eat / FEED_CAPACITY * 100);
        water = Math.max(0, water - drinkL / WATER_CAPACITY_L * 100);

        // control local
        feederRetryH = Math.max(0, feederRetryH - hours);
        if (autoFeed && feed < 30 && feederRetryH <= 0 && refill("auto") == 0) {
            feederRetryH = 1;                        // silo sin alimento: reintenta en 1 h del modelo
        }
        if (autoWater && water < 40) {
            double wanted = (100 - water) / 100 * WATER_CAPACITY_L;
            water += tank.draw(wanted) / WATER_CAPACITY_L * 100;
        }
        if (autoCooling && !cooling && thi > 75 && !herd.isEmpty()) {
            setCooling(true, "auto");
        } else if (autoCooling && cooling && thi + 4 < 72) {
            setCooling(false, "auto");
        }
        if (cooling) {
            double wantedL = SPRINKLER_LPM * dt / 60.0;
            if (tank.draw(wantedL) < wantedL * 0.5) {
                setCooling(false, "tanque sin agua");
            }
        }

        // bienestar
        double rate = thi < 72 ? 6 : thi < 78 ? -2 : thi < 89 ? -10 : -25;
        if (feed <= 0) {
            rate -= 15;
        }
        if (water <= 0) {
            rate -= 25;
        }
        welfare = Json.clamp(welfare + rate * hours, 0, 100);

        ThreadLocalRandom random = ThreadLocalRandom.current();
        // muerte por bienestar crítico (una cada ~6 h con bienestar < 10)
        if (welfare < 10 && !herd.isEmpty() && random.nextDouble() < hours / 6) {
            Cow victim = herd.remove(random.nextInt(herd.size()));
            babyAgeH.remove(victim.getUniqueId());
            victim.remove();
            deaths++;
            JsonObject data = new JsonObject();
            data.addProperty("welfare_pct", Math.round(welfare));
            data.addProperty("thi", Math.round(thi * 10) / 10.0);
            publishEvent("death", data);
        }

        // reproducción
        int pairs = adults / 2;
        if (pairs > 0 && herd.size() < HERD_MAX && feed > 40 && welfare > 60
                && random.nextDouble() < pairs * hours / HOURS_PER_BIRTH_PAIR) {
            spawn(true);
            births++;
            JsonObject data = new JsonObject();
            data.addProperty("herd", herd.size() + 1);
            publishEvent("birth", data);
        }

        // crecimiento de terneros
        for (Cow cow : herd) {
            if (!cow.isAdult()) {
                double age = babyAgeH.getOrDefault(cow.getUniqueId(), 0.0) + hours;
                if (age >= BABY_TO_ADULT_H) {
                    cow.setAdult();
                    babyAgeH.remove(cow.getUniqueId());
                } else {
                    babyAgeH.put(cow.getUniqueId(), age);
                }
            }
        }

        // planta de proceso: las adultas por encima del objetivo, con buen bienestar
        sinceProcessH += hours;
        if (autoProcess && adults > targetHerd && welfare >= 50 && sinceProcessH >= PROCESS_EVERY_H) {
            processOne(herd, "auto");
        }

        render(dt);
    }

    /**
     * Llena el comedero desde el silo: trigo y, si no alcanza, zanahoria como forraje.
     * Devuelve las unidades cargadas. Publica el evento solo si cargó algo, o una única vez cuando el
     * silo está vacío (antes de 0.6.1 se publicaba en cada segundo de reintento).
     */
    private int refill(String reason) {
        int wanted = (int) Math.floor((100 - feed) / 100 * FEED_CAPACITY);
        int wheat = silo.take(Material.WHEAT, wanted);
        int carrots = wheat < wanted ? silo.take(Material.CARROT, wanted - wheat) : 0;
        int got = wheat + carrots;
        feed = Math.min(100, feed + got / FEED_CAPACITY * 100);
        if (got > 0 || !feederEmpty) {
            JsonObject data = new JsonObject();
            data.addProperty("wheat", wheat);
            data.addProperty("carrots", carrots);
            data.addProperty("reason", reason);
            data.addProperty("feed_pct", Math.round(feed));
            publishEvent(got > 0 ? "feeder" : "feeder_empty", data);
        }
        feederEmpty = got == 0;
        return got;
    }

    private boolean processOne(List<Cow> herd, String reason) {
        Cow chosen = null;
        for (Cow cow : herd) {
            if (cow.isAdult()) {
                chosen = cow;
                break;
            }
        }
        if (chosen == null) {
            return false;
        }
        chosen.remove();
        herd.remove(chosen);
        sinceProcessH = 0;
        processed++;
        int stored = store(BEEF_PER_COW);
        beefTotal += BEEF_PER_COW;
        smokerLitFor = 30;
        JsonObject data = new JsonObject();
        data.addProperty("beef", BEEF_PER_COW);
        data.addProperty("stored", stored);
        data.addProperty("reason", reason);
        publishEvent("processing", data);
        return true;
    }

    private Container coldRoom() {
        Block block = layout.at(COLD_ROOM[0], 1, COLD_ROOM[1]);
        if (block.getType() != Material.BARREL) {
            block.setType(Material.BARREL, false);
        }
        return (Container) block.getState();
    }

    private int store(int amount) {
        int rejected = 0;
        for (ItemStack rest : coldRoom().getInventory().addItem(new ItemStack(Material.COOKED_BEEF, amount)).values()) {
            rejected += rest.getAmount();
        }
        return amount - rejected;
    }

    private int beefStored() {
        int total = 0;
        for (ItemStack stack : coldRoom().getInventory().getContents()) {
            if (stack != null && stack.getType() == Material.COOKED_BEEF) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private void setCooling(boolean on, String reason) {
        if (cooling == on) {
            return;
        }
        cooling = on;
        JsonObject data = new JsonObject();
        data.addProperty("on", on ? 1 : 0);
        data.addProperty("reason", reason);
        data.addProperty("thi", Math.round(thi * 10) / 10.0);
        publishEvent("cooling", data);
        publishState();
    }

    // ------------------------------------------------------------------------------ mundo
    private void render(double dt) {
        int feedLevel = (int) Math.round(feed / 100 * 7);
        if (feedLevel != shownFeedLevel) {
            Block feeder = layout.at(FEEDER[0], 1, FEEDER[1]);
            if (feeder.getType() != Material.COMPOSTER) {
                feeder.setType(Material.COMPOSTER, false);
            }
            Levelled data = (Levelled) feeder.getBlockData();
            data.setLevel(feedLevel);
            feeder.setBlockData(data, false);
            shownFeedLevel = feedLevel;
        }
        int waterLevel = water < 5 ? 0 : (int) Math.ceil(water / 100 * 3);
        if (waterLevel != shownWaterLevel) {
            Block trough = layout.at(TROUGH[0], 1, TROUGH[1]);
            if (waterLevel == 0) {
                trough.setType(Material.CAULDRON, false);
            } else {
                trough.setType(Material.WATER_CAULDRON, false);
                Levelled data = (Levelled) trough.getBlockData();
                data.setLevel(Math.min(3, waterLevel));
                trough.setBlockData(data, false);
            }
            shownWaterLevel = waterLevel;
        }
        Block smoker = layout.at(SMOKER[0], 1, SMOKER[1]);
        if (smoker.getType() != Material.SMOKER) {
            smoker.setType(Material.SMOKER, false);
        }
        boolean lit = smokerLitFor > 0;
        smokerLitFor = Math.max(0, smokerLitFor - (farm.simulated() ? dt / 60 : dt));
        if (smoker.getBlockData() instanceof Lightable data && data.isLit() != lit) {
            data.setLit(lit);
            smoker.setBlockData(data, false);
        }
        smokeTimer += 1;
        if (lit && smokeTimer >= 2) {
            smokeTimer = 0;
            layout.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, smoker.getLocation().add(0.5, 1.2, 0.5), 2, 0.1, 0.2, 0.1, 0.01);
        }
        sprinkleTimer += 1;
        if (cooling && sprinkleTimer >= 2) {
            sprinkleTimer = 0;
            layout.world.spawnParticle(Particle.SPLASH, center().add(0, 2.5, -2), 120, 3, 0.4, 4, 0.1);
        }
    }

    // --------------------------------------------------------------------------- telemetría
    @Override
    void sample(Map<String, Double> out) {
        out.put("cows_total", (double) (adults + babies));
        out.put("cows_adult", (double) adults);
        out.put("cows_baby", (double) babies);
        out.put("feed_pct", feed);
        out.put("water_pct", water);
        out.put("thi", thi);
        out.put("welfare_pct", welfare);
        out.put("cooling", cooling ? 1.0 : 0.0);
        out.put("beef_stored", (double) beefStored());
        out.put("beef_total", (double) beefTotal);
        out.put("births_total", (double) births);
        out.put("deaths_total", (double) deaths);
        out.put("processed_total", (double) processed);
    }

    @Override
    boolean actuate(String name, boolean value, StringBuilder detail) {
        switch (name) {
            case "feeder" -> {
                if (value) {
                    refill("comando");
                }
                detail.append("comedero: ").append(Math.round(feed)).append(" %");
                return true;
            }
            case "water" -> {
                if (value) {
                    double wanted = (100 - water) / 100 * WATER_CAPACITY_L;
                    water += tank.draw(wanted) / WATER_CAPACITY_L * 100;
                }
                detail.append("bebedero: ").append(Math.round(water)).append(" %");
                return true;
            }
            case "cooling" -> {
                autoCooling = false;
                setCooling(value, "comando");
                detail.append("cooling=").append(value ? 1 : 0).append(" auto=0");
                return true;
            }
            case "process" -> {
                if (value && !processOne(herd(), "comando")) {
                    detail.append("no hay vacas adultas");
                    return false;
                }
                detail.append("proceso ").append(value ? "ejecutado" : "sin cambio");
                return true;
            }
            default -> {
                return super.actuate(name, value, detail);
            }
        }
    }

    @Override
    boolean custom(String action, JsonObject params, StringBuilder detail) {
        if (action.equals("repoblar")) {
            int count = (int) Json.clamp(Json.num(params, "count", 4), 1, HERD_MAX);
            int room = HERD_MAX - herd().size();
            int n = Math.min(count, room);
            for (int i = 0; i < n; i++) {
                spawn(false);
            }
            detail.append("vacas agregadas: ").append(n);
            return n > 0;
        }
        return super.custom(action, params, detail);
    }

    @Override
    void writeConfig(JsonObject out) {
        out.addProperty("cooling", cooling ? 1 : 0);
        out.addProperty("auto_cooling", autoCooling ? 1 : 0);
        out.addProperty("auto_feed", autoFeed ? 1 : 0);
        out.addProperty("auto_water", autoWater ? 1 : 0);
        out.addProperty("auto_process", autoProcess ? 1 : 0);
        out.addProperty("target_herd", targetHerd);
    }

    @Override
    void readConfig(JsonObject in) {
        cooling = Json.flag(in, "cooling", cooling);
        autoCooling = Json.flag(in, "auto_cooling", autoCooling);
        autoFeed = Json.flag(in, "auto_feed", autoFeed);
        autoWater = Json.flag(in, "auto_water", autoWater);
        autoProcess = Json.flag(in, "auto_process", autoProcess);
        targetHerd = (int) Json.clamp(Json.num(in, "target_herd", targetHerd), 2, HERD_MAX);
    }

    @Override
    void resetConfig() {
        cooling = false;
        autoCooling = true;
        autoFeed = true;
        autoWater = true;
        autoProcess = true;
        targetHerd = 8;
    }

    @Override
    void writeModel(JsonObject out) {
        out.addProperty("feed", feed);
        out.addProperty("water", water);
        out.addProperty("welfare", welfare);
        out.addProperty("births", births);
        out.addProperty("deaths", deaths);
        out.addProperty("processed", processed);
        out.addProperty("beef_total", beefTotal);
        out.addProperty("since_process_h", sinceProcessH);
        out.addProperty("seeded", seeded);
        JsonObject ages = new JsonObject();
        babyAgeH.forEach((id, age) -> ages.addProperty(id.toString(), age));
        out.add("baby_age_h", ages);
    }

    @Override
    void readModel(JsonObject in) {
        feed = Json.clamp(Json.num(in, "feed", feed), 0, 100);
        water = Json.clamp(Json.num(in, "water", water), 0, 100);
        welfare = Json.clamp(Json.num(in, "welfare", welfare), 0, 100);
        births = (long) Json.num(in, "births", births);
        deaths = (long) Json.num(in, "deaths", deaths);
        processed = (long) Json.num(in, "processed", processed);
        beefTotal = (long) Json.num(in, "beef_total", beefTotal);
        sinceProcessH = Json.num(in, "since_process_h", sinceProcessH);
        seeded = Json.flag(in, "seeded", seeded);
        // copyFrom (simulación) no trae las vacas: el corral simulado conserva su propio rebaño
        if (in.has("baby_age_h") && in.get("baby_age_h").isJsonObject()) {
            for (var entry : in.getAsJsonObject("baby_age_h").entrySet()) {
                try {
                    babyAgeH.put(UUID.fromString(entry.getKey()), entry.getValue().getAsDouble());
                } catch (IllegalArgumentException ignored) {
                    // UUID inválido: se ignora
                }
            }
        }
    }
}
