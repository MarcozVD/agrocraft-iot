#!/usr/bin/env bash
# Entrega a Mosquitto el certificado que obtuvo Caddy y activa el listener MQTT sobre TLS (8883).
# Ejecutar después del primer arranque de Caddy y cada vez que el certificado se renueve.
set -euo pipefail
cd "$(dirname "$0")/../platform"
set -a; . ./.env; set +a

src="$(sudo find ../data/caddy/caddy/certificates -type d -name "$SITE_HOST" 2>/dev/null | head -1)"
if [ -z "$src" ]; then
  echo "Caddy todavía no tiene certificado para $SITE_HOST" >&2
  exit 1
fi

sudo install -o 1883 -g 1883 -m 644 "$src/$SITE_HOST.crt" ../data/mosquitto/certs/fullchain.pem
sudo install -o 1883 -g 1883 -m 600 "$src/$SITE_HOST.key" ../data/mosquitto/certs/privkey.pem

sudo tee ../data/mosquitto/conf.d/tls.conf >/dev/null <<'EOF'
# Generado por scripts/sync-certs.sh
listener 8883
certfile /mosquitto/certs/fullchain.pem
keyfile /mosquitto/certs/privkey.pem
EOF

docker compose restart mosquitto
echo "listener 8883 activo con el certificado de $SITE_HOST"
