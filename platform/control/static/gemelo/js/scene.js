// Escena: renderizador, cámaras (3D en perspectiva, 2.5D isométrica y 2D cenital), luz del día según la
// hora del mundo, marcadores de cada dispositivo con su etiqueta en vivo y paquetes que viajan entre los
// dispositivos y la plataforma en la nube.

import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import { CSS2DRenderer, CSS2DObject } from "three/addons/renderers/CSS2DRenderer.js";
import { CLASSES, DEVICES, HUB } from "./catalog.js";

const CENTER = new THREE.Vector3(14, 0, -4);
const ISO_ELEVATION = Math.atan(1 / Math.SQRT2);           // 35,26°
const HOME_OFFSET = new THREE.Vector3(-62, 96, 128);
const ZONES = [
  ["Parcela A", [-6, 10, 2, 18]], ["Parcela B", [30, 10, 38, 18]], ["Silo", [15, 13, 17, 15]],
  ["Tanque", [0, -18, 4, -14]], ["Invernadero", [24, -24, 32, -16]], ["Corral y establo", [-38, -30, -28, -14]],
  ["Planta de proceso", [-38, -6, -32, -3]], ["Estación", [-16, 28, -12, 32]], ["Seguridad", [6, 30, 10, 34]],
  ["Entrada", [14, 38, 18, 40]], ["Centro de control", [52, 12, 55, 16]], ["Mirador", [62, -42, 64, -40]],
];
const LINKS = {                                              // a qué se conecta cada dispositivo lógico
  "api-clima": [-14, 30], "api-aire": [-14, 30], "api-suelo": [-14, 30], "api-pronostico": [-14, 30],
  "ds-invernadero": [28, -20], "ds-energia": [-35, -4], "gw-pc-01": [53, 14], "phone-01": [16, 40],
};

const lerp = (a, b, t) => a + (b - a) * t;
const ease = (t) => (t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2);

export class Stage {
  constructor(host, world) {
    this.host = host;
    this.world = world;
    this.renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: "high-performance" });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.05;
    host.appendChild(this.renderer.domElement);
    this.labels = new CSS2DRenderer();
    this.labels.domElement.className = "labels";
    host.appendChild(this.labels.domElement);

    this.scene = new THREE.Scene();
    this.fog = new THREE.Fog("#a9d3f0", 220, 520);
    this.scene.fog = this.fog;
    this.sky = new THREE.Color("#a9d3f0");
    this.scene.background = this.sky;

    this.persp = new THREE.PerspectiveCamera(42, 1, 0.5, 1200);
    this.ortho = new THREE.OrthographicCamera(-1, 1, 1, -1, -500, 1500);
    this.orthoHeight = 120;
    this.camera = this.persp;
    this.mode = "3d";
    this.isoAzimuth = Math.PI / 4;

    this.hemi = new THREE.HemisphereLight("#dbeeff", "#5a6b3a", 0.9);
    this.sun = new THREE.DirectionalLight("#fff4df", 2.2);
    this.sun.castShadow = true;
    this.sun.shadow.mapSize.set(2048, 2048);
    Object.assign(this.sun.shadow.camera, { left: -90, right: 90, top: 80, bottom: -80, near: 1, far: 400 });
    this.sun.shadow.bias = -0.0004;
    this.sun.shadow.normalBias = 0.03;
    this.sun.target.position.copy(CENTER);
    this.scene.add(this.hemi, this.sun, this.sun.target);

    const ground = new THREE.Mesh(new THREE.PlaneGeometry(900, 900),
      new THREE.MeshStandardMaterial({ color: "#5f8f3e", roughness: 1 }));
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = 0.97;
    ground.receiveShadow = true;
    this.scene.add(ground);
    this.ground = ground;
    this.scene.add(world.group);

    this.grid = new THREE.GridHelper(260, 52, "#2d3f2a", "#2d3f2a");
    this.grid.position.set(14, 1.02, -4);
    this.grid.material.transparent = true;
    this.grid.material.opacity = 0.18;
    this.grid.visible = false;
    this.scene.add(this.grid);

    this.zoneGroup = new THREE.Group();
    for (const [name, [x1, z1, x2, z2]] of ZONES) {
      const outline = new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints([
        new THREE.Vector3(x1, 1.08, z1), new THREE.Vector3(x2 + 1, 1.08, z1),
        new THREE.Vector3(x2 + 1, 1.08, z2 + 1), new THREE.Vector3(x1, 1.08, z2 + 1)]),
        new THREE.LineBasicMaterial({ color: "#f4f1e8", transparent: true, opacity: 0.55 }));
      const div = document.createElement("div");
      div.className = "zone-label";
      div.textContent = name;
      const label = new CSS2DObject(div);
      label.position.set((x1 + x2 + 1) / 2, world.topAt(x1, z1, x2, z2) + 1.2, (z1 + z2 + 1) / 2);
      outline.add(label);
      this.zoneGroup.add(outline);
    }
    this.zoneGroup.visible = false;
    this.scene.add(this.zoneGroup);

    this.controls = null;
    this.makeControls();
    this.markers = new Map();
    this.pickables = [];
    this.packets = [];
    this.buildHub();
    this.buildSelection();

    this.raycaster = new THREE.Raycaster();
    this.pointer = new THREE.Vector2();
    this.onPick = () => {};
    this.onHover = () => {};
    const dom = this.renderer.domElement;
    let down = null;
    dom.addEventListener("pointerdown", (e) => { down = [e.clientX, e.clientY]; });
    dom.addEventListener("pointerup", (e) => {
      if (down && Math.hypot(e.clientX - down[0], e.clientY - down[1]) < 5) this.onPick(this.pick(e));
      down = null;
    });
    dom.addEventListener("pointermove", (e) => {
      const id = this.pick(e);
      dom.style.cursor = id ? "pointer" : "";
      this.onHover(id);
    });
    window.addEventListener("resize", () => this.resize());
    this.resize();
    this.setView("3d", true);
  }

  // ----------------------------------------------------------------------------- cámaras y vistas
  makeControls() {
    const target = this.controls ? this.controls.target.clone() : CENTER.clone();
    if (this.controls) this.controls.dispose();
    const c = new OrbitControls(this.camera, this.renderer.domElement);
    c.target.copy(target);
    c.enableDamping = true;
    c.dampingFactor = 0.08;
    c.maxPolarAngle = Math.PI * 0.47;
    c.minDistance = 8;
    c.maxDistance = 420;
    c.zoomToCursor = true;
    if (this.mode === "3d") {
      c.mouseButtons = { LEFT: THREE.MOUSE.ROTATE, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN };
      c.touches = { ONE: THREE.TOUCH.ROTATE, TWO: THREE.TOUCH.DOLLY_PAN };
    } else {
      c.enableRotate = false;
      c.screenSpacePanning = true;
      c.minZoom = 0.35;
      c.maxZoom = 12;
      c.mouseButtons = { LEFT: THREE.MOUSE.PAN, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN };
      c.touches = { ONE: THREE.TOUCH.PAN, TWO: THREE.TOUCH.DOLLY_PAN };
    }
    this.controls = c;
  }

  orthoOffset() {
    if (this.mode === "2d") return new THREE.Vector3(0, 300, 0.001);
    const r = 300;
    return new THREE.Vector3(Math.sin(this.isoAzimuth) * Math.cos(ISO_ELEVATION) * r, Math.sin(ISO_ELEVATION) * r,
      Math.cos(this.isoAzimuth) * Math.cos(ISO_ELEVATION) * r);
  }

  setView(mode, instant = false) {
    const target = this.controls ? this.controls.target.clone() : CENTER.clone();
    this.mode = mode;
    this.camera = mode === "3d" ? this.persp : this.ortho;
    if (mode === "3d") {
      this.persp.position.copy(target).add(HOME_OFFSET);
    } else {
      this.ortho.position.copy(target).add(this.orthoOffset());
      this.ortho.zoom = mode === "2d" ? 1.05 : 1;
      this.ortho.updateProjectionMatrix();
    }
    this.camera.lookAt(target);
    this.makeControls();
    this.controls.target.copy(target);
    this.zoneGroup.visible = mode !== "3d";
    this.scene.fog = mode === "3d" ? this.fog : null;
    this.grid.visible = mode === "2d";
    this.resize();
    if (!instant) {
      this.host.classList.add("switching");
      setTimeout(() => this.host.classList.remove("switching"), 260);
    }
  }

  rotateIso(dir) {
    if (this.mode !== "25d") return;
    const from = this.isoAzimuth, to = from + dir * Math.PI / 2;
    this.animate(700, (t) => {
      this.isoAzimuth = lerp(from, to, ease(t));
      this.ortho.position.copy(this.controls.target).add(this.orthoOffset());
      this.ortho.lookAt(this.controls.target);
    });
  }

  flyTo(x, y, z, size = 18) {
    const target = new THREE.Vector3(x, y, z);
    const fromTarget = this.controls.target.clone();
    if (this.mode === "3d") {
      const dir = this.persp.position.clone().sub(fromTarget).normalize();
      const fromPos = this.persp.position.clone();
      const toPos = target.clone().add(dir.multiplyScalar(size * 2.6 + 10));
      toPos.y = Math.max(toPos.y, y + size * 0.9);
      this.animate(1100, (t) => {
        const k = ease(t);
        this.controls.target.lerpVectors(fromTarget, target, k);
        this.persp.position.lerpVectors(fromPos, toPos, k);
      });
    } else {
      const fromZoom = this.ortho.zoom, toZoom = Math.min(6, Math.max(1.4, 60 / size));
      this.animate(900, (t) => {
        const k = ease(t);
        this.controls.target.lerpVectors(fromTarget, target, k);
        this.ortho.position.copy(this.controls.target).add(this.orthoOffset());
        this.ortho.zoom = lerp(fromZoom, toZoom, k);
        this.ortho.updateProjectionMatrix();
      });
    }
  }

  home() {
    const target = CENTER.clone();
    if (this.mode === "3d") {
      const fromT = this.controls.target.clone(), fromP = this.persp.position.clone();
      const toP = target.clone().add(HOME_OFFSET);
      this.animate(1000, (t) => {
        this.controls.target.lerpVectors(fromT, target, ease(t));
        this.persp.position.lerpVectors(fromP, toP, ease(t));
      });
    } else {
      this.flyTo(target.x, 0, target.z, 60);
    }
  }

  animate(ms, step) {
    this.tween = { start: performance.now(), ms, step };
  }

  resize() {
    const w = this.host.clientWidth, h = this.host.clientHeight;
    this.renderer.setSize(w, h);
    this.labels.setSize(w, h);
    this.persp.aspect = w / h;
    this.persp.updateProjectionMatrix();
    const half = this.orthoHeight / 2;
    Object.assign(this.ortho, { left: -half * w / h, right: half * w / h, top: half, bottom: -half });
    this.ortho.updateProjectionMatrix();
  }

  // ------------------------------------------------------------------------- luz según la hora
  setTime(ticks, daylight = null, flash = 0) {
    const theta = (ticks / 24000) * Math.PI * 2;
    const height = Math.sin(theta);
    let day = Math.min(1, Math.max(0, height * 3 + 0.25));
    if (daylight !== null) day = Math.min(day, 0.15 + daylight / 15);
    const dusk = Math.max(0, 1 - Math.abs(height) * 4) * (day > 0.05 ? 1 : 0.3);
    const night = new THREE.Color("#0c1526"), sunset = new THREE.Color("#e8a272"), noon = new THREE.Color("#a9d3f0");
    this.sky.copy(night).lerp(noon, day).lerp(sunset, dusk * 0.55);
    if (flash > 0) this.sky.lerp(new THREE.Color("#e9f0ff"), flash * 0.7);
    if (this.mode === "2d") this.sky.lerp(new THREE.Color("#1a2420"), 0.6);
    this.fog.color.copy(this.sky);
    this.sun.position.set(CENTER.x + Math.cos(theta) * 160, Math.max(12, Math.abs(height) * 180), CENTER.z + 60);
    this.sun.color.set(day > 0.1 ? "#fff1d6" : "#9fb4ff").lerp(new THREE.Color("#ffb37a"), dusk * 0.6);
    this.sun.intensity = 0.25 + 2.3 * day + flash * 2;
    this.hemi.intensity = 0.55 + 0.6 * day + flash;
    this.night = 1 - day;
    if (this.world.lanternMaterial) this.world.lanternMaterial.emissiveIntensity = 0.4 + 2.2 * this.night;
    this.renderer.toneMappingExposure = 0.95 + 0.25 * day;
  }

  // ---------------------------------------------------------------------- marcadores y etiquetas
  anchor(id) {
    const spec = DEVICES[id];
    if (spec.logical) return new THREE.Vector3(spec.logical[0] + 0.5, spec.logical[1], spec.logical[2] + 0.5);
    const [x1, z1, x2, z2] = spec.zone;
    return new THREE.Vector3((x1 + x2 + 1) / 2, this.world.topAt(x1, z1, x2, z2) + 3.2, (z1 + z2 + 1) / 2);
  }

  addMarker(id) {
    const spec = DEVICES[id];
    const color = new THREE.Color(CLASSES[spec.cls].color);
    const at = this.anchor(id);
    const group = new THREE.Group();
    group.position.copy(at);
    const geo = spec.cls === "api" ? new THREE.OctahedronGeometry(0.85)
      : spec.cls === "dataset" ? new THREE.BoxGeometry(1.1, 1.1, 1.1)
      : spec.cls === "real" ? new THREE.CylinderGeometry(0.55, 0.55, 1.2, 16)
      : new THREE.SphereGeometry(0.62, 20, 14);
    const material = new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: 0.65, roughness: 0.35 });
    const head = new THREE.Mesh(geo, material);
    head.userData.device = id;
    group.add(head);
    const ring = new THREE.Mesh(new THREE.TorusGeometry(1.05, 0.07, 8, 40),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0 }));
    ring.rotation.x = Math.PI / 2;
    group.add(ring);
    if (!spec.logical) {                                       // tallo hasta el techo de la zona
      const stem = new THREE.Mesh(new THREE.CylinderGeometry(0.05, 0.05, 2.6, 6),
        new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.55 }));
      stem.position.y = -1.6;
      group.add(stem);
    }
    if (spec.logical && LINKS[id]) {
      const [lx, lz] = LINKS[id];
      const end = new THREE.Vector3(lx + 0.5, this.world.topAt(lx, lz, lx, lz) + 0.5, lz + 0.5);
      // dentro del grupo del marcador: se oculta con él (p. ej. en la granja de simulación)
      const line = new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), end.sub(at)]),
        new THREE.LineDashedMaterial({ color, dashSize: 0.8, gapSize: 0.6, transparent: true, opacity: 0.7 }));
      line.computeLineDistances();
      group.add(line);
    }
    const pick = new THREE.Mesh(new THREE.SphereGeometry(1.7, 8, 6), new THREE.MeshBasicMaterial({ visible: false }));
    pick.userData.device = id;
    group.add(pick);
    this.pickables.push(pick);
    if (spec.zone) {
      const [x1, z1, x2, z2] = spec.zone;
      const h = this.world.topAt(x1, z1, x2, z2);
      const zonePick = new THREE.Mesh(new THREE.BoxGeometry(x2 - x1 + 1, h, z2 - z1 + 1), new THREE.MeshBasicMaterial({ visible: false }));
      zonePick.position.set((x1 + x2 + 1) / 2, h / 2 + 0.5, (z1 + z2 + 1) / 2);
      zonePick.userData.device = id;
      this.scene.add(zonePick);
      this.pickables.push(zonePick);
    }

    const div = document.createElement("button");
    div.className = `chip cls-${spec.cls}`;
    div.innerHTML = `<i></i><b>${spec.name}</b><span>—</span>`;
    div.addEventListener("click", () => this.onPick(id));
    const label = new CSS2DObject(div);
    label.position.set(0, 1.5, 0);
    group.add(label);
    this.scene.add(group);
    this.markers.set(id, { group, head, ring, material, color, label: div, flash: 0, at });
  }

  setMarker(id, { value, status, alert }) {
    const m = this.markers.get(id);
    if (!m) return;
    m.label.querySelector("span").textContent = value;
    m.label.classList.toggle("off", status !== "online");
    m.label.classList.toggle("alert", !!alert);
    const color = status === "online" ? m.color : new THREE.Color("#6b7380");
    m.material.color.copy(alert ? new THREE.Color("#ff4d3d") : color);
    m.material.emissive.copy(alert ? new THREE.Color("#ff4d3d") : color);
  }

  pulse(id) {
    const m = this.markers.get(id);
    if (m) m.flash = 1;
  }

  // ---------------------------------------------------------------------- nube y paquetes de datos
  buildHub() {
    const hub = new THREE.Group();
    hub.position.set(...HUB);
    const mat = new THREE.MeshStandardMaterial({ color: "#eef4ff", emissive: "#7fb3ff", emissiveIntensity: 0.35,
                                                 transparent: true, opacity: 0.92, roughness: 0.6 });
    for (const [x, y, z, r] of [[0, 0, 0, 3.2], [3, -0.6, 0.5, 2.4], [-3, -0.7, -0.3, 2.3], [1.2, 1.4, -0.4, 2.2], [-1.4, 1.1, 0.8, 2]]) {
      const puff = new THREE.Mesh(new THREE.SphereGeometry(r, 20, 14), mat);
      puff.position.set(x, y, z);
      hub.add(puff);
    }
    this.hubRing = new THREE.Mesh(new THREE.TorusGeometry(6.4, 0.12, 8, 64),
      new THREE.MeshBasicMaterial({ color: "#7fb3ff", transparent: true, opacity: 0.55 }));
    this.hubRing.rotation.x = Math.PI / 2;
    hub.add(this.hubRing);
    const div = document.createElement("div");
    div.className = "hub-label";
    div.innerHTML = "<b>Plataforma en Azure</b><span>Mosquitto · RabbitMQ · InfluxDB · Grafana</span><em id=\"rate\">0 msg/min</em>";
    const label = new CSS2DObject(div);
    label.position.set(0, 4.6, 0);
    hub.add(label);
    this.hub = hub;
    this.scene.add(hub);
    const geo = new THREE.SphereGeometry(0.34, 10, 8);
    this.packetPool = Array.from({ length: 160 }, () => {
      const mesh = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ color: "#ffffff", transparent: true }));
      mesh.visible = false;
      this.scene.add(mesh);
      return mesh;
    });
  }

  // kind: "up" (telemetría hacia la nube) | "down" (comando/ack hacia el dispositivo)
  sendPacket(id, kind, color) {
    const m = this.markers.get(id);
    if (!m || !this.showFlow) return;
    const mesh = this.packetPool.find((p) => !p.visible);
    if (!mesh) return;
    const hub = this.hub.position.clone();
    const from = kind === "up" ? m.at.clone() : hub;
    const to = kind === "up" ? hub : m.at.clone();
    const mid = from.clone().lerp(to, 0.5);
    mid.y += 10 + from.distanceTo(to) * 0.12;
    mesh.material.color.set(color);
    mesh.visible = true;
    mesh.scale.setScalar(kind === "up" ? 1 : 1.5);
    this.packets.push({ mesh, curve: new THREE.QuadraticBezierCurve3(from, mid, to), t: 0, speed: kind === "up" ? 0.7 : 0.9 });
  }

  // ------------------------------------------------------------------------------- selección
  buildSelection() {
    const geo = new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(0, 0, 0), new THREE.Vector3(1, 0, 0),
      new THREE.Vector3(1, 0, 1), new THREE.Vector3(0, 0, 1)]);
    this.selection = new THREE.LineLoop(geo, new THREE.LineBasicMaterial({ color: "#ffd166" }));
    this.selection.visible = false;
    this.scene.add(this.selection);
  }

  select(id) {
    const spec = id && DEVICES[id];
    this.selected = id;
    if (!spec) {
      this.selection.visible = false;
      return;
    }
    const [x1, z1, x2, z2] = spec.zone || [Math.floor(spec.logical[0]) - 1, Math.floor(spec.logical[2]) - 1,
      Math.floor(spec.logical[0]) + 1, Math.floor(spec.logical[2]) + 1];
    const pad = 0.6;
    this.selection.position.set(x1 - pad, spec.zone ? 1.12 : this.anchor(id).y - 1.6, z1 - pad);
    this.selection.scale.set(x2 - x1 + 1 + pad * 2, 1, z2 - z1 + 1 + pad * 2);
    this.selection.visible = true;
    const at = this.anchor(id);
    const size = spec.zone ? Math.max(spec.zone[2] - spec.zone[0], spec.zone[3] - spec.zone[1]) + 6 : 10;
    this.flyTo(at.x, spec.zone ? 2 : at.y - 2, at.z, size);
  }

  pick(event) {
    const rect = this.renderer.domElement.getBoundingClientRect();
    this.pointer.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1);
    this.raycaster.setFromCamera(this.pointer, this.camera);
    const hit = this.raycaster.intersectObjects(this.pickables, false)[0];
    return hit ? hit.object.userData.device : null;
  }

  // ---------------------------------------------------------------------------------- bucle
  frame(time, dt) {
    if (this.tween) {
      const t = Math.min(1, (performance.now() - this.tween.start) / this.tween.ms);
      this.tween.step(t);
      if (t >= 1) this.tween = null;
    }
    this.controls.update();
    for (const m of this.markers.values()) {
      m.head.rotation.y += dt * 0.8;
      m.head.position.y = Math.sin(time * 1.6 + m.at.x) * 0.18;
      if (m.flash > 0) {
        m.flash = Math.max(0, m.flash - dt * 1.4);
        m.ring.material.opacity = m.flash * 0.9;
        m.ring.scale.setScalar(1 + (1 - m.flash) * 1.8);
      }
    }
    if (this.selection.visible) this.selection.material.color.setHSL(0.12, 1, 0.55 + 0.15 * Math.sin(time * 5));
    this.hubRing.rotation.z += dt * 0.4;
    this.packets = this.packets.filter((p) => {
      p.t += dt * p.speed;
      if (p.t >= 1) {
        p.mesh.visible = false;
        return false;
      }
      p.mesh.position.copy(p.curve.getPoint(p.t));
      p.mesh.material.opacity = Math.min(1, (1 - p.t) * 3);
      return true;
    });
    this.renderer.render(this.scene, this.camera);
    this.labels.render(this.scene, this.camera);
  }
}
