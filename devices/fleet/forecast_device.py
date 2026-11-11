"""api-pronostico: pronóstico operativo de la granja para las próximas 24-48 h.

Combina la previsión horaria de Open-Meteo (temperatura, humedad, lluvia, ET0) con el estado actual
de los gemelos (humedad del suelo de cada parcela) y aplica el mismo modelo del plugin para estimar:
  - cuántas horas faltan para que cada parcela necesite riego (suelo < 35 %),
  - cuánta agua pedirá el riego en las próximas 24 h,
  - lluvia, ET0, temperaturas extremas y horas de estrés térmico para el rebaño (THI).

Lee la telemetría de las parcelas por MQTT (agro/twin/mc-parcela-*/telemetry) y publica como
cualquier otro dispositivo de API.
"""

import json
from datetime import datetime

import requests

from api_devices import LATITUDE, LONGITUDE
from base import Device

SOIL_MIN, SOIL_MAX = 35.0, 65.0
WETTING_PCT_H = 120.0           # PlotTwin: subida de humedad con riego abierto
FLOW_LPM = 20.0                 # PlotTwin: caudal de riego
DRY_FACTOR = {"mc-parcela-a": 1.0, "mc-parcela-b": 1.25}
HORIZON_H = 48


def thi(temp_c: float, hum_pct: float) -> float:
    return (1.8 * temp_c + 32) - (0.55 - 0.0055 * hum_pct) * (1.8 * temp_c - 26)


def project_soil(soil: float, hours: list, dry: float):
    """Simula el suelo hora a hora con riego automático. Devuelve (horas hasta el primer riego, litros en 24 h)."""
    first = None
    liters = 0.0
    irrigating = False
    for i, h in enumerate(hours):
        if irrigating:                                   # fracción de la hora que el riego sigue abierto
            fraction = max(0.0, min(1.0, (SOIL_MAX - soil) / WETTING_PCT_H))
            soil += WETTING_PCT_H * fraction
            if i < 24:
                liters += FLOW_LPM * 60 * fraction
            if fraction < 1.0:
                irrigating = False
        soil -= 0.5 + 12 * h["et0"] * dry
        soil += 8 * h["rain"]
        soil = max(0.0, min(100.0, soil))
        if not irrigating and soil < SOIL_MIN:
            irrigating = True
            if first is None:
                first = i + 1
    return (first if first is not None else HORIZON_H), liters


class ApiPronostico(Device):
    cls = "api"
    default_interval = 900
    variables = ("hours_to_irrigation_a", "hours_to_irrigation_b", "water_need_24h_l", "rain_next24_mm",
                 "rain_prob_max_pct", "et0_next24_mm", "tmax_next24_c", "tmin_next24_c", "thi_max_next24",
                 "thi_stress_hours_24h", "heat_hours_24h")

    def __init__(self, device_id: str):
        super().__init__(device_id)
        self.http = requests.Session()
        self.soil = {}

    def default_state(self) -> dict:
        state = super().default_state()
        state.update(latitude=LATITUDE, longitude=LONGITUDE)
        return state

    def _on_connect(self, client, userdata, flags, reason_code, properties):
        super()._on_connect(client, userdata, flags, reason_code, properties)
        if not reason_code.is_failure:
            for plot in DRY_FACTOR:
                client.subscribe(f"agro/twin/{plot}/telemetry", qos=0)

    def _on_message(self, client, userdata, message):
        parts = message.topic.split("/")
        if len(parts) == 4 and parts[3] == "telemetry":
            try:
                value = json.loads(message.payload).get("soil_pct")
            except ValueError:
                return
            if isinstance(value, (int, float)):
                self.soil[parts[2]] = float(value)
            return
        super()._on_message(client, userdata, message)

    def _hours(self) -> list:
        response = self.http.get("https://api.open-meteo.com/v1/forecast", params={
            "latitude": self.state["latitude"], "longitude": self.state["longitude"],
            "hourly": "temperature_2m,relative_humidity_2m,precipitation,precipitation_probability,et0_fao_evapotranspiration",
            "forecast_days": 3, "timezone": "auto"}, timeout=20)
        response.raise_for_status()
        h = response.json()["hourly"]
        now = datetime.now().strftime("%Y-%m-%dT%H:00")
        start = next((i for i, t in enumerate(h["time"]) if t >= now), 0)
        out = []
        for i in range(start, min(start + HORIZON_H, len(h["time"]))):
            out.append({"temp": h["temperature_2m"][i] or 0, "hum": h["relative_humidity_2m"][i] or 0,
                        "rain": h["precipitation"][i] or 0, "prob": h["precipitation_probability"][i] or 0,
                        "et0": h["et0_fao_evapotranspiration"][i] or 0})
        return out

    def sample(self) -> dict:
        hours = self._hours()
        day = hours[:24]
        values = {
            "rain_next24_mm": sum(h["rain"] for h in day),
            "rain_prob_max_pct": max(h["prob"] for h in day),
            "et0_next24_mm": sum(h["et0"] for h in day),
            "tmax_next24_c": max(h["temp"] for h in day),
            "tmin_next24_c": min(h["temp"] for h in day),
            "thi_max_next24": max(thi(h["temp"], h["hum"]) for h in day),
            "thi_stress_hours_24h": sum(1 for h in day if thi(h["temp"], h["hum"]) > 75),
            "heat_hours_24h": sum(1 for h in day if h["temp"] > 28),
        }
        water = 0.0
        for plot, dry in DRY_FACTOR.items():
            if plot in self.soil:
                first, liters = project_soil(self.soil[plot], hours, dry)
                values["hours_to_irrigation_" + plot[-1]] = first
                water += liters
        if self.soil:
            values["water_need_24h_l"] = water
        return values
