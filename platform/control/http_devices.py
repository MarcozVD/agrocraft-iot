"""Dispositivos que hablan HTTPS en lugar de MQTT.

El servicio de control los representa ante el bróker: cada uno tiene su propio cliente MQTT, con sus
credenciales y su última voluntad, y obedece los mismos comandos de retroceso que el resto de la
flota (encender/apagar, intervalo, calibración, reinicio). El dispositivo solo hace POST.

phone-01: celular con la app Sensor Logger (Android/iOS) en modo "HTTP Push".
  URL:   https://<host>/iot/api/ingest/phone-01?token=<MQTT_PASS_PHONE_01>
  Sensores a activar en la app: Light (solo Android), Barometer, Battery, Accelerometer, Microphone.
  En iPhone no hay sensor de luz accesible: llegan presión, altitud relativa, sonido, aceleración y batería.
  La ubicación GPS se descarta a propósito: Grafana es de lectura pública.
"""

import math
import time

from base import MQTT_HOST, MQTT_PORT, Device, password_for


class PhoneProxy(Device):
    cls = "real"
    default_interval = 15
    variables = ("lux", "pressure_hpa", "rel_alt_m", "battery_pct", "plugged", "accel_ms2", "sound_dbfs")

    def __init__(self, device_id: str):
        super().__init__(device_id)
        self.token = password_for(device_id)
        self.latest: dict = {}
        self.last_publish = 0.0
        self.last_ingest = 0.0
        self.online = False

    # ----------------------------------------------------------- conexión y estado en línea
    def _on_connect(self, client, userdata, flags, reason_code, properties):
        super()._on_connect(client, userdata, flags, reason_code, properties)
        if not reason_code.is_failure and not self.online:
            client.publish(f"{self.base}/status", "offline", qos=1, retain=True)

    def _set_online(self, online: bool) -> None:
        if online != self.online:
            self.online = online
            self.client.publish(f"{self.base}/status", "online" if online else "offline", qos=1, retain=True)

    def run(self) -> None:
        """Sin muestreo propio: los datos llegan por HTTP. Solo vigila si el celular dejó de enviar."""
        self.client.connect_async(MQTT_HOST, MQTT_PORT, keepalive=60)
        self.client.loop_start()
        while True:
            time.sleep(5)
            silence = time.time() - self.last_ingest
            if self.online and silence > max(60, 3 * self.state["interval_s"]):
                self._set_online(False)

    def sample(self) -> dict:
        return dict(self.latest)

    # -------------------------------------------------------------------------- ingesta
    @staticmethod
    def parse(body) -> dict:
        """Acepta el formato de Sensor Logger o un JSON plano {"variable": número}."""
        out = {}
        if isinstance(body, dict) and isinstance(body.get("payload"), list):
            for item in body["payload"]:
                name = str(item.get("name", "")).lower()
                values = item.get("values") or {}
                if name == "light":
                    out["lux"] = values.get("lux")
                elif name == "barometer":
                    out["pressure_hpa"] = values.get("pressure")
                    out["rel_alt_m"] = values.get("relativeAltitude")
                elif name == "battery":
                    level = values.get("batteryLevel")
                    if isinstance(level, (int, float)):
                        out["battery_pct"] = level * 100 if level <= 1 else level
                    state = str(values.get("batteryState", "")).lower()
                    if state:
                        out["plugged"] = 1 if state in ("charging", "full") else 0
                elif name in ("accelerometer", "totalacceleration"):
                    axes = [values.get(axis) for axis in ("x", "y", "z")]
                    if all(isinstance(a, (int, float)) for a in axes):
                        out["accel_ms2"] = math.sqrt(sum(a * a for a in axes))
                elif name == "microphone":
                    out["sound_dbfs"] = values.get("dBFS")
        elif isinstance(body, dict):
            out = {key: body.get(key) for key in PhoneProxy.variables}
        return {k: float(v) for k, v in out.items() if isinstance(v, (int, float)) and not isinstance(v, bool)}

    def ingest(self, body) -> dict:
        values = self.parse(body)
        if not values:
            return {"ok": False, "detail": "sin variables reconocidas"}
        with self.lock:
            enabled = self.state["enabled"]
            interval = self.state["interval_s"]
        self.last_ingest = time.time()
        self._set_online(True)
        if not enabled:
            return {"ok": True, "detail": "dispositivo apagado por comando: datos descartados"}
        self.latest.update(values)
        if time.time() - self.last_publish < interval:
            return {"ok": True, "detail": f"recibido; se publica cada {interval} s", "variables": sorted(values)}
        self.last_publish = time.time()
        self.publish_telemetry(self.latest)
        return {"ok": True, "detail": "publicado", "variables": sorted(self.latest)}
