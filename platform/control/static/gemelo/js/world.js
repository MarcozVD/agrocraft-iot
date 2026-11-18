// Convierte los bloques exportados del mundo (farm.json) en mallas instanciadas: una para todo lo opaco,
// una para el vidrio y una para las linternas. Colores planos propios (sin texturas del juego).
// Coordenadas de la escena: x y z del mundo; y = altura sobre el suelo (dy). El bloque (x, dy, z) ocupa
// el cubo [x, x+1] × [dy, dy+1] × [z, z+1].

import * as THREE from "three";

const COLORS = {
  grass_block: "#6fa046", dirt: "#7a5535", coarse_dirt: "#6e4e32", dirt_path: "#b39a62", farmland: "#5a3a22",
  oak_fence: "#a7834f", oak_fence_gate: "#a7834f", oak_log: "#6b5232", oak_leaves: "#4c8b32", oak_sign: "#b8935a",
  oak_trapdoor: "#a07a45", spruce_log: "#5a4128", stripped_spruce_log: "#a37c4c", spruce_fence: "#6f5232",
  spruce_slab: "#7a5a37", spruce_planks: "#7a5a37", dark_oak_slab: "#4a3320", dark_oak_log: "#3d2c1a",
  birch_log: "#ddd6c4", birch_leaves: "#7aa64b", hay_block: "#d4ad3a", composter: "#8a6236", water_cauldron: "#4b4f55",
  smooth_stone: "#a6a6a2", smooth_stone_slab: "#a6a6a2", stone: "#8c8c88", stone_bricks: "#8e8e8a",
  chiseled_stone_bricks: "#86867f", mossy_stone_bricks: "#7d8a6a", stone_brick_slab: "#8e8e8a",
  polished_andesite: "#9a9d9a", bricks: "#a2523d", smoker: "#5b5650", barrel: "#8e6a3c", crafting_table: "#9b7346",
  packed_ice: "#9ec8f0", observer: "#5f5f5f", lever: "#7b7b7b", ladder: "#9c7a48", iron_chain: "#4a4f57",
  iron_bars: "#6c7178", daylight_detector: "#c9b48a", waxed_cut_copper: "#c26f48", waxed_cut_copper_slab: "#c26f48",
  red_concrete: "#9e2b27", orange_concrete: "#e0681a", yellow_concrete: "#f0b324", lime_concrete: "#5fa818",
  white_concrete: "#e3e6e8", light_gray_concrete: "#8b8b85", red_wool: "#a8302b", flowering_azalea: "#6c9a3c",
  azalea: "#5f8f38", hopper: "#47494d", lectern: "#9b7346", campfire: "#4a3a2a", sweet_berry_bush: "#3f7a33",
  redstone_lamp: "#7a4d2d", lantern: "#f6c05c", glass: "#d6eef5", glass_pane: "#cfe8f0", water: "#3d7fd6",
  orange_stained_glass: "#f0a050",
};

// Bloques que la telemetría redibuja (se omiten aquí y los crea twins.js)
const DYNAMIC = new Set(["wheat", "carrots", "potatoes", "farmland", "redstone_lamp", "campfire",
                         "sweet_berry_bush", "oak_fence_gate", "oak_trapdoor"]);
const GREENHOUSE = [24, -24, 32, -16];
const TANK_IN = [1, -17, 3, -15];
const CONNECTS = /(fence|bars|pane|wall)$/;

export async function loadFarm(url) {
  const json = await (await fetch(url, { cache: "no-cache" })).json();
  const packed = Uint8Array.from(atob(json.data), (c) => c.charCodeAt(0));
  const stream = new Blob([packed]).stream().pipeThrough(new DecompressionStream("deflate"));
  const bytes = new Uint8Array(await new Response(stream).arrayBuffer());
  const grid = json.bytes === 2 ? new Uint16Array(bytes.buffer) : bytes;
  return { ...json, grid };
}

function parse(key) {
  const [name, rest] = key.split("[");
  const props = {};
  if (rest) {
    for (const pair of rest.slice(0, -1).split(",")) {
      const [k, v] = pair.split("=");
      props[k] = v;
    }
  }
  return { name, props };
}

function hash(x, y, z) {
  let h = (x * 374761393 + y * 668265263 + z * 2147483647) | 0;
  h = (h ^ (h >>> 13)) * 1274126177;
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}

export function buildWorld(farm) {
  const [ox, oy, oz] = farm.origin;
  const [sx, sy, sz] = farm.size;
  const states = farm.palette.map(parse);
  const at = (x, y, z) => {
    const ix = x - ox, iy = y - oy, iz = z - oz;
    if (ix < 0 || iy < 0 || iz < 0 || ix >= sx || iy >= sy || iz >= sz) return null;
    const id = farm.grid[(iy * sz + iz) * sx + ix];
    return id ? states[id] : null;
  };
  const top = new Map();                    // "x,z" -> altura del bloque más alto (para marcadores)

  const solid = [], glass = [], lamps = [];
  const color = new THREE.Color();
  const push = (list, x, y, z, w, h, d, hex, jitter = 0, seed = 0) => {
    color.set(hex);
    if (jitter) color.offsetHSL(0, 0, (seed - 0.5) * jitter);
    list.push([x, y, z, w, h, d, color.r, color.g, color.b]);
  };

  for (let iy = 0; iy < sy; iy++) {
    for (let iz = 0; iz < sz; iz++) {
      for (let ix = 0; ix < sx; ix++) {
        const id = farm.grid[(iy * sz + iz) * sx + ix];
        if (!id) continue;
        const { name, props } = states[id];
        const x = ix + ox, y = iy + oy, z = iz + oz;
        const key = `${x},${z}`;
        top.set(key, Math.max(top.get(key) ?? 0, y + 1));
        if (DYNAMIC.has(name)) continue;
        if (name === "water") continue;                       // canal y tanque: los dibuja twins.js
        const inGreenhouse = x >= GREENHOUSE[0] && x <= GREENHOUSE[2] && z >= GREENHOUSE[1] && z <= GREENHOUSE[3];
        if (inGreenhouse && y === 5 && name.endsWith("glass")) continue;      // techo de color: dinámico
        const hex = COLORS[name] || "#9a9a96";
        const cx = x + 0.5, cz = z + 0.5;
        const seed = hash(x, y, z);

        if (name === "glass" || name.endsWith("stained_glass")) {
          push(glass, cx, y + 0.5, cz, 1, 1, 1, hex);
        } else if (name === "glass_pane" || name === "iron_bars") {
          const list = name === "glass_pane" ? glass : solid;
          const n = at(x, y, z - 1), s = at(x, y, z + 1), w = at(x - 1, y, z), e = at(x + 1, y, z);
          const ns = (n && n.name !== "air") || (s && s.name !== "air");
          const we = (w && w.name !== "air") || (e && e.name !== "air");
          if (we || !ns) push(list, cx, y + 0.5, cz, 1, 1, 0.12, hex);
          if (ns) push(list, cx, y + 0.5, cz, 0.12, 1, 1, hex);
        } else if (name.endsWith("_slab")) {
          const type = props.type || "bottom";
          if (type === "double") push(solid, cx, y + 0.5, cz, 1, 1, 1, hex, 0.06, seed);
          else push(solid, cx, y + (type === "top" ? 0.75 : 0.25), cz, 1, 0.5, 1, hex, 0.06, seed);
        } else if (CONNECTS.test(name)) {
          push(solid, cx, y + 0.5, cz, 0.25, 1, 0.25, hex);
          for (const [dx, dz] of [[1, 0], [0, 1]]) {
            const other = at(x + dx, y, z + dz);
            if (other && (CONNECTS.test(other.name) || other.name.endsWith("fence_gate"))) {
              for (const ry of [0.4, 0.8]) {
                push(solid, cx + dx * 0.5, y + ry, cz + dz * 0.5, dx ? 1 : 0.12, 0.14, dz ? 1 : 0.12, hex);
              }
            }
          }
        } else if (name === "iron_chain") {
          const axis = props.axis || "y";
          push(solid, cx, y + 0.5, cz, axis === "x" ? 1 : 0.14, axis === "y" ? 1 : 0.14, axis === "z" ? 1 : 0.14, hex);
        } else if (name === "lantern") {
          const hanging = props.hanging === "true";
          lamps.push([cx, y + (hanging ? 0.62 : 0.28), cz]);
          if (hanging) push(solid, cx, y + 0.9, cz, 0.08, 0.2, 0.08, "#4a4f57");
        } else if (name === "ladder") {
          const f = props.facing || "north";
          const off = { north: [0, 0.44], south: [0, -0.44], west: [0.44, 0], east: [-0.44, 0] }[f];
          push(solid, cx + off[0], y + 0.5, cz + off[1], off[0] ? 0.1 : 0.9, 1, off[1] ? 0.1 : 0.9, hex);
        } else if (name === "oak_sign") {
          push(solid, cx, y + 0.3, cz, 0.1, 0.6, 0.1, hex);
          push(solid, cx, y + 0.75, cz, 0.9, 0.45, 0.1, hex);
        } else if (name === "lever") {
          push(solid, cx, y + 0.1, cz, 0.3, 0.2, 0.4, "#6e6e6e");
          push(solid, cx, y + 0.35, cz, 0.08, 0.4, 0.08, "#8a6a3c");
        } else if (name === "daylight_detector") {
          push(solid, cx, y + 0.19, cz, 1, 0.38, 1, hex);
        } else if (name === "oak_leaves" || name === "birch_leaves" || name.includes("azalea")) {
          push(solid, cx, y + 0.5, cz, 1, 1, 1, hex, 0.14, seed);
        } else {
          const jitter = name === "grass_block" ? 0.05 : name === "dirt_path" ? 0.06 : 0.035;
          push(solid, cx, y + 0.5, cz, 1, 1, 1, hex, jitter, seed);
        }
      }
    }
  }

  const group = new THREE.Group();
  group.name = "mundo";
  const box = new THREE.BoxGeometry(1, 1, 1);
  const make = (list, material, castShadow = true) => {
    const mesh = new THREE.InstancedMesh(box, material, list.length);
    const m = new THREE.Matrix4(), q = new THREE.Quaternion(), p = new THREE.Vector3(), s = new THREE.Vector3();
    list.forEach((item, i) => {
      m.compose(p.set(item[0], item[1], item[2]), q, s.set(item[3], item[4], item[5]));
      mesh.setMatrixAt(i, m);
      mesh.setColorAt(i, color.setRGB(item[6], item[7], item[8]));
    });
    mesh.castShadow = castShadow;
    mesh.receiveShadow = true;
    mesh.instanceMatrix.needsUpdate = true;
    return mesh;
  };
  group.add(make(solid, new THREE.MeshStandardMaterial({ roughness: 0.92, metalness: 0 })));
  const glassMesh = make(glass, new THREE.MeshStandardMaterial({
    roughness: 0.1, metalness: 0, transparent: true, opacity: 0.32, depthWrite: false }), false);
  glassMesh.renderOrder = 2;
  group.add(glassMesh);

  const lanternMaterial = new THREE.MeshStandardMaterial({ color: "#3c3326", emissive: "#ffc865", emissiveIntensity: 0.6 });
  const lanternMesh = new THREE.InstancedMesh(new THREE.BoxGeometry(0.36, 0.42, 0.36), lanternMaterial, lamps.length);
  lamps.forEach((p, i) => lanternMesh.setMatrixAt(i, new THREE.Matrix4().makeTranslation(p[0], p[1], p[2])));
  group.add(lanternMesh);

  const topAt = (x1, z1, x2, z2) => {
    let best = 1;
    for (let x = x1; x <= x2; x++) for (let z = z1; z <= z2; z++) best = Math.max(best, top.get(`${x},${z}`) ?? 1);
    return best;
  };
  return { group, lanternMaterial, topAt, at, bounds: { x1: ox, z1: oz, x2: ox + sx - 1, z2: oz + sz - 1 } };
}
