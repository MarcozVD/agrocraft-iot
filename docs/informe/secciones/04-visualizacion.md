# 4. Visualización y procesamiento de los datos

## 4.1 Dashboards

Grafana (lectura pública en la URL del proyecto) tiene siete dashboards generados por código
(`platform/grafana/build_dashboards.py`): **Resumen**, **Cultivo y riego**, **Clima, invernadero y
energía**, **Operación y salud**, **Por dispositivo**, **Ganadería** y **Simulación acelerada**. Todos
usan el selector de tiempo de Grafana: el mismo panel muestra 1 hora, 1 día o la semana completa, y la
agregación se ajusta sola al rango (`aggregateWindow(every: v.windowPeriod)`).

{{img:../evidencias/dash-resumen.png|Dashboard Resumen: estado de la flota (en línea / fuera de línea / sin datos), indicadores, temperatura real frente al gemelo y al invernadero de referencia, agua y línea de tiempo de actuadores.}}

{{img:../evidencias/dash-cultivo.png|Dashboard Cultivo y riego: humedad del suelo con los umbrales de riego, desarrollo del cultivo y cosechas, tanque, clima real que gobierna el riego y pronóstico.}}

## 4.2 Procesamiento: promedio, máximo, mínimo y derivadas

- **Promedio, máximo, mínimo y último valor** en la leyenda de cada gráfica, calculados sobre el rango
  elegido. El dashboard **Por dispositivo** tiene una tabla con estas estadísticas de cualquier
  dispositivo y un selector de agregación (promedio, máximo, mínimo, último por ventana).
- **Estado de la flota**: une los dispositivos esperados con su última muestra y marca "fuera de línea"
  si pasaron más de tres intervalos sin datos.
- **Mensajes perdidos**: diferencia del contador `seq` entre mensajes consecutivos.
- **Variables derivadas**: índice THI del rebaño, salud de las plantas, horas al próximo riego y agua
  prevista (dispositivo `api-pronostico`, que combina la previsión horaria con el modelo del gemelo).
- **Anotaciones**: cada comando de retroceso aceptado (naranja) y cada evento (morado) aparece como marca
  vertical sobre las gráficas.

{{img:../evidencias/dash-dispositivo.png|Dashboard Por dispositivo: promedio, máximo, mínimo, último y número de muestras del rango elegido, con selector de dispositivo y de agregación.}}

## 4.3 Alertas y reglas

**Alertas de Grafana** (evaluadas cada minuto, una alerta independiente por dispositivo). Varias ejecutan
un comando de retroceso al dispararse: el servicio de control recibe el webhook, ejecuta el comando,
registra el evento y avisa por Telegram y Discord.

{{tabla:reglas}}

**Reglas en la nube** (servicio de control), que cruzan datos de dispositivos distintos: encender las
lámparas de crecimiento de las parcelas en días nublados (radiación real < 150 W/m²) y cerrar las
ventilas del invernadero si el aire exterior es malo (US AQI > 100), y **riego según el pronóstico**: con 5 mm o más de lluvia esperada en 24 h (probabilidad ≥ 70 %) baja el umbral de riego de las parcelas a 25 % y con menos de 2 mm lo devuelve a 35 %.

**Detector de anomalías** (servicio de control): cada 10 minutos compara el promedio de 30 minutos de 14 variables con la media y la desviación de esa misma hora en los días anteriores; con |z| ≥ 3 dos veces seguidas abre una anomalía y con |z| < 2 la cierra. También detecta sensores «pegados» (dos horas sin variación). Cada anomalía se publica como evento, llega a Telegram y Discord y entra al informe diario.

**Control local en los gemelos**, con histéresis: riego entre 35 y 65 % de humedad, bomba entre 25 y 90 %
del tanque, ventilas entre 25 y 28 °C, calefacción bajo 16 °C, aspersores del corral sobre THI 75,
comedero bajo 30 %, alarma ante hostiles.

**Alertas registradas durante la captura:**

{{tabla:alertas}}

## 4.4 Otras vistas

- **Centro de datos** ([https://agrocraft.northcentralus.cloudapp.azure.com/iot/gemelo/](https://agrocraft.northcentralus.cloudapp.azure.com/iot/gemelo/)): una sola página con cuatro pantallas. *Granja 3D*: los bloques reales del
  mundo con cada sensor en vivo, en vistas 3D, 2.5D y 2D, reproducción de las últimas 24 h y recorrido guiado.
  *Panel de control*: los 17 dispositivos con su último valor, su estado en palabras y estadísticas de 6 h; el
  detalle de cada uno tiene una gráfica en vivo por variable (umbrales, promedio, mínimo y máximo), sus mensajes
  en crudo explicados y todos los comandos de retroceso (requiere usuario de operador). *Informes IA* y *Mapa*
  embebidos. Los datos llegan por Server-Sent Events en menos de un segundo.
- **Mapa web** (`/map/`): vista del mundo con un marcador por gemelo, coloreado según su estado.
- **Dentro del mundo**: letreros con lecturas en vivo, un tablero con los datos reales y palancas.
- **Informes con IA** (`/iot/reportes/`): análisis diario redactado por un modelo de lenguaje (DeepSeek V4.1 Flash vía TokenHarbor) a partir de las estadísticas.

{{img:../evidencias/centro-granja3d.png|Centro de datos, Granja 3D: los bloques reales del mundo con el valor en vivo de cada sensor; las bolitas que viajan a la nube son mensajes recibidos.}}

{{img:../evidencias/centro-panel.png|Centro de datos, Panel de control: resumen en palabras, reglas en la nube y una tarjeta por dispositivo con estadísticas de 6 h.}}
