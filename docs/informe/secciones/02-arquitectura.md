# 2. Arquitectura de referencia IoT

La arquitectura conserva las cuatro capas vistas en clase (dispositivo, telecomunicaciones, plataforma
y operación), pero reemplaza la plataforma gestionada (Azure IoT Central, usada en los laboratorios) por
una **plataforma propia** sobre una máquina virtual de Azure, construida con servicios de código abierto
y un servicio de control escrito para el proyecto.

{{img:estaticas/arquitectura.svg|Arquitectura de referencia de AgroCraft. En gris, el envío de telemetría; en rojo discontinuo, el retroceso (comando y respuesta).}}

## 2.1 Capas

| Capa | Componentes | Responsabilidad |
|---|---|---|
| Dispositivo | 17 dispositivos: 2 reales, 9 gemelos en Minecraft (más 8 de la granja de simulación), 4 de API y 2 de DataSet | Medir, actuar y responder comandos |
| Telecomunicaciones | MQTT 3.1.1 (TLS 8883, WebSocket seguro en 443, red interna), AMQP 0-9-1 (RabbitMQ), HTTPS REST, HTTPS GET a Open-Meteo | Transportar telemetría y comandos con cifrado y autenticación |
| Plataforma | Caddy, Mosquitto, RabbitMQ, Telegraf, InfluxDB, Grafana, servicio de control (reglas, detector de anomalías, IA), servidor Paper con el plugin AgroCraft | Recibir, autenticar, almacenar, procesar, decidir |
| Operación | Grafana, centro de datos (granja 3D, panel de control, informes, mapa), mundo de Minecraft, informes con IA, Telegram y Discord | Visualizar, alertar y operar |

## 2.2 Flujo de envío (telemetría)

1. Cada dispositivo publica `agro/<clase>/<dispositivo>/telemetry` con QoS 1, marca de tiempo y número
   de secuencia (`seq`), usando su propio usuario y clave del bróker.
2. Mosquitto valida usuario y ACL (cada equipo solo puede escribir sus propios temas) y entrega el mensaje.
3. Telegraf, suscrito con sesión persistente, escribe cada mensaje en InfluxDB con las etiquetas `class`
   y `device`; los comandos, respuestas, eventos y estados van a una segunda medición (`events`) y la
   granja de simulación a una tercera (`simulation`).
4. Grafana consulta InfluxDB con Flux para las gráficas y evalúa las alertas cada minuto.

El medidor de energía (`ds-energia`) publica su telemetría por **AMQP 0-9-1** en el exchange `agro.telemetry` de RabbitMQ, con confirmación del bróker; Telegraf la consume de una cola durable. Si RabbitMQ no responde, la lectura sale por MQTT. El celular no habla MQTT: hace POST por HTTPS al servicio de control, que lo representa ante el bróker
con su propio cliente MQTT. Los dispositivos de API consultan Open-Meteo por HTTPS y publican por MQTT.

## 2.3 Flujo de recepción (retroceso)

1. El operador (panel `/iot/`), una regla de la nube o una alerta de Grafana pide un comando al servicio de control.
2. El servicio publica `agro/<clase>/<dispositivo>/cmd` con un identificador (`cmd_id`) y su origen.
3. El dispositivo aplica el cambio, responde en `ack` con el mismo `cmd_id` y publica su nuevo `state`
   (retenido), que queda registrado y aparece como marca en las gráficas.

## 2.4 Seguridad

Certificado TLS de Let's Encrypt renovado automáticamente (compartido por Caddy y Mosquitto), bróker
sin acceso anónimo con usuario y ACL por dispositivo, comandos protegidos con usuario de operador,
ingesta del celular con token, secretos solo en la VM (fuera del repositorio), SSH solo con llave,
puerto de Minecraft restringido por IP y lista blanca de jugadores, y copia de seguridad diaria.
