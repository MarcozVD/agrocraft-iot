# AgroCraft — granja inteligente con gemelo digital en Minecraft

Proyecto final · IoT + Cloud + Sistemas Distribuidos · Universidad Autónoma de Bucaramanga

Un mundo de Minecraft alojado en una VM de Azure funciona como gemelo digital de una granja: sus
bloques son sensores y actuadores que publican telemetría por MQTT y obedecen comandos. El clima real
de Bucaramanga (Open-Meteo) gobierna el clima, el secado del suelo y la temperatura del gemelo.

- **Dashboards (Grafana, lectura sin login):** https://agrocraft.northcentralus.cloudapp.azure.com
- **Panel de control (retroceso):** https://agrocraft.northcentralus.cloudapp.azure.com/iot/ (abre el centro de datos en «Panel de control»)
- **Centro de datos:** https://agrocraft.northcentralus.cloudapp.azure.com/iot/gemelo/ — Granja 3D (3D/2.5D/2D, recorrido guiado,
  reproducción de 24 h), Panel de control (`?p=panel`, detalle `&d=<id>` con gráficas en vivo, mensajes en crudo y órdenes),
  Informes IA (`?p=informes`) y Mapa del mundo (`?p=mapa`), todo con el mismo diseño
  (lectura pública; los comandos piden usuario `operador` y la clave `CONTROL_PASS` de `platform/.env` en la VM)
- **Plan y estado del proyecto:** [PLAN.md](PLAN.md)

| Ruta | Servicio |
|---|---|
| `/` | Grafana: 5 dashboards (Resumen, Cultivo y riego, Clima, Operación y salud, Por dispositivo) y 6 alertas |
| `/iot/` | Redirige al Panel de control del centro de datos: comandos a cualquier dispositivo, reglas en la nube, simulación |
| `/iot/gemelo/` | Gemelo 3D: la granja real del mundo (bloques exportados con `scripts/export_world.py`) con los sensores en vivo, vistas 3D/2.5D/2D, reproducción de 24 h, granja de simulación e informes con IA |
| `/iot/api/devices` · `/iot/api/cmd` · `/iot/api/rules` | API REST del servicio de control |
| `/iot/api/stream` · `/latest` · `/history/<id>` · `/series` · `/replay` · `/events` · `/reports` | API de lectura del gemelo 3D (Server-Sent Events y consultas a InfluxDB) |
| `/iot/api/ingest/<device>?token=…` | Ingesta HTTPS para dispositivos sin MQTT (celular) |
| `/iot/api/alert?token=…` | Webhook de Grafana Alerting (puede ejecutar un comando por alerta) |
| `/mqtt` · `:8883` | MQTT sobre WebSocket / sobre TLS |
| `/map/` | Mapa web del mundo (squaremap), se actualiza cada 15 s |
| `:25565` | Servidor de Minecraft (solo IP autorizadas y lista blanca) |

## Arquitectura

```
dispositivos ── MQTT 3.1.1 (TLS 8883 / WSS 443 / red interna) ──> Mosquitto ──> Telegraf ──> InfluxDB 2 ──> Grafana
     ^                                                               │
     └────────────────────── cmd (retroceso) ────────────────────────┘
```

| Clase | Dispositivos | Dónde corre |
|---|---|---|
| Gemelos (`twin`) | `mc-parcela-a`, `mc-parcela-b`, `mc-tanque`, `mc-invernadero`, `mc-silo`, `mc-seguridad`, `mc-estacion`, `mc-server` | Plugin `agrocraft-bridge` dentro del servidor Paper |
| API (`api`) | `api-clima`, `api-aire`, `api-suelo` | Contenedor `fleet` (consulta HTTPS a Open-Meteo) |
| DataSet (`dataset`) | `ds-invernadero`, `ds-energia` | Contenedor `fleet` (reproduce CSV públicos) |
| Reales (`real`) | `gw-pc-01` (PC del operario), `phone-01` (celular), `esp32-01` (pendiente) | `devices/real/pc-agent` (MQTT/TLS) · app Sensor Logger → HTTPS → servicio `control` |

## Estructura

```
platform/     docker-compose y configuración de Caddy, Mosquitto, Telegraf, Grafana (dashboards y
              alertas como código), control/ (API REST, panel /iot/, reglas, ingesta HTTPS)
devices/      fleet/ (dispositivos de API y dataset) · datasets/fetch.py · real/pc-agent (gw-pc-01)
minecraft/    plugin/ (agrocraft-bridge, Java 21) · server/ · world/
scripts/      setup, deploy, sync-certs, build-plugin, cmd, backup, pull-backups, install-timers,
              check-dashboards
docs/         arquitectura, evidencias, informe
```

## Contrato de datos

Temas: `agro/<clase>/<device_id>/<canal>`

| Canal | Sentido | Contenido |
|---|---|---|
| `telemetry` | dispositivo → nube | `{"ts": <unix ms>, "seq": n, "<variable>": número, ...}` |
| `status` | dispositivo → nube | `online` / `offline` (retenido, última voluntad) |
| `state` | dispositivo → nube | configuración vigente (retenido) |
| `event` | dispositivo → nube | hechos puntuales: riego, cosecha, intruso, alarma |
| `cmd` | nube → dispositivo | `{"cmd_id": "...", "action": "...", "params": {...}}` |
| `ack` | dispositivo → nube | `{"cmd_id": "...", "ok": true, "detail": "..."}` |

Comandos comunes: `set_enabled`, `set_interval`, `calibrate`, `reboot`. Gemelos: `set_actuator`,
`set_config`; estación: `set_weather`, `set_time`. API: `set_location`. Dataset: `set_speed`, `restart`.

## Operación

Todo corre en la VM, en `~/agrocraft`. Los secretos están solo allí, en `platform/.env`.

```bash
# Entrar a la VM
ssh -i ~/Downloads/iot_key.pem mvale@agrocraft.northcentralus.cloudapp.azure.com

# Estado y registros
cd ~/agrocraft/platform
docker compose ps
docker compose logs -f --tail 50 fleet        # o minecraft, telegraf, mosquitto, caddy...

# Clave de administrador de Grafana (usuario: admin)
grep GRAFANA_ADMIN_PASS ~/agrocraft/platform/.env

# Enviar un comando de retroceso y ver el ack
cd ~/agrocraft
bash scripts/cmd.sh twin mc-parcela-a set_actuator '{"name":"irrigation","value":1}'
bash scripts/cmd.sh twin mc-parcela-a set_config '{"auto":1}'
bash scripts/cmd.sh twin mc-estacion set_weather '{"weather":"rain"}'
bash scripts/cmd.sh twin mc-estacion set_config '{"sync_weather":1}'
bash scripts/cmd.sh api api-clima calibrate '{"variable":"temp_c","offset":2}'
bash scripts/cmd.sh api api-clima calibrate '{"reset":true}'
```

Desde el PC (Git Bash), después de cambiar archivos del repo:

```bash
bash scripts/deploy.sh                                   # copia el proyecto a la VM (sin secretos)
ssh ... 'cd ~/agrocraft/platform && docker compose up -d --build'    # aplica cambios de plataforma o flota
ssh ... 'cd ~/agrocraft && bash scripts/build-plugin.sh'             # recompila el plugin y reinicia Minecraft
python platform/grafana/build_dashboards.py              # regenera los dashboards (luego deploy.sh)
ssh ... 'cd ~/agrocraft && python3 scripts/check-dashboards.py'      # ejecuta todas sus consultas
bash scripts/pull-backups.sh                             # baja al PC las copias de seguridad
```

Ojo: Caddyfile, `mosquitto.conf`, `acl` y `telegraf.conf` se montan como archivos sueltos. Tras
desplegar un cambio en ellos hay que reiniciar ese contenedor (`docker compose restart caddy`, etc.):
la copia nueva tiene otro inodo y el contenedor seguiría leyendo la anterior.

**Copias de seguridad:** temporizador de systemd diario a las 03:30 (`scripts/install-timers.sh`);
quedan en `~/agrocraft/backups` (últimas 5) e incluyen secretos, así que no van al repositorio.

**Agente del PC (`gw-pc-01`):** `devices/real/pc-agent`. Requiere `.env` con `MQTT_PASS_GW_PC_01`.
Arranque manual: `.venv\Scripts\pythonw.exe agent.py`. Arranque con Windows:
`powershell -ExecutionPolicy Bypass -File install-autostart.ps1`.

**Celular (`phone-01`):** app Sensor Logger → Settings → Data Streaming → HTTP Push, URL
`https://agrocraft.northcentralus.cloudapp.azure.com/iot/api/ingest/phone-01?token=<MQTT_PASS_PHONE_01>`,
sensores Barometer, Battery, Accelerometer y Microphone (Light solo existe en Android). La ubicación se descarta. Para obtener la URL
completa con su token (Git Bash en el PC):
`ssh -i ~/Downloads/iot_key.pem mvale@agrocraft.northcentralus.cloudapp.azure.com 'cd ~/agrocraft/platform && echo "https://$(grep ^SITE_HOST= .env | cut -d= -f2)/iot/api/ingest/phone-01?token=$(grep ^MQTT_PASS_PHONE_01= .env | cut -d= -f2)"'`.
Si el token está mal, `docker compose logs control` muestra "token inválido o ausente".

Instalación desde cero en una VM nueva (Debian 12 con Docker):

```bash
bash scripts/setup.sh                                    # genera platform/.env y los usuarios del bróker
python3 devices/datasets/fetch.py data/datasets          # descarga y prepara los datasets
cd platform && docker compose up -d --build
cd .. && bash scripts/sync-certs.sh                      # activa MQTT sobre TLS con el certificado de Caddy
bash scripts/build-plugin.sh                             # compila e instala el plugin
```

## Fuentes de datos

- Open-Meteo: https://open-meteo.com (clima, calidad del aire, suelo)
- Autonomous Greenhouse Challenge 2019, Wageningen University & Research, vía CEAOD:
  https://doi.org/10.4121/uuid:88d22c60-21b3-4ea8-90db-20249a5be2a7
- UCI Individual Household Electric Power Consumption: https://archive.ics.uci.edu/dataset/235

## Dentro del mundo (plugin 0.4)

- **Tablero** frente al punto de aparición: clima real de Bucaramanga, calidad del aire, celular,
  PC gateway y salud del servidor, actualizados cada 2 s.
- **Letrero junto a cada gemelo** con sus lecturas (parcelas, tanque, invernadero, silo, estación, seguridad).
- **Invernadero visible**: el techo cambia de color con la temperatura (azul < 18 °C, transparente
  18-27, amarillo 27-30, naranja 30-32, rojo > 32); los arbustos de bayas reflejan `plant_health_pct`
  (con frutos si están sanos, sin frutos o secos si sufren calor, frío o aire seco); sale vapor por las
  ventilas abiertas y humo de la calefacción.
- **Palancas**: riego A y B, bomba del tanque, ventilas del invernadero y alarma. Moverlas ejecuta el
  actuador y publica el evento `player_actuator` (retroceso iniciado desde el juego).
- Cada comando que llega desde la plataforma se anuncia en el chat del juego con su origen
  (panel, regla, alerta). Entradas y salidas de jugadores se publican como eventos de `mc-server`.

## Simulación acelerada (plugin 0.4)

Hay **dos granjas** en el mundo:

| | Granja real (x = 0) | Granja de simulación (x = 80) |
|---|---|---|
| Dispositivos | `mc-*` (clase `twin`) | `sim-*` (clase `sim`) + `sim-control` |
| Reloj | tiempo real | acelerado, 1 a 360 veces |
| Entradas del modelo | clima real (Open-Meteo) | escenario sintético |
| Datos | medición `telemetry` (captura del informe, alertas) | medición `simulation` (dashboard *Simulación*) |

Escenarios: `actual` (parte del clima real de ese momento), `normal` (día típico de Bucaramanga),
`ola_calor`, `sequia`, `tormenta`, `helada`, `falla_bomba`, `intrusion`. Cada inicio copia primero el
estado de la granja real, así la simulación responde a "¿qué pasaría desde aquí si...?".

Desde el panel `/iot/` (sección *Simulación*), o por línea de comandos:

```bash
bash scripts/cmd.sh sim sim-control start '{"scenario":"sequia","speed":240,"hours":48}'
bash scripts/cmd.sh sim sim-control set_speed '{"speed":60}'
bash scripts/cmd.sh sim sim-control stop
bash scripts/cmd.sh sim sim-control reset      # iguala la granja simulada a la real
```

La lluvia simulada se dibuja con partículas solo sobre la granja simulada; el cielo y la hora del
mundo siguen siendo los reales. Mapa: `/map/?world=minecraft_overworld&zoom=5&x=92&z=0`.

## Ganadería, pronóstico, mapa, notificaciones e IA (plugin 0.5-0.6)

- **Corral automático (`mc-corral`, `sim-corral`):** vacas reales del juego (máx. 12). Comedero que se
  llena con trigo del silo, bebedero y aspersores con agua del tanque, índice THI con el clima real,
  bienestar, reproducción y planta de proceso que lleva la carne asada a una cámara. Dashboard
  *Ganadería*. Alertas: estrés térmico del rebaño (enciende aspersores), bienestar bajo, comedero vacío.
- **Pronóstico (`api-pronostico`):** previsión horaria de Open-Meteo + modelo del gemelo: horas hasta el
  próximo riego de cada parcela, agua prevista, lluvia, extremos de temperatura y horas de estrés del
  rebaño. Panel en *Cultivo y riego*.
- **Mapa web:** capa "AgroCraft · sensores en vivo" con un círculo por gemelo (verde/naranja/rojo) y sus
  lecturas al pasar el cursor, en las dos granjas.
- **Notificaciones:** las alertas (y la acción automática que ejecutaron) y el informe diario van a
  Telegram y Discord. Variables en `platform/.env`: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`,
  `DISCORD_WEBHOOK_URL`; después `docker compose up -d control`. Prueba: `POST /iot/api/notify/test`.
- **Analista con IA:** cada día a las 07:00 reúne las estadísticas de 24 h, eventos, alertas, comandos y
  pronóstico, y Claude (`claude-opus-5-5`) redacta el análisis de comportamiento. Informes en
  `/iot/reportes/`; a demanda con `POST /iot/api/report?hours=24` (usuario operador). Requiere
  `ANTHROPIC_API_KEY` en `platform/.env`; sin ella el informe trae solo las estadísticas.
