#!/usr/bin/env bash
# Envía un comando de retroceso a un dispositivo y muestra su respuesta (ack). Se ejecuta en la VM.
#
# Uso:      bash scripts/cmd.sh <clase> <device_id> <action> ['<params json>']
# Ejemplos: bash scripts/cmd.sh twin mc-parcela-a set_actuator '{"name":"irrigation","value":1}'
#           bash scripts/cmd.sh twin mc-estacion set_weather '{"weather":"rain"}'
#           bash scripts/cmd.sh api api-clima set_interval '{"interval_s":600}'
#           bash scripts/cmd.sh dataset ds-energia calibrate '{"variable":"voltage_v","offset":2}'
set -euo pipefail
cd "$(dirname "$0")/../platform"
set -a; . ./.env; set +a

class="$1"; device="$2"; action="$3"; params="${4:-{\}}"
topic="agro/$class/$device"
cmd_id="cli-$(date +%s)"

docker compose exec -T mosquitto mosquitto_sub -u operator -P "$MQTT_PASS_OPERATOR" \
  -t "$topic/ack" -C 1 -W 10 &
sleep 1
docker compose exec -T mosquitto mosquitto_pub -u operator -P "$MQTT_PASS_OPERATOR" -q 1 \
  -t "$topic/cmd" -m "{\"cmd_id\":\"$cmd_id\",\"source\":\"${SOURCE:-cli}\",\"action\":\"$action\",\"params\":$params}"
wait
