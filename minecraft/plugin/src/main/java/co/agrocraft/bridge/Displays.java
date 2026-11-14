package co.agrocraft.bridge;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import com.google.gson.JsonObject;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Pantallas y mandos dentro del mundo.
 *  - Letreros junto a cada gemelo con sus lecturas, y un tablero en la entrada con datos reales
 *    (clima, aire, celular, PC) y la salud del servidor: el mundo físico se ve dentro del gemelo.
 *  - Palancas de riego, bomba, ventilas y alarma: si un jugador las mueve, el gemelo actúa y lo
 *    publica como evento MQTT (retroceso iniciado desde el juego).
 *  - Entrada y salida de jugadores, publicadas como eventos de mc-server.
 */
final class Displays implements Listener {
    record Spot(int x, int z) {
    }

    record Lever(String twin, String actuator, Spot spot) {
    }

    // Letrero y palanca de cada dispositivo, frente a su zona (ver Layout)
    private static final Spot SIGN_A = new Spot(Layout.PLOT_A.cx(), Layout.PLOT_A.z2() + 2);
    private static final Spot SIGN_B = new Spot(Layout.PLOT_B.cx(), Layout.PLOT_B.z2() + 2);
    private static final Spot SIGN_TANK = new Spot(Layout.TANK.cx(), Layout.TANK.z2() + 2);
    private static final Spot SIGN_GREENHOUSE = new Spot(Layout.GREENHOUSE.cx() - 2, Layout.GREENHOUSE.z2() + 2);
    private static final Spot SIGN_SECURITY = new Spot(Layout.SECURITY[0], Layout.SECURITY[1] + 1);
    private static final Spot SIGN_CORRAL = new Spot(Layout.CORRAL.cx() + 1, Layout.CORRAL.z2() + 2);

    private static final Map<String, Spot> DEVICE_SIGNS = new LinkedHashMap<>();
    private static final List<Spot> BOARD = new ArrayList<>();
    private static final List<Lever> LEVERS = List.of(
            new Lever("parcela-a", "irrigation", new Spot(SIGN_A.x() + 1, SIGN_A.z())),
            new Lever("parcela-b", "irrigation", new Spot(SIGN_B.x() + 1, SIGN_B.z())),
            new Lever("tanque", "pump", new Spot(SIGN_TANK.x() + 1, SIGN_TANK.z())),
            new Lever("invernadero", "vent", new Spot(SIGN_GREENHOUSE.x() + 1, SIGN_GREENHOUSE.z())),
            new Lever("seguridad", "alarm", new Spot(SIGN_SECURITY.x() + 1, SIGN_SECURITY.z())),
            new Lever("corral", "cooling", new Spot(SIGN_CORRAL.x() - 2, SIGN_CORRAL.z())));

    static {
        DEVICE_SIGNS.put("parcela-a", SIGN_A);
        DEVICE_SIGNS.put("parcela-b", SIGN_B);
        DEVICE_SIGNS.put("tanque", SIGN_TANK);
        DEVICE_SIGNS.put("invernadero", SIGN_GREENHOUSE);
        DEVICE_SIGNS.put("silo", new Spot(Layout.SILO_X, Layout.CHANNEL_Z + 1));
        DEVICE_SIGNS.put("estacion", new Spot(Layout.STATION[0], Layout.STATION[1] + 1));
        DEVICE_SIGNS.put("seguridad", SIGN_SECURITY);
        DEVICE_SIGNS.put("corral", SIGN_CORRAL);
        for (int x = Layout.GATE_X - 5; x <= Layout.GATE_X + 5; x += 2) {     // tablero frente a la entrada
            BOARD.add(new Spot(x, Layout.FARM.z2() - 3));
        }
    }

    /** Spots de letreros y palancas: Decor no construye encima. */
    static List<Spot> fixtures() {
        List<Spot> out = new ArrayList<>(DEVICE_SIGNS.values());
        out.addAll(BOARD);
        out.add(SIM_SIGN);
        for (Lever lever : LEVERS) {
            out.add(lever.spot());
        }
        return out;
    }

    /** Hologramas: posición (relativa al origen, y sobre el suelo) encima de cada dispositivo. */
    private static final Map<String, double[]> HOLOGRAMS = new LinkedHashMap<>();
    static final String HOLO_TAG = "agrocraft-holo";

    static {
        HOLOGRAMS.put("parcela-a", new double[] {Layout.PLOT_A.cx() + 0.5, 3.2, Layout.CHANNEL_Z + 2.5});
        HOLOGRAMS.put("parcela-b", new double[] {Layout.PLOT_B.cx() + 0.5, 3.2, Layout.CHANNEL_Z + 2.5});
        HOLOGRAMS.put("tanque", new double[] {Layout.TANK.cx() + 0.5, Layout.TANK_LAYERS + 3.0, Layout.TANK.cz() + 0.5});
        HOLOGRAMS.put("invernadero", new double[] {Layout.GREENHOUSE.cx() + 0.5, Layout.GREENHOUSE_ROOF + 2.4, Layout.GREENHOUSE.cz() + 0.5});
        HOLOGRAMS.put("silo", new double[] {Layout.SILO_X + 0.5, 12.6, Layout.CHANNEL_Z + 0.5});
        HOLOGRAMS.put("estacion", new double[] {Layout.STATION[0] + 0.5, 6.6, Layout.STATION[1] + 0.5});
        HOLOGRAMS.put("seguridad", new double[] {Layout.SECURITY[0] + 0.5, 4.8, Layout.SECURITY[1] + 0.5});
        HOLOGRAMS.put("corral", new double[] {Layout.CORRAL.cx() + 0.5, 10.6, Layout.CORRAL.z1() + 3.5});
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static final Spot SIM_SIGN = new Spot(Layout.GATE_X, Layout.FARM.z2() - 3);

    private final AgroCraftPlugin plugin;
    private final Farm farm;
    private final boolean main;
    private final Layout layout;
    private final ZoneId zone;
    private final Map<Spot, String> shown = new HashMap<>();
    private final Map<String, TextDisplay> holograms = new HashMap<>();
    private final Map<String, String> holoShown = new HashMap<>();

    Displays(AgroCraftPlugin plugin, Farm farm, ZoneId zone, boolean main) {
        this.plugin = plugin;
        this.farm = farm;
        this.main = main;
        this.layout = farm.layout;
        this.zone = zone;
    }

    // ----------------------------------------------------------------------------- refresco
    void update() {
        for (Map.Entry<String, Spot> entry : DEVICE_SIGNS.entrySet()) {
            Twin twin = farm.twin(entry.getKey());
            if (twin != null) {
                String[] lines = deviceLines(entry.getKey(), twin);
                sign(entry.getValue(), lines);
                double[] at = HOLOGRAMS.get(entry.getKey());
                if (at != null) {
                    hologram(entry.getKey(), at, twin, lines);
                }
            }
        }
        if (main) {
            label("entrada", new double[] {Layout.GATE_X + 0.5, 6.4, Layout.FARM.z2() + 0.5},
                    Component.text("AgroCraft", NamedTextColor.GOLD).append(Component.newline())
                            .append(Component.text("granja IoT · gemelo digital", NamedTextColor.WHITE)));
        } else {
            label("entrada", new double[] {Layout.GATE_X + 0.5, 6.4, Layout.FARM.z2() + 0.5},
                    Component.text("Granja de simulación", NamedTextColor.AQUA).append(Component.newline())
                            .append(Component.text("escenarios a reloj acelerado", NamedTextColor.WHITE)));
        }
        label("caseta", new double[] {Layout.BOOTH.cx() + 1.0, 5.3, Layout.BOOTH.cz() + 0.5},
                Component.text("Centro de control", NamedTextColor.YELLOW).append(Component.newline())
                        .append(Component.text("MQTT · Grafana · Azure", NamedTextColor.GRAY)));
        if (main) {
            List<String[]> board = boardLines();
            for (int i = 0; i < BOARD.size() && i < board.size(); i++) {
                sign(BOARD.get(i), board.get(i));
            }
        } else if (farm.twin("control") != null) {
            sign(SIM_SIGN, simLines(values(farm.twin("control"))));
        }
        for (Lever lever : LEVERS) {
            Twin twin = farm.twin(lever.twin());
            if (twin != null) {
                lever(lever.spot(), Json.flag(twin.config(), lever.actuator(), false));
            }
        }
    }

    private Map<String, Double> values(Twin twin) {
        Map<String, Double> out = new LinkedHashMap<>();
        twin.sample(out);
        return out;
    }

    private String[] simLines(Map<String, Double> v) {
        int code = (int) num(v.get("scenario_code"));
        String scenario = code < SimWorld.SCENARIOS.size() ? SimWorld.SCENARIOS.get(code) : "?";
        double clock = num(v.get("sim_clock_h"));
        return new String[] {"SIMULACION", num(v.get("running")) > 0 ? scenario + " x" + f(v.get("speed"), 0) : "en pausa",
            "Hora " + String.format(Locale.ROOT, "%02d:%02d", (int) clock, (int) ((clock % 1) * 60)),
            f(v.get("sim_hours"), 1) + " h simuladas"};
    }

    private String[] deviceLines(String suffix, Twin twin) {
        String[] lines = baseLines(suffix, twin);
        if (farm.simulated()) {
            lines[0] = "SIM " + lines[0];
        }
        return lines;
    }

    private String[] baseLines(String suffix, Twin twin) {
        Map<String, Double> v = values(twin);
        return switch (suffix) {
            case "parcela-a", "parcela-b" -> new String[] {
                suffix.endsWith("a") ? "Parcela A" : "Parcela B",
                "Suelo " + f(v.get("soil_pct"), 0) + " %",
                "Cultivo " + f(v.get("growth_pct"), 0) + " %",
                "Riego " + onOff(v.get("irrigation"))};
            case "tanque" -> new String[] {
                "Tanque", "Nivel " + f(v.get("level_pct"), 0) + " %", f(v.get("volume_l"), 0) + " L",
                "Bomba " + onOff(v.get("pump"))};
            case "invernadero" -> new String[] {
                "Invernadero", "T " + f(v.get("temp_c"), 1) + "C HR " + f(v.get("hum_pct"), 0) + "%",
                "Plantas " + f(v.get("plant_health_pct"), 0) + " %", "Ventilas " + onOff(v.get("vent_open"))};
            case "silo" -> new String[] {
                "Silo", "Lleno " + f(v.get("fill_pct"), 0) + " %", f(v.get("items"), 0) + " unid.",
                "Enviado " + f(v.get("dispatched_total"), 0)};
            case "estacion" -> new String[] {
                "Estacion", "Lluvia " + (num(v.get("raining")) > 0 ? "SI" : "NO"),
                "Luz " + f(v.get("daylight_lvl"), 0) + "/15", LocalTime.now(zone).format(CLOCK)};
            case "seguridad" -> new String[] {
                "Seguridad", "Hostiles " + f(v.get("hostiles_near"), 0),
                "Personas " + f(v.get("players_in_zone"), 0), "Alarma " + onOff(v.get("alarm"))};
            case "corral" -> new String[] {
                "Corral " + f(v.get("cows_total"), 0) + " vacas", "Bienestar " + f(v.get("welfare_pct"), 0) + " %",
                "THI " + f(v.get("thi"), 0) + " Com " + f(v.get("feed_pct"), 0) + "%",
                "Carne " + f(v.get("beef_stored"), 0) + " (" + f(v.get("beef_total"), 0) + ")"};
            default -> new String[] {suffix, "", "", ""};
        };
    }

    private List<String[]> boardLines() {
        Map<String, JsonObject> real = plugin.real.latest;
        JsonObject clima = real.get("api-clima");
        JsonObject aire = real.get("api-aire");
        JsonObject phone = real.get("phone-01");
        JsonObject pc = real.get("gw-pc-01");
        Twin server = plugin.twin("mc-server");
        Map<String, Double> s = server == null ? Map.of() : values(server);
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {"AgroCraft", "gemelo digital", "IoT UNAB", LocalTime.now(zone).format(CLOCK)});
        out.add(new String[] {"Bucaramanga", "T " + j(clima, "temp_c", 1) + " C", "HR " + j(clima, "hum_pct", 0) + " %",
            "Lluvia " + j(clima, "precip_mm", 1) + " mm"});
        out.add(new String[] {"Aire exterior", "AQI " + j(aire, "us_aqi", 0), "PM2.5 " + j(aire, "pm2_5", 1),
            "UV " + j(aire, "uv_index", 1)});
        out.add(new String[] {"Celular", j(phone, "pressure_hpa", 0) + " hPa", "Sonido " + j(phone, "sound_dbfs", 0) + " dB",
            "Bat " + j(phone, "battery_pct", 0) + " %"});
        out.add(new String[] {"PC gateway", "CPU " + j(pc, "cpu_pct", 0) + " %", "RAM " + j(pc, "ram_pct", 0) + " %",
            "RTT " + j(pc, "link_rtt_ms", 0) + " ms"});
        out.add(new String[] {"Servidor", "TPS " + f(s.get("tps"), 1), "Jugadores " + f(s.get("players_online"), 0),
            "RAM " + f(s.get("heap_mb"), 0) + " MB"});
        return out;
    }

    private static String j(JsonObject data, String key, int decimals) {
        return data == null || !data.has(key) ? "--" : f(Json.num(data, key, Double.NaN), decimals);
    }

    private static double num(Double value) {
        return value == null ? 0 : value;
    }

    private static String onOff(Double value) {
        return num(value) > 0 ? "ON" : "OFF";
    }

    private static String f(Double value, int decimals) {
        if (value == null || value.isNaN()) {
            return "--";
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    // ------------------------------------------------------------------------ hologramas
    /** Quita hologramas que hayan quedado de una carga anterior del plugin. */
    static void clearHolograms(org.bukkit.World world) {
        for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
            if (display.getScoreboardTags().contains(HOLO_TAG)) {
                display.remove();
            }
        }
    }

    private TextDisplay display(String key, double[] at) {
        TextDisplay display = holograms.get(key);
        if (display != null && display.isValid()) {
            return display;
        }
        Location where = new Location(layout.world, layout.ox + at[0], layout.y0 + at[1], layout.oz + at[2]);
        display = layout.world.spawn(where, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(HOLO_TAG);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(150, 10, 20, 30));
            d.setShadowed(true);
            d.setLineWidth(220);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
        });
        holograms.put(key, display);
        holoShown.remove(key);
        return display;
    }

    private void label(String key, double[] at, Component text) {
        TextDisplay display = display(key, at);
        if (!"static".equals(holoShown.get(key))) {
            display.text(text);
            holoShown.put(key, "static");
        }
    }

    private void hologram(String suffix, double[] at, Twin twin, String[] lines) {
        boolean enabled = twin.enabled;
        String key = String.join("|", lines) + enabled;
        TextDisplay display = display(suffix, at);
        if (key.equals(holoShown.get(suffix))) {
            return;
        }
        Component text = Component.text(lines[0], enabled ? NamedTextColor.GREEN : NamedTextColor.RED)
                .append(Component.text("  " + twin.id, NamedTextColor.GRAY));
        for (int i = 1; i < lines.length; i++) {
            text = text.append(Component.newline()).append(Component.text(lines[i], NamedTextColor.WHITE));
        }
        if (!enabled) {
            text = text.append(Component.newline()).append(Component.text("envío apagado", NamedTextColor.RED));
        }
        display.text(text);
        holoShown.put(suffix, key);
    }

    // --------------------------------------------------------------------------- bloques
    private void sign(Spot spot, String[] lines) {
        String key = String.join("|", lines);
        Block block = layout.at(spot.x(), 1, spot.z());
        if (block.getType() != Material.OAK_SIGN) {
            block.setType(Material.OAK_SIGN, false);
            Rotatable data = (Rotatable) block.getBlockData();
            data.setRotation(BlockFace.SOUTH);
            block.setBlockData(data, false);
            shown.remove(spot);
        }
        if (key.equals(shown.get(spot))) {
            return;
        }
        Sign sign = (Sign) block.getState();
        for (int i = 0; i < 4; i++) {
            String text = i < lines.length ? lines[i] : "";
            sign.getSide(Side.FRONT).line(i, Component.text(text, i == 0 ? NamedTextColor.DARK_GREEN : NamedTextColor.BLACK));
        }
        sign.setWaxed(true);            // nadie puede editarlo a mano
        sign.update(true, false);
        shown.put(spot, key);
    }

    private void lever(Spot spot, boolean on) {
        Block block = layout.at(spot.x(), 1, spot.z());
        if (block.getType() != Material.LEVER) {
            block.setType(Material.LEVER, false);
            Switch data = (Switch) block.getBlockData();
            data.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
            data.setFacing(BlockFace.SOUTH);
            block.setBlockData(data, false);
        }
        Switch data = (Switch) block.getBlockData();
        if (data.isPowered() != on) {
            data.setPowered(on);
            block.setBlockData(data, false);
        }
    }

    private Lever leverAt(Block block) {
        for (Lever lever : LEVERS) {
            if (block.equals(layout.at(lever.spot().x(), 1, lever.spot().z()))) {
                return lever;
            }
        }
        return null;
    }

    private boolean isFixture(Block block) {
        if (!block.getWorld().equals(layout.world) || block.getY() != layout.y0 + 1) {
            return false;
        }
        Spot spot = new Spot(block.getX() - layout.ox, block.getZ() - layout.oz);
        return DEVICE_SIGNS.containsValue(spot) || (main ? BOARD.contains(spot) : spot.equals(SIM_SIGN))
                || LEVERS.stream().anyMatch(lever -> lever.spot().equals(spot));
    }

    // ---------------------------------------------------------------------------- eventos
    @EventHandler(ignoreCancelled = false)
    public void onLever(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.LEVER) {
            return;
        }
        Lever lever = leverAt(event.getClickedBlock());
        if (lever == null) {
            return;
        }
        event.setCancelled(true);       // el estado lo decide el gemelo; la palanca solo lo refleja
        Twin twin = farm.twin(lever.twin());
        if (twin == null) {
            return;
        }
        boolean on = Json.flag(twin.config(), lever.actuator(), false);
        twin.playerActuate(lever.actuator(), !on, event.getPlayer().getName());
        lever(lever.spot(), Json.flag(twin.config(), lever.actuator(), false));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isFixture(event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Este letrero o palanca es parte del gemelo digital.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!main) {
            return;
        }
        playerEvent("player_join", event.getPlayer().getName());
        event.getPlayer().sendMessage(Component.text(
                "AgroCraft: este mundo es un gemelo digital IoT. Las palancas de la granja mandan comandos reales.",
                NamedTextColor.GREEN));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!main) {
            return;
        }
        playerEvent("player_quit", event.getPlayer().getName());
    }

    private void playerEvent(String type, String player) {
        Twin server = plugin.twin("mc-server");
        if (server != null) {
            JsonObject data = new JsonObject();
            data.addProperty("player", player);
            server.publishEvent(type, data);
        }
    }
}
