#!/usr/bin/env bash
# Prepara la VM: genera platform/.env con claves aleatorias y el archivo de usuarios de Mosquitto.
# Es idempotente: no cambia las claves que ya existen.
set -euo pipefail
cd "$(dirname "$0")/../platform"

SECRETS="INFLUX_ADMIN_PASS INFLUX_TOKEN GRAFANA_ADMIN_PASS RCON_PASSWORD"
MQTT_USERS="telegraf operator mc-bridge api-clima api-aire api-suelo ds-invernadero ds-energia gw-pc-01 phone-01 esp32-01"

var_of() { echo "MQTT_PASS_$(echo "$1" | tr 'a-z-' 'A-Z_')"; }

ensure() {   # rellena la variable si falta o está vacía
  local name="$1" value
  if grep -q "^$name=." .env; then return; fi
  value="$(openssl rand -hex 16)"
  if grep -q "^$name=" .env; then
    sed -i "s|^$name=.*|$name=$value|" .env
  else
    echo "$name=$value" >> .env
  fi
}

[ -f .env ] || cp .env.example .env
chmod 600 .env
for name in $SECRETS; do ensure "$name"; done
for user in $MQTT_USERS; do ensure "$(var_of "$user")"; done

set -a; . ./.env; set +a

mkdir -p ../data/caddy ../data/fleet ../data/datasets ../data/minecraft
sudo mkdir -p ../data/mosquitto/auth ../data/mosquitto/conf.d ../data/mosquitto/certs

# Usuarios del bróker: se escribe usuario:clave y mosquitto_passwd lo convierte a hashes
tmp="$(mktemp -d)"
for user in $MQTT_USERS; do
  name="$(var_of "$user")"
  printf '%s:%s\n' "$user" "${!name}" >> "$tmp/passwd"
done
docker run --rm -v "$tmp:/m" eclipse-mosquitto:2.0 mosquitto_passwd -U /m/passwd
sudo install -o 1883 -g 1883 -m 600 "$tmp/passwd" ../data/mosquitto/auth/passwd
sudo rm -rf "$tmp"

echo "listo: $(echo $MQTT_USERS | wc -w) usuarios MQTT; siguiente paso: docker compose up -d --build"
