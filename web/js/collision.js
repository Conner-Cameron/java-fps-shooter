import * as THREE from "three";
import { collidables, solidBoxes, rampColliders } from "./world.js";

const GROUND_PROBE_UP = 0.4;   // cast the ray from this far above current feet
const GROUND_SNAP_MARGIN = 0.1; // a little slack beyond this frame's fall distance
const DOWN = new THREE.Vector3(0, -1, 0);
const groundProbeOrigin = new THREE.Vector3();
const groundRaycaster = new THREE.Raycaster();
// Casts down from just above the player's current feet, far enough to
// cover however much they're about to fall/step down this frame -- not
// from high above, which would incorrectly detect a floor several
// stories up as "the ground" while standing on the level beneath it.
export function findGroundY(x, z, feetY, fallDistance) {
  groundProbeOrigin.set(x, feetY + GROUND_PROBE_UP, z);
  groundRaycaster.set(groundProbeOrigin, DOWN);
  groundRaycaster.far = GROUND_PROBE_UP + fallDistance + GROUND_SNAP_MARGIN;
  const hits = groundRaycaster.intersectObjects(collidables, false);
  return hits.length > 0 ? hits[0].point.y : null;
}

// Horizontal collision + ceiling detection against `solidBoxes` -- the
// player is approximated as a vertical cylinder (radius + height), which
// in turn is approximated as an axis-aligned box for these checks. Both
// are standard, cheap simplifications for a simple FPS controller.
const PLAYER_RADIUS = 0.35;
export const PLAYER_HEIGHT = 1.8;
// The floor/slab/ramp the player is currently standing on is itself in
// solidBoxes -- its top surface sits exactly at the player's feet, so
// without this small gap, standing still would count as "colliding with
// the ground" and block all horizontal movement. Starting the check
// box just above the feet avoids that without weakening real wall
// detection at all (walls span well past this tiny margin anyway).
const STAND_CLEARANCE = 0.05;
const playerBox = new THREE.Box3();

// Ramps get their own precise check in each ramp's own local (unrotated)
// frame: transform the player's feet/head into that frame, and compare
// against the ramp's exact half-extents there. This is what correctly
// tells "standing on this ramp's own sloped surface" (local Y at/above
// its local top face) apart from "some other loop of the spiral passes
// through this same world-space region at the wrong height" (local Y
// deep inside or below the slab) -- a loose world-axis box can't make
// that distinction at all, which is exactly what let players walk
// straight through staircase levels that weren't at their own height.
const rampLocalFeet = new THREE.Vector3();
const rampLocalHead = new THREE.Vector3();
const RAMP_STAND_CLEARANCE = 0.08;

export function collidesWithRamps(x, feetY, headY, z) {
  for (let i = 0; i < rampColliders.length; i++) {
    const r = rampColliders[i];
    rampLocalFeet.set(x, feetY, z).applyMatrix4(r.invMatrix);
    if (Math.abs(rampLocalFeet.x) > r.halfW + PLAYER_RADIUS) continue;
    if (Math.abs(rampLocalFeet.z) > r.halfL + PLAYER_RADIUS) continue;
    if (rampLocalFeet.y >= r.halfT - RAMP_STAND_CLEARANCE) continue; // at/above this ramp's own surface

    rampLocalHead.set(x, headY, z).applyMatrix4(r.invMatrix);
    if (rampLocalHead.y <= -r.halfT) continue; // entirely below this ramp's underside

    return true; // body intersects this ramp's solid slab at the wrong height
  }
  return false;
}

export function collidesAt(x, feetY, z) {
  const headY = feetY + PLAYER_HEIGHT;
  playerBox.min.set(x - PLAYER_RADIUS, feetY + STAND_CLEARANCE, z - PLAYER_RADIUS);
  playerBox.max.set(x + PLAYER_RADIUS, headY, z + PLAYER_RADIUS);
  for (let i = 0; i < solidBoxes.length; i++) {
    if (playerBox.intersectsBox(solidBoxes[i])) return true;
  }
  return collidesWithRamps(x, feetY, headY, z);
}

// Is there a solid ceiling between the player's current head height and
// where their head would end up this frame? Returns the lowest such
// ceiling's underside, or null if the way up is clear.
export function findCeilingY(x, z, headY, proposedHeadY) {
  let closest = null;
  for (let i = 0; i < solidBoxes.length; i++) {
    const b = solidBoxes[i];
    if (x + PLAYER_RADIUS < b.min.x || x - PLAYER_RADIUS > b.max.x) continue;
    if (z + PLAYER_RADIUS < b.min.z || z - PLAYER_RADIUS > b.max.z) continue;
    if (b.min.y >= headY && b.min.y <= proposedHeadY) {
      if (closest === null || b.min.y < closest) closest = b.min.y;
    }
  }
  return closest;
}
