#!/usr/bin/env bash
# Programa en la VM, con temporizadores de systemd (la imagen de Debian no trae cron):
#   agrocraft-backup.timer  copia de seguridad diaria a las 03:30
#   agrocraft-certs.timer   entrega a Mosquitto el certificado renovado por Caddy, lunes 04:00
# Persistent=true: si la VM estaba apagada a esa hora, la tarea corre al encender.
# Idempotente. Ver estado:  systemctl list-timers 'agrocraft-*'
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
USER_NAME="$(id -un)"

unit() {   # unit <nombre> <script> <OnCalendar> <descripción>
  sudo tee "/etc/systemd/system/agrocraft-$1.service" >/dev/null <<EOF
[Unit]
Description=AgroCraft: $4
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
User=$USER_NAME
ExecStart=/bin/bash -c 'bash $ROOT/scripts/$2 >> $ROOT/backups/$1.log 2>&1'
EOF
  sudo tee "/etc/systemd/system/agrocraft-$1.timer" >/dev/null <<EOF
[Unit]
Description=AgroCraft: $4 (programación)

[Timer]
OnCalendar=$3
Persistent=true
RandomizedDelaySec=120

[Install]
WantedBy=timers.target
EOF
}

mkdir -p "$ROOT/backups"
unit backup backup.sh "*-*-* 03:30:00" "copia de seguridad diaria"
unit certs sync-certs.sh "Mon *-*-* 04:00:00" "certificado TLS de Mosquitto"
sudo systemctl daemon-reload
sudo systemctl enable --now agrocraft-backup.timer agrocraft-certs.timer
systemctl list-timers 'agrocraft-*' --no-pager
