# Desasigna la VM del proyecto (deja de cobrar cómputo) con la sesión de Azure CLI de este PC.
# La tarea programada "AgroCraft - apagar VM" lo ejecuta el lunes 23 de noviembre de 2026 a las 8:00.
#   Ver:      Get-ScheduledTask -TaskName "AgroCraft - apagar VM"
#   Cancelar: Unregister-ScheduledTask -TaskName "AgroCraft - apagar VM" -Confirm:$false
# Si el PC estaba apagado a esa hora, la tarea corre apenas se encienda (StartWhenAvailable).
$log = Join-Path $PSScriptRoot "apagar-vm.log"
"$(Get-Date -Format s) desasignando la VM iot (grupo SOURCE)..." | Out-File $log -Append -Encoding utf8
az vm deallocate --resource-group SOURCE --name iot 2>&1 | Out-File $log -Append -Encoding utf8
$state = az vm get-instance-view --resource-group SOURCE --name iot --query "instanceView.statuses[1].displayStatus" -o tsv 2>&1
"$(Get-Date -Format s) estado: $state" | Out-File $log -Append -Encoding utf8
