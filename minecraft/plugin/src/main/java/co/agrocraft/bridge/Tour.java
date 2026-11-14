package co.agrocraft.bridge;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

/**
 * /agro tour      visita guiada en modo espectador por las zonas de la granja (para la sustentación).
 * /agro tour stop la termina y devuelve al jugador a donde estaba.
 * /agro decor     reconstruye la arquitectura de las dos granjas (solo operadores).
 *
 * Cada parada tiene una cámara (posición) y un punto al que mira; la cámara viaja suavemente entre
 * paradas y se queda quieta mientras se muestra el título. Todo es relativo al origen de cada granja.
 */
final class Tour implements CommandExecutor, Listener {
    record Stop(boolean sim, double cx, double cy, double cz, double tx, double ty, double tz, String title, String subtitle) {
    }

    private static final int MOVE_TICKS = 50;
    private static final int HOLD_TICKS = 110;

    private static final Layout.Box F = Layout.FARM;
    private static final List<Stop> STOPS = List.of(
        new Stop(false, Layout.GATE_X + 0.5, 6, F.z2() + 12, Layout.GATE_X, 3, F.z2() - 12,
                "AgroCraft", "17 dispositivos IoT · gemelo digital en Azure"),
        new Stop(false, Layout.SILO_X + 0.5, 16, Layout.CHANNEL_Z + 22, Layout.SILO_X + 0.5, 1, Layout.CHANNEL_Z,
                "Parcelas A y B", "humedad del suelo · riego automático · pronóstico de lluvia"),
        new Stop(false, Layout.SILO_X + 7, 10, Layout.CHANNEL_Z + 8, Layout.SILO_X + 0.5, 7, Layout.CHANNEL_Z + 0.5,
                "Silo de grano", "recibe la cosecha y alimenta el corral · mc-silo"),
        new Stop(false, Layout.TANK.cx() + 10, 11, Layout.TANK.z2() + 9, Layout.TANK.cx() + 0.5, 5, Layout.TANK.cz() + 0.5,
                "Tanque de agua", "nivel en vivo · bomba · lluvia real captada"),
        new Stop(false, Layout.GREENHOUSE.x2() + 9, 10, Layout.GREENHOUSE.z2() + 8, Layout.GREENHOUSE.cx() + 0.5, 3,
                Layout.GREENHOUSE.cz() + 0.5, "Invernadero", "temperatura, humedad, ventilas y calefacción"),
        new Stop(false, Layout.CORRAL.x2() + 12, 12, Layout.CORRAL.cz() + 6, Layout.CORRAL.cx() + 0.5, 2,
                Layout.CORRAL.cz() + 0.5, "Corral y establo", "THI · bienestar · comedero desde el silo · aspersores"),
        new Stop(false, Layout.PLANT.x2() + 8, 7, Layout.PLANT.z2() + 7, Layout.PLANT.cx() + 0.5, 1, Layout.PLANT.cz() + 0.5,
                "Planta de proceso", "ahumador y cámara fría · carne producida"),
        new Stop(false, Layout.STATION[0] + 7, 7, Layout.STATION[1] + 7, Layout.STATION[0] + 0.5, 3, Layout.STATION[1] + 0.5,
                "Estación meteorológica", "luz y lluvia del mundo · clima real de Bucaramanga"),
        new Stop(false, Layout.SECURITY[0] + 6, 6, Layout.SECURITY[1] + 6, Layout.SECURITY[0] + 0.5, 2, Layout.SECURITY[1] + 0.5,
                "Seguridad", "intrusos, monstruos y alarma · puerta controlada por MQTT"),
        new Stop(false, Layout.BOOTH.x1() - 8, 7, Layout.BOOTH.cz() + 7, Layout.BOOTH.cx() + 0.5, 2, Layout.BOOTH.cz() + 0.5,
                "Centro de control", "MQTT · AMQP · Grafana · reglas en la nube · IA"),
        new Stop(false, Layout.LOOKOUT.cx() + 0.5, 14, Layout.LOOKOUT.cz() + 0.5, F.cx(), 0, F.cz(),
                "Mirador", "toda la granja desde arriba"),
        new Stop(true, F.cx(), 45, F.z2() + 35, F.cx(), 0, F.cz(),
                "Granja de simulación", "olas de calor, sequía, tormentas e intrusos a 360x"),
        new Stop(false, F.cx(), 75, F.z2() + 65, F.cx(), 0, F.cz(),
                "Panorama", "datos reales de Bucaramanga moviendo el mundo"));

    private final AgroCraftPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private static final class Session {
        Location home;
        GameMode mode;
        BukkitTask task;
        int tick;
    }

    Tour(AgroCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "tour" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("La visita es para jugadores.");
                    return true;
                }
                if (args.length > 1 && args[1].equalsIgnoreCase("stop")) {
                    stop(player);
                } else {
                    start(player);
                }
                return true;
            }
            case "decor" -> {
                if (!sender.isOp()) {
                    sender.sendMessage(Component.text("Solo operadores.", NamedTextColor.RED));
                    return true;
                }
                int blocks = plugin.rebuildDecor();
                sender.sendMessage(Component.text("Arquitectura reconstruida: " + blocks + " bloques.", NamedTextColor.GREEN));
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("/agro tour [stop] · /agro decor", NamedTextColor.YELLOW));
                return true;
            }
        }
    }

    private Location camera(Stop stop, int index) {
        Layout l = stop.sim() ? plugin.simFarm.layout : plugin.realFarm.layout;
        Location at = new Location(l.world, l.ox + stop.cx(), l.y0 + stop.cy(), l.oz + stop.cz());
        Vector look = new Vector(l.ox + stop.tx(), l.y0 + stop.ty(), l.oz + stop.tz()).subtract(at.toVector());
        at.setDirection(look);
        return at;
    }

    private void start(Player player) {
        stop(player);
        Session session = new Session();
        session.home = player.getLocation();
        session.mode = player.getGameMode();
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(camera(STOPS.get(0), 0));
        session.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> step(player, session), 1L, 1L);
        sessions.put(player.getUniqueId(), session);
        Twin server = plugin.twin("mc-server");
        if (server != null) {
            com.google.gson.JsonObject data = new com.google.gson.JsonObject();
            data.addProperty("player", player.getName());
            server.publishEvent("tour_start", data);
        }
    }

    private void step(Player player, Session session) {
        if (!player.isOnline()) {
            finish(player, session);
            return;
        }
        int per = MOVE_TICKS + HOLD_TICKS;
        int index = session.tick / per;
        int phase = session.tick % per;
        if (index >= STOPS.size()) {
            finish(player, session);
            return;
        }
        Location target = camera(STOPS.get(index), index);
        if (phase < MOVE_TICKS && index > 0) {
            Location from = camera(STOPS.get(index - 1), index - 1);
            if (!from.getWorld().equals(target.getWorld())) {
                player.teleport(target);
            } else {
                double t = phase / (double) MOVE_TICKS;
                double s = t * t * (3 - 2 * t);                    // suavizado
                Location at = from.clone().add(target.clone().subtract(from).toVector().multiply(s));
                at.setYaw(lerpAngle(from.getYaw(), target.getYaw(), s));
                at.setPitch((float) (from.getPitch() + (target.getPitch() - from.getPitch()) * s));
                player.teleport(at);
            }
        } else if (phase == (index > 0 ? MOVE_TICKS : 0)) {
            player.teleport(target);
            Stop stop = STOPS.get(index);
            player.showTitle(Title.title(Component.text(stop.title(), NamedTextColor.GREEN),
                    Component.text(stop.subtitle(), NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(HOLD_TICKS * 50L - 900), Duration.ofMillis(500))));
        }
        session.tick++;
    }

    private static float lerpAngle(float a, float b, double t) {
        float diff = ((b - a) % 360 + 540) % 360 - 180;
        return (float) (a + diff * t);
    }

    private void stop(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            finish(player, session);
        }
    }

    private void finish(Player player, Session session) {
        session.task.cancel();
        sessions.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.teleport(session.home);
            player.setGameMode(session.mode);
            player.sendMessage(Component.text("Fin de la visita guiada.", NamedTextColor.GREEN));
        }
    }

    /** Si el jugador se va a mitad de la visita, vuelve a su modo y lugar. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            session.task.cancel();
            event.getPlayer().teleport(session.home);
            event.getPlayer().setGameMode(session.mode);
        }
    }

    void shutdown() {
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            Session session = sessions.remove(id);
            session.task.cancel();
            if (player != null) {
                player.teleport(session.home);
                player.setGameMode(session.mode);
            }
        }
    }
}
