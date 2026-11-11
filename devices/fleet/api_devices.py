"""Dispositivos de API: consultan Open-Meteo por HTTPS (pull) y publican el resultado por MQTT.

Open-Meteo no pide clave. Las condiciones actuales se actualizan cada 15 minutos (clima y suelo)
o cada hora (calidad del aire); entre actualizaciones la lectura se repite.
"""

import os

import requests

from base import Device

LATITUDE = float(os.environ.get("LATITUDE", "7.119"))       # Bucaramanga
LONGITUDE = float(os.environ.get("LONGITUDE", "-73.123"))


class OpenMeteoDevice(Device):
    cls = "api"
    url = "https://api.open-meteo.com/v1/forecast"
    mapping: dict = {}          # variable de la API -> variable publicada

    def __init__(self, device_id: str):
        super().__init__(device_id)
        self.http = requests.Session()

    @property
    def variables(self) -> tuple:
        return tuple(self.mapping.values())

    def default_state(self) -> dict:
        state = super().default_state()
        state.update(latitude=LATITUDE, longitude=LONGITUDE)
        return state

    def sample(self) -> dict:
        response = self.http.get(
            self.url,
            params={
                "latitude": self.state["latitude"],
                "longitude": self.state["longitude"],
                "current": ",".join(self.mapping),
                "timezone": "auto",
            },
            timeout=20,
        )
        response.raise_for_status()
        current = response.json()["current"]
        return {ours: current.get(theirs) for theirs, ours in self.mapping.items()}

    def handle_custom(self, action: str, params: dict) -> tuple[bool, str]:
        if action == "set_location":
            latitude, longitude = float(params["latitude"]), float(params["longitude"])
            if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
                return False, "coordenadas fuera de rango"
            self.state.update(latitude=latitude, longitude=longitude)
            return True, f"ubicación={latitude},{longitude}"
        return super().handle_custom(action, params)


class ApiClima(OpenMeteoDevice):
    """Estación meteorológica exterior. Además de graficarse, gobierna el clima del mundo de Minecraft."""

    default_interval = 300
    mapping = {
        "temperature_2m": "temp_c",
        "relative_humidity_2m": "hum_pct",
        "precipitation": "precip_mm",
        "rain": "rain_mm",
        "wind_speed_10m": "wind_kmh",
        "shortwave_radiation": "radiation_wm2",
        "surface_pressure": "pressure_hpa",
        "cloud_cover": "cloud_pct",
        "is_day": "is_day",
        "weather_code": "weather_code",
    }


class ApiAire(OpenMeteoDevice):
    """Calidad del aire exterior."""

    default_interval = 600
    url = "https://air-quality-api.open-meteo.com/v1/air-quality"
    mapping = {
        "pm2_5": "pm2_5",
        "pm10": "pm10",
        "ozone": "o3",
        "nitrogen_dioxide": "no2",
        "uv_index": "uv_index",
        "us_aqi": "us_aqi",
    }


class ApiSuelo(OpenMeteoDevice):
    """Suelo y evapotranspiración: marca el ritmo al que se secan las parcelas del gemelo."""

    default_interval = 600
    mapping = {
        "soil_temperature_0cm": "soil_temp_c",
        "soil_moisture_0_to_1cm": "soil_moist_m3m3",
        "et0_fao_evapotranspiration": "et0_mm",
        "evapotranspiration": "et_mm",
        "vapour_pressure_deficit": "vpd_kpa",
    }
