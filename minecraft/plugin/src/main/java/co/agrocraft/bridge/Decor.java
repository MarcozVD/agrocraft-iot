package co.agrocraft.bridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;

/**
 * Arquitectura de la granja. Diseño propio inspirado en granjas rurales: caminos entre zonas, bordes de
 * piedra, pérgolas de riego, silo, torre del tanque, invernadero con marco de madera, establo, planta de
 * proceso, caseta de control, mirador y arco de entrada. Todas las posiciones salen de Layout.
 *
 * Reglas para no alterar la captura de datos:
 *  - Nunca toca un bloque que lea o escriba un gemelo (parcelas, canal, tanque, techo y ventilas del
 *    invernadero, lámparas, comedero, bebedero, ahumador, cámara fría, letreros, palancas, puerta).
 *  - No pone fuentes de luz a menos de 16 bloques (distancia Manhattan) de los puntos donde los gemelos
 *    miden luz (parcelas, invernadero, estación): la luz de bloque cambiaría esas lecturas de noche.
 *  - No construye sobre las columnas de medición, así la luz del cielo no cambia.
 *  - Deja libre la fila donde aparecen los intrusos simulados.
 */
final class Decor {
    static final int VERSION = 2;

    private static final int[][] LIGHT_SENSORS = {
        {Layout.PLOT_A.x1() + 2, 1, Layout.PLOT_A.z1() + 2},
        {Layout.PLOT_B.x1() + 2, 1, Layout.PLOT_B.z1() + 2},
        {Layout.GREENHOUSE.cx(), 2, Layout.VENT_Z},
        {Layout.STATION[0], 5, Layout.STATION[1]},
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
        protectColumn(Layout.LAMP_A_X, Layout.CHANNEL_Z, 3, 5);       // lámparas de crecimiento y su energía
        protectColumn(Layout.LAMP_B_X, Layout.CHANNEL_Z, 3, 5);
        protectBox(Layout.TANK, 0, Layout.TANK_LAYERS);
        Layout.Box g = Layout.GREENHOUSE;
        protectBox(new Layout.Box(g.x1() + 1, g.z1() + 1, g.x2() - 1, g.z2() - 1), -1, Layout.GREENHOUSE_ROOF + 1);
        protectBox(g, Layout.GREENHOUSE_ROOF, Layout.GREENHOUSE_ROOF + 1);
        protectColumn(g.cx(), g.z2(), 1, 2);                             // puerta
        protectColumn(Layout.SILO_X, Layout.CHANNEL_Z, 1, 1);           // silo (barril)
        protectColumn(Layout.STATION[0], Layout.STATION[1], 1, 4);
        protectColumn(Layout.SECURITY[0], Layout.SECURITY[1], 1, 3);    // poste y lámpara de alarma
        for (int[] spot : new int[][] {CorralTwin.FEEDER, CorralTwin.TROUGH, CorralTwin.SMOKER, CorralTwin.COLD_ROOM}) {
            protectColumn(spot[0], spot[1], 1, 1);
        }
        Layout.Box c = Layout.CORRAL;
        protectBox(new Layout.Box(c.x1() + 1, c.z1() + 1, c.x2() - 1, c.z2() - 1), 1, 3);
        for (Displays.Spot spot : Displays.fixtures()) {                // letreros, palancas y tablero
            protectColumn(spot.x(), spot.z(), 1, 1);
        }
        protectColumn(Layout.GATE_X, Layout.FARM.z2(), 1, 2);           // puerta de la cerca
        for (int x = Layout.FARM.x1() + 1; x < Layout.FARM.x2(); x++) { // fila de aparición de intrusos
            protectColumn(x, Layout.FARM.z2() - 2, 1, 2);
        }
        for (int[] s : LIGHT_SENSORS) {                                 // columnas de medición de luz
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

    private void force(int x, int y, int z, Material material) {
        l.at(x, y, z).setType(material, false);
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

    private static boolean nearLightSensor(int x, int y, int z) {
        for (int[] s : LIGHT_SENSORS) {
            if (Math.abs(x - s[0]) + Math.abs(y - s[1]) + Math.abs(z - s[2]) < 16) {
                return true;
            }
        }
        return false;
    }

    /** Ilumina solo lejos de los sensores de luz. */
    private void lantern(int x, int y, int z, boolean hanging) {
        if (nearLightSensor(x, y, z)) {
            return;
        }
        Lantern data = (Lantern) Material.LANTERN.createBlockData();
        data.setHanging(hanging);
        set(x, y, z, data);
    }

    private void ground(int x, int z, Material material) {
        Material current = l.at(x, 0, z).getType();
        if (current == Material.GRASS_BLOCK || current == Material.DIRT || current == Material.DIRT_PATH) {
            set(x, 0, z, material);
        }
    }

    private void pathX(int z, int x1, int x2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            ground(x, z, Material.DIRT_PATH);
        }
    }

    private void pathZ(int x, int z1, int z2) {
        for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
            ground(x, z, Material.DIRT_PATH);
        }
    }

    // --------------------------------------------------------------------------- construcción
    int build() {
        placed = 0;
        plotBorders();
        paths();
        pergola(Layout.PLOT_A, Layout.LAMP_A_X);
        pergola(Layout.PLOT_B, Layout.LAMP_B_X);
        silo();
        tankTower();
        greenhouse();
        barn();
        processingPlant();
        station();
        securityPost();
        securityCameras();
        controlBooth();
        entranceArch();
        lookout();
        perimeterLights();
        pathLights();
        trees();
        return placed;
    }

    private void paths() {
        Layout.Box f = Layout.FARM;
        int west = f.x1() + 2, east = f.x2() - 2;
        pathX(0, west, east);                                         // avenida central este-oeste
        pathX(24, west, east);                                        // avenida sur
        pathZ(Layout.GATE_X, f.z1() + 2, f.z2() - 1);                 // eje norte-sur desde la entrada
        for (Layout.Box p : new Layout.Box[] {Layout.PLOT_A, Layout.PLOT_B}) {
            pathZ(p.x1() + 1, 1, p.z1() - 2);
            pathZ(p.x1() + 1, p.z2() + 2, 23);
        }
        pathZ(Layout.TANK.cx() - 1, Layout.TANK.z2() + 2, -1);
        pathZ(Layout.GREENHOUSE.cx(), Layout.GREENHOUSE.z2() + 1, -1);
        pathZ(Layout.CORRAL.x2() + 3, Layout.CORRAL.z2(), -1);       // entre corral y planta hasta la avenida
        pathX(Layout.CORRAL.z2() + 2, Layout.CORRAL.x1(), Layout.CORRAL.x2() + 3);
        pathX(Layout.PLANT.z2() + 1, Layout.PLANT.x1(), Layout.CORRAL.x2() + 3);
        pathZ(Layout.STATION[0], 25, Layout.STATION[1] - 2);
        pathZ(Layout.SECURITY[0], 25, Layout.SECURITY[1] - 1);
        pathX(Layout.BOOTH.cz(), Layout.PLOT_B.x2() + 2, Layout.BOOTH.x1() - 1);
        pathZ(Layout.BOOTH.x1() - 2, 1, Layout.BOOTH.cz());
        pathZ(Layout.LOOKOUT.cx(), Layout.LOOKOUT.z2() + 2, -1);
    }

    private void plotBorders() {
        for (Layout.Box p : new Layout.Box[] {Layout.PLOT_A, Layout.PLOT_B}) {
            for (int x = p.x1() - 1; x <= p.x2() + 1; x++) {
                for (int z = p.z1() - 1; z <= p.z2() + 1; z++) {
                    if (x == p.x1() - 1 || x == p.x2() + 1 || z == p.z1() - 1 || z == p.z2() + 1) {
                        set(x, 0, z, Math.floorMod(x + z, 3) == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                    }
                }
            }
        }
    }

    /** Pérgola de riego sobre el canal: postes en los extremos y viga a la altura de las lámparas. */
    private void pergola(Layout.Box plot, int lampX) {
        int z = Layout.CHANNEL_Z, x1 = plot.x1() - 1, x2 = plot.x2() + 1;
        column(x1, z, 1, 4, Material.SPRUCE_LOG);
        column(x2, z, 1, 4, Material.SPRUCE_LOG);
        for (int x = x1; x <= x2; x++) {
            if (x != lampX) {
                log(x, 5, z, Material.STRIPPED_SPRUCE_LOG, Axis.X);
            }
        }
        for (int x = x1 + 2; x <= x2 - 2; x += 3) {         // aspersores colgando de la viga
            if (Math.abs(x - lampX) > 1) {
                set(x, 4, z, Material.IRON_CHAIN);
            }
        }
    }

    /** Silo de grano de 3 x 3 alrededor del barril, con bandas y cúpula de cobre. */
    private void silo() {
        int cx = Layout.SILO_X, cz = Layout.CHANNEL_Z;
        for (int x = cx - 2; x <= cx + 2; x++) {             // base de piedra
            for (int z = cz - 2; z <= cz + 2; z++) {
                set(x, 0, z, Material.POLISHED_ANDESITE);
            }
        }
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
        set(cx + 1, 1, cz + 2, Material.HOPPER);             // tolva de carga
        set(cx - 1, 1, cz + 2, Material.BARREL);
    }

    /** Torre del tanque: pilares, aro superior, escalera, regla de nivel y tubería hacia el camino. */
    private void tankTower() {
        Layout.Box t = Layout.TANK;
        int top = Layout.TANK_LAYERS + 1;
        int[][] corners = {{t.x1() - 1, t.z1() - 1}, {t.x2() + 1, t.z1() - 1}, {t.x1() - 1, t.z2() + 1}, {t.x2() + 1, t.z2() + 1}};
        for (int x = t.x1() - 2; x <= t.x2() + 2; x++) {    // plataforma de piedra
            for (int z = t.z1() - 2; z <= t.z2() + 2; z++) {
                set(x, 0, z, Material.STONE_BRICKS);
            }
        }
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
        int ladderZ = t.cz();
        for (int y = 1; y <= top; y++) {
            facing(t.x1() - 1, y, ladderZ, Material.LADDER, BlockFace.WEST);
        }
        Material[] scale = {Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE};
        for (int y = 1; y <= Layout.TANK_LAYERS; y++) {      // regla de nivel: 25 % por color
            set(t.x2() + 1, y, ladderZ - 1, scale[Math.min(3, (y - 1) * 4 / Layout.TANK_LAYERS)]);
        }
        log(t.x2() + 1, 1, ladderZ + 1, Material.IRON_CHAIN, Axis.X);     // tubería hasta la avenida
        for (int z = ladderZ + 1; z <= -1; z++) {
            log(t.x2() + 2, 1, z, Material.IRON_CHAIN, Axis.Z);
        }
    }

    /** Invernadero: marco de roble oscuro sobre la caja de vidrio, zócalo de piedra y jardineras. */
    private void greenhouse() {
        Layout.Box g = Layout.GREENHOUSE;
        int[][] posts = {{g.x1(), g.z1()}, {g.x2(), g.z1()}, {g.x1(), g.z2()}, {g.x2(), g.z2()},
            {g.cx(), g.z1()}, {g.x1(), g.cz()}, {g.x2(), g.cz()}};
        for (int[] p : posts) {
            column(p[0], p[1], 1, Layout.GREENHOUSE_ROOF - 1, Material.DARK_OAK_LOG);
        }
        for (int x = g.x1() - 1; x <= g.x2() + 1; x++) {
            for (int z = g.z1() - 1; z <= g.z2() + 1; z++) {
                if (x == g.x1() - 1 || x == g.x2() + 1 || z == g.z1() - 1 || z == g.z2() + 1 || g.border(x, z)) {
                    set(x, 0, z, Material.POLISHED_ANDESITE);
                }
            }
        }
        for (int x = g.x1(); x <= g.x2(); x += 2) {          // jardineras al norte y al oeste
            set(x, 1, g.z1() - 2, Math.floorMod(x, 4) == 0 ? Material.FLOWERING_AZALEA : Material.AZALEA);
        }
        for (int z = g.z1(); z <= g.z2(); z += 2) {
            set(g.x1() - 2, 1, z, Math.floorMod(z, 4) == 0 ? Material.FLOWERING_AZALEA : Material.AZALEA);
        }
    }

    /** Establo con techo a dos aguas sobre la parte norte del corral; cubre comedero y bebedero. */
    private void barn() {
        Layout.Box c = Layout.CORRAL;
        int north = c.z1(), south = c.z1() + 6;
        for (int x : new int[] {c.x1(), c.x2()}) {
            for (int z : new int[] {north, north + 3, south}) {
                column(x, z, 1, 4, Material.SPRUCE_LOG);
            }
        }
        int ridge = c.cx();
        int rise = ridge - (c.x1() - 1);
        for (int x = c.x1() - 1; x <= c.x2() + 1; x++) {
            int half = rise - Math.abs(x - ridge);            // altura en medios bloques sobre y = 5
            int y = 5 + half / 2;
            Slab.Type type = half % 2 == 0 ? Slab.Type.BOTTOM : Slab.Type.TOP;
            for (int z = north - 1; z <= south + 1; z++) {
                slab(x, y, z, x == ridge ? Material.DARK_OAK_SLAB : Material.SPRUCE_SLAB, type);
            }
        }
        for (int x = c.x1() + 1; x < c.x2(); x++) {         // frontón norte
            int topY = 5 + (rise - Math.abs(x - ridge)) / 2 - 1;
            for (int y = 5; y <= topY; y++) {
                set(x, y, north, Material.SPRUCE_PLANKS);
            }
        }
        force(c.x1() + 1, 1, north + 1, Material.HAY_BLOCK);    // pacas en las esquinas del establo
        force(c.x1() + 1, 2, north + 1, Material.HAY_BLOCK);
        force(c.x2() - 1, 1, north + 1, Material.HAY_BLOCK);
        for (int x = c.x1() - 1; x <= c.x2() + 1; x++) {    // borde de tierra gruesa al frente
            ground(x, c.z2() + 1, Material.COARSE_DIRT);
        }
    }

    /** Planta de proceso: muros de ladrillo, puerta al camino y techo de losa. */
    private void processingPlant() {
        Layout.Box p = Layout.PLANT;
        for (int x = p.x1(); x <= p.x2(); x++) {
            for (int z = p.z1(); z <= p.z2(); z++) {
                if (p.border(x, z)) {
                    boolean door = x == p.x1() + 2 && z == p.z2();
                    for (int y = 1; y <= 3; y++) {
                        if (door && y <= 2) {
                            continue;
                        }
                        boolean window = y == 2 && (x == p.x1() || x == p.x2()) && z == p.z1() + 1;
                        set(x, y, z, window ? Material.GLASS_PANE : Material.BRICKS);
                    }
                }
                slab(x, 4, z, Material.SMOOTH_STONE_SLAB, Slab.Type.BOTTOM);
            }
        }
        set(p.x1() + 5, 1, p.z1() + 2, Material.PACKED_ICE);     // cámara fría
        set(p.x1() + 5, 2, p.z1() + 1, Material.PACKED_ICE);
        set(p.x1() + 1, 1, p.z1() + 2, Material.CRAFTING_TABLE);
    }

    /** Estación meteorológica sobre plataforma, con pluviómetro al lado. */
    private void station() {
        int sx = Layout.STATION[0], sz = Layout.STATION[1];
        for (int x = sx - 2; x <= sx + 2; x++) {
            for (int z = sz - 2; z <= sz + 2; z++) {
                set(x, 0, z, Math.abs(x - sx) == 2 || Math.abs(z - sz) == 2 ? Material.STONE_BRICKS : Material.POLISHED_ANDESITE);
            }
        }
        column(sx - 1, sz + 1, 1, 2, Material.IRON_BARS);
        slab(sx - 1, 3, sz + 1, Material.WAXED_CUT_COPPER_SLAB, Slab.Type.BOTTOM);
    }

    /** Puesto de seguridad: base de piedra y bandera de aviso. */
    private void securityPost() {
        int sx = Layout.SECURITY[0], sz = Layout.SECURITY[1];
        for (int x = sx - 1; x <= sx + 1; x++) {
            for (int z = sz - 1; z <= sz + 1; z++) {
                set(x, 0, z, Material.POLISHED_ANDESITE);
            }
        }
        column(sx - 1, sz - 1, 1, 4, Material.IRON_BARS);
        set(sx - 1, 5, sz - 1, Material.RED_WOOL);
    }

    /** Cámaras (observadores) en las cuatro esquinas de la cerca, mirando hacia adentro. */
    private void securityCameras() {
        Layout.Box f = Layout.FARM;
        facing(f.x1(), 2, f.z1(), Material.OBSERVER, BlockFace.NORTH);
        facing(f.x2(), 2, f.z1(), Material.OBSERVER, BlockFace.NORTH);
        facing(f.x1(), 2, f.z2(), Material.OBSERVER, BlockFace.SOUTH);
        facing(f.x2(), 2, f.z2(), Material.OBSERVER, BlockFace.SOUTH);
    }

    /** Caseta de control al este de la parcela B. */
    private void controlBooth() {
        Layout.Box b = Layout.BOOTH;
        int doorZ = b.cz();
        for (int x = b.x1(); x <= b.x2(); x++) {
            for (int z = b.z1(); z <= b.z2(); z++) {
                set(x, 0, z, Material.SPRUCE_PLANKS);
                boolean wall = b.border(x, z);
                for (int y = 1; y <= 3; y++) {
                    if (!wall) {
                        set(x, y, z, Material.AIR);
                        continue;
                    }
                    boolean door = x == b.x1() && z == doorZ && y <= 2;
                    boolean corner = (x == b.x1() || x == b.x2()) && (z == b.z1() || z == b.z2());
                    boolean window = y == 2 && !corner && !(x == b.x1() && z == doorZ);
                    set(x, y, z, door ? Material.AIR : corner ? Material.STRIPPED_SPRUCE_LOG
                            : window ? Material.GLASS_PANE : Material.SPRUCE_PLANKS);
                }
                slab(x, 4, z, Material.DARK_OAK_SLAB, Slab.Type.BOTTOM);
            }
        }
        facing(b.x2() - 1, 1, b.z1() + 1, Material.LECTERN, BlockFace.WEST);
        set(b.x2() - 1, 1, b.z2() - 1, Material.BARREL);
        lantern(b.x1() - 1, 3, doorZ - 1, false);
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
        Layout.Box o = Layout.LOOKOUT;
        int top = 11;
        for (int x : new int[] {o.x1(), o.x2()}) {
            for (int z : new int[] {o.z1(), o.z2()}) {
                column(x, z, 1, top - 1, Material.SPRUCE_LOG);
            }
        }
        column(o.cx(), o.z2(), 1, top - 1, Material.SPRUCE_PLANKS);
        for (int y = 1; y <= top; y++) {
            facing(o.cx(), y, o.z2() + 1, Material.LADDER, BlockFace.SOUTH);
        }
        for (int x = o.x1() - 1; x <= o.x2() + 1; x++) {
            for (int z = o.z1() - 1; z <= o.z2() + 1; z++) {
                if (x == o.cx() && z == o.z2() + 1) {
                    continue;                                // hueco de la escalera
                }
                set(x, top, z, Material.SPRUCE_PLANKS);
                boolean edge = x == o.x1() - 1 || x == o.x2() + 1 || z == o.z1() - 1 || z == o.z2() + 1;
                if (edge) {
                    set(x, top + 1, z, Material.SPRUCE_FENCE);
                }
            }
        }
        lantern(o.x1() - 1, top + 2, o.z1() - 1, false);
        lantern(o.x2() + 1, top + 2, o.z1() - 1, false);
    }

    /** Faroles sobre la cerca cada 8 bloques, solo donde no afectan los sensores de luz. */
    private void perimeterLights() {
        Layout.Box f = Layout.FARM;
        List<int[]> spots = new ArrayList<>();
        for (int x = f.x1(); x <= f.x2(); x += 8) {
            spots.add(new int[] {x, f.z1()});
            spots.add(new int[] {x, f.z2()});
        }
        for (int z = f.z1(); z <= f.z2(); z += 8) {
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

    /** Postes con farol junto a las avenidas (también evitan que aparezcan monstruos). */
    private void pathLights() {
        Layout.Box f = Layout.FARM;
        for (int x = f.x1() + 4; x < f.x2() - 2; x += 10) {
            lampPost(x, 1);
            lampPost(x, 25);
        }
        for (int z = f.z1() + 4; z < f.z2() - 4; z += 10) {
            lampPost(Layout.GATE_X + 1, z);
        }
    }

    private void lampPost(int x, int z) {
        if (nearLightSensor(x, 3, z) || protectedCells.contains(key(x, 1, z))
                || l.at(x, 0, z).getType() != Material.GRASS_BLOCK
                || !l.at(x, 1, z).getType().isAir() || !l.at(x, 2, z).getType().isAir()) {
            return;
        }
        set(x, 1, z, Material.SPRUCE_FENCE);
        set(x, 2, z, Material.SPRUCE_FENCE);
        lantern(x, 3, z, false);
    }

    private void trees() {
        int[][] spots = {{-24, 6}, {-24, 32}, {-32, 34}, {-20, 14}, {46, -6}, {46, 30}, {60, 4}, {44, -32},
            {6, -38}, {-10, -40}, {66, 26}, {-40, -40}, {-6, -24}, {40, 6}};
        for (int[] s : spots) {
            if (l.at(s[0], 1, s[1]).getType() == Material.AIR && l.at(s[0], 0, s[1]).getType() == Material.GRASS_BLOCK) {
                Location at = l.at(s[0], 1, s[1]).getLocation();
                if (l.world.generateTree(at, Math.floorMod(s[0] + s[1], 3) == 0 ? TreeType.BIRCH : TreeType.TREE)) {
                    placed++;
                }
            }
        }
    }
}
