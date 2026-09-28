import { camera } from "./core.js";
import { WEAPONS } from "./weapons.js";
import { makeCanvasTexture, tiledClone, metalTexture } from "./textures.js";

// The rifle is equipped until the loadout screen says otherwise (matches
// the initial currentWeapon in game.js).
const DEFAULT_GUN = 1;

function createGunModel(type, customMats) {
  const group = new THREE.Group();
  const metalMat = (customMats && customMats.metalMat) || new THREE.MeshLambertMaterial({ color: 0x6b6f78, map: tiledClone(metalTexture, 1, 1) });
  const accentMat = (customMats && customMats.accentMat) || new THREE.MeshLambertMaterial({ color: 0x17181a, map: tiledClone(metalTexture, 1, 1) });
  const flashMat = new THREE.MeshBasicMaterial({ color: 0xfff2b0 });

  function part(mat, x, y, z, sx, sy, sz) {
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(sx, sy, sz), mat);
    mesh.position.set(x, y, z);
    group.add(mesh);
    return mesh;
  }

  let flashPos;
  if (type === 0) {
    // Pistol -- compact, no stock
    part(metalMat, 0, -0.02, 0.05, 0.1, 0.1, 0.3);
    part(metalMat, 0, 0.01, -0.18, 0.04, 0.04, 0.18);
    part(accentMat, 0, -0.16, 0.14, 0.08, 0.2, 0.09);
    part(accentMat, 0, -0.24, 0.1, 0.06, 0.12, 0.08);
    flashPos = [0, 0.01, -0.3];
  } else if (type === 2) {
    // Sniper -- long barrel, scope, extended stock
    part(metalMat, 0, -0.02, 0.15, 0.1, 0.1, 0.6);
    part(metalMat, 0, 0.01, -0.5, 0.04, 0.04, 0.55);
    part(accentMat, 0, 0.09, 0.05, 0.06, 0.06, 0.3);
    part(accentMat, 0, -0.16, 0.28, 0.08, 0.18, 0.1);
    part(metalMat, 0, 0.02, 0.55, 0.08, 0.09, 0.3);
    flashPos = [0, 0.01, -0.8];
  } else if (type === 3) {
    // SMG -- compact body, short barrel, chunky high-capacity magazine,
    // small folding-style stock (shorter overall than the rifle)
    part(metalMat, 0, -0.02, 0.08, 0.11, 0.11, 0.42);
    part(metalMat, 0, 0.02, -0.24, 0.045, 0.045, 0.24);
    part(accentMat, 0, -0.14, 0.18, 0.08, 0.18, 0.1);
    part(accentMat, 0, -0.12, 0.09, 0.07, 0.24, 0.09);
    part(metalMat, 0, 0.02, 0.32, 0.07, 0.08, 0.16);
    flashPos = [0, 0.02, -0.36];
  } else if (type === 4) {
    // Knife -- flat blade, small crossguard, grip handle. No muzzle flash
    // (the flash mesh below still exists but is simply never triggered).
    part(metalMat, 0, 0, -0.15, 0.02, 0.03, 0.32);
    part(accentMat, 0, 0, 0.03, 0.07, 0.025, 0.02);
    part(accentMat, 0, -0.01, 0.13, 0.035, 0.035, 0.17);
    flashPos = [0, 0, 0.3];
  } else {
    // Rifle (default) -- body/barrel/grip/magazine/stock
    part(metalMat, 0, -0.02, 0.1, 0.12, 0.12, 0.55);
    part(metalMat, 0, 0.02, -0.35, 0.05, 0.05, 0.35);
    part(accentMat, 0, -0.14, 0.2, 0.08, 0.18, 0.1);
    part(accentMat, 0, -0.1, 0.02, 0.06, 0.14, 0.22);
    part(metalMat, 0, 0.02, 0.42, 0.09, 0.1, 0.22);
    flashPos = [0, 0.02, -0.56];
  }

  const flash = part(flashMat, flashPos[0], flashPos[1], flashPos[2], 0.16, 0.16, 0.16);
  flash.visible = false;
  group.userData.flash = flash;
  return group;
}

const GUN_BASE_POS = { x: 0.32, y: -0.32, z: -0.6 };
const ADS_GUN_POS = { x: 0.02, y: -0.16, z: -0.45 }; // raised toward center when aiming
const GUN_RECOIL_DURATION = 0.18;
const GUN_MUZZLE_FLASH_DURATION = 0.05;

// Weapon photo textures: loaded from real reference photos (web/assets/
// *_reference.png) instead of the procedural metal used by the other
// weapons. TextureLoader is async, but the gun models below are built
// synchronously, so each canvas starts out flat-filled with a fallback
// tint and gets repainted in place (two cropped regions -- one for the
// metal parts, one for the accent parts) once its image arrives.
function makeWeaponPhotoMats(imagePath, metalCrop, accentCrop) {
  const metalTex = makeCanvasTexture(256, (ctx, size) => {
    ctx.fillStyle = "#6b6f78";
    ctx.fillRect(0, 0, size, size);
  });
  const accentTex = makeCanvasTexture(256, (ctx, size) => {
    ctx.fillStyle = "#17181a";
    ctx.fillRect(0, 0, size, size);
  });
  new THREE.TextureLoader().load(imagePath, (loaded) => {
    const src = loaded.image;
    metalTex.image.getContext("2d").drawImage(src, ...metalCrop, 0, 0, 256, 256);
    metalTex.needsUpdate = true;
    accentTex.image.getContext("2d").drawImage(src, ...accentCrop, 0, 0, 256, 256);
    accentTex.needsUpdate = true;
  });
  return {
    metalMat: new THREE.MeshLambertMaterial({ color: 0xffffff, map: metalTex }),
    accentMat: new THREE.MeshLambertMaterial({ color: 0xffffff, map: accentTex })
  };
}

const pistolMats = makeWeaponPhotoMats("assets/pistol_reference.png", [550, 90, 300, 300], [740, 350, 180, 180]);
const sniperMats = makeWeaponPhotoMats("assets/sniper_reference.png", [640, 75, 220, 220], [100, 400, 220, 220]);
// Rifle and SMG have no reference photos of their own -- skinned from
// different crops of the same two sheets (gunmetal + in-hand glove
// leather from the pistol photo for the SMG, gunmetal + olive-drab
// chassis from the sniper sheet for the rifle) so all four weapons
// share one consistent, photo-real look.
const smgMats = makeWeaponPhotoMats("assets/pistol_reference.png", [430, 180, 200, 200], [780, 660, 260, 260]);
const rifleMats = makeWeaponPhotoMats("assets/sniper_reference.png", [550, 450, 220, 220], [300, 250, 220, 220]);

// Knife has no reference photo -- createGunModel() falls back to its
// original plain procedural metal/accent materials when no customMats
// are given, same as every weapon looked before the photo textures.
const gunModels = [createGunModel(0, pistolMats), createGunModel(1, rifleMats), createGunModel(2, sniperMats), createGunModel(3, smgMats), createGunModel(4)];
gunModels.forEach((g, i) => {
  g.position.set(GUN_BASE_POS.x, GUN_BASE_POS.y, GUN_BASE_POS.z);
  g.rotation.y = THREE.MathUtils.degToRad(8);
  g.visible = i === DEFAULT_GUN;
  camera.add(g);
});

// ================================================================
// Loadout-screen weapon icons: a separate offscreen renderer (its own
// canvas is never attached to the page) shared by all four preview
// cards. Each icon gets its own gun-model instance built from the same
// materials as the real equipped guns above (createGunModel() just
// makes new meshes; the materials/textures -- including the loaded
// photo crops -- are shared objects), so a preview always matches what
// you'll actually be holding, texture pop-in included. Rendered once
// per frame, only while the loadout screen is actually visible, via
// renderWeaponIcons() below (called from the main tick() loop).
// ================================================================
const iconPreviewCanvas = document.createElement("canvas");
iconPreviewCanvas.width = 160;
iconPreviewCanvas.height = 120;
const iconPreviewRenderer = new THREE.WebGLRenderer({ canvas: iconPreviewCanvas, alpha: true, antialias: true });
iconPreviewRenderer.setSize(160, 120, false);

const iconPreviewScene = new THREE.Scene();
iconPreviewScene.add(new THREE.AmbientLight(0xffffff, 0.7));
const iconPreviewLight = new THREE.DirectionalLight(0xffffff, 0.9);
iconPreviewLight.position.set(2, 3, 2);
iconPreviewScene.add(iconPreviewLight);

const iconPreviewCamera = new THREE.PerspectiveCamera(32, 160 / 120, 0.05, 10);
iconPreviewCamera.position.set(0.55, 0.22, 0.75);
iconPreviewCamera.lookAt(0, -0.02, 0);

const iconGunModels = [
  createGunModel(0, pistolMats),
  createGunModel(1, rifleMats),
  createGunModel(2, sniperMats),
  createGunModel(3, smgMats)
];
iconGunModels.forEach((g) => { g.rotation.y = THREE.MathUtils.degToRad(20); });

const iconCanvases = [0, 1, 2, 3].map((i) => document.querySelector(`[data-weapon-icon="${i}"]`));
const iconCtx = iconCanvases.map((c) => c.getContext("2d"));

export function renderWeaponIcons(dt, visible) {
  if (!visible) return;
  for (let i = 0; i < 4; i++) {
    iconGunModels[i].rotation.y += dt * 0.4;
    iconPreviewScene.add(iconGunModels[i]);
    iconPreviewRenderer.render(iconPreviewScene, iconPreviewCamera);
    iconPreviewScene.remove(iconGunModels[i]);
    iconCtx[i].clearRect(0, 0, 160, 120);
    iconCtx[i].drawImage(iconPreviewCanvas, 0, 0);
  }
}

let gunIdleTime = 0;
let gunWalkTime = 0;
let gunMovingFactor = 0;
let gunRecoilTimer = 0;

export function triggerGunFire() {
  gunRecoilTimer = GUN_RECOIL_DURATION;
}

let gunSprintFactor = 0;

export function updateGunModel(weaponIdx, dt, moving, adsBlendAmount, sprinting) {
  gunIdleTime += dt;
  gunMovingFactor += ((moving ? 1 : 0) - gunMovingFactor) * Math.min(1, dt * 8);
  gunSprintFactor += ((sprinting && moving ? 1 : 0) - gunSprintFactor) * Math.min(1, dt * 8);
  // Sprinting quickens the cadence on top of the normal walk pace.
  if (moving) gunWalkTime += dt * (9 + gunSprintFactor * 5);
  if (gunRecoilTimer > 0) gunRecoilTimer = Math.max(0, gunRecoilTimer - dt);

  // Idle sway and walk bob fade out while aiming -- steadying your aim
  // is the whole point of ADS.
  const steadiness = 1 - adsBlendAmount;
  const idleSwayX = Math.sin(gunIdleTime * 0.6) * 0.012 * steadiness;
  const idleSwayY = Math.sin(gunIdleTime * 1.1) * 0.008 * steadiness;
  // Bob amplitude scales up while sprinting (side-to-side and up-down),
  // on top of the normal walk bob -- reads as an exaggerated, natural
  // running motion rather than a separate effect.
  const bobAmpX = 0.02 + gunSprintFactor * 0.028;
  const bobAmpY = 0.018 + gunSprintFactor * 0.022;
  const walkBobX = Math.sin(gunWalkTime) * bobAmpX * gunMovingFactor * steadiness;
  const walkBobY = Math.abs(Math.sin(gunWalkTime)) * bobAmpY * gunMovingFactor * steadiness;
  const sprintTiltZ = Math.sin(gunWalkTime) * THREE.MathUtils.degToRad(3) * gunSprintFactor * gunMovingFactor * steadiness;
  const recoilT = gunRecoilTimer / GUN_RECOIL_DURATION;

  const basePos = {
    x: GUN_BASE_POS.x + (ADS_GUN_POS.x - GUN_BASE_POS.x) * adsBlendAmount,
    y: GUN_BASE_POS.y + (ADS_GUN_POS.y - GUN_BASE_POS.y) * adsBlendAmount,
    z: GUN_BASE_POS.z + (ADS_GUN_POS.z - GUN_BASE_POS.z) * adsBlendAmount
  };

  const activeGun = gunModels[weaponIdx];
  activeGun.position.set(
    basePos.x + idleSwayX + walkBobX,
    basePos.y + idleSwayY + walkBobY,
    basePos.z + recoilT * 0.12
  );
  activeGun.rotation.y = THREE.MathUtils.degToRad(8) * steadiness;
  activeGun.rotation.x = -THREE.MathUtils.degToRad(recoilT * 10);
  activeGun.rotation.z = sprintTiltZ;
  activeGun.userData.flash.visible = gunRecoilTimer > GUN_RECOIL_DURATION - GUN_MUZZLE_FLASH_DURATION;

  // A real scope shows the view through the tube, not the gun's body --
  // hide the model once mostly zoomed into the sniper's scope.
  activeGun.visible = !(WEAPONS[weaponIdx].scope && adsBlendAmount > 0.5);
}

// Shows only the given weapon's first-person model.
export function showGun(idx) {
  gunModels.forEach((g, i) => { g.visible = i === idx; });
}
