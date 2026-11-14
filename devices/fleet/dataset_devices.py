"""Dispositivos de dataset: reproducen un CSV histórico como si el equipo estuviera midiendo ahora.

La fecha del dataset se ata a la fecha real con un desplazamiento fijo, conservando la hora del día
(y el día de la semana cuando el desplazamiento es múltiplo de 7). Cada muestra sale con la marca de
tiempo actual. Los CSV los prepara devices/datasets/fetch.py: paso fijo, columna `t` en hora local.
"""

import csv
import os
import time
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from base import Device

DATASET_DIR = Path(os.environ.get("DATASET_DIR", "/datasets"))
LOCAL_TZ = ZoneInfo(os.environ.get("TZ", "America/Bogota"))
MAX_SPEED = 120.0


class ReplayDevice(Device):
    cls = "dataset"
    csv_name = ""
    step_s = 300                      # paso del CSV preparado
    wall_ref = date(2026, 11, 11)     # esta fecha real ...
    data_ref = date(2020, 4, 1)       # ... corresponde a esta fecha del dataset

    def __init__(self, device_id: str):
        with open(DATASET_DIR / self.csv_name, newline="") as handle:
            reader = csv.reader(handle)
            header = next(reader)
            self.columns = tuple(header[1:])
            self.rows = []
            self.t0 = None
            for record in reader:
                if self.t0 is None:
                    self.t0 = datetime.fromisoformat(record[0])
                self.rows.append(tuple(float(cell) if cell else None for cell in record[1:]))
        self.length_s = len(self.rows) * self.step_s
        self.default_interval = self.step_s
        super().__init__(device_id)
        self.log.info("%d filas desde %s, paso %d s", len(self.rows), self.t0, self.step_s)

    @property
    def variables(self) -> tuple:
        return self.columns

    # ------------------------------------------------------- reloj del dataset
    def _default_anchor(self) -> dict:
        now_local = datetime.now(LOCAL_TZ).replace(tzinfo=None)
        target = now_local - (self.wall_ref - self.data_ref)
        return {"anchor_wall": time.time(), "anchor_data": (target - self.t0).total_seconds()}

    def default_state(self) -> dict:
        state = super().default_state()
        state["speed"] = 1.0
        state.update(self._default_anchor())
        return state

    def data_seconds(self) -> float:
        state = self.state
        elapsed = (time.time() - state["anchor_wall"]) * state["speed"]
        return (state["anchor_data"] + elapsed) % self.length_s

    def sample(self) -> dict:
        index = int(self.data_seconds() // self.step_s)
        return dict(zip(self.columns, self.rows[index]))

    # --------------------------------------------------------------- comandos
    def handle_custom(self, action: str, params: dict) -> tuple[bool, str]:
        if action == "set_speed":
            speed = float(params["speed"])
            if not 0 <= speed <= MAX_SPEED:
                return False, f"speed fuera de rango [0, {MAX_SPEED:g}]"
            self.state.update(anchor_data=self.data_seconds(), anchor_wall=time.time(), speed=speed)
            return True, f"speed={speed:g}"
        if action == "restart":
            self.state["speed"] = 1.0
            self.state.update(self._default_anchor())
            return True, "reproducción reiniciada en la fecha de referencia"
        return super().handle_custom(action, params)


class DsInvernadero(ReplayDevice):
    """Invernadero real de referencia (Autonomous Greenhouse Challenge 2019, equipo Reference), cada 5 min."""

    csv_name = "invernadero.csv"
    step_s = 300
    data_ref = date(2020, 4, 1)


class DsEnergia(ReplayDevice):
    telemetry_amqp = True             # demuestra un segundo protocolo de mensajería: AMQP 0-9-1
    """Medidor eléctrico (UCI Individual Household Electric Power Consumption), cada minuto.

    2009-11-11 y 2026-11-11 son miércoles: la reproducción conserva el día de la semana.
    """

    csv_name = "energia.csv"
    step_s = 60
    data_ref = date(2009, 11, 11)
