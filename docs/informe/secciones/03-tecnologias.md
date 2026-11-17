# 3. Tecnologías seleccionadas

## 3.1 Sensores y dispositivos

Diecisiete dispositivos en las cuatro clases pedidas. Para cada uno se indica el sensor real que
representa (o la fuente de datos), el rango de su hoja de datos y el rango **observado durante la
captura** en sus variables principales.

{{tabla:dispositivos}}

**Por qué el rango observado es más estrecho que el del datasheet.** Los sensores de referencia cubren
condiciones extremas (por ejemplo, el SHT31 mide de −40 a 125 °C), mientras que el clima de Bucaramanga
en noviembre se movió entre {{stat:api-clima:temp_c:min}} y {{stat:api-clima:temp_c:max}} °C. Los
umbrales operativos (riego bajo 35 % de humedad de suelo, ventilación sobre 28 °C, estrés térmico del
rebaño sobre THI 72-78) se eligieron dentro del rango del escenario, no del sensor.

**Gemelos con modelo.** Los gemelos no generan números al azar: el plugin calcula su estado cada
segundo. La humedad del suelo baja `0,5 + 12 × ET0` puntos por hora (ET0 real de Open-Meteo) y sube 120
puntos por hora con el riego, que toma 20 L/min del tanque; la temperatura del invernadero persigue a la
exterior real más una ganancia solar proporcional a la radiación real, que las ventilas abiertas reducen
al 35 %; el índice THI del corral se calcula con la temperatura y la humedad reales; el cultivo crece
según humedad, luz y temperatura, y al madurar se cosecha hacia el silo, cuyo trigo alimenta el comedero
de las vacas.

## 3.2 Comunicación: bróker y protocolos

**Bróker:** Eclipse Mosquitto 2.0, con tres puertos de entrada: 1883 solo en la red interna de Docker
(plugin, flota, Telegraf, servicio de control), **8883 con TLS** para dispositivos externos (el agente
del PC) y **WebSocket** detrás de Caddy en `wss://…/mqtt`. Sin acceso anónimo: 13 usuarios, uno por
dispositivo o servicio, con ACL por patrón (`pattern write agro/+/%u/telemetry`): un dispositivo solo
puede publicar en sus propios temas y leer sus propios comandos.

**Protocolo MQTT 3.1.1.** Temas `agro/<clase>/<dispositivo>/<canal>`:

{{tabla:temas}}

- **QoS 1** en telemetría y comandos: el bróker confirma cada mensaje y Telegraf usa sesión persistente,
  así que lo publicado mientras se reinicia se entrega después.
- **Última voluntad (LWT):** cada dispositivo registra `offline` en su tema `status`; si se desconecta
  sin despedirse, el bróker lo publica por él.
- **Mensajes retenidos** en `status` y `state`: quien se suscribe recibe de inmediato el último estado.
- El número de secuencia `seq` permite medir mensajes perdidos (panel de Grafana "huecos en seq").

**HTTPS REST** (servicio de control detrás de Caddy): ingesta del celular (`POST /iot/api/ingest/phone-01`
con token), comandos (`POST /iot/api/cmd`, espera el `ack` hasta 10 s), webhook de alertas de Grafana.
**HTTPS GET** hacia Open-Meteo para los dispositivos de API.

**Sobre la API de Azure Maps Weather sugerida en el enunciado.** Se probó el 16 de noviembre con la
clave entregada y con las de la colección de Postman del curso: la primera respondió `401 AccountLocked`
(la cuenta asociada está deshabilitada) y las otras `401 InvalidKey`. Por eso los dispositivos de API usan
Open-Meteo, que ofrece las mismas variables (condiciones actuales, calidad del aire y pronóstico horario)
sin clave y con las mismas coordenadas de Bucaramanga.

**Ejemplo real de un ciclo de retroceso** (tomado de la captura):

```
agro/twin/mc-parcela-a/cmd  {"cmd_id": "alerta-b777a742", "action": "set_config", "params": {"auto": 1}, "source": "alerta"}
agro/twin/mc-parcela-a/ack  {"cmd_id": "alerta-b777a742", "action": "set_config", "ok": true, "detail": "configuración actualizada"}
agro/twin/mc-parcela-a/event {"type": "irrigation", "on": 1, "reason": "auto", "soil_pct": 29.4}
```

## 3.3 Nube y servicios

Infraestructura propia en **Microsoft Azure (Azure for Students)**: una máquina virtual
`Standard_B2als_v2` (2 vCPU, 4 GiB) con Debian 12 en la región North Central US, IP pública estática y
nombre DNS `agrocraft.northcentralus.cloudapp.azure.com`. El grupo de seguridad de red abre solo 22
(SSH con llave), 80/443 (HTTPS), 8883 (MQTT/TLS) y 25565 (Minecraft, solo IP autorizadas). Todo corre en
contenedores Docker orquestados con Docker Compose, con límites de memoria y reinicio automático.

{{tabla:servicios}}

Operación: copia de seguridad diaria a las 03:30 (InfluxDB, mundo, Grafana y configuración) con
temporizadores de systemd, renovación semanal del certificado de Mosquitto, despliegue por script
desde el repositorio y dashboards y alertas definidos como código.
