package co.agrocraft.bridge;

import java.util.Map;

import org.bukkit.Bukkit;

/** Salud del gemelo: equivale al monitoreo del gateway. Si el servidor se degrada, los demás datos pierden valor. */
final class ServerTwin extends Twin {
    ServerTwin(AgroCraftPlugin plugin) {
        super(plugin, "mc-server", 30);
    }

    @Override
    void step(double dt) {
    }

    @Override
    void sample(Map<String, Double> out) {
        Runtime runtime = Runtime.getRuntime();
        out.put("tps", Math.min(20.0, Bukkit.getTPS()[0]));
        out.put("mspt", Bukkit.getAverageTickTime());
        out.put("players_online", (double) Bukkit.getOnlinePlayers().size());
        out.put("heap_mb", (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0);
        out.put("loaded_chunks", (double) layout.world.getLoadedChunks().length);
        out.put("entities", (double) layout.world.getEntities().size());
    }
}
