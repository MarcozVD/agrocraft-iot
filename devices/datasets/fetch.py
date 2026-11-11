#!/usr/bin/env python3
"""Descarga los datasets públicos y los deja listos para reproducir (paso fijo, sin huecos de índice).

Uso:  python3 fetch.py <carpeta_destino>
Solo usa la biblioteca estándar. Si un archivo ya existe, no lo vuelve a generar.

Salida:
  invernadero.csv  t,temp_c,hum_pct,co2_ppm,par_umol,vent_pct,pipe_temp_c,irrigation_lm2   (cada 5 min)
  energia.csv      t,power_kw,reactive_kvar,voltage_v,current_a                            (cada 1 min)
"""

import csv
import io
import sys
import urllib.request
import zipfile
from datetime import datetime, timedelta
from pathlib import Path

GREENHOUSE_URL = (
    "https://raw.githubusercontent.com/CEAOD/Data/master/"
    "GH_Tomato/AutonomousGreenhouseChallenge2019/Reference/GreenhouseClimate.csv"
)
GREENHOUSE_COLUMNS = {      # columna original -> variable publicada
    "Tair": "temp_c",
    "Rhair": "hum_pct",
    "CO2air": "co2_ppm",
    "Tot_PAR": "par_umol",
    "VentLee": "vent_pct",
    "PipeLow": "pipe_temp_c",
    "Cum_irr": "irrigation_lm2",
}
EXCEL_EPOCH = datetime(1899, 12, 30)

ENERGY_URL = "https://archive.ics.uci.edu/static/public/235/individual+household+electric+power+consumption.zip"
ENERGY_COLUMNS = {
    "Global_active_power": "power_kw",
    "Global_reactive_power": "reactive_kvar",
    "Voltage": "voltage_v",
    "Global_intensity": "current_a",
}
ENERGY_FROM, ENERGY_TO = datetime(2009, 10, 1), datetime(2010, 2, 1)    # ventana alrededor de 2009-11-11


def download(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "agrocraft-dataset-fetch/1.0"})
    with urllib.request.urlopen(request, timeout=300) as response:
        return response.read()


def number(text: str):
    text = text.strip()
    if text in ("", "?", "NaN", "nan"):
        return None
    try:
        return float(text)
    except ValueError:
        return None


def write_dense(path: Path, names: list, samples: dict, step: timedelta) -> None:
    """Escribe una fila por paso entre la primera y la última marca; los pasos sin dato quedan vacíos."""
    first, last = min(samples), max(samples)
    rows = missing = 0
    with open(path, "w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["t", *names])
        moment = first
        while moment <= last:
            values = samples.get(moment)
            if values is None:
                missing += 1
                values = [None] * len(names)
            writer.writerow([moment.isoformat(sep=" "), *("" if v is None else f"{v:g}" for v in values)])
            rows += 1
            moment += step
    print(f"{path.name}: {rows} filas, {first} a {last}, {missing} pasos sin dato")


def greenhouse(out: Path) -> None:
    text = download(GREENHOUSE_URL).decode("utf-8", errors="replace")
    reader = csv.reader(io.StringIO(text))
    header = [name.strip() for name in next(reader)]
    index = {name: header.index(name) for name in GREENHOUSE_COLUMNS}
    samples = {}
    for record in reader:
        serial = number(record[0])
        if serial is None:
            continue
        moment = EXCEL_EPOCH + timedelta(days=serial)
        moment = datetime.min + timedelta(seconds=round((moment - datetime.min).total_seconds() / 300) * 300)
        samples[moment] = [number(record[index[name]]) for name in GREENHOUSE_COLUMNS]
    write_dense(out, list(GREENHOUSE_COLUMNS.values()), samples, timedelta(minutes=5))


def energy(out: Path) -> None:
    archive = zipfile.ZipFile(io.BytesIO(download(ENERGY_URL)))
    name = next(n for n in archive.namelist() if n.endswith(".txt"))
    samples = {}
    with archive.open(name) as raw:
        reader = csv.reader(io.TextIOWrapper(raw, encoding="latin-1"), delimiter=";")
        header = next(reader)
        index = {column: header.index(column) for column in ENERGY_COLUMNS}
        for record in reader:
            if len(record) < len(header):
                continue
            year = record[0][-4:]
            if year not in ("2009", "2010"):
                continue
            moment = datetime.strptime(f"{record[0]} {record[1]}", "%d/%m/%Y %H:%M:%S")
            if ENERGY_FROM <= moment < ENERGY_TO:
                samples[moment] = [number(record[index[column]]) for column in ENERGY_COLUMNS]
    write_dense(out, list(ENERGY_COLUMNS.values()), samples, timedelta(minutes=1))


def main() -> None:
    target = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    target.mkdir(parents=True, exist_ok=True)
    for filename, build in (("invernadero.csv", greenhouse), ("energia.csv", energy)):
        path = target / filename
        if path.exists():
            print(f"{filename}: ya existe, se omite")
        else:
            build(path)


if __name__ == "__main__":
    main()
