# Arranca el agente gw-pc-01 al iniciar sesión en Windows (acceso directo en la carpeta Inicio del usuario).
# No requiere administrador. Para quitarlo: borrar el acceso directo "AgroCraft gw-pc-01" de shell:startup.
#   Instalar:     powershell -ExecutionPolicy Bypass -File install-autostart.ps1
#   Desinstalar:  powershell -ExecutionPolicy Bypass -File install-autostart.ps1 -Remove
param([switch]$Remove)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$link = Join-Path ([Environment]::GetFolderPath('Startup')) 'AgroCraft gw-pc-01.lnk'

if ($Remove) {
    Remove-Item $link -ErrorAction SilentlyContinue
    Write-Output "quitado: $link"
    return
}

$pythonw = Join-Path $here '.venv\Scripts\pythonw.exe'
if (-not (Test-Path $pythonw)) { throw "Falta el entorno: python -m venv .venv; .venv\Scripts\pip install -r requirements.txt" }
if (-not (Test-Path (Join-Path $here '.env'))) { throw "Falta .env con MQTT_PASS_GW_PC_01" }

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($link)
$shortcut.TargetPath = $pythonw
$shortcut.Arguments = '"' + (Join-Path $here 'agent.py') + '"'
$shortcut.WorkingDirectory = $here
$shortcut.WindowStyle = 7
$shortcut.Description = 'AgroCraft: agente IoT gw-pc-01'
$shortcut.Save()
Write-Output "instalado: $link"
