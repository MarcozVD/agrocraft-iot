package co.agrocraft.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Una granja del mundo: su plano, las entradas de su modelo y sus gemelos.
 *   real ("twin", prefijo "mc-")   tiempo real, acoplada al clima real de Bucaramanga.
 *   sim  ("sim",  prefijo "sim-")  copia al lado, con reloj acelerado y escenarios (SimWorld).
 * La clase va en el tema MQTT (agro/<clase>/<id>/...): Telegraf guarda la simulación en otra
 * medición, así nunca se mezcla con la captura real.
 */
final class Farm {
    final String cls;
    final String prefix;
    final Layout layout;
    final RealWorld inputs;
    final Map<String, Twin> twins = new LinkedHashMap<>();

    Farm(String cls, String prefix, Layout layout, RealWorld inputs) {
        this.cls = cls;
        this.prefix = prefix;
        this.layout = layout;
        this.inputs = inputs;
    }

    boolean simulated() {
        return inputs instanceof SimWorld;
    }

    Twin twin(String suffix) {
        return twins.get(prefix + suffix);
    }
}
