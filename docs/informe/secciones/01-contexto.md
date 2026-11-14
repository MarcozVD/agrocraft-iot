# 1. Descripción y contexto

## 1.1 Problema

Una granja hortícola y ganadera en Santander (Colombia) opera hoy "a ojo": se riega por horario y no
por necesidad, el estrés térmico del invernadero y del ganado se detecta cuando ya hay daño, nadie sabe
con certeza cuánta agua queda en el tanque ni si hubo intrusos en la noche, y no existe un registro
histórico que permita decidir con datos. Antes de invertir en sensores para toda la finca, el productor
necesita un lugar donde **probar reglas de riego, alertas y automatismos sin arriesgar el cultivo ni los
animales**.

## 1.2 Propuesta: AgroCraft

AgroCraft es una plataforma IoT a la medida, desplegada en la nube con URL propia, cuyo **gemelo
digital** es un mundo de Minecraft. Cada parcela, el tanque, el invernadero, el silo, el perímetro y el
corral de vacas existen como estructuras del juego que se comportan según un modelo físico simple y
**reciben el clima real de Bucaramanga**: si llueve en la ciudad llueve en el mundo y el tanque se
recarga; la evapotranspiración real seca el suelo de las parcelas; la temperatura y la humedad reales
calientan el invernadero y generan estrés térmico en el rebaño. Los bloques del juego son sensores y
actuadores: publican telemetría por MQTT y obedecen comandos de la plataforma, y lo que hace un jugador
dentro del mundo (mover una palanca, cosechar) también llega a la plataforma.

Alrededor del gemelo hay dispositivos de las otras tres clases que pide el proyecto: **reales** (el PC
del operario como gateway y su celular), **API** (clima, calidad del aire, suelo y un pronóstico
operativo) y **DataSet** (un invernadero real de Wageningen y un medidor eléctrico doméstico,
reproducidos como si midieran hoy).

## 1.3 Preguntas que responde el sistema

1. ¿Cuál es el estado de cada parcela, del tanque, del invernadero y del rebaño ahora y en los últimos días?
2. ¿Cuándo hay que regar, ventilar, enfriar o alimentar, y puede hacerse de forma remota o automática?
3. ¿Cómo explica el clima real el comportamiento de la granja?
4. ¿Qué dispositivos están fuera de línea, descalibrados o reportando valores anómalos?
5. ¿Qué pasaría ante una ola de calor, una sequía, una tormenta o una falla de la bomba?

## 1.4 Alcance de la captura

Ventana analizada: **{{ventana}}** ({{dias}} días), **{{muestras}} mensajes de telemetría** de 17
dispositivos, más los comandos, respuestas, eventos y alertas de ese periodo.
