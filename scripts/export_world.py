"""Exporta los bloques de la granja real a platform/control/static/gemelo/farm.json para la vista 3D.

Lee directamente los archivos de región (.mca, formato Anvil 1.18+) sin dependencias externas:
cabecera de 4 KiB con la posición de cada chunk, chunk comprimido con zlib, NBT con `sections` y
`block_states` (paleta + arreglo empaquetado de longs, sin entradas partidas entre longs).

Uso (desde la raíz del repo, con el servidor guardado antes: `rcon-cli save-all flush`):
    ssh ... 'cd ~/agrocraft/data/minecraft/agrocraft/region && tar cf - r.-1.-1.mca r.0.-1.mca r.-1.0.mca r.0.0.mca' | tar xf - -C <dir>
    python scripts/export_world.py <dir>

Los bloques que cambian con la telemetría (cultivos, agua del canal y del tanque, techo y ventilas del
invernadero, lámparas) se exportan igual; la página los oculta y los dibuja según los datos en vivo.
"""

import base64
import json
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "platform" / "control" / "static" / "gemelo" / "farm.json"

Y0 = -61                       # suelo de la granja (dy = 0)
X1, X2 = -50, 78               # plano v2 (Layout.FARM) más un margen de 6 bloques
Z1, Z2 = -54, 46
DY1, DY2 = 0, 24
KEEP_PROPS = ("facing", "half", "type", "open", "lit", "hanging", "axis", "shape")


# ------------------------------------------------------------------------------------------- NBT
class Reader:
    def __init__(self, data: bytes):
        self.data, self.pos = data, 0

    def take(self, fmt: str):
        size = struct.calcsize(fmt)
        value = struct.unpack_from(">" + fmt, self.data, self.pos)
        self.pos += size
        return value[0] if len(value) == 1 else value

    def string(self) -> str:
        length = self.take("H")
        text = self.data[self.pos:self.pos + length].decode("utf-8", errors="replace")
        self.pos += length
        return text

    def payload(self, tag: int):
        if tag == 1:
            return self.take("b")
        if tag == 2:
            return self.take("h")
        if tag == 3:
            return self.take("i")
        if tag == 4:
            return self.take("q")
        if tag == 5:
            return self.take("f")
        if tag == 6:
            return self.take("d")
        if tag == 7:
            n = self.take("i")
            self.pos += n
            return None
        if tag == 8:
            return self.string()
        if tag == 9:
            inner, n = self.take("b"), self.take("i")
            return [self.payload(inner) for _ in range(n)]
        if tag == 10:
            out = {}
            while True:
                inner = self.take("b")
                if inner == 0:
                    return out
                name = self.string()
                out[name] = self.payload(inner)
        if tag == 11:
            n = self.take("i")
            values = struct.unpack_from(f">{n}i", self.data, self.pos)
            self.pos += 4 * n
            return list(values)
        if tag == 12:
            n = self.take("i")
            values = struct.unpack_from(f">{n}q", self.data, self.pos)
            self.pos += 8 * n
            return list(values)
        raise ValueError(f"etiqueta NBT desconocida {tag}")


def read_nbt(data: bytes) -> dict:
    reader = Reader(data)
    tag = reader.take("b")
    reader.string()
    return reader.payload(tag)


# ------------------------------------------------------------------------------------------ Anvil
def chunk(region_dir: Path, cx: int, cz: int):
    path = region_dir / f"r.{cx >> 5}.{cz >> 5}.mca"
    if not path.exists():
        return None
    raw = path.read_bytes()
    index = 4 * ((cx & 31) + (cz & 31) * 32)
    offset = int.from_bytes(raw[index:index + 3], "big") * 4096
    if offset == 0:
        return None
    length = struct.unpack_from(">i", raw, offset)[0]
    compression = raw[offset + 4]
    body = raw[offset + 5:offset + 4 + length]
    if compression == 2:
        body = zlib.decompress(body)
    elif compression != 3:
        raise ValueError(f"compresión {compression} no soportada")
    return read_nbt(body)


def state_key(entry: dict) -> str:
    name = entry["Name"].removeprefix("minecraft:")
    props = entry.get("Properties") or {}
    kept = [f"{k}={props[k]}" for k in KEEP_PROPS if k in props]
    return name + ("[" + ",".join(kept) + "]" if kept else "")


def section_blocks(section: dict):
    states = section.get("block_states") or {}
    palette = states.get("palette") or []
    data = states.get("data")
    if not palette:
        return None
    if data is None:
        return [state_key(palette[0])] * 4096
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    keys = [state_key(p) for p in palette]
    out = []
    for value in data:
        value &= (1 << 64) - 1
        for _ in range(per_long):
            out.append(keys[value & mask])
            value >>= bits
            if len(out) == 4096:
                return out
    return out


def main(region_dir: Path) -> None:
    sx, sy, sz = X2 - X1 + 1, DY2 - DY1 + 1, Z2 - Z1 + 1
    grid = [0] * (sx * sy * sz)
    palette = ["air"]
    lookup = {"air": 0}
    for cx in range(X1 >> 4, (X2 >> 4) + 1):
        for cz in range(Z1 >> 4, (Z2 >> 4) + 1):
            root = chunk(region_dir, cx, cz)
            if root is None:
                continue
            for section in root.get("sections", []):
                base_y = section["Y"] * 16
                if base_y + 15 < Y0 + DY1 or base_y > Y0 + DY2:
                    continue
                blocks = section_blocks(section)
                if blocks is None:
                    continue
                for i, key in enumerate(blocks):
                    if key in ("air", "cave_air", "void_air"):
                        continue
                    x = cx * 16 + (i & 15)
                    z = cz * 16 + ((i >> 4) & 15)
                    y = base_y + (i >> 8)
                    if not (X1 <= x <= X2 and Z1 <= z <= Z2 and Y0 + DY1 <= y <= Y0 + DY2):
                        continue
                    if key not in lookup:
                        lookup[key] = len(palette)
                        palette.append(key)
                    grid[((y - Y0 - DY1) * sz + (z - Z1)) * sx + (x - X1)] = lookup[key]
    if len(palette) > 65535:
        raise SystemExit("paleta demasiado grande")
    wide = len(palette) > 255
    packed = struct.pack(f"<{len(grid)}{'H' if wide else 'B'}", *grid)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({
        "version": 1, "origin": [X1, DY1, Z1], "size": [sx, sy, sz], "y0": Y0,
        "order": "y,z,x", "bytes": 2 if wide else 1, "palette": palette,
        "data": base64.b64encode(zlib.compress(packed, 9)).decode("ascii"),
    }, separators=(",", ":")), encoding="utf-8")
    used = sum(1 for v in grid if v)
    print(f"{OUT.relative_to(ROOT)}: {used} bloques, paleta {len(palette)}, {OUT.stat().st_size // 1024} KiB")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
