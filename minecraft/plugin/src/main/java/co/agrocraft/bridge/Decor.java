package co.agrocraft.bridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.Axis;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;

/**
 * Arquitectura de la granja (versión 0.7). Diseño propio inspirado en granjas rurales: bordes de piedra,
 * pérgolas de riego, silo, torre del tanque, invernadero con marco de madera, establo, planta de proceso,
 * caseta de control, mirador y arco de entrada.
 *
 * Reglas para no alterar la captura de datos:
 *  - Nunca toca un bloque que lea o escriba un gemelo (parcelas, canal, tanque, techo y ventilas del
 *    invernadero, lámparas, comedero, bebedero, ahumador, cámara fría, letreros, palancas, puerta).
 *  - No pone fuentes de luz a menos de 16 bloques (distancia Manhattan) de los puntos donde los gemelos
 *    miden luz (parcelas, invernadero, estación): la luz de bloque cambiaría esas lecturas de noche.
 *  - No construye sobre las columnas de medición, así la luz del cielo no cambia.
 *  - Deja libre la fila z = 16, donde aparecen los intrusos simulados.
 */
final class Decor {
    static final int VERSION = 1;

    private static final int[][] LIGHT_SENSORS = {
        {Layout.PLOT_A.x1() + 2, 1, Layout.PLOT_A.z1() + 2},
        {Layout.PLOT_B.x1() + 2, 1, Layout.PLOT_B.z1() + 2},
        {(Layout.GREENHOUSE.x1() + Layout.GREENHOUSE.x2()) / 2, 2, Layout.VENT_Z},
        {0, 5, 0},
    };

    private final Layout l;
    private final Set<Long> protectedCells = new HashSet<>();
    private int placed;

    Decor(Layout layout) {
        this.l = layout;
        protect();
    }

    // ----------------------------------------------------------------------- celdas protegidas
    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    private void protectBox(Layout.Box box, int y1, int y2) {
        for (int x = box.x1(); x <= box.x2(); x++) {
            for (int z = box.z1(); z <= box.z2(); z++) {
                for (int y = y1; y <= y2; y++) {
                    protectedCells.add(key(x, y, z));
                }
            }
        }
    }

    private void protectColumn(int x, int z, int y1, int y2) {
        for (int y = y1; y <= y2; y++) {
            protectedCells.add(key(x, y, z));
        }
    }

    private void protect() {
        protectBox(Layout.PLOT_A, -1, 2);
        protectBox(Layout.PLOT_B, -1, 2);
        protectColumn(8, Layout.CHANNEL_Z, 3, 5);              // lámparas de crecimiento y su energía
        protectColumn(20, Layout.CHANNEL_Z, 3, 5);
        protectBox(Layout.TANK, 0, Layout.TANK_LAYERS);
        protectBox(new Layout.Box(Layout.GREENHOUSE.x1() + 1, Layout.GREENHOUSE.z1() + 1,
                Layout.GREENHOUSE.x2() - 1, Layout.GREENHOUSE.z2() - 1), -1, Layout.GREENHOUSE_ROOF + 1);
        protectBox(Layout.GREENHOUSE, Layout.GREENHOUSE_ROOF, Layout.GREENHOUSE_ROOF + 1);
        protectColumn((Layout.GREENHOUSE.x1() + Layout.GREENHOUSE.x2()) / 2, Layout.GREENHOUSE.z2(), 1, 2);   // puerta
        protectColumn(14, Layout.CHANNEL_Z, 1, 1);               // silo (barril)
        protectColumn(0, 0, 1, 12);                              // estación y su punto de luz
        protectColumn(2, 2, 1, 3);                               // poste y lámpara de alarma
        for (int[] spot : new int[][] {CorralTwin.FEEDER, CorralTwin.TROUGH, CorralTwin.SMOKER, CorralTwin.COLD_ROOM}) {
            protectColumn(spot[0], spot[1], 1, 1);
        }
        Layout.Box corral = CorralTwin.CORRAL;
        protectBox(new Layout.Box(corral.x1() + 1, corral.z1() + 1, corral.x2() - 1, corral.z2() - 1), 1, 3);
        int[][] fixtures = {{9, 13}, {21, 13}, {7, -5}, {19, -5}, {3, 3}, {-3, -5},       // palancas
            {8, 13}, {20, 13}, {6, -5}, {18, -5}, {14, 9}, {0, 1}, {2, 3}, {-1, -5}, {14, 15}};   // letreros
        for (int[] spot : fixtures) {
            protectColumn(spot[0], spot[1], 1, 1);
        }
        for (int x = 9; x <= 19; x += 2) {                       // tablero de la entrada
            protectColumn(x, 15, 1, 1);
        }
        protectColumn(Layout.GATE_X, Layout.FARM.z2(), 1, 2);    // puerta de la cerca
        for (int x = Layout.FARM.x1() + 1; x < Layout.FARM.x2(); x++) {   // fila de aparición de intrusos
            protectColumn(x, Layout.FARM.z2() - 2, 1, 2);
        }
        for (int[] s : LIGHT_SENSORS) {                          // columnas de medición de luz
            protectColumn(s[0], s[2], s[1], 40);
        }
    }

    // ----------------------------------------------------------------------------- utilidades
    private boolean set(int x, int y, int z, Material material) {
        if (protectedCells.contains(key(x, y, z))) {
            return false;
        }
        l.at(x, y, z).setType(material, false);
        placed++;
        return true;
    }

    private void set(int x, int y, int z, BlockData data) {
        if (protectedCells.contains(key(x, y, z))) {
            return;
        }
        l.at(x, y, z).setBlockData(data, false);
        placed++;
    }

    private void column(int x, int z, int y1, int y2, Material material) {
        for (int y = y1; y <= y2; y++) {
            set(x, y, z, material);
        }
    }

    private void log(int x, int y, int z, Material material, Axis axis) {
        BlockData data = material.createBlockData();
        if (data instanceof Orientable o) {
            o.setAxis(axis);
        }
        set(x, y, z, data);
    }

    private void slab(int x, int y, int z, Material material, Slab.Type type) {
        Slab data = (Slab) material.createBlockData();
        data.setType(type);
        set(x, y, z, data);
    }

    private void facing(int x, int y, int z, Material material, BlockFace face) {
        BlockData data = material.createBlockData();
        if (data instanceof Directional d) {
            d.setFacing(face);
        }
        set(x, y, z, data);
    }

    /** Ilumina solo lejos de los sensores de luz. */
    private void lantern(int x, int y, int z, boolean hanging) {
        for (int[] s : LIGHT_SENSORS) {
            if (Math.abs(x - s[0]) + Math.abs(y - s[1]) + Math.abs(z - s[2]) < 16) {
                return;
            }
        }
        Lantern data = (Lantern) Material.LANTERN.createBlockData();
        data.setHanging(hanging);
        set(x, y, z, data);
    }

    private void ground(int x, int z, Material material) {
        Material current = l.at(x, 0, z).getType();
        if (current == Material.GRASS_BLOCK || current == Material.DIRT || current == Material.DIRT_PATH
                || current == Material.MOSSY_STONE_BRICKS || current == Material.COBBLESTONE) {
            set(x, 0, z, material);
        }
    }

    // --------------------------------------------------------------------------- construcción
    int build() {
        placed = 0;
        paths();
        plotBorders();
        pergola(Layout.PLOT_A.x1() - 1, Layout.PLOT_A.x2() + 1, 8);
        pergola(Layout.PLOT_B.x1() - 1, Layout.PLOT_B.x2() + 1, 20);
        silo();
        tankTower();
        greenhouse();
        barn();
        processingPlant();
        station();
        securityCameras();
        controlBooth();
        entranceArch();
        lookout();
        perimeterLights();
        trees();
        return placed;
    }

    private void paths() {
        for (int z = Layout.FARM.z1() + 1; z < Layout.FARM.z2(); z++) {
            if (z < 3 || z > 13) {
                ground(14, z, Material.DIRT_PATH);           // eje norte-sur de la entrada al tanque
            }
        }
        for (int x = Layout.FARM.x1() + 1; x < Layout.FARM.x2(); x++) {
            ground(x, 0, Material.DIRT_PATH);                // eje este-oeste
            ground(x, 14, Material.DIRT_PATH);               // frente a las parcelas
        }
        for (int z = 1; z <= 13; z++) {
            ground(Layout.PLOT_B.x2() + 2, z, Material.DIRT_PATH);
            ground(Layout.PLOT_A.x1() - 2, z, Material.DIRT_PATH);
        }
    }

    private void plotBorders() {
        for (Layout.Box p : new Layout.Box[] {Layout.PLOT_A, Layout.PLOT_B}) {
            for (int x = p.x1() - 1; x <= p.x2() + 1; x++) {
                for (int z = p.z1() - 1; z <= p.z2() + 1; z++) {
                    if (x == p.x1() - 1 || x == p.x2() + 1 || z == p.z1() - 1 || z == p.z2() + 1) {
                        ground(x, z, (x + z) % 3 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                    }
                }
            }
        }
    }

    /** Pérgola de riego sobre el canal: postes en los extremos y viga a la altura de las lámparas. */
    private void pergola(int x1, int x2, int lampX) {
        int z = Layout.CHANNEL_Z;
        for (int x : new int[] {x1, x2}) {
            if (x == 13 || x == 15) {
                continue;                                    // ahí está la pared del silo
            }
            column(x, z, 1, 4, Material.SPRUCE_LOG);
        }
        for (int x = x1; x <= x2; x++) {
            if (x != lampX && x != 13 && x != 15) {
                log(x, 5, z, Material.STRIPPED_SPRUCE_LOG, Axis.X);
            }
        }
        for (int x = x1 + 2; x <= x2 - 2; x += 4) {         // aspersores colgando de la viga
            if (x != lampX) {
                set(x, 4, z, Material.IRON_CHAIN);
            }
        }
    }

    /** Silo de grano de 3 x 3 alrededor del barril, con bandas y cúpula de cobre. */
    private void silo() {
        int cx = 14, cz = Layout.CHANNEL_Z;
        for (int y = 1; y <= 9; y++) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                for (int z = cz - 1; z <= cz + 1; z++) {
                    if (x == cx && z == cz) {
                        continue;
                    }
                    if (x == cx && z == cz + 1 && y <= 2) {
                        if (y == 2) {
                            set(x, y, z, Material.GLASS_PANE);
                        }
                        continue;                            // letrero del silo
                    }
                    set(x, y, z, y % 3 == 0 ? Material.LIGHT_GRAY_CONCRETE : Material.WHITE_CONCRETE);
                }
            }
        }
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                set(x, 10, z, Material.WAXED_CUT_COPPER);
            }
        }
        slab(cx, 11, cz, Material.WAXED_CUT_COPPER_SLAB, Slab.Type.BOTTOM);
        column(cx + 1, cz + 2, 1, 1, Material.HOPPER);       // tolva de carga
    }

    /** Torre del tanque: pilares, aro superior, escalera, regla de nivel y tubería hacia las parcelas. */
    private void tankTower() {
        Layout.Box t = Layout.TANK;
        int top = Layout.TANK_LAYERS + 1;
        int[][] corners = {{t.x1() - 1, t.z1() - 1}, {t.x2() + 1, t.z1() - 1}, {t.x1() - 1, t.z2() + 1}, {t.x2() + 1, t.z2() + 1}};
        for (int[] c : corners) {
            column(c[0], c[1], 1, top, Material.STONE_BRICKS);
            set(c[0], 0, c[1], Material.CHISELED_STONE_BRICKS);
        }
        for (int x = t.x1() - 1; x <= t.x2() + 1; x++) {
            for (int z = t.z1() - 1; z <= t.z2() + 1; z++) {
                boolean ring = x == t.x1() - 1 || x == t.x2() + 1 || z == t.z1() - 1 || z == t.z2() + 1;
                if (ring) {
                    slab(x, top, z, Material.STONE_BRICK_SLAB, Slab.Type.BOTTOM);
                }
            }
        }
        for (int[] c : corners) {
            set(c[0], top, c[1], Material.CHISELED_STONE_BRICKS);
        }
        int ladderZ = (t.z1() + t.z2()) / 2;
        for (int y = 1; y <= top; y++) {
            facing(t.x1() - 1, y, ladderZ, Material.LADDER, BlockFace.WEST);
        }
        Material[] scale = {Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE};
        for (int y = 1; y <= Layout.TANK_LAYERS; y++) {      // regla de nivel: 25 % por color
            set(t.x2() + 1, y, ladderZ - 1, scale[Math.min(3, (y - 1) * 4 / Layout.TANK_LAYERS)]);
        }
        log(t.x2() + 1, 1, ladderZ + 1, Material.IRON_CHAIN, Axis.X);          // tubería al sur, a las parcelas
        for (int z = ladderZ + 1; z <= -1; z++) {      // hasta la red enterrada bajo el camino
            log(t.x2() + 2, 1, z, Material.IRON_CHAIN, Axis.Z);
        }
    }

    /** Invernadero: marco de roble oscuro sobre la caja de vidrio, zócalo de piedra y jardineras. */
    private void greenhouse() {
        Layout.Box g = Layout.GREENHOUSE;
        int midX = (g.x1() + g.x2()) / 2, midZ = (g.z1() + g.z2()) / 2;
        int[][] posts = {{g.x1(), g.z1()}, {g.x2(), g.z1()}, {g.x1(), g.z2()}, {g.x2(), g.z2()},
            {midX, g.z1()}, {g.x1(), midZ}, {g.x2(), midZ}};
        for (int[] p : posts) {
            column(p[0], p[1], 1, Layout.GREENHOUSE_ROOF - 1, Material.DARK_OAK_LOG);
        }
        for (int x = g.x1(); x <= g.x2(); x++) {
            for (int z = g.z1(); z <= g.z2(); z++) {
                if (g.border(x, z)) {
                    set(x, 0, z, Material.POLISHED_ANDESITE);
                }
            }
        }
        for (int x = g.x1(); x <= g.x2(); x += 2) {          // jardineras al norte
            set(x, 1, g.z1() - 1, x % 4 == 0 ? Material.FLOWERING_AZALEA : Material.AZALEA);
        }
    }

    /** Establo con techo a dos aguas sobre la mitad norte del corral; cubre comedero y bebedero. */
    private void barn() {
        Layout.Box c = CorralTwin.CORRAL;
        int north = c.z1(), south = c.z1() + 5;
        for (int x : new int[] {c.x1(), c.x2()}) {
            for (int z : new int[] {north, south}) {
                column(x, z, 1, 4, Material.SPRUCE_LOG);
            }
        }
        int ridge = (c.x1() + c.x2()) / 2;
        for (int x = c.x1() - 1; x <= c.x2() + 1; x++) {
            int d = Math.abs(x - ridge);                     // distancia a la cumbrera
            int half = (ridge - (c.x1() - 1)) - d;          // altura en medios bloques sobre y = 5
            int y = 5 + half / 2;
            Slab.Type type = half % 2 == 0 ? Slab.Type.BOTTOM : Slab.Type.TOP;
            for (int z = north - 1; z <= south + 1; z++) {
                slab(x, y, z, x == ridge ? Material.DARK_OAK_SLAB : Material.SPRUCE_SLAB, type);
            }
        }
        for (int x = c.x1() + 1; x < c.x2(); x++) {         // frontón norte
            int d = Math.abs(x - ridge);
            int topY = 5 + ((ridge - (c.x1() - 1)) - d) / 2 - 1;
            for (int y = 5; y <= topY; y++) {
                set(x, y, north, Material.SPRUCE_PLANKS);
            }
        }
        l.at(c.x1() + 1, 1, north + 1).setType(Material.HAY_BLOCK, false);     // pacas en la esquina del establo
        l.at(c.x2() - 1, 1, north + 1).setType(Material.HAY_BLOCK, false);
        l.at(c.x1() + 1, 2, north + 1).setType(Material.HAY_BLOCK, false);
    }

    /** Planta de proceso: muros de ladrillo, puerta al camino y techo de losa. */
    private void processingPlant() {
        int x1 = -4, x2 = 2, z1 = -4, z2 = -1;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean wall = x == x1 || x == x2 || z == z1 || z == z2;
                if (wall) {
                    boolean door = x == -2 && z == z2;
                    for (int y = 1; y <= 3; y++) {
                        if (door && y <= 2) {
                            continue;
                        }
                        boolean window = y == 2 && (x == x1 || x == x2) && z == -3;
                        set(x, y, z, window ? Material.GLASS_PANE : Material.BRICKS);
                    }
                }
                slab(x, 4, z, Material.SMOOTH_STONE_SLAB, Slab.Type.BOTTOM);
            }
        }
        set(1, 1, -2, Material.PACKED_ICE);                  // cámara fría
        set(1, 2, -3, Material.PACKED_ICE);
        set(-3, 1, -2, Material.CRAFTING_TABLE);
    }

    /** Estación meteorológica sobre plataforma, con pluviómetro al lado. */
    private void station() {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                set(x, 0, z, Material.POLISHED_ANDESITE);
            }
        }
        column(-1, 1, 1, 2, Material.IRON_BARS);
        slab(-1, 3, 1, Material.WAXED_CUT_COPPER_SLAB, Slab.Type.BOTTOM);
    }

    /** Cámaras (observadores) en las cuatro esquinas de la cerca, mirando hacia adentro. */
    private void securityCameras() {
        Layout.Box f = Layout.FARM;
        facing(f.x1(), 2, f.z1(), Material.OBSERVER, BlockFace.NORTH);
        facing(f.x2(), 2, f.z1(), Material.OBSERVER, BlockFace.NORTH);
        facing(f.x1(), 2, f.z2(), Material.OBSERVER, BlockFace.SOUTH);
        facing(f.x2(), 2, f.z2(), Material.OBSERVER, BlockFace.SOUTH);
    }

    /** Caseta de control junto a la parcela B. */
    private void controlBooth() {
        int x1 = 26, x2 = 29, z1 = 9, z2 = 13;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                set(x, 0, z, Material.SPRUCE_PLANKS);
                boolean wall = x == x1 || x == x2 || z == z1 || z == z2;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        boolean door = x == x1 && z == 11 && y <= 2;
                        boolean window = y == 2 && !door && (z == 11 || x == 27 || x == 28) && !(x == x1 && z == 11);
                        boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                        if (!door) {
                            set(x, y, z, corner ? Material.STRIPPED_SPRUCE_LOG : window ? Material.GLASS_PANE : Material.SPRUCE_PLANKS);
                        } else {
                            set(x, y, z, Material.AIR);
                        }
                    } else {
                        set(x, y, z, Material.AIR);
                    }
                }
                slab(x, 4, z, Material.DARK_OAK_SLAB, Slab.Type.BOTTOM);
            }
        }
        facing(28, 1, 10, Material.LECTERN, BlockFace.WEST);
        set(28, 1, 12, Material.BARREL);
    }

    /** Arco de entrada sobre la puerta de la cerca. */
    private void entranceArch() {
        int z = Layout.FARM.z2();
        for (int x : new int[] {Layout.GATE_X - 2, Layout.GATE_X + 2}) {
            column(x, z, 1, 4, Material.STONE_BRICKS);
            set(x, 0, z, Material.CHISELED_STONE_BRICKS);
        }
        for (int x = Layout.GATE_X - 2; x <= Layout.GATE_X + 2; x++) {
            log(x, 5, z, Material.SPRUCE_LOG, Axis.X);
        }
        lantern(Layout.GATE_X - 1, 4, z, true);
        lantern(Layout.GATE_X + 1, 4, z, true);
    }

    /** Mirador en la esquina noreste: buena vista para la demo. */
    private void lookout() {
        int x1 = 26, x2 = 28, z1 = -17, z2 = -15, top = 9;
        for (int x : new int[] {x1, x2}) {
            for (int z : new int[] {z1, z2}) {
                column(x, z, 1, top - 1, Material.SPRUCE_LOG);
            }
        }
        column(27, z2, 1, top - 1, Material.SPRUCE_PLANKS);
        for (int y = 1; y <= top; y++) {
            facing(27, y, z2 + 1, Material.LADDER, BlockFace.SOUTH);
        }
        for (int x = x1 - 1; x <= x2 + 1; x++) {
            for (int z = z1 - 1; z <= z2 + 1; z++) {
                if (x == 27 && z == z2 + 1) {
                    continue;                                // hueco de la escalera
                }
                set(x, top, z, Material.SPRUCE_PLANKS);
                boolean edge = x == x1 - 1 || x == x2 + 1 || z == z1 - 1 || z == z2 + 1;
                if (edge && !(x == 27 && z == z2 + 1)) {
                    set(x, top + 1, z, Material.SPRUCE_FENCE);
                }
            }
        }
    }

    /** Faroles sobre la cerca cada 6 bloques, solo donde no afectan los sensores de luz. */
    private void perimeterLights() {
        Layout.Box f = Layout.FARM;
        List<int[]> spots = new ArrayList<>();
        for (int x = f.x1(); x <= f.x2(); x += 6) {
            spots.add(new int[] {x, f.z1()});
            spots.add(new int[] {x, f.z2()});
        }
        for (int z = f.z1(); z <= f.z2(); z += 6) {
            spots.add(new int[] {f.x1(), z});
            spots.add(new int[] {f.x2(), z});
        }
        for (int[] s : spots) {
            Material post = l.at(s[0], 1, s[1]).getType();
            if ((post == Material.OAK_FENCE || post == Material.SPRUCE_FENCE)
                    && l.at(s[0], 2, s[1]).getType() == Material.AIR) {
                lantern(s[0], 2, s[1], false);
            }
        }
    }

    private void trees() {
        int[][] spots = {{-3, 9}, {-3, 13}, {28, -3}, {28, 4}, {11, -16}};
        for (int[] s : spots) {
            if (l.at(s[0], 1, s[1]).getType() == Material.AIR && l.at(s[0], 0, s[1]).getType() == Material.GRASS_BLOCK) {
                Location at = l.at(s[0], 1, s[1]).getLocation();
                if (l.world.generateTree(at, (s[0] + s[1]) % 2 == 0 ? TreeType.TREE : TreeType.BIRCH)) {
                    placed++;
                }
            }
        }
    }
}
