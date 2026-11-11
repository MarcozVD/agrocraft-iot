#!/usr/bin/env bash
# Descarga al PC las copias de seguridad que aún no estén aquí (fuera de la VM).
# Uso (Git Bash):  bash scripts/pull-backups.sh [carpeta destino, por defecto ./backups]
# Las copias contienen secretos (.env): no subirlas al repositorio (ya están en .gitignore).
set -euo pipefail
cd "$(dirname "$0")/.."

KEY="${AGRO_SSH_KEY:-$HOME/Downloads/iot_key.pem}"
HOST="${AGRO_SSH_HOST:-mvale@agrocraft.northcentralus.cloudapp.azure.com}"
DEST="${1:-backups}"
mkdir -p "$DEST"

for name in $(ssh -o BatchMode=yes -i "$KEY" "$HOST" 'ls -1 ~/agrocraft/backups/ 2>/dev/null'); do
  if [ -f "$DEST/$name" ]; then
    continue
  fi
  scp -q -o BatchMode=yes -i "$KEY" "$HOST:agrocraft/backups/$name" "$DEST/$name.part"
  mv "$DEST/$name.part" "$DEST/$name"
  echo "descargada: $DEST/$name"
done
ls -lh "$DEST"
