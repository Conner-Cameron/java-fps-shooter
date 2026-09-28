import { scene } from "./core.js";
import { tiledClone, metalTexture, rockTexture, barkTexture, foliageTexture, makeGroundTexture } from "./textures.js";


// ================================================================
// World: same arena layout as the desktop version, now dressed with a
// gradient sky, a textured ground, a distant mountain ring (so the
// arena doesn't feel like it's floating in a void), and scattered
// trees/rocks for foreground detail.
// ================================================================
export const collidables = []; // meshes the ground-detection raycast can land the player on (see the jump/gravity code)
export const solidBoxes = []; // precomputed Box3s for horizontal + ceiling collision against static structure
export const bulletBlockers = []; // meshes the shoot() raycast checks first -- anything solid stops a bullet, hit or miss

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
  scene.add(mesh);
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
  const geo = new THREE.BoxGeometry(...box.s);
  const mat = new THREE.MeshLambertMaterial({ map: makeGroundTexture() });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.position.set(...box.c);
  scene.add(mesh);
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
    const mat = new THREE.MeshLambertMaterial({
      color: 0x5b6a86,
      map: tiledClone(rockTexture, r / 3, h / 3)
    });
    const mesh = new THREE.Mesh(new THREE.ConeGeometry(r, h, sides), mat);
    mesh.position.set(x, h / 2 - 2, z);
    mesh.rotation.y = i * 0.7;
    scene.add(mesh);
  }
}
addMountains();

// ---- Scattered trees and rocks for foreground detail ----
function addTree(x, z) {
  const group = new THREE.Group();
  const trunk = new THREE.Mesh(
    new THREE.CylinderGeometry(0.15, 0.22, 1.6, 6),
    new THREE.MeshLambertMaterial({ color: 0xffffff, map: tiledClone(barkTexture, 1, 1) })
  );
  trunk.position.y = 0.8;
  group.add(trunk);
  const leaves = new THREE.Mesh(
    new THREE.ConeGeometry(1.1, 2.4, 7),
    new THREE.MeshLambertMaterial({ color: 0xffffff, map: tiledClone(foliageTexture, 1, 1) })
  );
  leaves.position.y = 2.4;
  group.add(leaves);
  group.position.set(x, 0, z);
  scene.add(group);
  // Bullet-blocking only -- deliberately not registerSolid(), so trees
  // don't also become standable/walk-collision geometry (not asked for).
  bulletBlockers.push(group);
}

function addRock(x, z, scale) {
  const rock = new THREE.Mesh(
    new THREE.DodecahedronGeometry(0.6 * scale, 0),
    new THREE.MeshLambertMaterial({ color: 0x8a8a86, map: tiledClone(rockTexture, 1, 1) })
  );
  rock.position.set(x, 0.3 * scale, z);
  rock.rotation.set(scale * 1.3, scale * 2.1, 0);
  scene.add(rock);
  bulletBlockers.push(rock);
}

// General start/end ramp builder: position it at the segment's midpoint
// and orient it with lookAt so the math works for any direction/slope
// without hand-computing rotation angles.
function addRamp(start, end, width, thickness) {
  const startV = new THREE.Vector3(...start);
  const endV = new THREE.Vector3(...end);
  const length = Math.max(startV.distanceTo(endV), 0.01);
  const mid = new THREE.Vector3().addVectors(startV, endV).multiplyScalar(0.5);
  const mat = new THREE.MeshLambertMaterial({
    color: 0x9a968c,
    map: tiledClone(metalTexture, Math.max(width / 2, 0.5), Math.max(length / 2, 0.5))
  });
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(width, thickness, length), mat);
  mesh.position.copy(mid);
  mesh.lookAt(startV);
  scene.add(mesh);
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
      scene.add(glass);
      registerSolid(glass, b.bullets !== false);
    } else {
      registerSolid(addBox(b.c, b.s, parseInt(b.color.slice(1), 16), metalTexture, b.tile), b.bullets !== false);
    }
  });
  MAP.trees.forEach(([x, z]) => addTree(x, z));
  MAP.rocks.forEach(([x, z, s]) => addRock(x, z, s));
  MAP.ramps.forEach((r) => addRamp(r.a, r.b, r.w, r.t));
}
