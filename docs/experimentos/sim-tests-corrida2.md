# Pruebas de la granja de simulación

Velocidad 360×. Cada escenario parte del estado real con los gemelos simulados en configuración de fábrica.

## actual — cumple
24 h simuladas · 2026-11-11T21:57:48Z → 2026-11-11T22:01:58Z (UTC) · 19 eventos

| Criterio | Observado | Resultado |
|---|---|---|
| Hubo telemetría de los 7 dispositivos simulados | 7 | ✔ |
| Ciclo diario de temperatura (amplitud > 8 °C) | 11.0 | ✔ |
| Radiación 0 de noche y > 600 W/m² de día | (0.0, 806.762) | ✔ |
| El riego automático actúa y el suelo termina ≥ 33 % | (3, 48.8) | ✔ |

## normal — cumple
24 h simuladas · 2026-11-11T22:01:58Z → 2026-11-11T22:06:07Z (UTC) · 25 eventos

| Criterio | Observado | Resultado |
|---|---|---|
| Hubo telemetría de los 7 dispositivos simulados | 7 | ✔ |
| Ciclo diario de temperatura (amplitud > 8 °C) | 11.0 | ✔ |
| Radiación 0 de noche y > 600 W/m² de día | (0.0, 806.001) | ✔ |
| El riego automático actúa y el suelo termina ≥ 33 % | (4, 66.1) | ✔ |
| Temperatura exterior dentro del día típico (17-28 °C) | (17.0, 28.0) | ✔ |
