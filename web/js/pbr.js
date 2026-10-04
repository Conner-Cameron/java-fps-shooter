import * as THREE from "three";

// Photographic PBR texture sets for the map. Surfaces come from Poly Haven (CC0; see
// web/assets/textures/LICENSE.txt) and live under web/assets/textures/<set>/: color (sRGB),
// normal (OpenGL) and arm (Poly Haven's packed map: R = ambient occlusion, G = roughness,
// B = metalness). Leaves come from ambientCG (CC0): a color map plus an opacity cutout.

const SETS = {
  concrete: "assets/textures/concrete_panels/",    // walls, center cover, ramps, floors
  brushed: "assets/textures/brushed_concrete_03/", // the climbable towers
  plaster: "assets/textures/plastered_wall_02/",   // the multi-floor building
  grass: "assets/textures/sparse_grass/",          // the ground
  rock: "assets/textures/rock_face_03/",           // rocks and the distant mountains
  bark: "assets/textures/knotted_pine_bark/"       // tree trunks
};
const LEAF_DIR = "assets/textures/leaf/";

const loader = new THREE.TextureLoader();
const loaded = {};

function repeating(t) {
  t.wrapS = THREE.RepeatWrapping;
  t.wrapT = THREE.RepeatWrapping;
  return t;
}

function loadSet(name) {
  if (!loaded[name]) {
    const dir = SETS[name];
    const map = repeating(loader.load(dir + "color.jpg"));
    map.colorSpace = THREE.SRGBColorSpace;
    loaded[name] = {
      map,
      normal: repeating(loader.load(dir + "normal.jpg")),
      arm: repeating(loader.load(dir + "arm.jpg"))
    };
  }
  return loaded[name];
}

// Each material gets its own clones so every surface can repeat at its own rate. Clones share the
// loaded image, so they pick it up when it arrives.
function tiled(t, repeatX, repeatY) {
  const c = t.clone();
  c.repeat.set(repeatX, repeatY);
  return c;
}

/**
 * A standard PBR material for surface set `name`, repeating `repeatX` x `repeatY` times across each
 * face's UVs. The geometry must have UVs and a copy in `uv1` (see boxUVs / copyUv1), which the
 * ambient-occlusion map reads.
 */
export function pbrMaterial(name, color, repeatX, repeatY) {
  const s = loadSet(name);
  const ao = tiled(s.arm, repeatX, repeatY);
  ao.channel = 1;
  return new THREE.MeshStandardMaterial({
    color,
    map: tiled(s.map, repeatX, repeatY),
    normalMap: tiled(s.normal, repeatX, repeatY),
    roughnessMap: tiled(s.arm, repeatX, repeatY),
    aoMap: ao,
    roughness: 1,
    metalness: 0
  });
}

/**
 * Rescales a BoxGeometry's UVs so one texture repeat covers `tile` world units on every face,
 * instead of each face being stretched across its whole area (a 0.3-thick wall would otherwise
 * squash its texture). Also fills `uv1` for the AO map.
 */
export function boxUVs(geo, sx, sy, sz, tile) {
  const uv = geo.attributes.uv;
  // BoxGeometry faces, four vertices each, in order: +x, -x, +y, -y, +z, -z.
  const faceSizes = [[sz, sy], [sz, sy], [sx, sz], [sx, sz], [sx, sy], [sx, sy]];
  for (let i = 0; i < uv.count; i++) {
    const [w, h] = faceSizes[Math.floor(i / 4)];
    uv.setXY(i, (uv.getX(i) * w) / tile, (uv.getY(i) * h) / tile);
  }
  uv.needsUpdate = true;
  geo.setAttribute("uv1", uv.clone());
  return geo;
}

/** For non-box geometry (cylinders, cones): the AO map needs `uv1`, which copies the normal UVs. */
export function copyUv1(geo) {
  geo.setAttribute("uv1", geo.attributes.uv.clone());
  return geo;
}

// ---- leaves: cutout cards (color + opacity), used by the tree canopies
let leafMat = null;

/** One shared material for every leaf card. Alpha-tested so cutouts are sharp and sort correctly. */
export function leafMaterial() {
  if (!leafMat) {
    const color = repeating(loader.load(LEAF_DIR + "color.jpg"));
    color.colorSpace = THREE.SRGBColorSpace;
    const opacity = repeating(loader.load(LEAF_DIR + "opacity.jpg"));
    leafMat = new THREE.MeshStandardMaterial({
      map: color,
      alphaMap: opacity,
      alphaTest: 0.5,
      side: THREE.DoubleSide,
      roughness: 0.85,
      metalness: 0
    });
  }
  return leafMat;
}
