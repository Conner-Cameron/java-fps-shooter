import * as THREE from "three";
import { scene } from "./core.js";
import { tiledClone } from "./textures.js";
import { pbrMaterial, boxUVs, copyUv1, leafMaterial } from "./pbr.js";


// ================================================================
// World: same arena layout as the desktop version, now dressed with a
// gradient sky, a textured ground, a distant mountain ring (so the
// arena doesn't feel like it's floating in a void), and scattered
// trees/rocks for foreground detail.
// ================================================================
export const collidables = []; // meshes the ground-detection raycast can land the player on (see the jump/gravity code)
export const solidBoxes = []; // precomputed Box3s for horizontal + ceiling collision against static structure
export const bulletBlockers = []; // meshes the shoot() raycast checks first -- anything solid stops a bullet, hit or miss
export const portals = []; // {x, y, z, r, to}, eye-height like spawn/respawn positions -- see collision.js's portalAt()
export const climbables = []; // {minX, maxX, minZ, maxZ, topY, centerX, centerZ, durationMs} -- see collision.js's climbableAt()
export const portalBeams = []; // the glowing columns, animated (a slow spin) each frame in game.js's tick()
// Everything the map builds lives under one group, so a different map can be swapped in (see clearWorld).
export const worldRoot = new THREE.Group();
scene.add(worldRoot);

// Registers a static mesh as real solid structure: standable from above
// (via `collidables`, used by the downward ground raycast), blocking
// horizontally / from below (via `solidBoxes`, used by the horizontal
// slide + ceiling checks), AND (unless `blocksBullets` is false) blocking
// gunfire via `bulletBlockers` -- the building's window glass opts out of
// that last part: it's still solid to walk into, but a real window pane
// wouldn't stop a bullet, and the whole point of the high ground is an
// unobstructed line of fire down through it. Moving objects (practice
// targets) deliberately don't go through this -- their Box3 would need
// recomputing every frame.
function registerSolid(mesh, blocksBullets = true) {
  collidables.push(mesh);
  solidBoxes.push(new THREE.Box3().setFromObject(mesh));
  if (blocksBullets) bulletBlockers.push(mesh);
  return mesh;
}

// Rotated ramps need a precise oriented check, not a loose world-axis
// Box3 (see registerRamp below for why). Stored as the ramp's inverse
// world matrix + its exact local half-extents, so a query point can be
// transformed into the ramp's own local frame and checked exactly,
// regardless of which way it's tilted.
export const rampColliders = [];

function registerRamp(mesh, width, thickness, length) {
  collidables.push(mesh);
  bulletBlockers.push(mesh);
  mesh.updateMatrixWorld(true);
  rampColliders.push({
    invMatrix: new THREE.Matrix4().copy(mesh.matrixWorld).invert(),
    halfW: width / 2,
    halfT: thickness / 2,
    halfL: length / 2
  });
  return mesh;
}

export function addBox(position, size, color, texture, tileSize) {
  const geo = new THREE.BoxGeometry(size[0], size[1], size[2]);
  const matOptions = { color };
  if (texture) {
    const t = Math.max(tileSize || 2.5, 0.01);
    matOptions.map = tiledClone(texture, Math.max(size[0] / t, 0.5), Math.max(size[1] / t, 0.5));
  }
  const mat = new THREE.MeshLambertMaterial(matOptions);
  const mesh = new THREE.Mesh(geo, mat);
  mesh.position.set(position[0], position[1], position[2]);
  worldRoot.add(mesh);
  return mesh;
}

// A map box dressed with a photo PBR set: towers read as brushed concrete, the multi-floor building as
// plaster (its `tile` field sets the panel size), and walls/cover/floors as concrete.
function addPbrBox(b) {
  const [sx, sy, sz] = b.s;
  // The map color is a flat tint on the photo. Pulled 40% toward white so the darkest trim (#2b2b2e)
  // reads as dark concrete instead of black: the photo should carry the surface, not the tint.
  const tint = new THREE.Color(parseInt(b.color.slice(1), 16)).lerp(new THREE.Color(0xffffff), 0.4);
  // A map box can name its surface outright ("concrete", "plaster", "brushed"); otherwise the role picks it.
  const set = b.surface || (b.climb ? "brushed" : b.tile ? "plaster" : "concrete");
  const t = Math.max(b.tile || 2.5, 0.01);
  const mat = pbrMaterial(set, tint, 1, 1);
  const mesh = new THREE.Mesh(boxUVs(new THREE.BoxGeometry(sx, sy, sz), sx, sy, sz, t), mat);
  mesh.position.set(b.c[0], b.c[1], b.c[2]);
  worldRoot.add(mesh);
  return mesh;
}

// ================================================================

// ---- Sky dome: vertical gradient instead of a single flat color ----
function addSkyDome() {
  const geo = new THREE.SphereGeometry(300, 24, 16);
  const mat = new THREE.ShaderMaterial({
    uniforms: {
      topColor: { value: new THREE.Color(0x4d76b3) },
      bottomColor: { value: new THREE.Color(0xb8d4dc) },
      offset: { value: 20 },
      exponent: { value: 0.6 }
    },
    vertexShader: `
      varying vec3 vWorldPosition;
      void main() {
        vec4 worldPosition = modelMatrix * vec4(position, 1.0);
        vWorldPosition = worldPosition.xyz;
        gl_Position = projectionMatrix * viewMatrix * worldPosition;
      }
    `,
    fragmentShader: `
      uniform vec3 topColor;
      uniform vec3 bottomColor;
      uniform float offset;
      uniform float exponent;
      varying vec3 vWorldPosition;
      void main() {
        float h = normalize(vWorldPosition + vec3(0.0, offset, 0.0)).y;
        gl_FragColor = vec4(mix(bottomColor, topColor, max(pow(max(h, 0.0), exponent), 0.0)), 1.0);
      }
    `,
    side: THREE.BackSide,
    fog: false
  });
  scene.add(new THREE.Mesh(geo, mat));
}
addSkyDome();


function addGround(box) {
  // One grass tile every ~6 world units, so the ground doesn't read as a single smeared photo.
  const geo = boxUVs(new THREE.BoxGeometry(...box.s), box.s[0], box.s[1], box.s[2], 6);
  const mat = pbrMaterial("grass", 0xb4e07a, 1, 1);
  const mesh = new THREE.Mesh(geo, mat);
  mesh.position.set(...box.c);
  worldRoot.add(mesh);
  registerSolid(mesh);
}

// ---- Distant mountain ring -- breaks the "floating in a void" look ----
function addMountains() {
  const count = 16;
  const radius = 78;
  for (let i = 0; i < count; i++) {
    const angle = (i / count) * Math.PI * 2;
    const dist = radius + ((i * 37) % 17) - 8;
    const x = Math.cos(angle) * dist;
    const z = Math.sin(angle) * dist;
    const h = 20 + ((i * 53) % 20);
    const r = 10 + ((i * 29) % 8);
    const sides = 5 + (i % 3);
    const mat = pbrMaterial("rock", 0x8a94aa, r / 3, h / 3);
    const mesh = new THREE.Mesh(copyUv1(new THREE.ConeGeometry(r, h, sides)), mat);
    mesh.position.set(x, h / 2 - 2, z);
    mesh.rotation.y = i * 0.7;
    worldRoot.add(mesh);
  }
}

// ---- Scattered trees and rocks for foreground detail ----
// Small seeded PRNG (mulberry32) so every tree's canopy comes out the same on every load.
function seeded(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// A canopy of leaf cards (one photo leaf each) scattered over a shell around the crown. One
// InstancedMesh per tree, so the whole crown is a single draw call.
const CANOPY_CARDS = 70;
const CANOPY_CENTER_Y = 2.5;
const leafCard = copyUv1(new THREE.PlaneGeometry(1, 1));
// The leaf photo holds two leaves side by side; each card uses the left one.
{
  const uv = leafCard.attributes.uv;
  for (let i = 0; i < uv.count; i++) uv.setX(i, uv.getX(i) * 0.5);
}
function addCanopy(group, seed) {
  const rnd = seeded(seed);
  const cards = new THREE.InstancedMesh(leafCard, leafMaterial(), CANOPY_CARDS);
  const dummy = new THREE.Object3D();
  const center = new THREE.Vector3(0, CANOPY_CENTER_Y, 0);
  const shade = new THREE.Color();
  for (let i = 0; i < CANOPY_CARDS; i++) {
    // Random direction on the sphere, pushed out to the crown's surface.
    const u = rnd() * 2 - 1;
    const phi = rnd() * Math.PI * 2;
    const s = Math.sqrt(1 - u * u);
    const dir = new THREE.Vector3(s * Math.cos(phi), u * 0.8, s * Math.sin(phi));
    dummy.position.copy(center).addScaledVector(dir, 0.85 + rnd() * 0.4);
    dummy.lookAt(center.clone().addScaledVector(dir, 2));
    dummy.rotateZ(rnd() * Math.PI * 2); // random roll so the leaves don't line up
    dummy.scale.setScalar(0.7 + rnd() * 0.5);
    dummy.updateMatrix();
    cards.setMatrixAt(i, dummy.matrix);
    // Slight per-leaf variation in green so the crown isn't one flat color.
    shade.setHSL(0.24 + rnd() * 0.05, 0.45 + rnd() * 0.2, 0.25 + rnd() * 0.15);
    cards.setColorAt(i, shade);
  }
  cards.instanceMatrix.needsUpdate = true;
  cards.instanceColor.needsUpdate = true;
  group.add(cards);
}

function addTree(x, z) {
  const group = new THREE.Group();
  const trunk = new THREE.Mesh(
    copyUv1(new THREE.CylinderGeometry(0.15, 0.22, 1.6, 6)),
    pbrMaterial("bark", 0xffffff, 1, 2)
  );
  trunk.position.y = 0.8;
  group.add(trunk);
  addCanopy(group, Math.round(x * 100) * 1000 + Math.round(z * 100));
  group.position.set(x, 0, z);
  worldRoot.add(group);
  // Bullet-blocking only -- deliberately not registerSolid(), so trees
  // don't also become standable/walk-collision geometry (not asked for).
  bulletBlockers.push(group);
}

function addRock(x, z, scale) {
  const rock = new THREE.Mesh(
    copyUv1(new THREE.DodecahedronGeometry(0.6 * scale, 0)),
    pbrMaterial("rock", 0x8a8a86, 1, 1)
  );
  rock.position.set(x, 0.3 * scale, z);
  rock.rotation.set(scale * 1.3, scale * 2.1, 0);
  worldRoot.add(rock);
  bulletBlockers.push(rock);
}

// ---- Portals: a glowing floor pad + an energy column, purely visual -- not solid and not a
// bullet-blocker. The teleport itself is a plain position check (see collision.js's portalAt()),
// resolved locally for feel and confirmed/corrected by the server in PvP (see GameServer.java).
const EYE_HEIGHT = 1.7; // matches game.js -- portal centers (like spawn/respawn positions) are eye-height
const PORTAL_GLOW = 0x54ccf2;

function addPortal(p) {
  const feetY = p.c[1] - EYE_HEIGHT;

  const pad = new THREE.Mesh(
    new THREE.CylinderGeometry(p.r, p.r, 0.06, 24),
    new THREE.MeshBasicMaterial({ color: PORTAL_GLOW })
  );
  pad.position.set(p.c[0], feetY + 0.03, p.c[2]);
  worldRoot.add(pad);

  const beam = new THREE.Mesh(
    new THREE.CylinderGeometry(p.r * 0.55, p.r * 0.55, 3.2, 24, 1, true),
    new THREE.MeshBasicMaterial({
      color: PORTAL_GLOW, transparent: true, opacity: 0.25, side: THREE.DoubleSide,
      blending: THREE.AdditiveBlending, depthWrite: false
    })
  );
  beam.position.set(p.c[0], feetY + 1.6, p.c[2]);
  worldRoot.add(beam);
  portalBeams.push(beam);

  portals.push({ x: p.c[0], y: p.c[1], z: p.c[2], r: p.r, to: p.to });
}

// General start/end ramp builder: position it at the segment's midpoint
// and orient it with lookAt so the math works for any direction/slope
// without hand-computing rotation angles.
function addRamp(start, end, width, thickness) {
  const startV = new THREE.Vector3(...start);
  const endV = new THREE.Vector3(...end);
  const length = Math.max(startV.distanceTo(endV), 0.01);
  const mid = new THREE.Vector3().addVectors(startV, endV).multiplyScalar(0.5);
  const mat = pbrMaterial("concrete", 0xb4b1a8, 1, 1);
  const mesh = new THREE.Mesh(boxUVs(new THREE.BoxGeometry(width, thickness, length), width, thickness, length, 2.5), mat);
  mesh.position.copy(mid);
  mesh.lookAt(startV);
  worldRoot.add(mesh);
  // Deliberately NOT registerSolid(): an inclined mesh's axis-aligned Box3
  // has to span its full rise (loose bounding box around a rotated
  // shape), which would block the player from walking along the ramp at
  // almost any point on it. registerRamp() instead checks collision in
  // the ramp's own local (unrotated) frame, so it can tell "standing on
  // this ramp's surface" apart from "a different ramp/level happens to
  // pass through this same space at head height" -- which a simple
  // world-axis box couldn't distinguish at all.
  registerRamp(mesh, width, thickness, length);
  return mesh;
}

export function buildWorld(MAP) {
  addMountains();
  // ---- Build the world from the shared map file (web/map.json) ----
  // The server loads this exact file for bullet blocking, spawn safety, and
  // movement validation, so walls/building/ramps/trees/rocks only ever
  // need to be defined once. Boxes: c = center, s = size; `glass` panes
  // and anything with bullets:false are walk-solid but don't stop gunfire.
  MAP.boxes.forEach((b) => {
    if (b.kind === "ground") {
      addGround(b);
    } else if (b.glass) {
      const glass = new THREE.Mesh(
        new THREE.BoxGeometry(...b.s),
        new THREE.MeshLambertMaterial({ color: 0x8fd0e6, transparent: true, opacity: 0.28 })
      );
      glass.position.set(...b.c);
      worldRoot.add(glass);
      registerSolid(glass, b.bullets !== false);
    } else {
      registerSolid(addPbrBox(b), b.bullets !== false);
    }
    if (b.climb) {
      climbables.push({
        minX: b.c[0] - b.s[0] / 2, maxX: b.c[0] + b.s[0] / 2,
        minZ: b.c[2] - b.s[2] / 2, maxZ: b.c[2] + b.s[2] / 2,
        topY: b.c[1] + b.s[1] / 2, centerX: b.c[0], centerZ: b.c[2],
        durationMs: b.climb
      });
    }
  });
  MAP.trees.forEach(([x, z]) => addTree(x, z));
  MAP.rocks.forEach(([x, z, s]) => addRock(x, z, s));
  MAP.ramps.forEach((r) => addRamp(r.a, r.b, r.w, r.t));
  (MAP.portals || []).forEach((p) => addPortal(p));
}

// Removes the current map's meshes and empties every collision and trigger list, so buildWorld() can
// build another map in its place. The shared materials and textures stay cached.
export function clearWorld() {
  for (const child of [...worldRoot.children]) {
    worldRoot.remove(child);
    child.traverse((o) => {
      if (o.geometry) o.geometry.dispose();
    });
  }
  for (const list of [collidables, solidBoxes, bulletBlockers, portals, climbables, portalBeams, rampColliders]) {
    list.length = 0;
  }
}
