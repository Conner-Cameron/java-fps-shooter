import { WEAPONS } from "./weapons.js";
import { hazardTexture } from "./textures.js";
import { addBox } from "./world.js";

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

function spawnTarget() {
  const [x, y, z] = randomArenaPosition(1 + Math.random() * 3.5);
  const size = randomTargetSize();
  const mesh = addBox([x, y, z], [size, size, size], 0xffffff, hazardTexture, 1.2);
  const maxHp = randomTargetHp();
  return {
    mesh,
    hp: maxHp,
    maxHp,
    flashUntil: 0,
    size,
    home: new THREE.Vector3(x, y, z),
    motion: randomTargetMotion(),
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
