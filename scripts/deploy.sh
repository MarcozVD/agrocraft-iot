#!/usr/bin/env bash
# Copia el proyecto a la VM por tar sobre SSH. No envía secretos (.env) ni datos.
# Uso (Git Bash):  bash scripts/deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

KEY="${AGRO_SSH_KEY:-$HOME/Downloads/iot_key.pem}"
HOST="${AGRO_SSH_HOST:-mvale@agrocraft.northcentralus.cloudapp.azure.com}"

tar czf - \
  --exclude='.env' --exclude='__pycache__' --exclude='.gradle' --exclude='build' \
  --exclude='devices/datasets/*.csv' --exclude='minecraft/world' \
  platform devices minecraft scripts \
  | ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -i "$KEY" "$HOST" \
      'mkdir -p ~/agrocraft && tar xzf - -C ~/agrocraft'

echo "copiado a $HOST:~/agrocraft"
