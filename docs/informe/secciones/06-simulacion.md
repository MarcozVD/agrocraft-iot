# 6. Simulación de escenarios

Junto a la granja real, a 80 bloques, hay una **copia simulada** con el mismo modelo pero con reloj
acelerado (hasta 360 veces: un día en 4 minutos) y entradas sintéticas en lugar del clima real. Cada
simulación parte del estado actual de la granja real, así responde a la pregunta "¿qué pasaría desde
aquí si…?". Sus datos van a una medición aparte (`simulation`), de modo que nunca se mezclan con la
captura real ni disparan alertas.

| Escenario | Qué cambia |
|---|---|
| `actual` | Día típico desplazado al clima real del momento de inicio |
| `normal` | Día típico de Bucaramanga (17-28 °C, chubasco a las 16:00) |
| `ola_calor` | +7 °C, −20 % de humedad, sin lluvia |
| `sequia` | Sin lluvia, ET0 × 2,2, aire más seco |
| `tormenta` | Lluvia de 18 mm/h la primera hora, radiación baja |
| `helada` | Temperatura entre 2 y 10 °C |
| `falla_bomba` | La bomba del tanque no entrega agua |
| `intrusion` | Intrusiones frecuentes a cualquier hora |

Se escribió una batería de pruebas (`scripts/sim_test.py`) con criterios definidos **antes** de correr
cada escenario; todos los escenarios cumplen sus criterios:

{{tabla:simulacion}}

{{img:../evidencias/dash-simulacion.png|Dashboard de simulación durante la batería de pruebas: entradas de cada escenario, agua, invernadero, actuadores, cultivo y reloj simulado.}}

**Lo que enseña la simulación.** Con una ola de calor el invernadero llega a 38-40 °C aun con las
ventilas abiertas y las plantas mueren en un día: las ventilas no bastan y haría falta sombra o
enfriamiento evaporativo. Con la bomba averiada el tanque se vacía y el suelo se seca por completo en
dos días. Con una tormenta el riego se detiene solo y el tanque se recarga. Estas conclusiones salen del
mismo modelo que corre la granja real, con los mismos umbrales.
