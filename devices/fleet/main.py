"""Flota virtual de AgroCraft: dispositivos de API y de dataset en un solo proceso."""

import logging
import signal
import threading

from api_devices import ApiAire, ApiClima, ApiSuelo
from dataset_devices import DsEnergia, DsInvernadero
from forecast_device import ApiPronostico

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)-15s %(levelname)s %(message)s")


def main() -> None:
    devices = [
        ApiClima("api-clima"),
        ApiAire("api-aire"),
        ApiSuelo("api-suelo"),
        ApiPronostico("api-pronostico"),
        DsInvernadero("ds-invernadero"),
        DsEnergia("ds-energia"),
    ]
    for device in devices:
        device.start()

    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    stop.wait()

    logging.info("apagando: se publica status=offline")
    for device in devices:
        device.shutdown()


if __name__ == "__main__":
    main()
