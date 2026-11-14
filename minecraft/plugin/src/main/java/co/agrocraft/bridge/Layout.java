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
 * Es la única fuente de posiciones: gemelos, letreros, hologramas, marcadores, arquitectura y visita
 * guiada leen de aquí.
 *
 * Versión 2 del plano (plugin 0.8): zonas separadas por 12-16 bloques y unidas por caminos.
 *
 *          N (z negativo)
 *   corral + establo      tanque           invernadero              mirador
 *   planta de proceso
 *   ---------------------------- camino z = 0 ----------------------------
 *                         parcela A  silo  parcela B      caseta de control
 *   ---------------------------- camino z = 24 ---------------------------
 *   estación              seguridad   entrada (puerta, tablero)
 *          S (z positivo)
 */
final class Layout {
    record Box(int x1, int z1, int x2, int z2) {
        boolean has(int x, int z) {
            return x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }

        boolean border(int x, int z) {
            return x == x1 || x == x2 || z == z1 || z == z2;
        }

        int cx() {
            return (x1 + x2) / 2;
        }

        int cz() {
            return (z1 + z2) / 2;
        }
    }

    static final int VERSION = 2;

    static final Box FARM = new Box(-44, -48, 72, 40);
    static final Box PLOT_A = new Box(-6, 10, 2, 18);
    static final Box PLOT_B = new Box(30, 10, 38, 18);
    static final int CHANNEL_Z = 14;                        // canal de riego que cruza cada parcela
    static final int LAMP_A_X = PLOT_A.cx();
    static final int LAMP_B_X = PLOT_B.cx();
    static final int SILO_X = 16;                           // barril del silo, sobre el canal
    static final Box TANK = new Box(0, -18, 4, -14);        // muros de vidrio; el agua ocupa el interior
    static final int TANK_LAYERS = 8;
    static final Box GREENHOUSE = new Box(24, -24, 32, -16);
    static final int GREENHOUSE_ROOF = 5;
    static final int VENT_Z = GREENHOUSE.cz();              // fila de ventilas en el techo
    static final int HEATER_Z = GREENHOUSE.z1() + 2;        // fogata y lámparas de calefacción
    static final Box CORRAL = new Box(-38, -30, -28, -14);
    static final Box PLANT = new Box(-38, -6, -32, -3);     // planta de proceso
    static final int[] STATION = {-14, 30};
    static final int[] SECURITY = {8, 32};                  // poste y lámpara de alarma
    static final int GATE_X = 16;
    static final Box BOOTH = new Box(52, 12, 55, 16);       // caseta de control
    static final Box LOOKOUT = new Box(62, -42, 64, -40);   // mirador

    /** Plano anterior (v1): se limpia al migrar. */
    static final Box LEGACY = new Box(-8, -20, 32, 20);

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

    /** Deja una caja como terreno plano: pasto, tierra debajo y aire encima. */
    void clear(Box box, int height) {
        for (int x = box.x1(); x <= box.x2(); x++) {
            for (int z = box.z1(); z <= box.z2(); z++) {
                at(x, -1, z).setType(Material.DIRT, false);
                at(x, 0, z).setType(Material.GRASS_BLOCK, false);
                for (int dy = 1; dy <= height; dy++) {
                    Block block = at(x, dy, z);
                    if (!block.getType().isAir()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
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

        int doorX = GREENHOUSE.cx();
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
        at(doorX, 1, HEATER_Z).setType(Material.CAMPFIRE, false);   // calefacción
        lamp(doorX - 2, 1, HEATER_Z, 0, false);
        lamp(doorX + 2, 1, HEATER_Z, 0, false);

        at(SILO_X, 1, CHANNEL_Z).setType(Material.BARREL, false);   // silo de cosecha

        for (int dy = 1; dy <= 3; dy++) {                           // estación meteorológica
            at(STATION[0], dy, STATION[1]).setType(Material.OAK_FENCE, false);
        }
        at(STATION[0], 4, STATION[1]).setType(Material.DAYLIGHT_DETECTOR, false);

        at(SECURITY[0], 1, SECURITY[1]).setType(Material.STONE_BRICKS, false);   // poste de la alarma
        lamp(SECURITY[0], 3, SECURITY[1], 2, false);

        lamp(LAMP_A_X, 4, CHANNEL_Z, 5, false);                     // lámparas de crecimiento
        lamp(LAMP_B_X, 4, CHANNEL_Z, 5, false);

        if (setSpawn) {
            world.setSpawnLocation(ox + GATE_X, y0 + 1, oz + FARM.z2() - 2);
        }
    }

    /** Corral de vacas y planta de proceso. */
    void buildCorral() {
        Box c = CORRAL;
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
        for (int x = PLANT.x1(); x <= PLANT.x2(); x++) {          // piso de la planta de proceso
            for (int z = PLANT.z1(); z <= PLANT.z2(); z++) {
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
