package co.agrocraft.bridge;

import java.util.Map;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.google.gson.JsonObject;

/**
 * Silo de cosecha: un barril real del juego. Sensor: nivel de llenado (celda de carga).
 * Actuador: descarga hacia el camión. Lo que un jugador meta o saque del barril también cuenta.
 */
final class SiloTwin extends Twin {
    private boolean auto = true;
    private double dispatchAt = 80;
    private long received;
    private long dispatched;

    SiloTwin(AgroCraftPlugin plugin) {
        super(plugin, "mc-silo", 60);
    }

    private Inventory inventory() {
        Block block = layout.at(14, 1, Layout.CHANNEL_Z);
        if (block.getType() != Material.BARREL) {
            block.setType(Material.BARREL, false);
        }
        return ((Container) block.getState()).getInventory();
    }

    private static int items(Inventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static int capacity(Inventory inventory) {
        return inventory.getSize() * 64;
    }

    /** Guarda la cosecha de una parcela; devuelve cuánto cupo. */
    int deposit(Material item, int amount) {
        Inventory inventory = inventory();
        int left = amount;
        while (left > 0) {
            int batch = Math.min(64, left);
            int rejected = 0;
            for (ItemStack rest : inventory.addItem(new ItemStack(item, batch)).values()) {
                rejected += rest.getAmount();
            }
            left -= batch - rejected;
            if (rejected > 0) {
                break;
            }
        }
        int stored = amount - left;
        received += stored;
        if (left > 0) {
            JsonObject data = new JsonObject();
            data.addProperty("lost", left);
            publishEvent("overflow", data);
        }
        return stored;
    }

    private void dispatch(String reason) {
        Inventory inventory = inventory();
        int items = items(inventory);
        inventory.clear();
        dispatched += items;
        JsonObject data = new JsonObject();
        data.addProperty("items", items);
        data.addProperty("reason", reason);
        publishEvent("dispatch", data);
    }

    @Override
    void step(double dt) {
        Inventory inventory = inventory();
        if (auto && items(inventory) * 100.0 / capacity(inventory) >= dispatchAt) {
            dispatch("auto");
        }
    }

    @Override
    void sample(Map<String, Double> out) {
        Inventory inventory = inventory();
        int items = items(inventory);
        double fullness = items / (double) capacity(inventory);
        out.put("fill_pct", fullness * 100);
        out.put("items", (double) items);
        out.put("comparator_lvl", items == 0 ? 0.0 : Math.floor(1 + fullness * 14));    // como un comparador de redstone
        out.put("received_total", (double) received);
        out.put("dispatched_total", (double) dispatched);
    }

    @Override
    boolean actuate(String name, boolean value, StringBuilder detail) {
        if (!name.equals("discharge")) {
            return super.actuate(name, value, detail);
        }
        if (value) {
            dispatch("comando");
        }
        detail.append("descarga ").append(value ? "ejecutada" : "sin cambio");
        return true;
    }

    @Override
    void writeConfig(JsonObject out) {
        out.addProperty("auto", auto ? 1 : 0);
        out.addProperty("dispatch_at", dispatchAt);
    }

    @Override
    void readConfig(JsonObject in) {
        auto = Json.flag(in, "auto", auto);
        dispatchAt = Json.clamp(Json.num(in, "dispatch_at", dispatchAt), 10, 100);
    }

    @Override
    void resetConfig() {
        auto = true;
        dispatchAt = 80;
    }

    @Override
    void writeModel(JsonObject out) {
        out.addProperty("received", received);
        out.addProperty("dispatched", dispatched);
    }

    @Override
    void readModel(JsonObject in) {
        received = (long) Json.num(in, "received", received);
        dispatched = (long) Json.num(in, "dispatched", dispatched);
    }
}
