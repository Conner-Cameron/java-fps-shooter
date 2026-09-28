// glTF weapon-model pipeline.
//
// web/assets/manifest.json lists which weapons have a real model:
//
//   { "weapons": { "0": { "model": "models/pistol.glb", "scale": 2.2,
//                         "position": [0, 0, 0], "rotationY": 0,
//                         "muzzle": [0, 0.02, -0.3] } } }
//
// Keys are weapon indices (see weapons.js). A weapon with no entry -- or
// whose file fails to load -- silently keeps its procedural box model, so
// models can be dropped in one at a time and a bad file never breaks the game.
//
// Model conventions (export from Blender etc. as .glb with PBR materials):
// +Y up, the muzzle pointing toward -Z, origin near the receiver/grip.
// `scale` converts the file's units to first-person view-model size,
// `position`/`rotationY` nudge it into place, `muzzle` is where the muzzle
// flash appears (in the same units as the placed model).
import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";

const MANIFEST_URL = "assets/manifest.json";

const loadingEl = document.getElementById("loading");

export function setLoadingText(text) {
  if (loadingEl) loadingEl.textContent = text;
}

export function finishLoading() {
  if (loadingEl) loadingEl.remove();
}

/**
 * Fetches and parses every model in the manifest in parallel.
 * Resolves to Map<weaponIdx, { root, spec }> containing only the models that loaded.
 */
export async function loadWeaponAssets() {
  let manifest;
  try {
    manifest = await (await fetch(MANIFEST_URL)).json();
  } catch (e) {
    console.warn("No asset manifest; using procedural weapon models", e);
    return new Map();
  }

  const entries = Object.entries(manifest.weapons || {});
  const loader = new GLTFLoader();
  const loaded = new Map();
  let done = 0;
  setLoadingText(`Loading models (0/${entries.length})…`);

  await Promise.all(entries.map(async ([idx, spec]) => {
    try {
      const gltf = await loader.loadAsync("assets/" + spec.model);
      loaded.set(Number(idx), { root: gltf.scene, spec });
    } catch (e) {
      console.warn(`Weapon model ${spec.model} failed to load; using the procedural model`, e);
    }
    setLoadingText(`Loading models (${++done}/${entries.length})…`);
  }));
  return loaded;
}

/**
 * Image-based lighting for PBR materials. Metallic surfaces look black
 * without something to reflect, and a WebGL texture can't be shared between
 * renderers, so each renderer (the main view, the loadout-icon preview)
 * builds its own environment.
 */
export function makeEnvironment(renderer) {
  const pmrem = new THREE.PMREMGenerator(renderer);
  const env = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  pmrem.dispose();
  return env;
}

/**
 * Builds a fresh first-person weapon from a loaded asset: geometry and
 * textures are shared with the template, materials are cloned so each
 * renderer's environment map can be set independently.
 */
export function instantiateWeapon(asset, env, envIntensity = 0.7) {
  const { spec } = asset;
  const group = new THREE.Group();

  const model = asset.root.clone(true);
  model.traverse((o) => {
    if (!o.isMesh) return;
    o.material = o.material.clone();
    if (o.material.isMeshStandardMaterial) {
      o.material.envMap = env;
      o.material.envMapIntensity = envIntensity;
    }
  });
  model.scale.setScalar(spec.scale ?? 1);
  if (spec.position) model.position.set(...spec.position);
  if (spec.rotationY) model.rotation.y = spec.rotationY;
  group.add(model);

  const muzzle = spec.muzzle || [0, 0, -0.3];
  const flash = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.16, 0.16), new THREE.MeshBasicMaterial({ color: 0xfff2b0 }));
  flash.position.set(...muzzle);
  flash.visible = false;
  group.add(flash);
  group.userData.flash = flash;
  return group;
}
