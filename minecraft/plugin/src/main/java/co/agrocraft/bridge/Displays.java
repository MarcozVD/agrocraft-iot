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

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.sign.Side;
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

    private static final Map<String, Spot> DEVICE_SIGNS = new LinkedHashMap<>();
    private static final List<Spot> BOARD = new ArrayList<>();
    private static final List<Lever> LEVERS = List.of(
            new Lever("mc-parcela-a", "irrigation", new Spot(9, 13)),
            new Lever("mc-parcela-b", "irrigation", new Spot(21, 13)),
            new Lever("mc-tanque", "pump", new Spot(7, -5)),
            new Lever("mc-invernadero", "vent", new Spot(19, -5)),
            new Lever("mc-seguridad", "alarm", new Spot(3, 3)));

    static {
        DEVICE_SIGNS.put("mc-parcela-a", new Spot(8, 13));
        DEVICE_SIGNS.put("mc-parcela-b", new Spot(20, 13));
        DEVICE_SIGNS.put("mc-tanque", new Spot(6, -5));
        DEVICE_SIGNS.put("mc-invernadero", new Spot(18, -5));
        DEVICE_SIGNS.put("mc-silo", new Spot(14, 9));
        DEVICE_SIGNS.put("mc-estacion", new Spot(0, 1));
        DEVICE_SIGNS.put("mc-seguridad", new Spot(2, 3));
        for (int x = 9; x <= 19; x += 2) {          // tablero frente al punto de aparición (14, 16)
            BOARD.add(new Spot(x, 15));
        }
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final AgroCraftPlugin plugin;
    private final Layout layout;
    private final ZoneId zone;
    private final Map<Spot, String> shown = new HashMap<>();

    Displays(AgroCraftPlugin plugin, ZoneId zone) {
        this.plugin = plugin;
        this.layout = plugin.layout;
        this.zone = zone;
    }

    // ----------------------------------------------------------------------------- refresco
    void update() {
        for (Map.Entry<String, Spot> entry : DEVICE_SIGNS.entrySet()) {
            Twin twin = plugin.twin(entry.getKey());
            if (twin != null) {
                sign(entry.getValue(), deviceLines(twin));
            }
        }
        List<String[]> board = boardLines();
        for (int i = 0; i < BOARD.size() && i < board.size(); i++) {
            sign(BOARD.get(i), board.get(i));
        }
        for (Lever lever : LEVERS) {
            Twin twin = plugin.twin(lever.twin());
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

    private String[] deviceLines(Twin twin) {
        Map<String, Double> v = values(twin);
        return switch (twin.id) {
            case "mc-parcela-a", "mc-parcela-b" -> new String[] {
                twin.id.endsWith("a") ? "Parcela A" : "Parcela B",
                "Suelo " + f(v.get("soil_pct"), 0) + " %",
                "Cultivo " + f(v.get("growth_pct"), 0) + " %",
                "Riego " + onOff(v.get("irrigation"))};
            case "mc-tanque" -> new String[] {
                "Tanque", "Nivel " + f(v.get("level_pct"), 0) + " %", f(v.get("volume_l"), 0) + " L",
                "Bomba " + onOff(v.get("pump"))};
            case "mc-invernadero" -> new String[] {
                "Invernadero", "T " + f(v.get("temp_c"), 1) + "C HR " + f(v.get("hum_pct"), 0) + "%",
                "Plantas " + f(v.get("plant_health_pct"), 0) + " %", "Ventilas " + onOff(v.get("vent_open"))};
            case "mc-silo" -> new String[] {
                "Silo", "Lleno " + f(v.get("fill_pct"), 0) + " %", f(v.get("items"), 0) + " unid.",
                "Enviado " + f(v.get("dispatched_total"), 0)};
            case "mc-estacion" -> new String[] {
                "Estacion", "Lluvia " + (num(v.get("raining")) > 0 ? "SI" : "NO"),
                "Luz " + f(v.get("daylight_lvl"), 0) + "/15", LocalTime.now(zone).format(CLOCK)};
            case "mc-seguridad" -> new String[] {
                "Seguridad", "Hostiles " + f(v.get("hostiles_near"), 0),
                "Personas " + f(v.get("players_in_zone"), 0), "Alarma " + onOff(v.get("alarm"))};
            default -> new String[] {twin.id, "", "", ""};
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
        return DEVICE_SIGNS.containsValue(spot) || BOARD.contains(spot)
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
        Twin twin = plugin.twin(lever.twin());
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
        playerEvent("player_join", event.getPlayer().getName());
        event.getPlayer().sendMessage(Component.text(
                "AgroCraft: este mundo es un gemelo digital IoT. Las palancas de la granja mandan comandos reales.",
                NamedTextColor.GREEN));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
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
