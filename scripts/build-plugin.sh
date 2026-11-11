#!/usr/bin/env bash
# Compila el plugin agrocraft-bridge dentro de un contenedor (no hace falta Gradle ni JDK en la VM),
# lo instala en el servidor de Minecraft y reinicia el servidor.
# Uso (en la VM):  bash scripts/build-plugin.sh [--no-restart]
set -euo pipefail
cd "$(dirname "$0")/.."

docker run --rm -u "$(id -u):$(id -g)" \
  -v "$PWD/minecraft/plugin:/work" -w /work \
  -e GRADLE_USER_HOME=/work/.gradle-home \
  gradle:8-jdk21 gradle --no-daemon --console=plain -q build

jar="$(ls -t minecraft/plugin/build/libs/agrocraft-bridge-*.jar | head -1)"
mkdir -p data/minecraft/plugins
rm -f data/minecraft/plugins/agrocraft-bridge-*.jar
cp "$jar" data/minecraft/plugins/
echo "instalado: $(basename "$jar")"

if [ "${1:-}" != "--no-restart" ]; then
  (cd platform && docker compose restart minecraft)
fi
