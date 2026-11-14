# Guía de sustentación (individual)

Cada integrante debe poder explicar cualquier capa y hacer una demostración en vivo. Duración sugerida
de la demo: 8 minutos.

## Antes de entrar (10 min antes)

1. Abrir en pestañas:
   - Grafana Resumen: https://agrocraft.northcentralus.cloudapp.azure.com (rango: últimos 7 días)
   - Panel de control: https://agrocraft.northcentralus.cloudapp.azure.com/iot/ (iniciar sesión como operador)
   - Mapa: https://agrocraft.northcentralus.cloudapp.azure.com/map/?world=minecraft_overworld&zoom=5&x=12&z=0
   - Informes IA: https://agrocraft.northcentralus.cloudapp.azure.com/iot/reportes/
   - Repositorio en GitHub
2. Si se va a mostrar el juego: TLauncher 1.21.11, usuario de la lista blanca, desde una IP autorizada.
3. Comprobar en Resumen → "Estado de la flota" que todo esté en línea.

## Demo en vivo (orden sugerido)

| Min | Qué mostrar | Qué decir |
|---|---|---|
| 0-1 | Arquitectura (figura del informe) | Cuatro capas; plataforma propia en Azure en vez de IoT Central; 17 dispositivos en 4 clases |
| 1-2 | Grafana Resumen, rango 6 días | Clima real vs gemelo vs dataset; ciclo diario; promedio/máx/mín en la leyenda; cambiar el rango a 1 h y a 6 días |
| 2-4 | Panel `/iot/`: encender la lámpara de la parcela A y apagar el envío de un sensor | MQTT `cmd` → `ack` con el mismo `cmd_id`; aparece la marca naranja en Grafana; en el mundo se enciende la lámpara y sale el aviso en el chat |
| 4-5 | Calibrar `api-clima` temp_c offset +2 y luego borrar | Retroceso de calibración; se ve el escalón en la gráfica |
| 5-6 | Experimento E5 en el informe (figura) | Ciclo cerrado sin personas: sensor → alerta → webhook → comando → riego |
| 6-7 | Simulación: iniciar "ola de calor" a 360× desde el panel | Dashboard Simulación: invernadero > 38 °C, THI > 78, aspersores; datos en medición aparte |
| 7-8 | En el juego: `/agro tour` (visita guiada de 11 paradas) | Hologramas con el valor en vivo sobre cada dispositivo = mismo número que en Grafana |
| 8-9 | Informe con IA → sección "Anomalías" | Detección por patrón diario (z ≥ 3 frente a la misma hora de días anteriores) |

## Preguntas probables

- **¿Por qué MQTT y no solo HTTP?** Conexión persistente, QoS 1, última voluntad, retenidos y
  publicación/suscripción: el mismo bróker sirve para telemetría y para comandos. HTTPS se usa donde el
  dispositivo no puede mantener una conexión (celular) y para la API.
- **¿Cómo se asegura?** TLS (Let's Encrypt), usuario + ACL por dispositivo, comandos con usuario de
  operador, token para la ingesta HTTPS, secretos fuera del repositorio, SSH con llave, puertos mínimos.
- **¿Qué pasa si se cae un dispositivo?** El bróker publica su LWT `offline`, la tabla de flota lo marca
  fuera de línea y la alerta "Dispositivo sin datos" avisa a los 30 min (pasó con E1).
- **¿Se pierden mensajes?** QoS 1 + sesión persistente de Telegraf; el contador `seq` mide los huecos.
- **¿El gemelo es real?** Es un modelo físico simple acoplado a datos reales (ET0, lluvia, radiación,
  temperatura, humedad de Open-Meteo); los sensores de referencia y sus rangos están en la tabla 3.1.
- **¿Dónde está el procesamiento?** Flux en Grafana (agregación por ventana, prom/máx/mín), alertas,
  reglas en el servicio de control, modelo en el plugin, pronóstico en la flota.
- **¿Solo MQTT?** No: `ds-energia` envía su telemetría por **AMQP 0-9-1** a RabbitMQ (exchange topic
  `agro.telemetry`, cola durable de Telegraf y confirmación del bróker); sus comandos siguen por MQTT. El
  celular usa HTTPS. Si RabbitMQ cae, la lectura sale por MQTT y no se pierde.
- **¿Cómo detectan anomalías?** Cada 10 min se compara el promedio de 30 min de 14 variables con la
  media y desviación de esa misma hora en los días anteriores; |z| ≥ 3 dos veces seguidas abre la
  anomalía, |z| < 2 la cierra; también detecta sensores "pegados". Se publica como evento y llega al informe.
- **¿Las reglas usan el pronóstico?** Sí: `riego-pronostico` baja el umbral de riego a 25 % si se
  esperan ≥ 5 mm de lluvia con probabilidad ≥ 70 %, y lo devuelve a 35 % con < 2 mm.
- **¿Qué hace la IA?** Analiza las estadísticas de 24 h y redacta el informe diario (DeepSeek vía TokenHarbor); el análisis del
  documento se redactó con IA y se verificó contra las gráficas.

## Si algo falla en vivo

- Grafana vacío: revisar el rango de tiempo.
- Comando sin `ack`: el dispositivo está apagado (`set_enabled 0`) o en reinicio; usar otro.
- La VM no responde: `az vm start -g SOURCE -n iot` (todo arranca solo en ~2 min).
- Respaldo: el PDF del informe y los videos/capturas de `docs/evidencias/`.
