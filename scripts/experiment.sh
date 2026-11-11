#!/usr/bin/env bash
# Experimento de retroceso: envía un comando, registra hora, motivo y ack, y opcionalmente programa
# la reversión en la propia VM (systemd-run), así no depende de que alguien siga conectado.
#
# Uso:  bash scripts/experiment.sh <id> <clase> <device> <action> '<params>' "<motivo>" [<min> '<params_reversion>' [<accion_reversion>]]
# Ej.:  bash scripts/experiment.sh E1 twin mc-invernadero set_enabled '{"enabled":0}' "Apagar sensor" \
#            60 '{"enabled":1}'
# Registro: ~/agrocraft/experiments/experimentos.csv (hora local;id;fase;clase;device;action;params;ok;detalle;motivo)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/experiments"
LOG="$LOG_DIR/experimentos.csv"
mkdir -p "$LOG_DIR"
[ -f "$LOG" ] || echo "hora;id;fase;clase;dispositivo;accion;params;ok;detalle;motivo" > "$LOG"

id="$1"; cls="$2"; device="$3"; action="$4"; params="$5"; reason="$6"
phase="${PHASE:-inicio}"
ack="$(SOURCE="experimento-$id" bash "$ROOT/scripts/cmd.sh" "$cls" "$device" "$action" "$params" 2>&1 | tail -1)"
ok="$(echo "$ack" | python3 -c 'import sys,json
try: d=json.load(sys.stdin); print("ok" if d.get("ok") else "rechazado", end="")
except Exception: print("sin-ack", end="")')"
detail="$(echo "$ack" | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("detail",""), end="")
except Exception: print(sys.stdin.read()[:120], end="")')"
echo "$(date '+%Y-%m-%d %H:%M:%S');$id;$phase;$cls;$device;$action;$params;$ok;$detail;$reason" >> "$LOG"
echo "[$id/$phase] $device $action $params -> $ok $detail"

if [ $# -ge 8 ]; then
  minutes="$7"; revert_params="$8"; revert_action="${9:-$action}"
  sudo systemd-run --quiet --uid="$(id -u)" --gid="$(id -g)" --on-active="${minutes}m" \
    --unit="agrocraft-exp-$id-$(date +%s)" --setenv=PHASE=reversion \
    /bin/bash "$ROOT/scripts/experiment.sh" "$id" "$cls" "$device" "$revert_action" "$revert_params" \
    "Reversión automática de $id tras $minutes min"
  echo "[$id] reversión programada en $minutes min: $revert_action $revert_params"
fi
