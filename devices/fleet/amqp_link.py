"""Publicación de telemetría por AMQP 0-9-1 (RabbitMQ).

Se usa en los dispositivos con `telemetry_amqp = True` (ds-energia). Cada lectura sale como mensaje
persistente al exchange de tipo topic `agro.telemetry` con clave de enrutamiento `<clase>.<dispositivo>`;
Telegraf la consume con su propia cola durable y el servicio de control con una cola temporal para el
panel en vivo. Los comandos y el estado del dispositivo siguen por MQTT (retroceso).

Se abre una conexión por mensaje: el envío es cada varios minutos y así no hay que atender latidos.
"""

import json
import os

import pika

EXCHANGE = "agro.telemetry"


def parameters() -> pika.ConnectionParameters:
    return pika.ConnectionParameters(
        host=os.environ.get("AMQP_HOST", "rabbitmq"), port=int(os.environ.get("AMQP_PORT", "5672")),
        credentials=pika.PlainCredentials(os.environ.get("AMQP_USER", "agro"), os.environ["AMQP_PASS"]),
        connection_attempts=2, retry_delay=1, socket_timeout=5, blocked_connection_timeout=10)


def publish(cls: str, device: str, payload: dict) -> None:
    body = json.dumps({"class": cls, "device": device, **payload})
    connection = pika.BlockingConnection(parameters())
    try:
        channel = connection.channel()
        channel.exchange_declare(EXCHANGE, exchange_type="topic", durable=True)
        channel.confirm_delivery()                     # el bróker confirma que lo guardó
        channel.basic_publish(EXCHANGE, f"{cls}.{device}", body,
                              pika.BasicProperties(content_type="application/json", delivery_mode=2),
                              mandatory=False)
    finally:
        connection.close()
