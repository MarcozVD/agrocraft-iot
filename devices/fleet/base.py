"""Base común de los dispositivos virtuales: conexión MQTT, estado persistente y comandos de retroceso.

Cada dispositivo es un hilo con su propio cliente MQTT (usuario = device_id), de modo que el bróker
lo ve como un equipo independiente, con su última voluntad (LWT) y sus permisos.

Temas:  agro/<clase>/<device_id>/<canal>
  telemetry  dispositivo -> nube   {"ts": <unix ms>, "seq": n, "<variable>": número, ...}
  status     dispositivo -> nube   "online" | "offline"   (retenido, LWT)
  state      dispositivo -> nube   configuración vigente  (retenido)
  cmd        nube -> dispositivo   {"cmd_id": "...", "action": "...", "params": {...}}
  ack        dispositivo -> nube   {"cmd_id": "...", "ok": true, "detail": "..."}
"""

import json
import logging
import math
import os
import threading
import time
from pathlib import Path

import paho.mqtt.client as mqtt

FW = "fleet-0.1.0"
MQTT_HOST = os.environ.get("MQTT_HOST", "mosquitto")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "1883"))
MQTT_TLS = os.environ.get("MQTT_TLS", "0") == "1"
STATE_DIR = Path(os.environ.get("STATE_DIR", "/state"))
MIN_INTERVAL_S, MAX_INTERVAL_S = 5, 3600


def now_ms() -> int:
    return int(time.time() * 1000)


def as_bool(value) -> bool:
    if isinstance(value, str):
        return value.strip().lower() in ("1", "true", "on", "si", "sí", "yes")
    return bool(value)


def password_for(device_id: str) -> str:
    return os.environ["MQTT_PASS_" + device_id.upper().replace("-", "_")]


class Device(threading.Thread):
    cls = "api"                 # clase del dispositivo dentro del tema
    default_interval = 300      # segundos entre muestras
    variables: tuple = ()       # variables que publica (las que admite `calibrate`)
    telemetry_amqp = False      # True: la telemetría sale por AMQP (RabbitMQ); comandos y estado por MQTT

    def __init__(self, device_id: str):
        super().__init__(name=device_id, daemon=True)
        self.id = device_id
        self.log = logging.getLogger(device_id)
        self.base = f"agro/{self.cls}/{device_id}"
        self.state_file = STATE_DIR / f"{device_id}.json"
        self.lock = threading.RLock()
        self.wake = threading.Event()
        self.state = self.default_state()
        self._load_state()

        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=device_id, protocol=mqtt.MQTTv311)
        client.username_pw_set(device_id, password_for(device_id))
        if MQTT_TLS:
            client.tls_set()
        client.will_set(f"{self.base}/status", "offline", qos=1, retain=True)
        client.reconnect_delay_set(1, 30)
        client.on_connect = self._on_connect
        client.on_message = self._on_message
        self.client = client

    # ------------------------------------------------------------------ estado
    def default_state(self) -> dict:
        return {"enabled": True, "interval_s": self.default_interval, "calibration": {}, "seq": 0}

    def _load_state(self) -> None:
        try:
            self.state.update(json.loads(self.state_file.read_text()))
        except FileNotFoundError:
            pass
        except (OSError, ValueError) as error:
            self.log.warning("estado guardado ilegible, se usan valores de fábrica: %s", error)

    def _save_state(self) -> None:
        tmp = self.state_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.state))
        tmp.replace(self.state_file)

    def publish_state(self) -> None:
        with self.lock:
            state = {key: value for key, value in self.state.items() if key != "seq"}
            state["enabled"] = int(self.state["enabled"])
        state.update(ts=now_ms(), fw=FW)
        self.client.publish(f"{self.base}/state", json.dumps(state), qos=1, retain=True)

    # ------------------------------------------------------------------- MQTT
    def _on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            self.log.error("conexión rechazada por el bróker: %s", reason_code)
            return
        client.subscribe(f"{self.base}/cmd", qos=1)
        client.publish(f"{self.base}/status", "online", qos=1, retain=True)
        self.publish_state()
        self.log.info("conectado")

    def _on_message(self, client, userdata, message):
        cmd_id, action, ok, detail = None, None, False, ""
        try:
            command = json.loads(message.payload)
            cmd_id, action = command.get("cmd_id"), command.get("action")
            ok, detail = self.handle_command(action, command.get("params") or {})
        except (ValueError, KeyError, TypeError, AttributeError) as error:
            detail = f"comando inválido: {error!r}"
        ack = {"ts": now_ms(), "cmd_id": cmd_id, "action": action, "ok": ok, "detail": detail}
        client.publish(f"{self.base}/ack", json.dumps(ack), qos=1)
        self.log.info("cmd %s -> ok=%s %s", action, ok, detail)
        if ok:
            with self.lock:
                self._save_state()
            self.publish_state()
            self.wake.set()

    # --------------------------------------------------------------- comandos
    def handle_command(self, action: str, params: dict) -> tuple[bool, str]:
        with self.lock:
            if action == "set_enabled":
                self.state["enabled"] = as_bool(params["enabled"])
                return True, f"enabled={int(self.state['enabled'])}"

            if action == "set_interval":
                interval = int(params["interval_s"])
                if not MIN_INTERVAL_S <= interval <= MAX_INTERVAL_S:
                    return False, f"interval_s fuera de rango [{MIN_INTERVAL_S}, {MAX_INTERVAL_S}]"
                self.state["interval_s"] = interval
                return True, f"interval_s={interval}"

            if action == "calibrate":
                if as_bool(params.get("reset", False)):
                    self.state["calibration"] = {}
                    return True, "calibración borrada"
                variable = params["variable"]
                if variable not in self.variables:
                    return False, f"variable desconocida: {variable}; válidas: {', '.join(self.variables)}"
                offset, gain = float(params.get("offset", 0)), float(params.get("gain", 1))
                if offset == 0 and gain == 1:
                    self.state["calibration"].pop(variable, None)
                else:
                    self.state["calibration"][variable] = {"offset": offset, "gain": gain}
                return True, f"{variable}: offset={offset} gain={gain}"

            if action == "reboot":
                seq = self.state["seq"]
                self.state = self.default_state()
                self.state["seq"] = seq
                return True, "reiniciado con la configuración de fábrica"

            return self.handle_custom(action, params)

    def handle_custom(self, action: str, params: dict) -> tuple[bool, str]:
        return False, f"acción no soportada: {action}"

    # --------------------------------------------------------------- muestreo
    def sample(self) -> dict:
        """Devuelve {variable: valor} con la lectura actual."""
        raise NotImplementedError

    def publish_telemetry(self, values: dict) -> None:
        with self.lock:
            calibration = self.state["calibration"]
            payload = {}
            for name, value in values.items():
                if value is None or (isinstance(value, float) and math.isnan(value)):
                    continue
                adjust = calibration.get(name)
                if adjust:
                    value = value * adjust.get("gain", 1) + adjust.get("offset", 0)
                payload[name] = round(float(value), 4)
            if not payload:
                return
            self.state["seq"] += 1
            payload = {"ts": now_ms(), "seq": self.state["seq"], **payload}
            self._save_state()
        if self.telemetry_amqp:
            try:
                import amqp_link                # solo lo necesitan los dispositivos AMQP
                amqp_link.publish(self.cls, self.id, payload)
                return
            except Exception as error:          # sin RabbitMQ la lectura no se pierde: va por MQTT
                self.log.warning("AMQP no disponible (%r); la lectura sale por MQTT", error)
        self.client.publish(f"{self.base}/telemetry", json.dumps(payload), qos=1)

    def _sample_and_publish(self) -> None:
        try:
            self.publish_telemetry(self.sample())
        except Exception as error:  # una muestra fallida no debe tumbar el dispositivo
            self.log.warning("muestra fallida: %r", error)

    def run(self) -> None:
        self.client.connect_async(MQTT_HOST, MQTT_PORT, keepalive=60)
        self.client.loop_start()
        time.sleep(2)
        if self.state["enabled"]:
            self._sample_and_publish()          # primera lectura al arrancar
        while True:
            interval = self.state["interval_s"]
            wait = interval - (time.time() % interval)      # muestras alineadas al reloj
            if self.wake.wait(timeout=wait):
                self.wake.clear()               # llegó un comando: recalcular la espera
                continue
            if self.state["enabled"]:
                self._sample_and_publish()

    def shutdown(self) -> None:
        info = self.client.publish(f"{self.base}/status", "offline", qos=1, retain=True)
        try:
            info.wait_for_publish(timeout=3)
        except (ValueError, RuntimeError):
            pass
        self.client.loop_stop()
        self.client.disconnect()
