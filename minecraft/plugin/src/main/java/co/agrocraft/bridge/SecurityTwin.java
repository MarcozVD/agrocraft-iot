package co.agrocraft.bridge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;

import com.google.gson.JsonObject;

/**
 * Seguridad perimetral. Sensores: presencia hostil (PIR), personas en la zona y contacto del portón.
 * Actuadores: alarma (lámpara + campana) y portón.
 *
 * Sin jugadores no aparecen criaturas, así que de noche el gemelo simula una intrusión de vez en
 * cuando: un zombi real dentro de la cerca que se retira a los pocos minutos.
 */
final class SecurityTwin extends Twin {
    private static final double MEAN_SECONDS_BETWEEN_INTRUSIONS = 9000;

    private int hostiles;
    private int players;
    private long intruders;
    private UUID intruder;
    private long intruderUntil;

    private boolean alarm;
    private boolean auto = true;
    private boolean simulate = true;

    private Boolean shownAlarm;
    private double bellTimer;

    SecurityTwin(AgroCraftPlugin plugin, Farm farm) {
        super(plugin, farm, "seguridad", farm.simulated() ? 5 : 30);
    }

    private Block gate() {
        return layout.at(Layout.GATE_X, 1, Layout.FARM.z2());
    }

    @Override
    void step(double dt) {
        hostiles = 0;
        for (Monster monster : layout.world.getEntitiesByClass(Monster.class)) {
            Location where = monster.getLocation();
            if (!monster.isDead() && layout.inFarm(where.getX(), where.getZ())) {
                hostiles++;
            }
        }
        players = 0;
        for (Player player : layout.world.getPlayers()) {
            Location where = player.getLocation();
            if (layout.inFarm(where.getX(), where.getZ())) {
                players++;
            }
        }
        simulateIntrusion(dt);

        if (auto && alarm != hostiles > 0) {
            setAlarm(hostiles > 0, "auto");
        }
        if (shownAlarm == null || shownAlarm != alarm) {
            layout.lamp(Layout.SECURITY[0], 3, Layout.SECURITY[1], 2, alarm);
            shownAlarm = alarm;
        }
        if (alarm) {
            bellTimer += dt;
            if (bellTimer >= 3) {
                bellTimer = 0;
                layout.world.playSound(layout.at(Layout.SECURITY[0], 3, Layout.SECURITY[1]).getLocation(), Sound.BLOCK_BELL_USE, 3f, 1f);
            }
        }
    }

    private void simulateIntrusion(double dt) {
        long now = System.currentTimeMillis();
        if (intruder != null) {
            Entity entity = Bukkit.getEntity(intruder);
            if (entity == null || entity.isDead() || now >= intruderUntil) {
                if (entity != null) {
                    entity.remove();
                }
                intruder = null;
                publishEvent("intruder_gone", null);
            }
            return;
        }
        boolean night = in.night(layout.world) || in.intrusionBoost > 1;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (!simulate || !night || random.nextDouble() >= dt * in.intrusionBoost / MEAN_SECONDS_BETWEEN_INTRUSIONS) {
            return;
        }
        int x = random.nextInt(Layout.FARM.x1() + 2, Layout.FARM.x2() - 1);
        Location where = layout.at(x, 1, Layout.FARM.z2() - 2).getLocation().add(0.5, 0, 0.5);
        Zombie zombie = layout.world.spawn(where, Zombie.class);
        zombie.setRemoveWhenFarAway(false);
        zombie.setCanPickupItems(false);
        zombie.setShouldBurnInDay(false);     // en simulación puede ser "de noche" con sol en el mundo
        intruder = zombie.getUniqueId();
        intruderUntil = now + random.nextInt(4, 9) * 60_000L;
        intruders++;
        JsonObject data = new JsonObject();
        data.addProperty("kind", "zombie");
        data.addProperty("x", where.getBlockX());
        data.addProperty("z", where.getBlockZ());
        publishEvent("intruder", data);
    }

    private void setAlarm(boolean on, String reason) {
        if (alarm == on) {
            return;
        }
        alarm = on;
        JsonObject data = new JsonObject();
        data.addProperty("on", on ? 1 : 0);
        data.addProperty("reason", reason);
        data.addProperty("hostiles", hostiles);
        publishEvent("alarm", data);
        publishState();
    }

    @Override
    void sample(Map<String, Double> out) {
        out.put("hostiles_near", (double) hostiles);
        out.put("players_in_zone", (double) players);
        out.put("gate_open", gate().getBlockData() instanceof Openable data && data.isOpen() ? 1.0 : 0.0);
        out.put("alarm", alarm ? 1.0 : 0.0);
        out.put("intruders_total", (double) intruders);
    }

    @Override
    boolean actuate(String name, boolean value, StringBuilder detail) {
        switch (name) {
            case "alarm" -> {
                auto = false;       // una orden manual saca la alarma de automático
                setAlarm(value, "comando");
                detail.append("alarm=").append(value ? 1 : 0).append(" auto=0");
                return true;
            }
            case "gate" -> {
                Block gate = gate();
                if (!(gate.getBlockData() instanceof Openable data)) {
                    detail.append("no hay portón en su sitio");
                    return false;
                }
                data.setOpen(value);
                gate.setBlockData(data, false);
                detail.append("gate=").append(value ? 1 : 0);
                return true;
            }
            default -> {
                return super.actuate(name, value, detail);
            }
        }
    }

    @Override
    void writeConfig(JsonObject out) {
        out.addProperty("alarm", alarm ? 1 : 0);
        out.addProperty("auto", auto ? 1 : 0);
        out.addProperty("simulate", simulate ? 1 : 0);
    }

    @Override
    void readConfig(JsonObject in) {
        alarm = Json.flag(in, "alarm", alarm);
        auto = Json.flag(in, "auto", auto);
        simulate = Json.flag(in, "simulate", simulate);
    }

    @Override
    void resetConfig() {
        alarm = false;
        auto = true;
        simulate = true;
    }

    @Override
    void writeModel(JsonObject out) {
        out.addProperty("intruders", intruders);
        if (intruder != null) {
            out.addProperty("intruder", intruder.toString());
            out.addProperty("intruder_until", intruderUntil);
        }
    }

    @Override
    void readModel(JsonObject in) {
        intruders = (long) Json.num(in, "intruders", intruders);
        String saved = Json.text(in, "intruder");
        if (saved != null) {
            intruder = UUID.fromString(saved);
            intruderUntil = (long) Json.num(in, "intruder_until", 0);
        }
    }
}
