#!/usr/bin/env bash
# Regenera el informe completo: exporta los datos de la VM, hace gráficas y tablas, y lo imprime a PDF.
# Uso (Git Bash, desde la raíz del repo):
#   bash docs/informe/generar.sh                       # ventana desde el inicio de la captura hasta ahora
#   bash docs/informe/generar.sh 2026-11-17T06:05:00Z  # ventana cerrada (6 días: 11 nov 01:05 - 17 nov 01:05)
# Requisitos: docs/informe/.venv con matplotlib, pandas, markdown, pymupdf; playwright-cli.
set -euo pipefail
cd "$(dirname "$0")"
KEY="${AGRO_SSH_KEY:-$HOME/Downloads/iot_key.pem}"
HOST="${AGRO_SSH_HOST:-mvale@agrocraft.northcentralus.cloudapp.azure.com}"
START="2026-11-11T06:05:00Z"                 # 11 nov 01:05 hora Colombia: inicio de la captura de los gemelos
STOP="${1:-$(date -u +%Y-%m-%dT%H:%M:00Z)}"

scp -q -o BatchMode=yes -i "$KEY" export_data.py "$HOST:/tmp/export_informe.py"
ssh -o BatchMode=yes -i "$KEY" "$HOST" "mkdir -p ~/agrocraft/docs/informe && cp /tmp/export_informe.py ~/agrocraft/docs/informe/export_data.py && rm -rf /tmp/informe-datos && python3 ~/agrocraft/docs/informe/export_data.py $START $STOP /tmp/informe-datos"
mkdir -p datos
scp -q -o BatchMode=yes -i "$KEY" "$HOST:/tmp/informe-datos/*" datos/
ssh -o BatchMode=yes -i "$KEY" "$HOST" "cat ~/agrocraft/experiments/experimentos.csv" > ../experimentos/experimentos.csv

.venv/Scripts/python.exe build.py

# Impresión a PDF (el navegador necesita servir los archivos por HTTP)
(cd .. && informe/.venv/Scripts/python.exe -m http.server 8765 --bind 127.0.0.1 >/dev/null 2>&1) &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq \"python.exe\" -and $_.CommandLine -match \"http.server 8765\" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" || true' EXIT
sleep 2
playwright-cli open "http://127.0.0.1:8765/informe/informe.html" >/dev/null
playwright-cli run-code "async page => { await page.waitForLoadState('networkidle'); await page.waitForTimeout(1500); await page.pdf({ path: '$(cygpath -m "$PWD")/AgroCraft-informe.pdf', format: 'A4', printBackground: true, displayHeaderFooter: true, headerTemplate: '<div></div>', footerTemplate: '<div style=\"font-size:7pt;width:100%;text-align:center;color:#888\">AgroCraft · Proyecto final IoT · <span class=pageNumber></span> / <span class=totalPages></span></div>', margin: { top: '16mm', bottom: '16mm', left: '14mm', right: '14mm' } }); }" >/dev/null
playwright-cli close >/dev/null
rm -rf .playwright-cli
.venv/Scripts/python.exe -c "import pymupdf; print('AgroCraft-informe.pdf:', pymupdf.open('AgroCraft-informe.pdf').page_count, 'páginas')"
