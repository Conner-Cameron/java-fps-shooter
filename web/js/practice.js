import * as THREE from "three";
import { WEAPONS } from "./weapons.js";
import { hazardTexture } from "./textures.js";
import { scene } from "./core.js";
import { addBox, bulletBlockers, treeZones } from "./world.js";

// A leaf canopy reaches about 1.9 m from its tree's center (its leaf cards sit on a shell of radius 1.25 plus half a card),
// and spans from roughly 0.6 m to 4.4 m high.
const CANOPY_REACH = 2.0, CANOPY_LOW = 0.6, CANOPY_HIGH = 4.4;

export const targets = []; // local practice bots -- shootable, respawn on hit, not networked

const TARGET_SIZE_MIN = 0.6;
const TARGET_SIZE_MAX = 1.2;
const TARGET_COUNT = 6;
const ARENA_X = 34, ARENA_Z_NEAR = -2, ARENA_Z_FAR = 26;

export function randomArenaPosition(y) {
  const x = (Math.random() - 0.5) * ARENA_X;
  const z = ARENA_Z_NEAR - Math.random() * ARENA_Z_FAR;
  return [x, y, z];
}

// Target HP is randomized between the weakest and strongest weapon's
// damage, so a one-shot kill isn't guaranteed regardless of weapon --
// a full-HP block needs several SMG/pistol hits but can still go down
// in one sniper shot, mirroring how damage already works in PvP.
const MIN_WEAPON_DAMAGE = Math.min(...WEAPONS.map((w) => w.damage));
const MAX_WEAPON_DAMAGE = Math.max(...WEAPONS.map((w) => w.damage));

export function randomTargetHp() {
  return MIN_WEAPON_DAMAGE + Math.floor(Math.random() * (MAX_WEAPON_DAMAGE - MIN_WEAPON_DAMAGE + 1));
}

// Each block gets its own horizontal drift and vertical bob speed/amplitude,
// rolled once at spawn and held fixed for that block's lifetime -- only
// re-rolled when it's destroyed and respawns elsewhere. Sine-based drift
// around a fixed "home" point (rather than bouncing off boundaries) avoids
// ever needing collision logic against the arena's walls/terrain.
export function randomTargetMotion() {
  return {
    horizSpeed: 0.3 + Math.random() * 1.5,
    horizRadius: 1 + Math.random() * 3,
    vertSpeed: 0.4 + Math.random() * 2.0,
    vertRadius: 0.3 + Math.random() * 0.9,
    phase: Math.random() * Math.PI * 2
  };
}

export function randomTargetSize() {
  return TARGET_SIZE_MIN + Math.random() * (TARGET_SIZE_MAX - TARGET_SIZE_MIN);
}

// A block's home must leave its own footprint clear of anything solid (trees' canopies included), and its drift
// radius is cut down to the free space around home, so a block can never drift into a tree, rock or wall.
const _obj = new THREE.Box3();
const _edgeBox = new THREE.Box3();

// Free horizontal room around (x, z) at this height: distance to the nearest scenery less `edge`. Infinity if none.
function sceneryGap(x, y, z, edge, mv) {
  let best = Infinity;
  for (const obj of bulletBlockers) {
    const b = _obj.setFromObject(obj);
    if (b.min.y > y + mv || b.max.y < y - mv) continue;
    const dx = Math.max(b.min.x - x, 0, x - b.max.x);
    const dz = Math.max(b.min.z - z, 0, z - b.max.z);
    best = Math.min(best, Math.hypot(dx, dz));
  }
  for (const tree of treeZones) {
    if (y + mv < CANOPY_LOW || y - mv > CANOPY_HIGH) continue;
    best = Math.min(best, Math.hypot(x - tree.x, z - tree.z) - CANOPY_REACH);
  }
  return best - edge;
}

// True if a block with this half-width and reach, centered on (x, y, z), touches nothing solid.
function clearOfScenery(x, y, z, edge, mv) {
  _edgeBox.min.set(x - edge, y - mv, z - edge);
  _edgeBox.max.set(x + edge, y + mv, z + edge);
  for (const obj of bulletBlockers) {
    if (_obj.setFromObject(obj).intersectsBox(_edgeBox)) return false;
  }
  return sceneryGap(x, y, z, edge, mv) >= 0;
}

// A clear home for a block with this size and motion; its drift radius is reduced to fit the space around home.
export function placeTarget(size, motion) {
  const edge = size / 2 + 0.3;
  const mv = motion.vertRadius + edge;
  for (let attempt = 0; attempt < 200; attempt++) {
    const [x, y, z] = randomArenaPosition(1 + Math.random() * 3.5);
    if (clearOfScenery(x, y, z, edge, mv)) {
      motion.horizRadius = Math.max(0, Math.min(motion.horizRadius, sceneryGap(x, y, z, edge, mv)));
      return [x, y, z];
    }
  }
  motion.horizRadius = 0.3;
  return randomArenaPosition(1 + Math.random() * 3.5);
}

function spawnTarget() {
  const size = randomTargetSize();
  const motion = randomTargetMotion();
  const [x, y, z] = placeTarget(size, motion);
  const mesh = addBox([x, y, z], [size, size, size], 0xffffff, hazardTexture, 1.2);
  const maxHp = randomTargetHp();
  return {
    mesh,
    hp: maxHp,
    maxHp,
    flashUntil: 0,
    size,
    home: new THREE.Vector3(x, y, z),
    motion,
    age: 0
  };
}

// Only spawned in Aim Training mode -- PvP is pure player-vs-player, no
// practice blocks cluttering the arena. See the mode-select wiring below.
export function createPracticeTargets() {
  for (let i = 0; i < TARGET_COUNT; i++) {
    targets.push(spawnTarget());
  }
}

// Removes every block from the scene (leaving Aim Training) so a fresh
// createPracticeTargets() starts from nothing.
export function clearPracticeTargets() {
  for (const t of targets) {
    scene.remove(t.mesh);
    t.mesh.geometry.dispose();
    if (t.mesh.material.map) t.mesh.material.map.dispose();
    t.mesh.material.dispose();
  }
  targets.length = 0;
}
