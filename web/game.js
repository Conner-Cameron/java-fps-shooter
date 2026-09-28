// ================================================================
// Game session: owns the mutable state (weapon/ammo, look angles, movement,
// networking state) plus input handling, the movement/physics loop, combat
// (shoot / melee) and the server-message handler. Everything else is split
// into single-purpose modules under js/:
//
//   core.js         renderer, scene, camera, lights
//   weapons.js      WEAPONS stat table (mirrors GameServer.java's tables)
//   textures.js     procedural canvas textures
//   world.js        map.json -> scene, plus the solid/bullet-blocker lists
//   collision.js    ground / wall / ceiling / ramp collision queries
//   practice.js     Aim Training target blocks
//   playerModel.js  remote-player avatar model
//   weaponModels.js first-person gun models, animation, loadout icons
//   audio.js        synthesized gunshot / reload / melee / hit sounds
//   hud.js          in-game HUD rendering (values in, DOM out)
//   screens.js      pre-game menu flow (mode -> loadout -> setup)
//   net.js          WebSocket transport
// ================================================================

import * as THREE from "three";
import { renderer, scene, camera, BASE_FOV } from "./js/core.js";
import { WEAPONS, KNIFE_INDEX } from "./js/weapons.js";
import { hazardTexture } from "./js/textures.js";
import { playGunshot, playReloadSound, playMeleeSwing, playHitTick, resumeAudio } from "./js/audio.js";
import { bulletBlockers, addBox, buildWorld } from "./js/world.js";
import {
  targets, randomArenaPosition, randomTargetHp, randomTargetMotion, randomTargetSize, createPracticeTargets,
  clearPracticeTargets
} from "./js/practice.js";
import { createPlayerModel, EYE_OFFSET, lerpAngle } from "./js/playerModel.js";
import { findGroundY, collidesAt, collidesWithRamps, findCeilingY, PLAYER_HEIGHT } from "./js/collision.js";
import { showGun, triggerGunFire, updateGunModel, renderWeaponIcons } from "./js/weaponModels.js";
import { initMenus, isScreenVisible, setOverlayVisible, showScreen, setPvpError } from "./js/screens.js";
import { connect, disconnect, isConnected, sendMessage, startKeepAlive } from "./js/net.js";
import { finishLoading } from "./js/assets.js";
import {
  showHitMarker, setScopeVisible, setAdsCrosshairVisible, setCrosshairHidden, setCrosshairGap, clearAimUi,
  setDeathOverlay, showBanner, hideBanner, setStatus, renderScoreboard, renderHealth, renderWeapon, renderAmmo,
  renderTrainingScore, setRoomCode
} from "./js/hud.js";

// World geometry lives in map.json (shared with the server) -- fetched
// first so everything below can build synchronously from it as before.
let MAP;
try {
  MAP = await (await fetch("map.json")).json();
} catch (e) {
  document.body.textContent = "Failed to load map data (map.json).";
  throw e;
}

buildWorld(MAP);

// ================================================================
// First-person weapon view-model (parented to the camera, so it stays
// anchored to the screen exactly like a real FPS gun) -- mirrors the
// desktop build's GunModel: idle sway, a walk bob, a recoil kick and a
// muzzle flash on firing. Without this the local player was just a bare
// floating camera with no sense of a body/weapon in their own view.
// ================================================================
let currentWeapon = 1;
let lastShotTime = 0;
let ammo = WEAPONS.map((w) => w.magSize);
let reloading = false;
let reloadEndTime = 0;

function requestReload() {
  if (WEAPONS[currentWeapon].melee) return; // no ammo, nothing to reload
  if (reloading || ammo[currentWeapon] >= WEAPONS[currentWeapon].magSize) return;
  reloading = true;
  aiming = false; // lower the weapon to reload, same as most FPS games
  reloadEndTime = performance.now() + WEAPONS[currentWeapon].reloadMs;
  playReloadSound(currentWeapon);
  updateAmmoHud();
  sendMessage({ type: "reload" });
}




function selectWeapon(idx) {
  if (idx === currentWeapon || idx < 0 || idx >= WEAPONS.length) return;
  currentWeapon = idx;
  showGun(idx);
  reloading = false; // switching holsters any in-progress reload, same as the server
  aiming = false; // re-raise and re-aim fresh each time you switch weapons
  adsBlend = 0; // snap out of any aimed pose/zoom immediately, don't ease out
  clearAimUi(); // force the scope/ADS reticle off the instant you switch away
  updateWeaponHud();
  updateAmmoHud();
  sendMessage({ type: "weapon", id: idx });
}


// ================================================================
// Networking
// ================================================================
const remotePlayers = new Map(); // id -> { group, targetPos, targetYaw, name, kills, alive }
let myId = null;
let killLimit = 10;
let myKills = 0;

const MAX_HP = 100;
let myHp = MAX_HP;

function ensureRemotePlayer(id, name) {
  let rp = remotePlayers.get(id);
  if (!rp) {
    const group = createPlayerModel();
    scene.add(group);
    rp = { group, targetPos: new THREE.Vector3(0, 1.7, 0), targetYaw: 0, name: name || ("Player" + id), kills: 0, alive: true };
    remotePlayers.set(id, rp);
  }
  if (name) rp.name = name;
  return rp;
}

function removeRemotePlayer(id) {
  const rp = remotePlayers.get(id);
  if (rp) {
    scene.remove(rp.group);
    remotePlayers.delete(id);
  }
}

function handleServerMessage(msg) {
  switch (msg.type) {
    case "correct": {
      console.warn("Server corrected position:", msg.reason);
      if (!isDead) teleportLocalPlayer(msg.pos);
      break;
    }
    case "welcome": {
      myId = msg.id;
      killLimit = msg.killLimit;
      if (msg.pos) teleportLocalPlayer(msg.pos);
      for (const p of msg.players) {
        const rp = ensureRemotePlayer(p.id, p.name);
        rp.kills = p.kills;
        rp.targetPos.set(p.pos[0], p.pos[1] - EYE_OFFSET, p.pos[2]);
        rp.group.position.copy(rp.targetPos);
        rp.targetYaw = -p.yaw;
      }
      setRoomCode(msg.room);
      setStatus(`Connected as ${playerName || "you"}` + (msg.roomPublic === false ? ` — private room ${msg.room}, share the code` : ""));
      updateHud();
      break;
    }
    case "error": {
      // Couldn't get into the requested room: back to the PvP setup screen with the reason.
      leaveGame({ keepLoadout: true });
      showScreen("pvp");
      setPvpError(msg.reason || "Couldn't join that room");
      if (isLocked()) document.exitPointerLock();
      break;
    }
    case "playerJoined": {
      if (msg.id !== myId) ensureRemotePlayer(msg.id, msg.name);
      updateHud();
      break;
    }
    case "playerLeft": {
      removeRemotePlayer(msg.id);
      updateHud();
      break;
    }
    case "state": {
      if (msg.id === myId) break;
      const rp = ensureRemotePlayer(msg.id);
      rp.targetPos.set(msg.pos[0], msg.pos[1] - EYE_OFFSET, msg.pos[2]);
      rp.targetYaw = -msg.yaw;
      break;
    }
    case "damage": {
      // Fires on every landed hit, fatal or not -- the shooter always gets
      // a hit marker/tick (matches the CoD-style feedback), independent of
      // whether it happened to be the killing blow.
      if (msg.shooterId === myId) triggerHitFeedback();
      if (msg.victimId === myId) {
        myHp = msg.victimHp;
        updateHealthHud();
      }
      break;
    }
    case "ammo": {
      if (typeof msg.weapon === "number" && typeof msg.ammo === "number") {
        ammo[msg.weapon] = msg.ammo;
        if (msg.weapon === currentWeapon) updateAmmoHud();
      }
      break;
    }
    case "reload": {
      if (typeof msg.weapon === "number" && msg.weapon === currentWeapon) {
        reloading = true;
        reloadEndTime = performance.now() + (msg.durationMs || WEAPONS[currentWeapon].reloadMs);
        updateAmmoHud();
      }
      break;
    }
    case "kill": {
      if (msg.shooterId === myId) {
        myKills = msg.shooterKills;
      } else {
        const shooter = remotePlayers.get(msg.shooterId);
        if (shooter) shooter.kills = msg.shooterKills;
      }
      if (msg.victimId === myId) {
        triggerLocalDeath();
      } else {
        const victim = remotePlayers.get(msg.victimId);
        if (victim) victim.alive = false;
      }
      updateHud();
      break;
    }
    case "respawn": {
      if (msg.id === myId) {
        myHp = msg.hp || MAX_HP;
        reloading = false;
        updateHealthHud();
        updateAmmoHud();
        clearLocalDeath(msg.pos);
      } else {
        const rp = remotePlayers.get(msg.id);
        if (rp) {
          rp.alive = true;
          rp.targetPos.set(msg.pos[0], msg.pos[1] - EYE_OFFSET, msg.pos[2]);
          rp.group.position.copy(rp.targetPos);
        }
      }
      break;
    }
    case "matchOver": {
      showMatchBanner(msg);
      break;
    }
    case "matchReset": {
      myKills = 0;
      for (const rp of remotePlayers.values()) rp.kills = 0;
      updateHud();
      hideBanner();
      break;
    }
    default:
      break;
  }
}

let lastSentState = 0;
function sendStateIfDue(now) {
  if (!isConnected() || isDead) return;
  if (now - lastSentState < 50) return;
  lastSentState = now;
  sendMessage({
    type: "state",
    pos: [camera.position.x, camera.position.y, camera.position.z],
    yaw, pitch
  });
}

// ================================================================
// Input: pointer-lock mouse look + WASD fly movement
// ================================================================
const canvas = renderer.domElement;

let gameMode = null; // "pvp" | "training"
let playerName = ""; // as entered on the PvP setup screen
let primaryWeapon = 1; // the class weapon picked on the loadout screen -- what the knife toggle returns to

const keys = Object.create(null);
window.addEventListener("keydown", (e) => {
  keys[e.code] = true;
  // Weapon choice is locked in on the loadout screen for the rest of
  // this life -- no in-game switching, so the only weapon-related key
  // left once locked in is reload.
  if (isLocked() && e.code === "KeyR") requestReload();
});
window.addEventListener("keyup", (e) => { keys[e.code] = false; });

// Knife toggle: the one exception to "loadout is locked in" -- the knife
// is a secondary available to every class, freely swappable against
// whichever primary was actually picked. Only two slots exist, so any
// scroll direction just flips between them.
canvas.addEventListener("wheel", (e) => {
  if (!isLocked() || isDead) return;
  e.preventDefault();
  selectWeapon(currentWeapon === KNIFE_INDEX ? primaryWeapon : KNIFE_INDEX);
}, { passive: false });

let yaw = -Math.PI / 2;
let pitch = 0;
const MOUSE_SENSITIVITY = 0.0022;
const MOVE_SPEED = 6.0;
const SPRINT_MULT = 1.6; // Shift held (and not aiming) moves 60% faster
let started = false;
let isDead = false;

// Jump/gravity -- replaces the old free-fly Space (rise) / Shift (descend).
// Ground height is no longer a fixed constant: a downward raycast against
// `collidables` each frame finds whatever surface is actually beneath the
// player, so standing on a cover wall or a building floor works the same
// as standing on the ground plane.
const EYE_HEIGHT = 1.7;
const JUMP_SPEED = 7.0;
const GRAVITY = 18.0;
let verticalVelocity = 0;
let grounded = true;

// Safety net: a "prevent entry" collision system has no answer for
// actually ending up embedded in solid geometry, however that happens --
// every further move attempt also reads as "colliding" from in there,
// since you're already inside something, so without this a single edge
// case anywhere in the collision math means being stuck forever. Each
// frame, if the player's current spot doesn't collide, it's remembered;
// if it ever does, they're snapped back to the last spot that was fine.
let lastSafeX = 0, lastSafeY = 1.7, lastSafeZ = 8;


function isLocked() {
  return document.pointerLockElement === canvas;
}

function lockPointer() {
  // requestPointerLock() returns a promise in newer browsers; a refused lock
  // (e.g. Esc pressed too fast) is harmless, so don't let it surface as an unhandled rejection.
  Promise.resolve(canvas.requestPointerLock()).catch(() => {});
}

initMenus({
  onLoadoutChosen(idx) {
    primaryWeapon = idx;
    selectWeapon(idx); // no-op if it's already equipped (e.g. the default rifle)
  },
  onStartPvp(name, room) {
    if (!started) {
      started = true;
      gameMode = "pvp";
      playerName = name;
      setStatus("Connecting2026");
      connect(name, room, handleServerMessage, setStatus);
      document.getElementById("hud").hidden = false;
      document.getElementById("healthPanel").hidden = false;
      resumeAudio();
    }
    lockPointer();
  },
  onStartTraining() {
    if (!started) {
      started = true;
      gameMode = "training";
      // No connect() call at all -- Aim Training never opens a WebSocket,
      // so there is no way for another player to ever appear here.
      createPracticeTargets();
      document.getElementById("trainingHud").hidden = false;
      resumeAudio();
    }
    lockPointer();
  },
  onResume() {
    lockPointer();
  },
  onLeave() {
    leaveGame();
  }
});

startKeepAlive();

document.addEventListener("pointerlockchange", () => {
  if (isLocked() && !started) { // a join failed while the lock request was still in flight
    document.exitPointerLock();
    return;
  }
  setOverlayVisible(!isLocked());
  if (!isLocked()) {
    mouseHeld = false;
    aiming = false;
    // Losing the pointer mid-game (Esc) opens the pause menu: resume, or leave for mode select.
    if (started) showScreen("pause");
  }
});

// Leaves the current game entirely -- disconnects from the match, tears down
// its world state, and returns to mode select so a different mode/class can
// be chosen without reloading the page.
function leaveGame({ keepLoadout = false } = {}) {
  disconnect();
  setRoomCode(null);
  for (const id of [...remotePlayers.keys()]) removeRemotePlayer(id);
  clearPracticeTargets();
  for (const k of Object.keys(keys)) keys[k] = false;

  started = false;
  gameMode = null;
  playerName = "";
  myId = null;
  killLimit = 10;
  myKills = 0;
  myHp = MAX_HP;
  isDead = false;
  score = 0;
  mouseHeld = false;
  aiming = false;
  adsBlend = 0;
  camera.fov = BASE_FOV;
  camera.updateProjectionMatrix();

  // back to the default loadout, full magazines
  ammo = WEAPONS.map((w) => w.magSize);
  reloading = false;
  if (!keepLoadout) primaryWeapon = 1;
  currentWeapon = primaryWeapon;
  showGun(currentWeapon);

  yaw = -Math.PI / 2;
  pitch = 0;
  teleportLocalPlayer([0, EYE_HEIGHT, 8]);

  for (const id of ["hud", "trainingHud", "healthPanel"]) document.getElementById(id).hidden = true;
  renderTrainingScore(0);
  renderScoreboard({ myKills: 0, killLimit: 10, playerCount: 1, rows: [] });
  setStatus("");
  hideBanner();
  setDeathOverlay(false);
  clearAimUi();
  updateWeaponHud();
  updateAmmoHud();
  updateHealthHud();

  if (!keepLoadout) showScreen("mode");
  setOverlayVisible(true);
}

document.addEventListener("mousemove", (e) => {
  if (!isLocked()) return;
  // Mouse sensitivity scales down with the current (possibly mid-zoom)
  // FOV, so the same physical mouse movement always turns the camera by
  // the same on-screen angle regardless of zoom level -- without this,
  // the sniper's scope would feel wildly twitchy at 4-5x zoom.
  const sensScale = camera.fov / BASE_FOV;
  yaw += e.movementX * MOUSE_SENSITIVITY * sensScale;
  pitch -= e.movementY * MOUSE_SENSITIVITY * sensScale;
  const limit = Math.PI / 2 - 0.01;
  pitch = Math.max(-limit, Math.min(limit, pitch));
});

function getForward() {
  return new THREE.Vector3(
    Math.cos(yaw) * Math.cos(pitch),
    Math.sin(pitch),
    Math.sin(yaw) * Math.cos(pitch)
  ).normalize();
}

let mouseHeld = false;
let aiming = false;
let adsBlend = 0; // 0 = hip-fire, 1 = fully aimed -- smoothed each frame in tick()

// Right-click would otherwise open the browser's context menu, which
// would both break aiming and leave pointer lock in a weird state.
canvas.addEventListener("contextmenu", (e) => e.preventDefault());

canvas.addEventListener("mousedown", (e) => {
  if (!isLocked() || isDead) return;
  if (e.button === 0) {
    mouseHeld = true;
    shoot();
  } else if (e.button === 2 && !WEAPONS[currentWeapon].melee) { // no aiming down a knife
    aiming = true;
  }
});
window.addEventListener("mouseup", (e) => {
  if (e.button === 0) mouseHeld = false;
  else if (e.button === 2) aiming = false;
});

// ================================================================
// Shooting: local raycast against practice-bot targets, plus a
// networked "shoot" message the server resolves authoritatively
// against other players' positions.
// ================================================================
const raycaster = new THREE.Raycaster();
let score = 0;

// Converts an angular spread (degrees, half-angle of the cone) into an
// on-screen pixel radius, using the actual camera FOV and viewport
// height -- the same relationship a perspective projection uses to map
// angles to screen space. This has to match applySpread()'s real math,
// or the crosshair is just decoration: a target that visually "fills
// the gap" needs to actually have good hit odds, which only holds if
// the gap's pixel size is derived from the same angle, not an arbitrary
// constant. (Uses BASE_FOV rather than the live camera.fov since the
// hip-fire crosshair is only ever shown while not aiming, i.e. at
// BASE_FOV; this technically only accounts for vertical FOV, treating
// the gap as if uniform in both axes, which is a small approximation
// on a non-square/widescreen viewport but far closer to reality than a
// flat constant.)
function spreadDegreesToPixels(spreadDegrees) {
  const halfFovRad = THREE.MathUtils.degToRad(BASE_FOV / 2);
  const spreadRad = THREE.MathUtils.degToRad(spreadDegrees);
  return (Math.tan(spreadRad) / Math.tan(halfFovRad)) * (window.innerHeight / 2);
}

// Perturbs a direction within a random cone (uniform over the cone's
// area, so hits cluster naturally toward center rather than piling up
// at the edge) -- the small-angle tangent-plane approximation used here
// is the standard, cheap way games do weapon spread/bloom.
function applySpread(dir, spreadDegrees) {
  if (spreadDegrees <= 0) return dir;
  const maxRad = THREE.MathUtils.degToRad(spreadDegrees);
  const r = maxRad * Math.sqrt(Math.random());
  const phi = Math.random() * Math.PI * 2;
  const upHint = Math.abs(dir.y) < 0.99 ? new THREE.Vector3(0, 1, 0) : new THREE.Vector3(1, 0, 0);
  const right = new THREE.Vector3().crossVectors(dir, upHint).normalize();
  const up = new THREE.Vector3().crossVectors(right, dir).normalize();
  return new THREE.Vector3()
    .copy(dir)
    .addScaledVector(right, r * Math.cos(phi))
    .addScaledVector(up, r * Math.sin(phi))
    .normalize();
}

// Shared by shoot() and meleeAttack(): applies damage to whichever local
// practice-bot target the ray actually reached first (nothing behind an
// obstacle counts), and respawns/glows it the same way either weapon type.
function resolveTargetHit(hits, obstacleDist) {
  if (hits.length === 0 || hits[0].distance >= obstacleDist) return;
  const hitMesh = hits[0].object;
  const target = targets.find((t) => t.mesh === hitMesh);
  target.hp -= WEAPONS[currentWeapon].damage;
  triggerHitFeedback();

  if (target.hp <= 0) {
    // Size is baked into the geometry (and the hazard texture's tiling
    // scales with it), so a fresh size means a fresh mesh rather than
    // mutating the old one in place.
    scene.remove(target.mesh);
    target.mesh.geometry.dispose();
    if (target.mesh.material.map) target.mesh.material.map.dispose();
    target.mesh.material.dispose();

    const [x, y, z] = randomArenaPosition(1 + Math.random() * 3.5);
    const size = randomTargetSize();
    target.mesh = addBox([x, y, z], [size, size, size], 0xffffff, hazardTexture, 1.2);
    target.size = size;
    target.home.set(x, y, z);
    target.motion = randomTargetMotion();
    target.age = 0;
    target.maxHp = randomTargetHp();
    target.hp = target.maxHp;
    target.flashUntil = 0;
    score++;
    renderTrainingScore(score);
  } else {
    // Briefly glow so a hit that didn't destroy the block still reads
    // as "damaged" rather than looking like nothing happened.
    target.mesh.material.emissive.setHex(0x554400);
    target.flashUntil = performance.now() + 120;
  }
}

function shoot() {
  if (WEAPONS[currentWeapon].melee) {
    meleeAttack();
    return;
  }
  if (reloading) return;
  if (ammo[currentWeapon] <= 0) {
    requestReload(); // out of ammo -- reload automatically
    return;
  }

  const now = performance.now();
  if (now - lastShotTime < WEAPONS[currentWeapon].cooldown) return;
  lastShotTime = now;

  ammo[currentWeapon]--;
  updateAmmoHud();

  triggerGunFire();
  playGunshot(currentWeapon);

  const aimDir = getForward();
  const weaponSpec = WEAPONS[currentWeapon];
  const spreadDegrees = weaponSpec.hipSpread + (weaponSpec.adsSpread - weaponSpec.hipSpread) * adsBlend;
  const forward = applySpread(aimDir, spreadDegrees);
  raycaster.set(camera.position, forward);

  // Walls, the building, ramps, trees, and rocks all block gunfire --
  // find the nearest one along this shot's path first, then only count
  // a target hit if it's actually closer than whatever's in the way.
  const obstacleHits = raycaster.intersectObjects(bulletBlockers, true);
  const obstacleDist = obstacleHits.length > 0 ? obstacleHits[0].distance : Infinity;

  resolveTargetHit(raycaster.intersectObjects(targets.map((t) => t.mesh)), obstacleDist);

  if (ammo[currentWeapon] <= 0) requestReload();

  if (isConnected() && !isDead) {
    sendMessage({
      type: "shoot",
      origin: [camera.position.x, camera.position.y, camera.position.z],
      dir: [forward.x, forward.y, forward.z]
    });
  }
}

// No ammo, no spread, no ADS -- just a short-range, no-bloom swing. Reuses
// the exact same "shoot" network message as the guns (the server tells
// melee and gunfire apart by the shooter's currently equipped weapon id,
// the same way it already tells the 4 guns apart) and independently caps
// the range server-side so a modified client can't turn this into a
// long-range one-shot.
function meleeAttack() {
  const weaponSpec = WEAPONS[currentWeapon];
  const now = performance.now();
  if (now - lastShotTime < weaponSpec.cooldown) return;
  lastShotTime = now;

  triggerGunFire(); // reuses the guns' forward-punch recoil kick as a stand-in swing motion
  playMeleeSwing();

  const aimDir = getForward();
  raycaster.set(camera.position, aimDir);
  raycaster.far = weaponSpec.meleeRange;

  const obstacleHits = raycaster.intersectObjects(bulletBlockers, true);
  const obstacleDist = obstacleHits.length > 0 ? obstacleHits[0].distance : Infinity;

  resolveTargetHit(raycaster.intersectObjects(targets.map((t) => t.mesh)), obstacleDist);

  raycaster.far = Infinity; // restore -- shared raycaster, guns need unlimited range

  if (isConnected() && !isDead) {
    sendMessage({
      type: "shoot",
      origin: [camera.position.x, camera.position.y, camera.position.z],
      dir: [aimDir.x, aimDir.y, aimDir.z]
    });
  }
}

// ================================================================
// Hit feedback, death/respawn, match banner, and HUD state -> js/hud.js
// (rendering only; this file owns the state those functions read)
// ================================================================
function triggerHitFeedback() {
  showHitMarker();
}

function triggerLocalDeath() {
  isDead = true;
  setDeathOverlay(true);
}

function clearLocalDeath(pos) {
  isDead = false;
  setDeathOverlay(false);
  if (pos) teleportLocalPlayer(pos);
  verticalVelocity = 0;
  grounded = true;
}

// Server-authoritative repositioning: the server decides spawn points and
// snaps back any move it can't accept (speed, bounds, walking through
// solids), so the local player just goes where it says.
function teleportLocalPlayer(pos) {
  camera.position.set(pos[0], pos[1], pos[2]);
  lastSafeX = pos[0];
  lastSafeY = pos[1];
  lastSafeZ = pos[2];
  verticalVelocity = 0;
  grounded = true;
}

function showMatchBanner(msg) {
  const youWon = msg.winnerId === myId;
  showBanner(youWon ? `YOU WIN! (${msg.score} kills)` : `${msg.winnerName} WINS! (${msg.score} kills)`);
}

function updateHud() {
  const rows = [{ name: "You", kills: myKills }];
  for (const rp of remotePlayers.values()) rows.push({ name: rp.name, kills: rp.kills });
  rows.sort((a, b) => b.kills - a.kills);
  renderScoreboard({ myKills, killLimit, playerCount: remotePlayers.size + 1, rows });
}

function updateHealthHud() {
  renderHealth(myHp, MAX_HP);
}

function updateAmmoHud() {
  renderAmmo(WEAPONS[currentWeapon], ammo[currentWeapon], reloading);
}

function updateWeaponHud() {
  renderWeapon(WEAPONS[currentWeapon]);
}
updateWeaponHud();
updateHealthHud();
updateAmmoHud();

// ================================================================
// Game loop
// ================================================================
let lastTime = performance.now();

function tick(now) {
  const dt = Math.min((now - lastTime) / 1000, 0.1);
  lastTime = now;

  // Local fallback in case the server's "ammo" completion message is lost
  // or delayed -- keeps the reload timer feeling responsive regardless.
  if (reloading && now >= reloadEndTime) {
    reloading = false;
    ammo[currentWeapon] = WEAPONS[currentWeapon].magSize;
    updateAmmoHud();
  }

  for (const t of targets) {
    if (t.flashUntil && now >= t.flashUntil) {
      t.mesh.material.emissive.setHex(0x000000);
      t.flashUntil = 0;
    }

    t.age += dt;
    const phased = t.age + t.motion.phase;
    const dx = Math.sin(phased * t.motion.horizSpeed) * t.motion.horizRadius;
    const dz = Math.cos(phased * t.motion.horizSpeed * 0.8) * t.motion.horizRadius;
    const dy = Math.sin(phased * t.motion.vertSpeed) * t.motion.vertRadius;
    t.mesh.position.set(t.home.x + dx, Math.max(t.size / 2 + 0.1, t.home.y + dy), t.home.z + dz);
  }

  // Automatic weapons (SMG) keep firing every frame the button is held,
  // gated by shoot()'s own cooldown check -- semi-auto weapons ignore
  // this and only fire once per actual click.
  if (isLocked() && !isDead && mouseHeld && WEAPONS[currentWeapon].automatic) {
    shoot();
  }

  let moving = false;
  let sprinting = false;
  if (isLocked() && !isDead) {
    const currentFeetY = camera.position.y - EYE_HEIGHT;
    if (collidesAt(camera.position.x, currentFeetY, camera.position.z)) {
      camera.position.set(lastSafeX, lastSafeY, lastSafeZ);
      verticalVelocity = 0;
      grounded = false; // let ground detection sort out standing vs falling next frame
    } else {
      lastSafeX = camera.position.x;
      lastSafeY = camera.position.y;
      lastSafeZ = camera.position.z;
    }

    const forward = getForward();
    const flatForward = new THREE.Vector3(forward.x, 0, forward.z);
    if (flatForward.lengthSq() > 0.0001) flatForward.normalize();
    const right = new THREE.Vector3().crossVectors(flatForward, new THREE.Vector3(0, 1, 0));

    // Aiming and sprinting are mutually exclusive -- aiming always wins
    // (you can't sprint while looking down sights, standard FPS
    // convention), matching how requestReload()/selectWeapon() already
    // cancel aiming rather than letting states stack unpredictably.
    sprinting = !aiming && (keys["ShiftLeft"] || keys["ShiftRight"]);
    let speedMult = 1;
    if (aiming && WEAPONS[currentWeapon].adsMoveMult != null) {
      speedMult = WEAPONS[currentWeapon].adsMoveMult;
    } else if (sprinting) {
      speedMult = SPRINT_MULT;
    }
    const velocity = MOVE_SPEED * speedMult * dt;
    moving = keys["KeyW"] || keys["KeyS"] || keys["KeyD"] || keys["KeyA"];

    let moveX = 0, moveZ = 0;
    if (keys["KeyW"]) { moveX += flatForward.x * velocity; moveZ += flatForward.z * velocity; }
    if (keys["KeyS"]) { moveX -= flatForward.x * velocity; moveZ -= flatForward.z * velocity; }
    if (keys["KeyD"]) { moveX += right.x * velocity; moveZ += right.z * velocity; }
    if (keys["KeyA"]) { moveX -= right.x * velocity; moveZ -= right.z * velocity; }

    // Resolve X and Z separately (not as one combined step) so walking
    // diagonally into a wall slides you along it instead of just
    // stopping dead -- whichever axis isn't blocked still moves.
    const feetYForXZ = camera.position.y - EYE_HEIGHT;
    if (moveX !== 0 && !collidesAt(camera.position.x + moveX, feetYForXZ, camera.position.z)) {
      camera.position.x += moveX;
    }
    if (moveZ !== 0 && !collidesAt(camera.position.x, feetYForXZ, camera.position.z + moveZ)) {
      camera.position.z += moveZ;
    }

    if (keys["Space"] && grounded) {
      verticalVelocity = JUMP_SPEED;
      grounded = false;
    }
    verticalVelocity -= GRAVITY * dt;

    const feetY = camera.position.y - EYE_HEIGHT;
    const proposedFeetY = feetY + verticalVelocity * dt;

    if (verticalVelocity > 0) {
      const headY = feetY + PLAYER_HEIGHT;
      const proposedHeadY = proposedFeetY + PLAYER_HEIGHT;
      let ceilingY = findCeilingY(camera.position.x, camera.position.z, headY, proposedHeadY);
      // Ramps aren't in solidBoxes (see collidesWithRamps' comment), so
      // findCeilingY alone can't see them -- without this, jumping into
      // the underside of a ramp (e.g. under the spiral) went completely
      // unchecked and could clip the player up into its solid interior.
      if (ceilingY === null && collidesWithRamps(camera.position.x, proposedFeetY, proposedHeadY, camera.position.z)) {
        ceilingY = headY; // block the ascent right where it is this frame
      }
      if (ceilingY !== null) {
        camera.position.y = ceilingY - PLAYER_HEIGHT + EYE_HEIGHT;
        verticalVelocity = 0;
      } else {
        camera.position.y = proposedFeetY + EYE_HEIGHT;
      }
      grounded = false;
    } else {
      const fallDistance = Math.max(0, feetY - proposedFeetY);
      const surfaceY = findGroundY(camera.position.x, camera.position.z, feetY, fallDistance);
      if (surfaceY !== null) {
        camera.position.y = surfaceY + EYE_HEIGHT;
        verticalVelocity = 0;
        grounded = true;
      } else {
        camera.position.y = proposedFeetY + EYE_HEIGHT;
        grounded = false;
      }
    }

    camera.lookAt(
      camera.position.x + forward.x,
      camera.position.y + forward.y,
      camera.position.z + forward.z
    );
  }

  // Smoothly zoom the camera toward the equipped weapon's ADS FOV while
  // aiming (and back to normal when not) -- each weapon transitions at
  // its own speed via adsSpeed, so a pistol snaps up quick while the
  // sniper's scope raise feels a bit more deliberate.
  const activeWeapon = WEAPONS[currentWeapon];
  const targetFov = (aiming && isLocked() && !isDead) ? activeWeapon.adsFov : BASE_FOV;
  camera.fov += (targetFov - camera.fov) * Math.min(1, dt * (activeWeapon.adsSpeed || 10));
  camera.updateProjectionMatrix();

  const targetAdsBlend = (aiming && isLocked() && !isDead) ? 1 : 0;
  adsBlend += (targetAdsBlend - adsBlend) * Math.min(1, dt * (activeWeapon.adsSpeed || 10));

  // The sniper's circular scope overlay only kicks in once mostly zoomed
  // in -- fully hides the regular crosshair while any weapon is aimed,
  // matching how ADS/iron-sights normally replace the floating reticle.
  // !! matters here: activeWeapon.scope is `undefined` (not `false`) for
  // every weapon except the sniper, so without coercing to a real
  // boolean, `undefined && ...` short-circuits to `undefined` -- and
  // passing `undefined` as classList.toggle's second argument is
  // ambiguous enough (across engines) to behave as "no force given, just
  // flip the current class" instead of "force it off". That produced
  // exactly this bug: every non-sniper weapon flickering the class on
  // and off every frame, while the sniper (a real `true`/`false`) never
  // hit the ambiguity at all.
  const scopedIn = !!(activeWeapon.scope && adsBlend > 0.85);
  setScopeVisible(scopedIn);
  // Pistol/rifle/SMG get a tighter precision reticle once mostly raised
  // into their ADS pose; the sniper uses its scope overlay instead, so
  // it's deliberately excluded here.
  const showAdsCrosshair = !!(aiming && !activeWeapon.scope && adsBlend > 0.4);
  setAdsCrosshairVisible(showAdsCrosshair);
  setCrosshairHidden(aiming);
  // Visualizes the current weapon's hip-fire bloom -- wider gap = less
  // accurate. Only actually visible while not aiming (the crosshair
  // hides instantly otherwise), so this doesn't need to track adsBlend.
  // spreadDegreesToPixels() is the SAME angle->pixel conversion used by
  // shot spread itself, so this is an honest picture: a target that
  // visually fills this gap genuinely has good hit odds, not just a
  // vaguely-proportional decoration.
  setCrosshairGap(spreadDegreesToPixels(activeWeapon.hipSpread || 0));

  updateGunModel(currentWeapon, dt, moving, adsBlend, sprinting);
  sendStateIfDue(now);

  for (const rp of remotePlayers.values()) {
    rp.group.position.lerp(rp.targetPos, 0.25);
    rp.group.rotation.y = lerpAngle(rp.group.rotation.y, rp.targetYaw, 0.25);
  }

  renderWeaponIcons(dt, isScreenVisible("loadout"));
  renderer.render(scene, camera);
  requestAnimationFrame(tick);
}

requestAnimationFrame(tick);
finishLoading();
