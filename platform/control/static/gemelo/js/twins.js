// Lo que se mueve con los datos: cultivos, humedad del suelo, agua del canal y del tanque, techo y
// ventilas del invernadero, calefacción, lámparas, alarma, portón, vacas, monstruos, aspersores,
// lluvia, día y noche. Cada elemento sigue las mismas reglas que el plugin dibuja en el mundo.

import * as THREE from "three";

const PLOTS = {
  "mc-parcela-a": { box: [-6, 10, 2, 18], crops: ["wheat"], lampX: -2 },
  "mc-parcela-b": { box: [30, 10, 38, 18], crops: ["carrots", "potatoes"], lampX: 34 },
};
const CHANNEL_Z = 14;
const TANK_IN = [1, -17, 3, -15];
const TANK_LAYERS = 8;
const GH = [24, -24, 32, -16];
const GH_ROOF = 5;
const VENT_Z = -20;
const HEATER = [28, 1, -22];
const GH_LAMPS = [[26, 1, -22], [30, 1, -22]];
const BUSH_X = [25, 26, 27, 29, 30, 31];
const BUSH_Z = [-18, -20];
const ROOF_BANDS = ["#8fd3f4", "#e6f3f6", "#f2d94e", "#f29c38", "#e5533d"];
const SECURITY_LAMP = [8, 3, 32];
const GATE = [16, 1, 40];
const CORRAL = [-38, -30, -28, -14];
const SILO = [16, 14];
const FEEDER = [-36, -28];
const TROUGH = [-34, -28];
const STATION = [-14, 30];
const BOOTH = [52, 12, 55, 16];
const FARM = [-44, -48, 72, 40];

const lerp = (a, b, t) => a + (b - a) * t;
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const num = (v, d = 0) => (typeof v === "number" && !Number.isNaN(v) ? v : d);
const on = (v) => num(v) >= 0.5;

function rng(seed) {
  let s = seed >>> 0;
  return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
}

function box(w, h, d, color, extra = {}) {
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, h, d),
    new THREE.MeshStandardMaterial({ color, roughness: 0.85, ...extra }));
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  return mesh;
}

function lampBlock(x, y, z) {
  const mesh = box(1, 1, 1, "#7a4d2d", { emissive: "#ffb347", emissiveIntensity: 0 });
  mesh.position.set(x + 0.5, y + 0.5, z + 0.5);
  const light = new THREE.PointLight("#ffbf66", 0, 16, 1.6);
  light.position.set(x + 0.5, y + 0.5, z + 0.5);
  mesh.userData.light = light;
  return mesh;
}

function setLamp(mesh, lit, strength = 1) {
  mesh.material.emissiveIntensity = lit ? 1.6 * strength : 0;
  mesh.material.color.set(lit ? "#f7d08a" : "#7a4d2d");
  mesh.userData.light.intensity = lit ? 9 * strength : 0;
}

// ------------------------------------------------------------------------------------------ parcelas
class Plot {
  constructor(group, id, spec) {
    this.id = id;
    this.spec = spec;
    const [x1, z1, x2, z2] = spec.box;
    this.cells = [];
    for (let x = x1; x <= x2; x++) {
      for (let z = z1; z <= z2; z++) {
        if (z === CHANNEL_Z) continue;
        const crop = spec.crops[(x - x1) % spec.crops.length];
        const r = rng(x * 92821 + z * 68917)();
        this.cells.push({ x, z, crop, uneven: r < 0.2 ? -1 : r > 0.85 ? 1 : 0 });
      }
    }
    const geo = new THREE.BoxGeometry(1, 1, 1);
    this.soil = new THREE.InstancedMesh(geo, new THREE.MeshStandardMaterial({ roughness: 1 }), this.cells.length);
    this.plants = new THREE.InstancedMesh(geo, new THREE.MeshStandardMaterial({ roughness: 0.8 }), this.cells.length);
    this.tops = new THREE.InstancedMesh(geo, new THREE.MeshStandardMaterial({ roughness: 0.7 }), this.cells.length);
    for (const mesh of [this.soil, this.plants, this.tops]) {
      mesh.receiveShadow = true;
      mesh.castShadow = mesh !== this.soil;
      group.add(mesh);
    }
    const length = x2 - x1 + 1;
    this.trench = box(length, 0.85, 1, "#3b2a1c");
    this.trench.position.set(x1 + length / 2, 0.425, CHANNEL_Z + 0.5);
    this.water = new THREE.Mesh(new THREE.BoxGeometry(length, 0.7, 0.96),
      new THREE.MeshStandardMaterial({ color: "#3d7fd6", transparent: true, opacity: 0.82, roughness: 0.15,
                                       emissive: "#1b4f9c", emissiveIntensity: 0.25 }));
    this.water.position.set(x1 + length / 2, 0.5, CHANNEL_Z + 0.5);
    this.water.visible = false;
    this.lamp = lampBlock(spec.lampX, 4, CHANNEL_Z);
    group.add(this.trench, this.water, this.lamp, this.lamp.userData.light);
    this.shown = null;
    this.apply({});
  }

  apply(t) {
    const soil = num(t.soil_pct, 50), growth = num(t.growth_pct, 40);
    const stage = Math.min(7, Math.floor(growth / 100 * 8));
    const key = `${Math.round(soil)}|${stage}`;
    if (key !== this.shown) {
      this.shown = key;
      const wet = clamp(soil / 100, 0, 1);
      const soilColor = new THREE.Color("#9c6b3f").lerp(new THREE.Color("#3e2615"), wet);
      const m = new THREE.Matrix4(), c = new THREE.Color();
      this.cells.forEach((cell, i) => {
        m.makeScale(1, 0.94, 1).setPosition(cell.x + 0.5, 0.47, cell.z + 0.5);
        this.soil.setMatrixAt(i, m);
        this.soil.setColorAt(i, soilColor);
        const age = stage === 0 ? 0 : clamp(stage + cell.uneven, 0, 7);
        const ripe = age / 7;
        const h = 0.12 + 0.78 * ripe;
        m.makeScale(cell.crop === "wheat" ? 0.62 : 0.7, h, cell.crop === "wheat" ? 0.62 : 0.7)
          .setPosition(cell.x + 0.5, 0.94 + h / 2, cell.z + 0.5);
        this.plants.setMatrixAt(i, m);
        if (cell.crop === "wheat") c.set("#5c9a35").lerp(new THREE.Color("#d8b64a"), clamp((ripe - 0.45) / 0.55, 0, 1));
        else c.set("#3f8a31").lerp(new THREE.Color("#5aa43a"), ripe);
        this.plants.setColorAt(i, c);
        // espiga madura (trigo) o raíz asomando (zanahoria/papa) cuando está lista
        const show = ripe >= 0.99;
        const topColor = cell.crop === "wheat" ? "#e9c95a" : cell.crop === "carrots" ? "#ec7a22" : "#c9a265";
        m.makeScale(show ? 0.4 : 0.001, show ? 0.22 : 0.001, show ? 0.4 : 0.001)
          .setPosition(cell.x + 0.5, cell.crop === "wheat" ? 0.94 + h + 0.05 : 1.02, cell.z + 0.5);
        this.tops.setMatrixAt(i, m);
        this.tops.setColorAt(i, c.set(topColor));
      });
      for (const mesh of [this.soil, this.plants, this.tops]) {
        mesh.instanceMatrix.needsUpdate = true;
        mesh.instanceColor.needsUpdate = true;
      }
    }
    this.irrigating = on(t.irrigation);
    this.water.visible = this.irrigating;
    setLamp(this.lamp, on(t.lamp));
  }

  tick(time) {
    if (this.irrigating) {
      this.water.material.emissiveIntensity = 0.25 + 0.15 * Math.sin(time * 4);
      this.water.position.y = 0.5 + 0.03 * Math.sin(time * 3);
    }
  }
}

// ------------------------------------------------------------------------------------------- tanque
class Tank {
  constructor(group) {
    const w = TANK_IN[2] - TANK_IN[0] + 1, d = TANK_IN[3] - TANK_IN[1] + 1;
    this.water = new THREE.Mesh(new THREE.BoxGeometry(w * 0.98, 1, d * 0.98),
      new THREE.MeshStandardMaterial({ color: "#2f74cf", transparent: true, opacity: 0.78, roughness: 0.1,
                                       emissive: "#123f80", emissiveIntensity: 0.3 }));
    this.water.position.set(TANK_IN[0] + w / 2, 1, TANK_IN[1] + d / 2);
    group.add(this.water);
    this.level = 0.5;
    this.target = 0.5;
    this.pump = false;
    // chorro de la bomba
    const geo = new THREE.BufferGeometry();
    this.jetCount = 60;
    geo.setAttribute("position", new THREE.BufferAttribute(new Float32Array(this.jetCount * 3), 3));
    this.jet = new THREE.Points(geo, new THREE.PointsMaterial({ color: "#9ed0ff", size: 0.18, transparent: true, opacity: 0.9 }));
    this.jet.visible = false;
    this.jetSeeds = Array.from({ length: this.jetCount }, (_, i) => i / this.jetCount);
    group.add(this.jet);
  }

  apply(t) {
    this.target = clamp(num(t.level_pct, 50) / 100, 0, 1);
    this.pump = on(t.pump);
    this.jet.visible = this.pump;
  }

  tick(time, dt) {
    this.level = lerp(this.level, this.target, 1 - Math.exp(-dt * 2));
    const h = Math.max(0.02, this.level * TANK_LAYERS);
    this.water.scale.y = h;
    this.water.position.y = 1 + h / 2 + 0.02 * Math.sin(time * 2);
    if (this.pump) {
      const pos = this.jet.geometry.attributes.position;
      const top = 1 + TANK_LAYERS + 0.5, bottom = 1 + h;
      for (let i = 0; i < this.jetCount; i++) {
        const k = (this.jetSeeds[i] + time * 0.9) % 1;
        pos.setXYZ(i, 2.5 + Math.sin(i * 7.1) * 0.15, lerp(top, bottom, k), -15.5 + Math.cos(i * 3.3) * 0.15);
      }
      pos.needsUpdate = true;
    }
  }
}

// -------------------------------------------------------------------------------------- invernadero
class Greenhouse {
  constructor(group) {
    this.roofCells = [];
    for (let x = GH[0]; x <= GH[2]; x++) {
      for (let z = GH[1]; z <= GH[3]; z++) {
        const vent = z === VENT_Z && x > GH[0] && x < GH[2];
        if (!vent) this.roofCells.push([x, z]);
      }
    }
    this.roof = new THREE.InstancedMesh(new THREE.BoxGeometry(1, 1, 1),
      new THREE.MeshStandardMaterial({ transparent: true, opacity: 0.5, roughness: 0.15, depthWrite: false }),
      this.roofCells.length);
    const m = new THREE.Matrix4();
    this.roofCells.forEach(([x, z], i) => this.roof.setMatrixAt(i, m.makeTranslation(x + 0.5, GH_ROOF + 0.5, z + 0.5)));
    this.roof.renderOrder = 3;
    group.add(this.roof);
    this.vents = [];
    for (let x = GH[0] + 1; x < GH[2]; x++) {
      const pivot = new THREE.Group();
      pivot.position.set(x + 0.5, GH_ROOF, VENT_Z);                // bisagra en el borde norte
      const panel = box(1, 0.19, 1, "#a07a45");
      panel.position.set(0, 0.095, 0.5);
      pivot.add(panel);
      group.add(pivot);
      this.vents.push(pivot);
    }
    this.ventAngle = 0;
    this.ventTarget = 0;
    const base = box(0.9, 0.3, 0.9, "#4a3a2a");
    base.position.set(HEATER[0] + 0.5, HEATER[1] + 0.15, HEATER[2] + 0.5);
    this.flame = new THREE.Mesh(new THREE.ConeGeometry(0.32, 0.8, 6),
      new THREE.MeshStandardMaterial({ color: "#ffb347", emissive: "#ff7a1a", emissiveIntensity: 2, transparent: true, opacity: 0.9 }));
    this.flame.position.set(HEATER[0] + 0.5, HEATER[1] + 0.7, HEATER[2] + 0.5);
    this.heatLight = new THREE.PointLight("#ff8a3a", 0, 12, 1.5);
    this.heatLight.position.copy(this.flame.position);
    group.add(base, this.flame, this.heatLight);
    this.lamps = GH_LAMPS.map(([x, y, z]) => {
      const lamp = lampBlock(x, y, z);
      group.add(lamp, lamp.userData.light);
      return lamp;
    });
    this.bushes = [];
    for (const x of BUSH_X) {
      for (const z of BUSH_Z) {
        const bush = box(0.8, 0.8, 0.8, "#3f7a33");
        bush.position.set(x + 0.5, 1.4, z + 0.5);
        const berries = box(0.86, 0.12, 0.86, "#c3263a", { emissive: "#5a0a12", emissiveIntensity: 0.2 });
        berries.position.set(0, 0.25, 0);
        bush.add(berries);
        group.add(bush);
        this.bushes.push(bush);
      }
    }
    this.heater = false;
    this.apply({});
  }

  apply(t) {
    const temp = num(t.temp_c, 25);
    const band = temp < 18 ? 0 : temp < 27 ? 1 : temp < 30 ? 2 : temp < 32 ? 3 : 4;
    if (band !== this.band) {
      this.band = band;
      const c = new THREE.Color(ROOF_BANDS[band]);
      this.roofCells.forEach((_, i) => this.roof.setColorAt(i, c));
      this.roof.instanceColor.needsUpdate = true;
    }
    this.ventTarget = on(t.vent_open ?? t.vent) ? -Math.PI / 2 : 0;
    this.heater = on(t.heater);
    this.flame.visible = this.heater;
    this.heatLight.intensity = this.heater ? 6 : 0;
    for (const lamp of this.lamps) setLamp(lamp, on(t.lamp));
    const health = num(t.plant_health_pct, 70);
    const age = health > 75 ? 3 : health > 50 ? 2 : health > 25 ? 1 : 0;
    const dead = health <= 5;
    for (const bush of this.bushes) {
      const s = dead ? 0.55 : 0.45 + age * 0.17;
      bush.scale.setScalar(s);
      bush.position.y = 1 + 0.4 * s;
      bush.material.color.set(dead ? "#7a6142" : "#3f7a33");
      bush.children[0].visible = !dead && age >= 2;
    }
  }

  tick(time, dt) {
    this.ventAngle = lerp(this.ventAngle, this.ventTarget, 1 - Math.exp(-dt * 3));
    for (const v of this.vents) v.rotation.x = this.ventAngle;
    if (this.heater) {
      this.flame.scale.set(1, 0.85 + 0.25 * Math.sin(time * 13) * Math.sin(time * 7.3), 1);
      this.heatLight.intensity = 5 + 1.5 * Math.sin(time * 11);
    }
  }
}

// -------------------------------------------------------------------------------- figuras (vacas…)
function cowMesh(baby) {
  const cow = new THREE.Group();
  const body = box(0.9, 0.75, 1.35, "#efece4");
  body.position.y = 0.95;
  const spot1 = box(0.92, 0.36, 0.42, "#24221f");
  spot1.position.set(0, 0.08, 0.15);
  const spot2 = box(0.5, 0.3, 0.3, "#24221f");
  spot2.position.set(0.25, 0.38, -0.35);
  body.add(spot1, spot2);
  const head = box(0.6, 0.55, 0.5, "#2a2724");
  head.position.set(0, 1.2, 0.85);
  const snout = box(0.42, 0.24, 0.12, "#e9b7a6");
  snout.position.set(0, -0.15, 0.27);
  head.add(snout);
  cow.add(body, head);
  for (const [lx, lz] of [[-0.28, -0.45], [0.28, -0.45], [-0.28, 0.45], [0.28, 0.45]]) {
    const leg = box(0.22, 0.6, 0.22, "#d9d5cb");
    leg.position.set(lx, 0.3, lz);
    cow.add(leg);
  }
  cow.scale.setScalar(baby ? 0.55 : 1);
  return cow;
}

function figureMesh(color, eye) {
  const fig = new THREE.Group();
  const body = box(0.5, 0.75, 0.3, color);
  body.position.y = 1.15;
  const head = box(0.48, 0.48, 0.48, color);
  head.position.y = 1.8;
  const eyes = box(0.3, 0.08, 0.05, eye, { emissive: eye, emissiveIntensity: 1.2 });
  eyes.position.set(0, 0.05, 0.25);
  head.add(eyes);
  const legs = box(0.45, 0.75, 0.25, color);
  legs.position.y = 0.38;
  fig.add(body, head, legs);
  return fig;
}

class Walkers {
  constructor(group, area, factory, speed = 0.7) {
    this.group = new THREE.Group();
    group.add(this.group);
    this.area = area;
    this.factory = factory;
    this.speed = speed;
    this.items = [];
    this.random = rng(area[0] * 31 + area[1] * 17 + 7);
  }

  point() {
    const [x1, z1, x2, z2] = this.area;
    return new THREE.Vector3(lerp(x1, x2, this.random()), 1, lerp(z1, z2, this.random()));
  }

  setCount(list) {                      // list: arreglo de "tipos" (p. ej. [false, false, true] = adulto/bebé)
    const key = list.join(",");
    if (key === this.key) return;
    this.key = key;
    for (const item of this.items) this.group.remove(item.mesh);
    this.items = list.map((kind) => {
      const mesh = this.factory(kind);
      const start = this.point();
      mesh.position.copy(start);
      this.group.add(mesh);
      return { mesh, target: this.point(), wait: this.random() * 3 };
    });
  }

  tick(dt) {
    for (const item of this.items) {
      if (item.wait > 0) {
        item.wait -= dt;
        continue;
      }
      const to = item.target.clone().sub(item.mesh.position);
      to.y = 0;
      const dist = to.length();
      if (dist < 0.2) {
        item.target = this.point();
        item.wait = 1 + this.random() * 4;
        continue;
      }
      const step = Math.min(dist, this.speed * dt);
      item.mesh.position.addScaledVector(to.normalize(), step);
      const heading = Math.atan2(to.x, to.z);
      let diff = heading - item.mesh.rotation.y;
      diff = Math.atan2(Math.sin(diff), Math.cos(diff));
      item.mesh.rotation.y += diff * Math.min(1, dt * 5);
    }
  }
}

// --------------------------------------------------------------------------------- partículas
class Drops {
  constructor(group, count, color, size) {
    const geo = new THREE.BufferGeometry();
    this.count = count;
    this.pos = new Float32Array(count * 3);
    this.speed = new Float32Array(count);
    geo.setAttribute("position", new THREE.BufferAttribute(this.pos, 3));
    this.points = new THREE.Points(geo, new THREE.PointsMaterial({ color, size, transparent: true, opacity: 0.85, depthWrite: false }));
    this.points.frustumCulled = false;
    this.points.visible = false;
    group.add(this.points);
  }
}

class Rain extends Drops {
  constructor(group) {
    super(group, 5000, "#a9c8ff", 0.22);
    this.random = rng(99);
    for (let i = 0; i < this.count; i++) this.reset(i, true);
  }

  reset(i, anywhere) {
    this.pos[i * 3] = lerp(FARM[0] - 10, FARM[2] + 10, this.random());
    this.pos[i * 3 + 1] = anywhere ? this.random() * 45 : 40 + this.random() * 6;
    this.pos[i * 3 + 2] = lerp(FARM[1] - 10, FARM[3] + 10, this.random());
    this.speed[i] = 18 + this.random() * 8;
  }

  tick(dt) {
    if (!this.points.visible) return;
    for (let i = 0; i < this.count; i++) {
      this.pos[i * 3 + 1] -= this.speed[i] * dt;
      if (this.pos[i * 3 + 1] < 1) this.reset(i, false);
    }
    this.points.geometry.attributes.position.needsUpdate = true;
  }
}

class Sprinklers extends Drops {
  constructor(group) {
    super(group, 700, "#bfe3ff", 0.16);
    this.random = rng(7);
    this.heads = [];
    for (let x = CORRAL[0] + 2; x < CORRAL[2]; x += 4) {
      for (let z = CORRAL[1] + 3; z < CORRAL[3]; z += 5) this.heads.push([x + 0.5, 4.2, z + 0.5]);
    }
    this.vel = new Float32Array(this.count * 3);
    for (let i = 0; i < this.count; i++) this.reset(i);
  }

  reset(i) {
    const h = this.heads[i % this.heads.length];
    this.pos.set(h, i * 3);
    const a = this.random() * Math.PI * 2, r = 1 + this.random() * 2.2;
    this.vel.set([Math.cos(a) * r, 1 + this.random() * 1.5, Math.sin(a) * r], i * 3);
  }

  tick(dt) {
    if (!this.points.visible) return;
    for (let i = 0; i < this.count; i++) {
      this.vel[i * 3 + 1] -= 9 * dt;
      for (let k = 0; k < 3; k++) this.pos[i * 3 + k] += this.vel[i * 3 + k] * dt;
      if (this.pos[i * 3 + 1] < 1) this.reset(i);
    }
    this.points.geometry.attributes.position.needsUpdate = true;
  }
}

// ---------------------------------------------------------------------------------- medidores
function gauge(group, x, y, z, height, color) {
  const shell = new THREE.Mesh(new THREE.CylinderGeometry(0.32, 0.32, height, 12),
    new THREE.MeshStandardMaterial({ color: "#dfe8ea", transparent: true, opacity: 0.25, depthWrite: false }));
  shell.position.set(x, y + height / 2, z);
  const fill = new THREE.Mesh(new THREE.CylinderGeometry(0.26, 0.26, 1, 12),
    new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: 0.35 }));
  fill.position.set(x, y, z);
  group.add(shell, fill);
  return {
    target: 0.5, value: 0.5,
    set(f) { this.target = clamp(f, 0, 1); },
    tick(dt) {
      this.value = lerp(this.value, this.target, 1 - Math.exp(-dt * 2));
      const h = Math.max(0.02, this.value * height);
      fill.scale.y = h;
      fill.position.y = y + h / 2;
    },
  };
}

// ----------------------------------------------------------------------------------- conjunto
export class Twins {
  constructor(scene, world) {
    this.group = new THREE.Group();
    this.group.name = "gemelos";
    scene.add(this.group);
    this.world = world;
    this.plots = Object.fromEntries(Object.entries(PLOTS).map(([id, spec]) => [id, new Plot(this.group, id, spec)]));
    this.tank = new Tank(this.group);
    this.greenhouse = new Greenhouse(this.group);

    this.alarm = lampBlock(...SECURITY_LAMP);
    this.alarm.userData.light.color.set("#ff3b2f");
    this.alarm.userData.light.distance = 26;
    this.siren = new THREE.Mesh(new THREE.RingGeometry(0.8, 1.1, 40),
      new THREE.MeshBasicMaterial({ color: "#ff3b2f", transparent: true, opacity: 0, side: THREE.DoubleSide, depthWrite: false }));
    this.siren.rotation.x = -Math.PI / 2;
    this.siren.position.set(SECURITY_LAMP[0] + 0.5, 1.05, SECURITY_LAMP[2] + 0.5);
    this.group.add(this.alarm, this.alarm.userData.light, this.siren);

    this.gate = new THREE.Group();
    this.gate.position.set(GATE[0], GATE[1], GATE[2] + 0.5);
    const gatePanel = box(1, 0.75, 0.12, "#a7834f");
    gatePanel.position.set(0.5, 0.5, 0);
    this.gate.add(gatePanel);
    this.group.add(this.gate);
    this.gateAngle = 0;
    this.gateTarget = 0;

    this.cows = new Walkers(this.group, [CORRAL[0] + 1.5, CORRAL[1] + 1.5, CORRAL[2] - 1, CORRAL[3] - 1.5], cowMesh, 0.6);
    this.hostiles = new Walkers(this.group, [FARM[0], FARM[3] + 2, FARM[2], FARM[3] + 7],
      () => figureMesh("#3d5a3a", "#ff3030"), 1.1);
    this.people = new Walkers(this.group, [4, 26, 22, 38], () => figureMesh("#3b6ea5", "#ffffff"), 1.2);
    this.sprinklers = new Sprinklers(this.group);
    this.rain = new Rain(this.group);

    const siloTop = world.topAt(SILO[0] - 1, SILO[1] - 1, SILO[0] + 1, SILO[1] + 1);
    this.silo = gauge(this.group, SILO[0] + 2.6, 1, SILO[1] + 0.5, Math.max(6, siloTop - 2), "#e2b13c");
    this.feeder = gauge(this.group, FEEDER[0] + 0.5, 2.2, FEEDER[1] - 0.6, 2.4, "#d6a73a");
    this.trough = gauge(this.group, TROUGH[0] + 0.5, 2.2, TROUGH[1] - 0.6, 2.4, "#3d8be0");

    const stationTop = world.topAt(STATION[0], STATION[1], STATION[0], STATION[1]);
    this.anemometer = new THREE.Group();
    this.anemometer.position.set(STATION[0] + 0.5, stationTop + 0.9, STATION[1] + 0.5);
    const mast = box(0.08, 1.4, 0.08, "#c9ced3");
    mast.position.y = -0.4;
    this.anemometer.add(mast);
    for (let i = 0; i < 3; i++) {
      const arm = new THREE.Group();
      arm.rotation.y = (i * Math.PI * 2) / 3;
      const rod = box(0.9, 0.05, 0.05, "#c9ced3");
      rod.position.x = 0.45;
      const cup = box(0.22, 0.22, 0.22, "#e8eef2");
      cup.position.x = 0.9;
      arm.add(rod, cup);
      this.anemometer.add(arm);
    }
    this.group.add(this.anemometer);
    this.wind = 0;

    const boothTop = world.topAt(...BOOTH);
    this.beacon = new THREE.Mesh(new THREE.SphereGeometry(0.35, 16, 12),
      new THREE.MeshStandardMaterial({ color: "#7bc86c", emissive: "#7bc86c", emissiveIntensity: 1.4 }));
    this.beacon.position.set((BOOTH[0] + BOOTH[2]) / 2 + 0.5, boothTop + 1.2, (BOOTH[1] + BOOTH[3]) / 2 + 0.5);
    this.group.add(this.beacon);

    this.thunder = false;
    this.flash = 0;
  }

  // t: { device -> valores }; weather: { raining, thundering, wind_kmh, cloud_pct }
  apply(t) {
    for (const [id, plot] of Object.entries(this.plots)) plot.apply(t[id] || {});
    this.tank.apply(t["mc-tanque"] || {});
    this.greenhouse.apply(t["mc-invernadero"] || {});
    const sec = t["mc-seguridad"] || {};
    this.alarmOn = on(sec.alarm);
    setLamp(this.alarm, this.alarmOn, 1.4);
    this.gateTarget = on(sec.gate_open) ? -Math.PI / 2 : 0;
    this.hostiles.setCount(Array(clamp(Math.round(num(sec.hostiles_near)), 0, 10)).fill(0));
    this.people.setCount(Array(clamp(Math.round(num(sec.players_in_zone)), 0, 6)).fill(0));
    const corral = t["mc-corral"] || {};
    const adults = clamp(Math.round(num(corral.cows_adult, num(corral.cows_total, 6))), 0, 12);
    const babies = clamp(Math.round(num(corral.cows_baby)), 0, 12 - adults);
    this.cows.setCount([...Array(adults).fill(false), ...Array(babies).fill(true)]);
    this.sprinklers.points.visible = on(corral.cooling);
    this.feeder.set(num(corral.feed_pct, 50) / 100);
    this.trough.set(num(corral.water_pct, 50) / 100);
    this.silo.set(num((t["mc-silo"] || {}).fill_pct, 30) / 100);
    const w = t.weather || {};
    this.rain.points.visible = on(w.raining);
    this.thunder = on(w.thundering);
    this.wind = num(w.wind_kmh, 5);
    const tps = num((t["mc-server"] || {}).tps, 20);
    const beacon = tps >= 19 ? "#7bc86c" : tps >= 15 ? "#f0b34a" : "#f2495c";
    this.beacon.material.color.set(beacon);
    this.beacon.material.emissive.set(beacon);
  }

  setView() {}

  tick(time, dt) {
    for (const plot of Object.values(this.plots)) plot.tick(time);
    this.tank.tick(time, dt);
    this.greenhouse.tick(time, dt);
    this.gateAngle = lerp(this.gateAngle, this.gateTarget, 1 - Math.exp(-dt * 3));
    this.gate.rotation.y = this.gateAngle;
    if (this.alarmOn) {
      const pulse = 0.5 + 0.5 * Math.sin(time * 8);
      this.alarm.userData.light.intensity = 6 + 14 * pulse;
      const k = (time * 0.8) % 1;
      this.siren.scale.setScalar(1 + k * 9);
      this.siren.material.opacity = 0.6 * (1 - k);
    } else {
      this.siren.material.opacity = 0;
    }
    this.cows.tick(dt);
    this.hostiles.tick(dt);
    this.people.tick(dt);
    this.sprinklers.tick(dt);
    this.rain.tick(dt);
    for (const g of [this.silo, this.feeder, this.trough]) g.tick(dt);
    this.anemometer.rotation.y += dt * (0.3 + this.wind * 0.25);
    this.beacon.scale.setScalar(1 + 0.15 * Math.sin(time * 3));
    if (this.thunder && Math.random() < dt * 0.15) this.flash = 1;
    this.flash = Math.max(0, this.flash - dt * 3);
  }
}
