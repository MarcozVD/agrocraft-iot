"""Consumidor AMQP del servicio de control: lleva al panel la telemetría que llega por RabbitMQ.

Cola exclusiva y temporal (se borra al desconectarse) enlazada a `agro.telemetry` con `#`: el panel solo
necesita el último valor; la copia durable para InfluxDB es la cola de Telegraf.
"""

import json
import logging
import os
import threading
import time

import pika

log = logging.getLogger("amqp")
EXCHANGE = "agro.telemetry"


class AmqpLive(threading.Thread):
    def __init__(self, hub):
        super().__init__(name="amqp-live", daemon=True)
        self.hub = hub

    def _parameters(self):
        return pika.ConnectionParameters(
            host=os.environ.get("AMQP_HOST", "rabbitmq"), port=int(os.environ.get("AMQP_PORT", "5672")),
            credentials=pika.PlainCredentials(os.environ.get("AMQP_USER", "agro"), os.environ["AMQP_PASS"]),
            heartbeat=60, connection_attempts=3, retry_delay=2)

    def _on_message(self, channel, method, properties, body):
        try:
            data = json.loads(body)
            cls, device = data.pop("class"), data.pop("device")
        except (ValueError, KeyError):
            return
        self.hub.feed(cls, device, "telemetry", data)

    def run(self):
        while True:
            try:
                connection = pika.BlockingConnection(self._parameters())
                channel = connection.channel()
                channel.exchange_declare(EXCHANGE, exchange_type="topic", durable=True)
                queue = channel.queue_declare("", exclusive=True, auto_delete=True).method.queue
                channel.queue_bind(queue, EXCHANGE, routing_key="#")
                channel.basic_consume(queue, self._on_message, auto_ack=True)
                log.info("conectado a RabbitMQ (%s)", EXCHANGE)
                channel.start_consuming()
            except Exception as error:      # reintenta para siempre: RabbitMQ puede reiniciarse
                log.warning("AMQP desconectado: %r; reintento en 15 s", error)
                time.sleep(15)
