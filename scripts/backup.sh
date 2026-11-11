#!/usr/bin/env bash
# Copia de seguridad diaria: InfluxDB (todas las series), mundo de Minecraft + estado del plugin,
# base de Grafana y la configuración con secretos (.env, usuarios del bróker).
# Deja un archivo por día en ~/agrocraft/backups y conserva los últimos KEEP días.
# Para sacarlas de la VM, desde el PC:  bash scripts/pull-backups.sh
#
# Programado con un temporizador de systemd (scripts/install-timers.sh). Manual:  bash scripts/backup.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
KEEP="${KEEP:-5}"
STAMP="$(date +%Y%m%d-%H%M)"
DEST="$ROOT/backups"
WORK="$(mktemp -d)"
trap 'sudo rm -rf "$WORK"' EXIT
mkdir -p "$DEST"
cd platform

echo "[$(date -Is)] InfluxDB"
docker compose exec -T influxdb sh -c \
  'rm -rf /tmp/agro-backup && influx backup /tmp/agro-backup -t "$DOCKER_INFLUXDB_INIT_ADMIN_TOKEN" >/dev/null \
   && tar czf - -C /tmp agro-backup && rm -rf /tmp/agro-backup' > "$WORK/influxdb.tar.gz"

echo "[$(date -Is)] Minecraft (guardado en disco y pausa de escritura durante la copia)"
docker compose exec -T minecraft rcon-cli save-off >/dev/null
docker compose exec -T minecraft rcon-cli save-all flush >/dev/null
sleep 5
sudo tar czf "$WORK/minecraft.tar.gz" -C "$ROOT/data/minecraft" \
  agrocraft plugins/AgroCraft whitelist.json ops.json server.properties 2>/dev/null || true
docker compose exec -T minecraft rcon-cli save-on >/dev/null

echo "[$(date -Is)] Grafana y configuración"
docker run --rm -v agrocraft_grafana-data:/g:ro -v "$WORK:/out" alpine:3 \
  tar czf /out/grafana.tar.gz -C /g grafana.db
sudo tar czf "$WORK/config.tar.gz" -C "$ROOT" platform/.env data/mosquitto/auth

tar cf "$DEST/agrocraft-$STAMP.tar" -C "$WORK" .
chmod 600 "$DEST/agrocraft-$STAMP.tar"
ls -1t "$DEST"/agrocraft-*.tar | tail -n +"$((KEEP + 1))" | xargs -r rm -f
echo "[$(date -Is)] listo: $DEST/agrocraft-$STAMP.tar ($(du -h "$DEST/agrocraft-$STAMP.tar" | cut -f1))"
