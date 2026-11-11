package co.agrocraft.bridge;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.plugin.Plugin;

/**
 * Plano de la granja. Todas las coordenadas son relativas al origen configurado; dy = 0 es el suelo.
 * build() levanta la estructura mínima para que los gemelos funcionen; el grupo puede decorarla después.
 */
final class Layout {
    record Box(int x1, int z1, int x2, int z2) {
        boolean has(int x, int z) {
            return x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }

        boolean border(int x, int z) {
            return x == x1 || x == x2 || z == z1 || z == z2;
        }
    }

    static final Box FARM = new Box(-6, -18, 30, 18);
    static final Box PLOT_A = new Box(4, 4, 12, 12);
    static final Box PLOT_B = new Box(16, 4, 24, 12);
    static final int CHANNEL_Z = 8;                         // canal de riego que cruza cada parcela
    static final Box TANK = new Box(4, -10, 8, -6);         // muros de vidrio; el agua ocupa el interior
    static final int TANK_LAYERS = 8;
    static final Box GREENHOUSE = new Box(16, -14, 24, -6);
    static final int GREENHOUSE_ROOF = 5;
    static final int VENT_Z = -10;                          // fila de ventilas en el techo
    static final int GATE_X = 14;

    final World world;
    final int ox;
    final int y0;
    final int oz;

    Layout(World world, int ox, int y0, int oz) {
        this.world = world;
        this.ox = ox;
        this.y0 = y0;
        this.oz = oz;
    }

    Block at(int dx, int dy, int dz) {
        return world.getBlockAt(ox + dx, y0 + dy, oz + dz);
    }

    boolean inFarm(Block block) {
        return block.getWorld().equals(world) && FARM.has(block.getX() - ox, block.getZ() - oz);
    }

    boolean inFarm(double x, double z) {
        return FARM.has((int) Math.floor(x) - ox, (int) Math.floor(z) - oz);
    }

    /** Mantiene cargados los chunks de la granja aunque no haya jugadores. */
    void keepLoaded(Plugin plugin) {
        for (int cx = (ox + FARM.x1()) >> 4; cx <= (ox + FARM.x2()) >> 4; cx++) {
            for (int cz = (oz + FARM.z1()) >> 4; cz <= (oz + FARM.z2()) >> 4; cz++) {
                world.addPluginChunkTicket(cx, cz, plugin);
            }
        }
    }

    /** Lámpara de redstone con su bloque de energía: encendida = bloque de redstone, apagada = piedra. */
    void lamp(int x, int lampDy, int z, int powerDy, boolean on) {
        at(x, powerDy, z).setType(on ? Material.REDSTONE_BLOCK : Material.STONE, false);
        Block lamp = at(x, lampDy, z);
        if (lamp.getType() != Material.REDSTONE_LAMP) {
            lamp.setType(Material.REDSTONE_LAMP, false);
        }
        Lightable data = (Lightable) lamp.getBlockData();
        data.setLit(on);
        lamp.setBlockData(data, false);
    }

    void build(boolean setSpawn) {
        List<Block> fences = new ArrayList<>();
        for (int x = FARM.x1(); x <= FARM.x2(); x++) {
            fences.add(at(x, 1, FARM.z1()));
            fences.add(at(x, 1, FARM.z2()));
        }
        for (int z = FARM.z1(); z <= FARM.z2(); z++) {
            fences.add(at(FARM.x1(), 1, z));
            fences.add(at(FARM.x2(), 1, z));
        }
        Block gate = at(GATE_X, 1, FARM.z2());
        for (Block fence : fences) {
            fence.setType(fence.equals(gate) ? Material.OAK_FENCE_GATE : Material.OAK_FENCE, false);
        }
        connectFences(fences);

        soil(PLOT_A);
        soil(PLOT_B);

        for (int x = TANK.x1(); x <= TANK.x2(); x++) {
            for (int z = TANK.z1(); z <= TANK.z2(); z++) {
                at(x, 0, z).setType(Material.STONE_BRICKS, false);
                for (int dy = 1; dy <= TANK_LAYERS; dy++) {
                    at(x, dy, z).setType(TANK.border(x, z) ? Material.GLASS : Material.AIR, false);
                }
            }
        }

        int doorX = (GREENHOUSE.x1() + GREENHOUSE.x2()) / 2;
        for (int x = GREENHOUSE.x1(); x <= GREENHOUSE.x2(); x++) {
            for (int z = GREENHOUSE.z1(); z <= GREENHOUSE.z2(); z++) {
                if (GREENHOUSE.border(x, z)) {
                    for (int dy = 1; dy < GREENHOUSE_ROOF; dy++) {
                        boolean door = x == doorX && z == GREENHOUSE.z2() && dy <= 2;
                        at(x, dy, z).setType(door ? Material.AIR : Material.GLASS, false);
                    }
                }
                boolean vent = z == VENT_Z && x > GREENHOUSE.x1() && x < GREENHOUSE.x2();
                at(x, GREENHOUSE_ROOF, z).setType(vent ? Material.OAK_TRAPDOOR : Material.GLASS, false);
            }
        }
        at(doorX, 1, -12).setType(Material.CAMPFIRE, false);        // calefacción
        lamp(doorX - 2, 1, -12, 0, false);
        lamp(doorX + 2, 1, -12, 0, false);

        at(14, 1, CHANNEL_Z).setType(Material.BARREL, false);       // silo de cosecha

        for (int dy = 1; dy <= 3; dy++) {                           // estación meteorológica
            at(0, dy, 0).setType(Material.OAK_FENCE, false);
        }
        at(0, 4, 0).setType(Material.DAYLIGHT_DETECTOR, false);

        at(2, 1, 2).setType(Material.STONE_BRICKS, false);          // poste de la alarma
        lamp(2, 3, 2, 2, false);

        lamp(8, 4, CHANNEL_Z, 5, false);                            // lámparas de crecimiento
        lamp(20, 4, CHANNEL_Z, 5, false);

        if (setSpawn) {
            world.setSpawnLocation(ox + GATE_X, y0 + 1, oz + FARM.z2() - 2);
        }
    }

    /** Corral de vacas y planta de proceso (se agregó en la versión 0.5 a granjas ya construidas). */
    void buildCorral() {
        Box c = CorralTwin.CORRAL;
        List<Block> fences = new ArrayList<>();
        for (int x = c.x1(); x <= c.x2(); x++) {
            for (int z = c.z1(); z <= c.z2(); z++) {
                at(x, 0, z).setType(Material.GRASS_BLOCK, false);
                Block above = at(x, 1, z);
                if (c.border(x, z)) {
                    fences.add(above);
                    above.setType(Material.SPRUCE_FENCE, false);
                } else {
                    above.setType(Material.AIR, false);
                }
            }
        }
        for (Block fence : fences) {
            if (fence.getBlockData() instanceof MultipleFacing data) {
                for (BlockFace side : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                    data.setFace(side, fence.getRelative(side).getType() == Material.SPRUCE_FENCE);
                }
                fence.setBlockData(data, false);
            }
        }
        at(CorralTwin.FEEDER[0], 1, CorralTwin.FEEDER[1]).setType(Material.COMPOSTER, false);
        at(CorralTwin.TROUGH[0], 1, CorralTwin.TROUGH[1]).setType(Material.WATER_CAULDRON, false);
        for (int x = -4; x <= 2; x++) {                     // piso de la planta de proceso
            for (int z = -4; z <= -1; z++) {
                at(x, 0, z).setType(Material.SMOOTH_STONE, false);
            }
        }
        at(CorralTwin.SMOKER[0], 1, CorralTwin.SMOKER[1]).setType(Material.SMOKER, false);
        at(CorralTwin.COLD_ROOM[0], 1, CorralTwin.COLD_ROOM[1]).setType(Material.BARREL, false);
    }

    private void soil(Box plot) {
        for (int x = plot.x1(); x <= plot.x2(); x++) {
            for (int z = plot.z1(); z <= plot.z2(); z++) {
                at(x, 0, z).setType(z == CHANNEL_Z ? Material.AIR : Material.FARMLAND, false);
                at(x, -1, z).setType(Material.DIRT, false);
            }
        }
    }

    private void connectFences(List<Block> fences) {
        BlockFace[] sides = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        for (Block fence : fences) {
            if (!(fence.getBlockData() instanceof MultipleFacing data)) {
                continue;
            }
            for (BlockFace side : sides) {
                Material neighbor = fence.getRelative(side).getType();
                data.setFace(side, neighbor == Material.OAK_FENCE || neighbor == Material.OAK_FENCE_GATE);
            }
            fence.setBlockData(data, false);
        }
    }
}
