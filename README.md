# AgroCraft — granja inteligente con gemelo digital en Minecraft

Proyecto final · IoT + Cloud + Sistemas Distribuidos · Universidad Autónoma de Bucaramanga

Un mundo de Minecraft alojado en una VM de Azure funciona como gemelo digital de una granja: sus
bloques son sensores y actuadores que publican telemetría por MQTT y obedecen comandos. El clima real
de Bucaramanga (Open-Meteo) gobierna el clima, el secado del suelo y la temperatura del gemelo.

- **URL:** https://agrocraft.northcentralus.cloudapp.azure.com (Grafana, lectura sin login)
- **Plan y estado del proyecto:** [PLAN.md](PLAN.md)

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
| Reales (`real`) | `gw-pc-01`, `phone-01`, `esp32-01` | Pendientes (ver PLAN.md) |

## Estructura

```
platform/     docker-compose y configuración de Caddy, Mosquitto, Telegraf, Grafana
devices/      fleet/ (dispositivos de API y dataset) · datasets/fetch.py · real/ (pendiente)
minecraft/    plugin/ (agrocraft-bridge, Java 21) · server/ · world/
scripts/      setup, deploy, sync-certs, build-plugin, cmd
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
```

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
