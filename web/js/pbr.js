import * as THREE from "three";

// Photographic PBR texture sets for the map (color + normal + roughness). They come from Poly Haven
// (CC0, see web/assets/textures/LICENSE.txt) and live under web/assets/textures/<set>/. Each set is
// fetched once; every surface gets its own clones so each can repeat at its own rate.

const SETS = {
  concrete: "assets/textures/concrete_panels/",   // walls, center cover, ramps
  brushed: "assets/textures/brushed_concrete_03/", // the climbable towers
  plaster: "assets/textures/plastered_wall_02/",   // the multi-floor building
  grass: "assets/textures/sparse_grass/",          // the ground
  rock: "assets/textures/rock_face_03/",           // rocks and the distant mountains
  bark: "assets/textures/knotted_pine_bark/"       // tree trunks
};

const loader = new THREE.TextureLoader();
const loaded = {};

function loadSet(name) {
  if (!loaded[name]) {
    const dir = SETS[name];
    const wrap = (t) => {
      t.wrapS = THREE.RepeatWrapping;
      t.wrapT = THREE.RepeatWrapping;
      return t;
    };
    const map = wrap(loader.load(dir + "color.jpg"));
    map.colorSpace = THREE.SRGBColorSpace;
    loaded[name] = { map, normal: wrap(loader.load(dir + "normal.jpg")), rough: wrap(loader.load(dir + "rough.jpg")) };
  }
  return loaded[name];
}

/** A standard PBR material for surface set `name`, repeating `repeatX` x `repeatY` times across the face. */
export function pbrMaterial(name, color, repeatX, repeatY) {
  const s = loadSet(name);
  const tile = (t) => {
    const c = t.clone();
    c.repeat.set(repeatX, repeatY); // clones share the loaded image, so they pick it up when it arrives
    return c;
  };
  return new THREE.MeshStandardMaterial({
    color,
    map: tile(s.map),
    normalMap: tile(s.normal),
    roughnessMap: tile(s.rough),
    roughness: 1,
    metalness: 0
  });
}
