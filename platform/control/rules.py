"""Reglas en la nube: retroceso iniciado por la plataforma a partir de la telemetría.

Los gemelos ya tienen control local (riego, bomba, ventilas por umbral). Estas reglas cruzan datos
de dispositivos distintos, algo que ningún dispositivo puede decidir solo:

  lamparas-radiacion  Día nublado (radiación real baja) -> enciende las lámparas de crecimiento de
                      las parcelas; con sol o de noche las apaga. Histéresis 150/250 W/m².
  aire-ventilas       Aire exterior malo (US AQI > 100) -> cierra las ventilas del invernadero y
                      lo saca de automático; cuando mejora (< 80) lo devuelve a automático.
  riego-pronostico    El pronóstico (api-pronostico) anuncia lluvia >= 5 mm con probabilidad >= 70 %
                      en 24 h -> baja el umbral de riego de las parcelas de 35 % a 25 %: se deja que
                      llueva y se ahorra agua del tanque, sin dejar secar el cultivo. Con < 2 mm
                      previstos vuelve a 35 %.

Cada decisión queda como evento en agro/platform/reglas/event y el comando en el canal cmd.
"""

import asyncio
import json
import logging
import os
from pathlib import Path

log = logging.getLogger("reglas")
STATE_FILE = Path(os.environ.get("STATE_DIR", "/state")) / "rules.json"


class Rules:
    def __init__(self, hub):
        self.hub = hub
        self.enabled = {"lamparas-radiacion": True, "aire-ventilas": True, "riego-pronostico": True}
        self.last = {}          # regla -> última decisión ("on"/"off")
        try:
            saved = json.loads(STATE_FILE.read_text())
            self.enabled.update(saved.get("enabled", {}))
            self.last.update(saved.get("last", {}))
        except (OSError, ValueError):
            pass

    def _save(self) -> None:
        STATE_FILE.write_text(json.dumps({"enabled": self.enabled, "last": self.last}))

    def describe(self) -> dict:
        return {"rules": [
            {"id": "lamparas-radiacion", "enabled": self.enabled["lamparas-radiacion"],
             "last": self.last.get("lamparas-radiacion"),
             "description": "De día, con radiación real < 150 W/m² enciende las lámparas de las parcelas; "
                            "> 250 W/m² o de noche las apaga."},
            {"id": "aire-ventilas", "enabled": self.enabled["aire-ventilas"],
             "last": self.last.get("aire-ventilas"),
             "description": "Con US AQI exterior > 100 cierra las ventilas del invernadero; "
                            "< 80 lo devuelve a control automático."},
            {"id": "riego-pronostico", "enabled": self.enabled["riego-pronostico"],
             "last": self.last.get("riego-pronostico"),
             "description": "Si se pronostican >= 5 mm de lluvia (prob. >= 70 %) en 24 h baja el umbral de riego "
                            "de las parcelas a 25 %; con < 2 mm lo devuelve a 35 %."},
        ]}

    def set_enabled(self, rule: str, enabled: bool) -> bool:
        if rule not in self.enabled:
            return False
        self.enabled[rule] = enabled
        self._save()
        self.hub.platform_event("reglas", {"type": "rule_toggle", "rule": rule, "enabled": int(enabled)})
        return True

    # ------------------------------------------------------------------------- evaluación
    def on_message(self, cls, device, channel, data) -> None:
        if channel != "telemetry" or not isinstance(data, dict):
            return
        if device == "api-clima" and self.enabled["lamparas-radiacion"]:
            self._lamps(data)
        elif device == "api-aire" and self.enabled["aire-ventilas"]:
            self._air(data)
        elif device == "api-pronostico" and self.enabled["riego-pronostico"]:
            self._forecast(data)

    def _decide(self, rule: str, decision: str, reason: str, commands: list) -> None:
        if self.last.get(rule) == decision:
            return
        self.last[rule] = decision
        self._save()
        self.hub.platform_event("reglas", {"type": "rule", "rule": rule, "decision": decision, "reason": reason})
        log.info("%s -> %s (%s)", rule, decision, reason)
        for cls, device, action, params in commands:
            asyncio.ensure_future(self.hub.command(cls, device, action, params, source=f"regla-{rule}"))

    def _lamps(self, data: dict) -> None:
        radiation, is_day = data.get("radiation_wm2"), data.get("is_day")
        if radiation is None or is_day is None:
            return
        if is_day and radiation < 150:
            decision = "on"
        elif not is_day or radiation > 250:
            decision = "off"
        else:
            return                                   # zona de histéresis: no cambia
        value = 1 if decision == "on" else 0
        self._decide("lamparas-radiacion", decision,
                     f"radiación {radiation:.0f} W/m², {'día' if is_day else 'noche'}",
                     [("twin", plot, "set_actuator", {"name": "lamp", "value": value})
                      for plot in ("mc-parcela-a", "mc-parcela-b")])

    def _air(self, data: dict) -> None:
        aqi = data.get("us_aqi")
        if aqi is None:
            return
        if aqi > 100:
            self._decide("aire-ventilas", "on", f"US AQI {aqi:.0f}",
                         [("twin", "mc-invernadero", "set_actuator", {"name": "vent", "value": 0})])
        elif aqi < 80:
            self._decide("aire-ventilas", "off", f"US AQI {aqi:.0f}",
                         [("twin", "mc-invernadero", "set_config", {"auto": 1})])

    def _forecast(self, data: dict) -> None:
        rain, prob = data.get("rain_next24_mm"), data.get("rain_prob_max_pct")
        if rain is None or prob is None:
            return
        if rain >= 5 and prob >= 70:
            decision, soil_min = "on", 25
        elif rain < 2:
            decision, soil_min = "off", 35
        else:
            return                                   # zona de histéresis
        self._decide("riego-pronostico", decision,
                     f"lluvia prevista {rain:.1f} mm en 24 h (prob. máx. {prob:.0f} %)",
                     [("twin", plot, "set_config", {"soil_min": soil_min})
                      for plot in ("mc-parcela-a", "mc-parcela-b")])
