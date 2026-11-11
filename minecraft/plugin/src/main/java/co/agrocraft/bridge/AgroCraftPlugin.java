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
    static final String FW = "agrocraft-bridge-0.1.0";

    Layout layout;
    Mqtt mqtt;
    final RealWorld real = new RealWorld();
    private final Map<String, Twin> twins = new LinkedHashMap<>();
    private File stateFile;
    private long lastStep;
    private int secondsToSave = 60;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        World world = Bukkit.getWorlds().get(0);
        stateFile = new File(getDataFolder(), "state.json");
        JsonObject saved = readState();

        int ox = getConfig().getInt("origin.x", 0);
        int oz = getConfig().getInt("origin.z", 0);
        boolean built = saved.has("y0");
        int y0 = built ? saved.get("y0").getAsInt() : world.getHighestBlockYAt(ox, oz);
        layout = new Layout(world, ox, y0, oz);
        layout.keepLoaded(this);
        if (!built) {
            layout.build();
            getLogger().info("Granja construida en x=" + ox + " y=" + y0 + " z=" + oz);
        }

        TankTwin tank = new TankTwin(this);
        SiloTwin silo = new SiloTwin(this);
        // Las parcelas van antes que el tanque: primero piden agua y luego el tanque hace su balance
        register(new PlotTwin(this, "mc-parcela-a", Layout.PLOT_A, new Material[] {Material.WHEAT},
                Material.WHEAT, 8, 1.0, 1.0, tank, silo));
        register(new PlotTwin(this, "mc-parcela-b", Layout.PLOT_B, new Material[] {Material.CARROTS, Material.POTATOES},
                Material.CARROT, 20, 1.25, 0.85, tank, silo));
        register(tank);
        register(new GreenhouseTwin(this));
        register(silo);
        register(new SecurityTwin(this));
        register(new StationTwin(this, ZoneId.of(getConfig().getString("timezone", "America/Bogota"))));
        register(new ServerTwin(this));

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
                    new String[] {"agro/twin/+/cmd", "agro/api/+/telemetry"},
                    this::onMqttMessage, this::onMqttConnected, getLogger());
        } catch (MqttException error) {
            getLogger().log(Level.SEVERE, "No se pudo crear el cliente MQTT; el plugin se desactiva", error);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        lastStep = System.nanoTime();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 40L, 20L);
        saveState();
    }

    @Override
    public void onDisable() {
        if (layout != null && !twins.isEmpty()) {
            saveState();
        }
        if (mqtt != null) {
            mqtt.close(twins.values().stream().map(twin -> twin.topic + "/status").toArray(String[]::new));
        }
    }

    private void register(Twin twin) {
        twins.put(twin.id, twin);
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
        for (Twin twin : twins.values()) {
            try {
                twin.step(dt);
                twin.maybePublish();
            } catch (RuntimeException error) {
                getLogger().log(Level.WARNING, "Fallo en " + twin.id, error);
            }
        }
        if (--secondsToSave <= 0) {
            secondsToSave = 60;
            saveState();
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
            if (parts[1].equals("twin") && parts[3].equals("cmd")) {
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
        if (layout.inFarm(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMoisture(MoistureChangeEvent event) {
        if (layout.inFarm(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND && layout.inFarm(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrample(EntityChangeBlockEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND && layout.inFarm(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onStep(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.FARMLAND && layout.inFarm(event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }
}
