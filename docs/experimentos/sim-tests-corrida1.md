# Pruebas de la granja de simulación

Velocidad 360×. Cada escenario parte del estado real con los gemelos simulados en configuración de fábrica.

## actual — NO cumple
24 h simuladas · 2026-11-11T21:22:10Z → 2026-11-11T21:26:19Z (UTC) · 18 eventos

| Criterio | Observado | Resultado |
|---|---|---|
| Hubo telemetría de los 7 dispositivos simulados | 7 | ✔ |
| Ciclo diario de temperatura (amplitud > 8 °C) | 11.0 | ✔ |
| Radiación 0 de noche y > 600 W/m² de día | (0.0, 807.027) | ✔ |
| El riego automático mantiene el suelo cerca o por encima de 35 % (mín. > 30 %) | 29.8 | ✘ |

## normal — NO cumple
24 h simuladas · 2026-11-11T21:26:20Z → 2026-11-11T21:30:29Z (UTC) · 25 eventos

| Criterio | Observado | Resultado |
|---|---|---|
| Hubo telemetría de los 7 dispositivos simulados | 7 | ✔ |
| Ciclo diario de temperatura (amplitud > 8 °C) | 11.0 | ✔ |
| Radiación 0 de noche y > 600 W/m² de día | (0.0, 807.396) | ✔ |
| El riego automático mantiene el suelo cerca o por encima de 35 % (mín. > 30 %) | 29.6 | ✘ |
| Temperatura exterior dentro del día típico (17-28 °C) | (17.0, 28.0) | ✔ |

## ola_calor — cumple
24 h simuladas · 2026-11-11T21:30:30Z → 2026-11-11T21:34:39Z (UTC) · 31 eventos

| Criterio | Observado | Resultado |
|---|---|---|
| Hubo telemetría de los 7 dispositivos simulados | 7 | ✔ |
| Exterior supera 34 °C | 35.0 | ✔ |
| Invernadero supera 32 °C | 40.3 | ✔ |
| Se abren las ventilas en automático | 3 | ✔ |
| La salud de las plantas cae | (67.2, 0.0) | ✔ |

## sequia — cumple
36 h simuladas · 2026-11-11T21:34:39Z → 2026-11-11T21:40:49Z (UTC) · 52 eventos

| Criterio | Observado | Resultado |
|---|---|---|
| Hubo telemetría de los 7 dispositivos simulados | 7 | ✔ |
| ET0 máxima mayor a la del día normal (×1.8) | (1.93, 0.64) | ✔ |
| Sin lluvia | 0.0 | ✔ |
| Más riegos que en el día normal (por hora) | (12, 0.20833333333333334) | ✔ |
| El tanque termina más bajo de lo que empezó | (73.2, 58.2) | ✔ |

## tormenta — cumple
12 h simuladas · 2026-11-11T21:40:50Z → 2026-11-11T21:42:58Z (UTC) · 8 eventos

| Criterio | Observado | Resultado |
|---|---|---|
| Hubo telemetría de los 7 dispositivos simulados | 7 | ✔ |
| Lluvia fuerte (≥ 18 mm/h) | 18.0 | ✔ |
| Entra agua al tanque por lluvia | 12.0 | ✔ |
| El suelo se moja (> 90 %) | 100.0 | ✔ |
| Sin riegos mientras llueve | 0 | ✔ |

## helada — cumple
24 h simuladas · 2026-11-11T21:42:59Z → 2026-11-11T21:47:08Z (UTC) · 35 eventos

| Criterio | Observado | Resultado |
|---|---|---|
| Hubo telemetría de los 7 dispositivos simulados | 7 | ✔ |
| Exterior baja de 3 °C | 2.0 | ✔ |
| Se enciende la calefacción | 10 | ✔ |
| El invernadero queda por encima del exterior (mín.) | (8.0, 2.0) | ✔ |

## falla_bomba — cumple
48 h simuladas · 2026-11-11T21:47:09Z → 2026-11-11T21:55:22Z (UTC) · 45 eventos

| Criterio | Observado | Resultado |
|---|---|---|
| Hubo telemetría de los 7 dispositivos simulados | 7 | ✔ |
| La falla queda registrada (pump_failure = 1) | 1.0 | ✔ |
| El tanque se vacía (< 5 %) | 0.0 | ✔ |
| La bomba se ordena pero no entra agua | (1.0, 1.33) | ✔ |
| Sin agua, el suelo cae por debajo de 30 % | 0.0 | ✔ |

## intrusion — cumple
12 h simuladas · 2026-11-11T21:55:22Z → 2026-11-11T21:57:31Z (UTC) · 25 eventos

| Criterio | Observado | Resultado |
|---|---|---|
| Hubo telemetría de los 7 dispositivos simulados | 7 | ✔ |
| Aparece un intruso | 6 | ✔ |
| Se detecta (hostiles_near > 0) | 1.0 | ✔ |
| Suena la alarma en automático | 6 | ✔ |
