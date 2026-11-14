# 8. Conclusiones

1. **Una plataforma IoT propia es viable con servicios de código abierto en una sola VM.** Mosquitto,
   Telegraf, InfluxDB, Grafana y Caddy, más un servicio de control propio, cubrieron lo que en los
   laboratorios hacía Azure IoT Central (dispositivos, telemetría, comandos, reglas y vistas), con más
   control sobre los protocolos y la seguridad y sin límites de dispositivos.
2. **El gemelo digital es útil porque está acoplado a datos reales.** La lluvia, la radiación, la
   evapotranspiración, la temperatura y la humedad de Bucaramanga explican el comportamiento de las
   parcelas, el invernadero y el rebaño, y se puede verificar en las gráficas.
3. **El retroceso funciona en las cuatro clases de dispositivo y por cinco vías:** panel, línea de
   comandos, reglas, alertas y palancas en el mundo. El ciclo sensor → alerta → comando → actuador se
   cerró sin intervención humana (E5), y cada comando queda trazado con su `cmd_id` y su respuesta.
4. **Los datos encontraron fallas que no se veían a simple vista.** Una alerta que dejaba el invernadero
   en manual, plantillas de alertas sin rellenar y un comedero que publicaba 30 000 eventos. Las tres se
   corrigieron, y por eso el sistema terminó mejor de lo que empezó.
5. **La simulación acelerada responde "qué pasaría si".** La ola de calor muestra que las ventilas no
   bastan; la falla de la bomba vacía el tanque en dos días; la tormenta detiene el riego sola. Son
   decisiones de inversión (sombra, bomba de respaldo) apoyadas en el mismo modelo que corre la granja.
6. **Trabajo futuro:** AMQP para el medidor de energía (completar los tres protocolos vistos en clase),
   detección automática de anomalías y sensores físicos (ESP32 con DHT22 y sonda de suelo) para validar
   el modelo del gemelo contra mediciones reales.
