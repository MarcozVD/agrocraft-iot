package co.agrocraft.bridge;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** Cliente MQTT 3.1.1 del puente: reconecta solo y guarda en memoria lo publicado mientras no hay conexión. */
final class Mqtt implements MqttCallbackExtended {
    private final MqttAsyncClient client;
    private final MqttConnectOptions options = new MqttConnectOptions();
    private final ScheduledExecutorService retry = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agrocraft-mqtt-retry");
        thread.setDaemon(true);
        return thread;
    });
    private final String[] subscriptions;
    private final BiConsumer<String, String> onMessage;
    private final Runnable onConnected;
    private final Logger log;
    private volatile boolean closing;

    Mqtt(String url, String user, String password, String willTopic, String[] subscriptions,
         BiConsumer<String, String> onMessage, Runnable onConnected, Logger log) throws MqttException {
        this.subscriptions = subscriptions;
        this.onMessage = onMessage;
        this.onConnected = onConnected;
        this.log = log;

        client = new MqttAsyncClient(url, "mc-bridge", new MemoryPersistence());
        client.setCallback(this);
        DisconnectedBufferOptions buffer = new DisconnectedBufferOptions();
        buffer.setBufferEnabled(true);
        buffer.setBufferSize(5000);
        buffer.setDeleteOldestMessages(true);
        client.setBufferOpts(buffer);

        options.setUserName(user);
        options.setPassword(password.toCharArray());
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setKeepAliveInterval(30);
        options.setMaxInflight(500);
        options.setWill(willTopic, "offline".getBytes(StandardCharsets.UTF_8), 1, true);
        connect();
    }

    /** Paho solo reconecta por su cuenta después de una primera conexión exitosa: el primer intento se reintenta aquí. */
    private void connect() {
        if (closing) {
            return;
        }
        try {
            client.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                }

                @Override
                public void onFailure(IMqttToken token, Throwable error) {
                    log.warning("MQTT: no se pudo conectar (" + error + "); reintento en 10 s");
                    retry.schedule(Mqtt.this::connect, 10, TimeUnit.SECONDS);
                }
            });
        } catch (MqttException error) {
            log.warning("MQTT: " + error + "; reintento en 10 s");
            retry.schedule(this::connect, 10, TimeUnit.SECONDS);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        log.info("MQTT conectado a " + serverUri + (reconnect ? " (reconexión)" : ""));
        try {
            for (String filter : subscriptions) {
                client.subscribe(filter, 1);
            }
        } catch (MqttException error) {
            log.warning("MQTT: fallo al suscribir: " + error);
        }
        onConnected.run();
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warning("MQTT: conexión perdida: " + cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        onMessage.accept(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    void publish(String topic, String payload, int qos, boolean retained) {
        try {
            client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), qos, retained);
        } catch (MqttException error) {
            log.fine("MQTT: mensaje descartado en " + topic + ": " + error);
        }
    }

    /** Publica los mensajes de despedida, espera su entrega y cierra la conexión. */
    void close(String[] offlineTopics) {
        closing = true;
        retry.shutdownNow();
        try {
            if (client.isConnected()) {
                for (String topic : offlineTopics) {
                    client.publish(topic, "offline".getBytes(StandardCharsets.UTF_8), 1, true).waitForCompletion(1000);
                }
                client.disconnect(2000).waitForCompletion(3000);
            }
        } catch (MqttException error) {
            log.fine("MQTT: cierre con error: " + error);
        }
        try {
            client.disconnectForcibly(500, 500);
            client.close();
        } catch (MqttException error) {
            log.fine("MQTT: " + error);
        }
    }
}
