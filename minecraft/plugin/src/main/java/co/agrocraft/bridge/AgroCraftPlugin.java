package co.agrocraft.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.eclipse.paho.client.mqttv3.MqttException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * AgroCraft: convierte el mundo en un conjunto de dispositivos IoT.
 * Cada gemelo publica telemetría por MQTT y obedece comandos; el clima real entra por los
 * dispositivos de API. El plugin lleva el modelo, así que el mundo evoluciona sin jugadores.
 */
public final class AgroCraftPlugin extends JavaPlugin implements Listener {
    static final String FW = "agrocraft-bridge-0.8.0";

    Layout layout;
    Mqtt mqtt;
    final RealWorld real = new RealWorld();
    private final Map<String, Twin> twins = new LinkedHashMap<>();
    static final int SIM_OFFSET = 200;          // v1 del plano usaba 80
    private static final int LEGACY_SIM_OFFSET = 80;
    private final java.util.List<Farm> farms = new java.util.ArrayList<>();
    Farm realFarm;
    Farm simFarm;
    private File stateFile;
    private long lastStep;
    private int secondsToSave = 60;
    private int decorVersion;
    private int layoutVersion;
    private Tour tour;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        World world = Bukkit.getWorlds().get(0);
        stateFile = new File(getDataFolder(), "state.json");
        JsonObject saved = readState();

        ZoneId zone = ZoneId.of(getConfig().getString("timezone", "America/Bogota"));
        int ox = getConfig().getInt("origin.x", 0);
        int oz = getConfig().getInt("origin.z", 0);
        boolean built = saved.has("y0");
        int y0 = built ? saved.get("y0").getAsInt() : world.getHighestBlockYAt(ox, oz);
        layout = new Layout(world, ox, y0, oz);
        layout.keepLoaded(this);
        if (!built) {
            layout.build(true);
            getLogger().info("Granja construida en x=" + ox + " y=" + y0 + " z=" + oz);
        }
        realFarm = new Farm("twin", "mc-", layout, real);
        farms.add(realFarm);
        if (!saved.has("corral")) {
            layout.buildCorral();
        }
        buildFarm(realFarm);
        register(realFarm, new StationTwin(this, realFarm, zone));
        register(realFarm, new ServerTwin(this, realFarm));

        // Granja de simulación: copia de la granja a SIM_OFFSET bloques al este, con reloj acelerado
        int sx = ox + getConfig().getInt("simulation.offset_x", SIM_OFFSET);
        boolean simBuilt = saved.has("sim_y0");
        int sy0 = simBuilt ? saved.get("sim_y0").getAsInt() : world.getHighestBlockYAt(sx, oz);
        Layout simLayout = new Layout(world, sx, sy0, oz);
        simLayout.keepLoaded(this);
        if (!simBuilt) {
            simLayout.build(false);
            getLogger().info("Granja de simulación construida en x=" + sx + " y=" + sy0 + " z=" + oz);
        }
        simFarm = new Farm("sim", "sim-", simLayout, new SimWorld());
        farms.add(simFarm);
        if (!saved.has("sim_corral")) {
            simLayout.buildCorral();
        }
        buildFarm(simFarm);
        register(simFarm, new SimControl(this, simFarm, realFarm, zone));

        layoutVersion = saved.has("layout_v") ? saved.get("layout_v").getAsInt() : 1;
        if (built && layoutVersion < Layout.VERSION) {
            migrateLayout(world, ox, oz, sy0);
        }
        layoutVersion = Layout.VERSION;

        decorVersion = saved.has("decor_v") ? saved.get("decor_v").getAsInt() : 0;
        if (decorVersion < Decor.VERSION) {
            int blocks = rebuildDecor();
            getLogger().info("Arquitectura v" + Decor.VERSION + " construida en las dos granjas: " + blocks + " bloques");
        }
        Displays.clearHolograms(world);

        JsonObject savedTwins = saved.has("twins") ? saved.getAsJsonObject("twins") : new JsonObject();
        for (Twin twin : twins.values()) {
            if (savedTwins.has(twin.id)) {
                twin.load(savedTwins.getAsJsonObject(twin.id));
            }
        }

        try {
            mqtt = new Mqtt(
                    setting("AGRO_MQTT_URL", "mqtt.url", "tcp://mosquitto:1883"),
                    setting("AGRO_MQTT_USER", "mqtt.user", "mc-bridge"),
                    setting("AGRO_MQTT_PASS", "mqtt.password", ""),
                    "agro/twin/mc-server/status",
                    new String[] {"agro/twin/+/cmd", "agro/sim/+/cmd", "agro/api/+/telemetry", "agro/real/+/telemetry"},
                    this::onMqttMessage, this::onMqttConnected, getLogger());
        } catch (MqttException error) {
            getLogger().log(Level.SEVERE, "No se pudo crear el cliente MQTT; el plugin se desactiva", error);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        tour = new Tour(this);
        Bukkit.getPluginManager().registerEvents(tour, this);
        if (getCommand("agro") != null) {
            getCommand("agro").setExecutor(tour);
        }
        for (Farm farm : farms) {
            Displays displays = new Displays(this, farm, zone, farm == realFarm);
            Bukkit.getPluginManager().registerEvents(displays, this);
            Bukkit.getScheduler().runTaskTimer(this, displays::update, 60L, 40L);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("squaremap") && new MapMarkers(this, farms).start()) {
            getLogger().info("Marcadores en vivo publicados en el mapa web");
        }
        lastStep = System.nanoTime();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 40L, 20L);
        saveState();
    }

    @Override
    public void onDisable() {
        if (tour != null) {
            tour.shutdown();
        }
        for (Farm farm : farms) {
            Displays.clearHolograms(farm.layout.world);
        }
        if (layout != null && !twins.isEmpty()) {
            saveState();
        }
        if (mqtt != null) {
            mqtt.close(twins.values().stream().map(twin -> twin.topic + "/status").toArray(String[]::new));
        }
    }

    Twin twin(String id) {
        return twins.get(id);
    }

    /**
     * Pasa del plano v1 (todo junto) al v2 (zonas separadas): guarda lo que hay en el silo y en la cámara
     * fría, limpia las dos granjas viejas, levanta las nuevas y devuelve el inventario. El estado de los
     * modelos (humedad, nivel, rebaño...) vive en state.json y no se toca; las vacas vuelven solas al corral.
     */
    private void migrateLayout(World world, int ox, int oz, int simY0) {
        Layout oldSim = new Layout(world, ox + LEGACY_SIM_OFFSET, simY0, oz);
        Layout[] olds = {layout, oldSim};
        Layout[] news = {layout, simFarm.layout};
        org.bukkit.inventory.ItemStack[][] silos = new org.bukkit.inventory.ItemStack[2][];
        org.bukkit.inventory.ItemStack[][] colds = new org.bukkit.inventory.ItemStack[2][];
        for (int i = 0; i < 2; i++) {
            silos[i] = contents(olds[i].at(14, 1, 8));
            colds[i] = contents(olds[i].at(0, 1, -3));
        }
        for (Layout old : olds) {
            old.clear(Layout.LEGACY, 16);
        }
        layout.build(true);
        layout.buildCorral();
        simFarm.layout.build(false);
        simFarm.layout.buildCorral();
        for (int i = 0; i < 2; i++) {
            restore(news[i].at(Layout.SILO_X, 1, Layout.CHANNEL_Z), silos[i]);
            restore(news[i].at(CorralTwin.COLD_ROOM[0], 1, CorralTwin.COLD_ROOM[1]), colds[i]);
        }
        getLogger().info("Plano v" + Layout.VERSION + ": granjas reconstruidas con zonas separadas; silo y cámara fría conservados");
    }

    private static org.bukkit.inventory.ItemStack[] contents(org.bukkit.block.Block block) {
        if (block.getState() instanceof org.bukkit.block.Container container) {
            org.bukkit.inventory.ItemStack[] items = container.getInventory().getContents().clone();
            container.getInventory().clear();
            return items;
        }
        return new org.bukkit.inventory.ItemStack[0];
    }

    private static void restore(org.bukkit.block.Block block, org.bukkit.inventory.ItemStack[] items) {
        if (block.getType() != Material.BARREL) {
            block.setType(Material.BARREL, false);
        }
        if (items.length > 0 && block.getState() instanceof org.bukkit.block.Container container) {
            container.getInventory().setContents(items);
        }
    }

    /** Levanta la arquitectura en las dos granjas sin tocar los bloques de los gemelos (ver Decor). */
    int rebuildDecor() {
        int blocks = 0;
        for (Farm farm : farms) {
            blocks += new Decor(farm.layout).build();
        }
        decorVersion = Decor.VERSION;
        if (simFarm != null) {
            saveState();
        }
        return blocks;
    }

    private void register(Farm farm, Twin twin) {
        twins.put(twin.id, twin);
        farm.twins.put(twin.id, twin);
    }

    /** Gemelos de una granja. Las parcelas van antes que el tanque: primero piden agua y luego el tanque hace su balance. */
    private void buildFarm(Farm farm) {
        TankTwin tank = new TankTwin(this, farm);
        SiloTwin silo = new SiloTwin(this, farm);
        register(farm, new PlotTwin(this, farm, "parcela-a", Layout.PLOT_A, new Material[] {Material.WHEAT},
                Material.WHEAT, Layout.LAMP_A_X, 1.0, 1.0, tank, silo));
        register(farm, new PlotTwin(this, farm, "parcela-b", Layout.PLOT_B,
                new Material[] {Material.CARROTS, Material.POTATOES}, Material.CARROT, Layout.LAMP_B_X, 1.25, 0.85, tank, silo));
        register(farm, new CorralTwin(this, farm, tank, silo));     // bebe y se enfría con agua del tanque
        register(farm, tank);
        register(farm, new GreenhouseTwin(this, farm));
        register(farm, silo);
        register(farm, new SecurityTwin(this, farm));
    }

    boolean inAnyFarm(org.bukkit.block.Block block) {
        for (Farm farm : farms) {
            if (farm.layout.inFarm(block)) {
                return true;
            }
        }
        return false;
    }

    private String setting(String env, String path, String fallback) {
        String value = System.getenv(env);
        return value != null && !value.isEmpty() ? value : getConfig().getString(path, fallback);
    }

    // ------------------------------------------------------------------------------- ciclo
    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(10.0, (now - lastStep) / 1e9);
        if (dt <= 0) {
            return;
        }
        lastStep = now;
        for (Twin twin : realFarm.twins.values()) {
            runTwin(twin, dt, true);
        }
        SimWorld sim = (SimWorld) simFarm.inputs;
        double simDt = sim.advance(dt);
        while (simDt > 1e-6) {
            double part = Math.min(60, simDt);
            simDt -= part;
            for (Twin twin : simFarm.twins.values()) {
                if (!(twin instanceof SimControl)) {
                    runTwin(twin, part, false);
                }
            }
        }
        for (Twin twin : simFarm.twins.values()) {
            if (twin instanceof SimControl) {
                runTwin(twin, dt, true);             // el control mide tiempo real y publica siempre
            } else if (sim.running) {
                twin.maybePublish();                 // la granja simulada solo publica mientras corre
            }
        }
        if (--secondsToSave <= 0) {
            secondsToSave = 60;
            saveState();
        }
    }

    private void runTwin(Twin twin, double dt, boolean publish) {
        try {
            twin.step(dt);
            if (publish) {
                twin.maybePublish();
            }
        } catch (RuntimeException error) {
            getLogger().log(Level.WARNING, "Fallo en " + twin.id, error);
        }
    }

    // -------------------------------------------------------------------------------- MQTT
    /** Llega en un hilo de Paho: todo lo que toca el mundo se pasa al hilo principal. */
    private void onMqttMessage(String topic, String payload) {
        if (!isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            String[] parts = topic.split("/");
            if (parts.length != 4) {
                return;
            }
            if ((parts[1].equals("twin") || parts[1].equals("sim")) && parts[3].equals("cmd")) {
                Twin twin = twins.get(parts[2]);
                if (twin != null) {
                    twin.handleCommand(payload);
                }
            } else if (parts[3].equals("telemetry")) {
                real.update(parts[2], payload);
            }
        });
    }

    private void onMqttConnected() {
        if (!isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            for (Twin twin : twins.values()) {
                mqtt.publish(twin.topic + "/status", "online", 1, true);
                twin.publishState();
            }
        });
    }

    // ------------------------------------------------------------------------ persistencia
    private JsonObject readState() {
        try {
            if (stateFile.isFile()) {
                return JsonParser.parseString(Files.readString(stateFile.toPath())).getAsJsonObject();
            }
        } catch (IOException | RuntimeException error) {
            getLogger().log(Level.WARNING, "state.json ilegible; se parte de valores de fábrica", error);
        }
        return new JsonObject();
    }

    void saveState() {
        JsonObject root = new JsonObject();
        root.addProperty("y0", layout.y0);
        root.addProperty("sim_y0", simFarm.layout.y0);
        root.addProperty("corral", true);
        root.addProperty("sim_corral", true);
        root.addProperty("decor_v", decorVersion);
        root.addProperty("layout_v", layoutVersion);
        JsonObject all = new JsonObject();
        for (Twin twin : twins.values()) {
            all.add(twin.id, twin.save());
        }
        root.add("twins", all);
        try {
            File tmp = new File(getDataFolder(), "state.json.tmp");
            Files.writeString(tmp.toPath(), root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            getLogger().log(Level.WARNING, "No se pudo guardar state.json", error);
        }
    }

    // ------------------------------------------------- el juego no altera lo que lleva el modelo
    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (inAnyFarm(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMoisture(MoistureChangeEvent event) {
        if (inAnyFarm(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND && inAnyFarm(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrample(EntityChangeBlockEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND && inAnyFarm(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onStep(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.FARMLAND && inAnyFarm(event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }
}
