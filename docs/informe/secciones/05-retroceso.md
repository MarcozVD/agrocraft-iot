# 5. Retroceso: control de los dispositivos desde la plataforma

Todos los dispositivos, de las cuatro clases, aceptan los mismos comandos básicos:

| Comando | Efecto |
|---|---|
| `set_enabled` | Apaga o enciende el envío de datos |
| `set_interval` | Cambia el periodo de muestreo (5 s a 1 h) |
| `calibrate` | Aplica offset y ganancia a una variable (o borra la calibración) |
| `reboot` | Restablece la configuración de fábrica |
| `set_actuator` | Riego, lámparas, ventilas, calefacción, bomba, alarma, portón, aspersores, comedero, proceso |
| `set_config` | Umbrales y modo automático de cada gemelo |
| `set_weather`, `set_time` | Clima y hora del mundo (estación) |
| `set_location`, `set_speed`, `restart` | Ubicación consultada (API) y reproducción (DataSet) |

Los comandos llegan por cinco vías: el panel web, la línea de comandos (`scripts/cmd.sh`), las reglas de
la nube, las alertas de Grafana y las **palancas dentro del mundo**. Cada uno queda registrado con su
`cmd_id`, su origen y la respuesta del dispositivo.

## 5.1 Experimentos

Durante la captura se ejecutaron experimentos planificados, con hora, motivo y reversión programada
(`docs/experimentos/experimentos.csv`):

{{tabla:experimentos}}

{{fig:exp-e1|E1: al apagar el envío de api-aire queda un hueco de una hora; la alerta "Dispositivo sin datos" se disparó a los 30 minutos sin datos y se resolvió al reencenderlo.}}

{{fig:exp-e2|E2: la descalibración de +3 °C produce un escalón en la temperatura reportada del invernadero de referencia, que desaparece al borrar la calibración.}}

{{fig:exp-e5|E5: con el riego en manual, la humedad de la parcela A cae bajo 30 %; la alerta "Suelo seco" ejecuta set_config auto=1 y el riego arranca solo un segundo después.}}

{{fig:exp-calor|Invernadero: la alerta de estrés térmico abre las ventilas (11:14 y 14:59); en E9 un jugador las cierra y abre con la palanca del mundo.}}

## 5.2 Lecciones de los experimentos

- **Una orden automática no debe dejar al equipo en manual.** La primera alerta de calor abrió las
  ventilas con un comando "manual" y el invernadero quedó así; se corrigió para que las alertas cambien
  el actuador sin quitar el modo automático.
- **Las acciones del operador tienen prioridad.** Mover una palanca o enviar un actuador desde el panel
  saca a ese gemelo de automático hasta que se devuelva explícitamente.
- **El ciclo completo funciona sin personas:** sensor → InfluxDB → alerta → webhook → comando MQTT →
  actuador → evento, en menos de un segundo después de la evaluación de la alerta.
