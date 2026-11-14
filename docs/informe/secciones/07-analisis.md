# 7. Análisis de gráficas y comportamiento

> Este análisis fue redactado con asistencia de inteligencia artificial (Claude, de Anthropic) a partir de
> las estadísticas exportadas de InfluxDB para la ventana {{ventana}}, y cada afirmación se verificó
> contra su gráfica. Todas las cifras de esta sección se recalculan automáticamente desde los datos.
> Además, el sistema genera cada día un informe automático con IA en `/iot/reportes/`. Las zonas grises
> de las gráficas son huecos de más de 30 minutos sin datos.

## 7.1 Visión general

{{fig:disponibilidad|Disponibilidad de datos de los 17 dispositivos durante la captura.}}

Los nueve gemelos, los dos datasets y las cuatro APIs reportaron de forma continua. Sus únicos cortes
fueron de alrededor de un minuto, por los reinicios del servidor al instalar nuevas versiones del plugin
(registrados en la bitácora), y uno intencional: el experimento E1 apagó `api-aire` durante una hora.
Los dos dispositivos reales muestran el comportamiento esperable de un equipo físico: el PC deja de
reportar cuando se apaga y el celular solo envía mientras la app está grabando.

{{tabla:huecos}}

## 7.2 El clima real gobierna al gemelo

{{fig:cruce-temperatura|Temperatura exterior real de Bucaramanga, invernadero gemelo e invernadero de referencia de Wageningen.}}

La temperatura exterior siguió un ciclo diario regular entre {{stat:api-clima:temp_c:min}} °C (madrugada)
y {{stat:api-clima:temp_c:max}} °C (primeras horas de la tarde), con un promedio de
{{stat:api-clima:temp_c:mean}} °C. El invernadero gemelo reproduce ese ciclo con un desfase de minutos y
una **ganancia solar de 3 a 4 °C** en las horas de sol: alcanzó {{stat:mc-invernadero:temp_c:max}} °C
mientras afuera hacía como máximo {{stat:api-clima:temp_c:max}} °C. Cuando pasa de 28 °C abre sus ventilas
y la curva se aplana. De noche, sin radiación, las dos curvas coinciden. El invernadero real de
Wageningen (dataset) muestra un régimen distinto: calefacción controlada que lo mantiene cerca de
22-27 °C de día y caídas nocturnas hasta {{stat:ds-invernadero:temp_c:min}} °C. Es el comportamiento de
un invernadero holandés en primavera, que sirve de contraste con el clima tropical de montaña.

{{fig:cruce-agua|Humedad del suelo de las parcelas, nivel del tanque y lluvia real.}}

El riego automático mantuvo las parcelas entre 35 y 65 % de humedad. Cada parcela se secó despacio de
noche y rápido al mediodía, por la evapotranspiración real (ET0 de hasta {{stat:api-suelo:et0_mm:max:2}} mm
cada 15 min). Cuando bajaban de 35 % se abría el riego y el tanque bajaba en escalones; la bomba lo
rellenaba al pasar de 25 %. Las lluvias reales de las tardes del 13 y 14 de noviembre (hasta
{{stat:api-clima:precip_mm:max}} mm cada 15 min) se ven en la gráfica: el suelo subió solo hasta saturarse
y el tanque se recargó sin bomba. La parcela B, que se seca 25 % más rápido, necesitó más riegos que la A.
El único momento en que el suelo bajó de 30 % fue el experimento E5 (mínimo
{{stat:mc-parcela-a:soil_pct:min}} %), que terminó con la alerta "Suelo seco" devolviendo el riego a automático.

## 7.3 Dispositivos de API y pronóstico

{{fig:dev-api-clima|api-clima: temperatura, humedad, radiación y lluvia de Bucaramanga.}}

La humedad relativa fue inversa a la temperatura: entre {{stat:api-clima:hum_pct:min:0}} % a mediodía y
{{stat:api-clima:hum_pct:max:0}} % de madrugada. La radiación solar llegó a {{stat:api-clima:radiation_wm2:max:0}} W/m²
en días despejados y se recortó en las tardes nubladas. La presión se mantuvo estable alrededor de
{{stat:api-clima:pressure_hpa:mean}} hPa, lo esperable a unos 960 m de altitud.

{{fig:dev-api-aire|api-aire: material particulado, índice de calidad del aire e índice UV.}}

La calidad del aire fue buena a moderada: PM2.5 promedio de {{stat:api-aire:pm2_5:mean}} µg/m³ (máximo
{{stat:api-aire:pm2_5:max}}) y US AQI entre {{stat:api-aire:us_aqi:min:0}} y {{stat:api-aire:us_aqi:max:0}}.
Nunca se activó la regla que cierra las ventilas (AQI > 100). El índice UV llegó a
{{stat:api-aire:uv_index:max}} al mediodía. El hueco del 11 de noviembre es el experimento E1.

{{fig:dev-api-suelo|api-suelo: evapotranspiración de referencia, humedad y temperatura del suelo.}}

{{fig:dev-api-pronostico|api-pronostico: horas al próximo riego de la parcela A, agua prevista y horas de estrés térmico del rebaño.}}

El pronóstico anticipó entre {{stat:api-pronostico:thi_stress_hours_24h:min:0}} y
{{stat:api-pronostico:thi_stress_hours_24h:max:0}} horas diarias de estrés térmico para el rebaño (THI > 75)
y máximas de {{stat:api-pronostico:tmax_next24_c:min}} a {{stat:api-pronostico:tmax_next24_c:max}} °C. Las
horas al próximo riego bajan como una rampa hasta 1 h justo antes de cada riego real, y saltan a 48 h
(el horizonte) después de las lluvias, cuando el modelo no prevé riego en dos días.

## 7.4 Datasets

{{fig:dev-ds-invernadero|ds-invernadero: invernadero de tomate de Wageningen reproducido en tiempo real.}}

El invernadero de referencia muestra la operación de un invernadero comercial: CO₂ enriquecido de día
(hasta {{stat:ds-invernadero:co2_ppm:max:0}} ppm) que baja al ventilar, luz PAR con ciclo diario de hasta
{{stat:ds-invernadero:par_umol:max:0}} µmol/m²s y humedad alta (promedio {{stat:ds-invernadero:hum_pct:mean:0}} %).
El escalón de +3 °C del 11 de noviembre a las 14:37 es la descalibración del experimento E2.

{{fig:dev-ds-energia|ds-energia: consumo eléctrico de la vivienda de la finca (UCI).}}

La potencia promedio fue {{stat:ds-energia:power_kw:mean:2}} kW, con picos de hasta
{{stat:ds-energia:power_kw:max}} kW en las noches (cocina y calefacción de la vivienda original). Se ve
la **caída de tensión ante las cargas altas**: cada pico de potencia coincide con una baja de tensión,
que llegó a {{stat:ds-energia:voltage_v:min}} V frente a un promedio de {{stat:ds-energia:voltage_v:mean}} V.

## 7.5 Dispositivos reales

{{fig:dev-gw-pc-01|gw-pc-01: uso de CPU y RAM del PC del operario y latencia de su enlace con la nube.}}

El PC trabajó con CPU promedio de {{stat:gw-pc-01:cpu_pct:mean:0}} % y RAM de {{stat:gw-pc-01:ram_pct:mean:0}} %,
con jornadas de uso intenso y noches de reposo. La latencia hacia la VM en Azure (Estados Unidos) fue
estable, alrededor de 200 ms, con picos aislados de más de un segundo propios de una conexión
doméstica. El hueco gris es una noche con el equipo apagado: un dispositivo real deja de reportar cuando
se apaga, y la alerta "Dispositivo sin datos" lo detecta.

{{fig:dev-phone-01|phone-01: presión barométrica, sonido ambiente y batería del iPhone.}}

El celular solo transmite mientras Sensor Logger graba, por eso sus datos llegan en sesiones. La presión
medida (promedio {{stat:phone-01:pressure_hpa:mean}} hPa) es unos 10 hPa menor que la de Open-Meteo para
Bucaramanga: el teléfono está más alto que el punto de referencia del modelo. Los dos sensores siguen la
misma tendencia. El micrófono distingue silencio (−160 dBFS, el piso del sensor) de actividad, y la
batería muestra los ciclos de carga y descarga del día.

## 7.6 Gemelos digitales

{{fig:dev-mc-invernadero|mc-invernadero: temperatura, humedad, salud de las plantas y ventilas.}}

El invernadero gemelo abrió sus ventilas cada mediodía al pasar de 28 °C y las cerró al bajar de 25 °C.
Aun así superó 32 °C varias horas el 11 y el 12 de noviembre; esa tarde la salud de las plantas cayó
hasta {{stat:mc-invernadero:plant_health_pct:min:0}} % (los arbustos perdieron sus frutos en el mundo) y se
recuperó en las noches frescas. Es la misma conclusión que dio la simulación de ola de calor: **las
ventilas solas no bastan en las horas de mayor radiación**. Las noches lluviosas del 13 y 14 saturaron la
humedad interior al 100 %.

{{fig:dev-mc-parcela-a|mc-parcela-a: humedad del suelo, crecimiento del trigo y riego.}}

{{fig:dev-mc-parcela-b|mc-parcela-b: humedad del suelo, crecimiento de zanahoria y papa, y riego.}}

Cada parcela completó **{{stat:mc-parcela-a:harvests:last:0}} cosechas** en la ventana: el crecimiento
sube como rampa, más rápido con suelo húmedo y sol, y vuelve a cero al cosechar. Los pulsos azules son
los riegos automáticos, más frecuentes en la parcela B.

{{fig:dev-mc-tanque|mc-tanque: nivel, salida a riego y entrada por bomba y lluvia.}}

El tanque osciló entre {{stat:mc-tanque:level_pct:min:0}} % y {{stat:mc-tanque:level_pct:max:0}} %. Cada
riego se ve como una salida de 20 L/min y cada arranque de la bomba como una entrada de 30 L/min. Las
lluvias reales aportaron entradas menores sin bomba.

{{fig:dev-mc-silo|mc-silo: unidades de cosecha almacenadas y porcentaje de llenado.}}

El silo recibió las cosechas de trigo y zanahoria y entregó trigo al comedero del corral. Llegó a
{{stat:mc-silo:items:max:0}} unidades ({{stat:mc-silo:fill_pct:max:0}} % de su capacidad) sin alcanzar el 80 %
que dispara la descarga automática.

{{fig:dev-mc-corral|mc-corral: rebaño, índice THI, bienestar y comedero.}}

{{fig:cruce-corral|Corral: THI calculado con el clima real y bienestar del rebaño.}}

El rebaño creció de 4 a {{stat:mc-corral:cows_total:max:0}} vacas ({{stat:mc-corral:births_total:last:0}}
nacimientos). Al superar el objetivo de 8 adultas, la planta de proceso entregó
{{stat:mc-corral:beef_total:last:0}} unidades de carne asada a la cámara. El THI siguió el ciclo del clima
real, entre {{stat:mc-corral:thi:min}} y {{stat:mc-corral:thi:max}}: todas las tardes entró en zona de
alerta (> 72) y rozó la de peligro (78). El bienestar bajó en esas tardes y se recuperó de noche.

La caída más fuerte (a {{stat:mc-corral:welfare_pct:min:0}} %) ocurrió la madrugada del 12 de noviembre:
el comedero se vació porque en el silo todavía no había trigo. El análisis de los eventos reveló además
un error: mientras el silo estaba vacío, el comedero publicaba un evento de reintento cada segundo
(más de 30 000 en la ventana). Se corrigió en la versión 0.6.1 del plugin: ahora usa zanahoria como
forraje y avisa una sola vez.

{{fig:dev-mc-seguridad|mc-seguridad: criaturas hostiles en la granja, alarma y personas en la zona.}}

Hubo {{stat:mc-seguridad:intruders_total:last:0}} intrusiones simuladas, todas detectadas, con alarma
automática y alerta "Intruso en la granja". El pico de 11 hostiles del 11 de noviembre coincide con la
visita de un jugador: en Minecraft las criaturas aparecen cerca de los jugadores. Las dos que quedaron
esa noche mantuvieron la alarma encendida hasta el amanecer, cuando el sol las eliminó.

{{fig:dev-mc-estacion|mc-estacion: lluvia en el mundo (sincronizada con la lluvia real) y luz del cielo.}}

{{fig:dev-mc-server|mc-server: rendimiento (TPS) y memoria del servidor del gemelo.}}

El servidor del gemelo mantuvo **{{stat:mc-server:tps:mean}} TPS de promedio** (el máximo es 20), con un
mínimo de {{stat:mc-server:tps:min}} durante un reinicio. La memoria osciló entre
{{stat:mc-server:heap_mb:min:0}} y {{stat:mc-server:heap_mb:max:0}} MB por la recolección de basura de
Java. La VM de 4 GiB alcanza para las dos granjas, la plataforma y los servicios.
