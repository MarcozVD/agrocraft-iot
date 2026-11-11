"""gw-pc-01: el PC del operario como gateway de campo.

Dispositivo REAL: mide el propio equipo (CPU, memoria, disco, batería, Wi-Fi y enlace hacia la nube)
y publica por MQTT 3.1.1 sobre TLS (puerto 8883). Reutiliza la base de la flota virtual, así que
entiende los mismos comandos de retroceso: set_enabled, set_interval, calibrate y reboot.

Configuración: archivo .env junto a este script con MQTT_PASS_GW_PC_01=<clave del bróker>.
Ejecutar:      python agent.py
"""

import logging
import os
import re
import socket
import subprocess
import sys
import time
from logging.handlers import RotatingFileHandler
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load_env(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())


load_env(HERE / ".env")
os.environ.setdefault("MQTT_HOST", "agrocraft.northcentralus.cloudapp.azure.com")
os.environ.setdefault("MQTT_PORT", "8883")
os.environ.setdefault("MQTT_TLS", "1")
os.environ.setdefault("STATE_DIR", str(HERE / "state"))
Path(os.environ["STATE_DIR"]).mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(HERE.parent.parent / "fleet"))

import psutil  # noqa: E402

from base import MQTT_HOST, MQTT_PORT, Device  # noqa: E402

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


class PcGateway(Device):
    cls = "real"
    default_interval = 30
    variables = (
        "cpu_pct", "ram_pct", "disk_pct", "battery_pct", "plugged",
        "wifi_signal_pct", "wifi_rssi_dbm", "net_up_kbps", "net_down_kbps", "link_rtt_ms",
    )

    def __init__(self, device_id: str):
        super().__init__(device_id)
        psutil.cpu_percent(interval=None)       # la primera llamada solo fija la referencia
        self._net = psutil.net_io_counters()
        self._net_at = time.monotonic()

    def sample(self) -> dict:
        values = {
            "cpu_pct": psutil.cpu_percent(interval=None),
            "ram_pct": psutil.virtual_memory().percent,
            "disk_pct": psutil.disk_usage(os.environ.get("SystemDrive", "C:") + "\\").percent,
            "link_rtt_ms": self._link_rtt(),
        }
        battery = psutil.sensors_battery()
        if battery is not None:
            values["battery_pct"] = battery.percent
            values["plugged"] = 1 if battery.power_plugged else 0

        net, now = psutil.net_io_counters(), time.monotonic()
        elapsed = max(now - self._net_at, 1e-3)
        values["net_up_kbps"] = (net.bytes_sent - self._net.bytes_sent) * 8 / 1000 / elapsed
        values["net_down_kbps"] = (net.bytes_recv - self._net.bytes_recv) * 8 / 1000 / elapsed
        self._net, self._net_at = net, now

        values.update(self._wifi())
        return values

    @staticmethod
    def _link_rtt():
        """Tiempo de establecimiento TCP hacia el bróker: latencia real del enlace PC -> nube."""
        started = time.perf_counter()
        try:
            with socket.create_connection((MQTT_HOST, MQTT_PORT), timeout=5):
                return (time.perf_counter() - started) * 1000
        except OSError:
            return None

    @staticmethod
    def _wifi() -> dict:
        """Intensidad de la señal Wi-Fi según Windows (netsh). Sin Wi-Fi o sin permiso, no reporta nada."""
        try:
            output = subprocess.run(
                ["netsh", "wlan", "show", "interfaces"],
                capture_output=True, timeout=10, creationflags=NO_WINDOW,
            ).stdout.decode("cp850", errors="replace")
        except (OSError, subprocess.SubprocessError):
            return {}
        result = {}
        signal = re.search(r"^\s*(?:Se.{1,2}al|Signal)\s*:\s*(\d+)\s*%", output, re.MULTILINE | re.IGNORECASE)
        if signal:
            percent = int(signal.group(1))
            result["wifi_signal_pct"] = percent
            result["wifi_rssi_dbm"] = percent / 2 - 100      # conversión habitual de calidad a dBm
        rssi = re.search(r"^\s*Rssi\s*:\s*(-?\d+)", output, re.MULTILINE | re.IGNORECASE)
        if rssi:
            result["wifi_rssi_dbm"] = int(rssi.group(1))
        return result


def main() -> None:
    handlers = [RotatingFileHandler(HERE / "agent.log", maxBytes=500_000, backupCount=2, encoding="utf-8")]
    if sys.stderr is not None:                  # con pythonw no hay consola
        handlers.append(logging.StreamHandler())
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s",
                        handlers=handlers)
    device = PcGateway("gw-pc-01")
    device.start()
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        device.shutdown()


if __name__ == "__main__":
    main()
