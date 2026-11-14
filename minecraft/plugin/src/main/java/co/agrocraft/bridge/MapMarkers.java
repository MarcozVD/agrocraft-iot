package co.agrocraft.bridge;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.Bukkit;

import xyz.jpenilla.squaremap.api.BukkitAdapter;
import xyz.jpenilla.squaremap.api.Key;
import xyz.jpenilla.squaremap.api.MapWorld;
import xyz.jpenilla.squaremap.api.Point;
import xyz.jpenilla.squaremap.api.SimpleLayerProvider;
import xyz.jpenilla.squaremap.api.Squaremap;
import xyz.jpenilla.squaremap.api.SquaremapProvider;
import xyz.jpenilla.squaremap.api.marker.Marker;
import xyz.jpenilla.squaremap.api.marker.MarkerOptions;

/**
 * Marcadores en vivo en el mapa web (squaremap): un círculo por gemelo, coloreado según su estado
 * (verde normal, naranja atención, rojo alerta) y con sus lecturas al pasar el cursor.
 * Si squaremap no está instalado, la clase no se usa.
 */
final class MapMarkers {
    private static final Map<String, double[]> SPOTS = new LinkedHashMap<>();       // sufijo -> {x, z, radio}

    static {
        SPOTS.put("parcela-a", new double[] {Layout.PLOT_A.cx() + 0.5, Layout.PLOT_A.cz() + 0.5, 4});
        SPOTS.put("parcela-b", new double[] {Layout.PLOT_B.cx() + 0.5, Layout.PLOT_B.cz() + 0.5, 4});
        SPOTS.put("tanque", new double[] {Layout.TANK.cx() + 0.5, Layout.TANK.cz() + 0.5, 2.5});
        SPOTS.put("invernadero", new double[] {Layout.GREENHOUSE.cx() + 0.5, Layout.GREENHOUSE.cz() + 0.5, 4});
        SPOTS.put("silo", new double[] {Layout.SILO_X + 0.5, Layout.CHANNEL_Z + 0.5, 1.5});
        SPOTS.put("estacion", new double[] {Layout.STATION[0] + 0.5, Layout.STATION[1] + 0.5, 1.5});
        SPOTS.put("seguridad", new double[] {Layout.SECURITY[0] + 0.5, Layout.SECURITY[1] + 0.5, 1.5});
        SPOTS.put("corral", new double[] {Layout.CORRAL.cx() + 0.5, Layout.CORRAL.cz() + 0.5, 5});
    }

    private static final Color GREEN = new Color(0x4CAF50);
    private static final Color ORANGE = new Color(0xFF9830);
    private static final Color RED = new Color(0xF2495C);

    private final AgroCraftPlugin plugin;
    private final List<Farm> farms;
    private SimpleLayerProvider provider;

    MapMarkers(AgroCraftPlugin plugin, List<Farm> farms) {
        this.plugin = plugin;
        this.farms = farms;
    }

    boolean start() {
        try {
            Squaremap api = SquaremapProvider.get();
            MapWorld world = api.getWorldIfEnabled(BukkitAdapter.worldIdentifier(farms.get(0).layout.world)).orElse(null);
            if (world == null) {
                return false;
            }
            provider = SimpleLayerProvider.builder("AgroCraft · sensores en vivo")
                    .showControls(true).defaultHidden(false).layerPriority(1).zIndex(250).build();
            world.layerRegistry().register(Key.of("agrocraft_sensores"), provider);
            Bukkit.getScheduler().runTaskTimer(plugin, this::update, 100L, 100L);
            return true;
        } catch (IllegalStateException | NoClassDefFoundError error) {
            plugin.getLogger().log(Level.INFO, "squaremap no disponible: sin marcadores en el mapa", error);
            return false;
        }
    }

    private void update() {
        provider.clearMarkers();
        for (Farm farm : farms) {
            for (Map.Entry<String, double[]> spot : SPOTS.entrySet()) {
                Twin twin = farm.twin(spot.getKey());
                if (twin == null) {
                    continue;
                }
                Map<String, Double> v = new LinkedHashMap<>();
                twin.sample(v);
                double[] s = spot.getValue();
                Color color = color(spot.getKey(), v);
                String html = tooltip(twin, v);
                Marker marker = Marker.circle(Point.of(farm.layout.ox + s[0], farm.layout.oz + s[1]), s[2])
                        .markerOptions(MarkerOptions.builder()
                                .strokeColor(color).strokeWeight(2)
                                .fillColor(color).fillOpacity(farm.simulated() ? 0.25 : 0.45)
                                .hoverTooltip(html).clickTooltip(html).build());
                provider.addMarker(Key.of(twin.id.replace('-', '_')), marker);
            }
        }
    }

    private static double n(Map<String, Double> v, String key) {
        Double value = v.get(key);
        return value == null ? Double.NaN : value;
    }

    private static Color color(String suffix, Map<String, Double> v) {
        return switch (suffix) {
            case "parcela-a", "parcela-b" -> n(v, "soil_pct") < 30 ? RED : n(v, "soil_pct") < 35 ? ORANGE : GREEN;
            case "tanque" -> n(v, "level_pct") < 20 ? RED : n(v, "level_pct") < 30 ? ORANGE : GREEN;
            case "invernadero" -> n(v, "temp_c") > 32 ? RED : n(v, "temp_c") > 28 ? ORANGE : GREEN;
            case "silo" -> n(v, "fill_pct") > 80 ? ORANGE : GREEN;
            case "seguridad" -> n(v, "hostiles_near") > 0 || n(v, "alarm") > 0 ? RED : GREEN;
            case "corral" -> n(v, "welfare_pct") < 40 ? RED : n(v, "thi") > 78 || n(v, "welfare_pct") < 60 ? ORANGE : GREEN;
            default -> GREEN;
        };
    }

    private static String tooltip(Twin twin, Map<String, Double> v) {
        StringBuilder html = new StringBuilder("<b>").append(twin.id).append("</b>");
        if (!twin.enabled) {
            html.append(" <i>(envío apagado)</i>");
        }
        html.append("<br>");
        for (Map.Entry<String, Double> entry : v.entrySet()) {
            double value = entry.getValue();
            if (Double.isNaN(value)) {
                continue;
            }
            String text = value == Math.rint(value) && Math.abs(value) < 1e6
                    ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
            html.append(entry.getKey()).append(": <b>").append(text).append("</b><br>");
        }
        return html.toString();
    }
}
