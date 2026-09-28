import * as THREE from "three";
import { tiledClone, metalTexture } from "./textures.js";

// ================================================================
// Blocky humanoid model, shared by all remote players
// ================================================================
export function createPlayerModel() {
  const group = new THREE.Group();
  const armorMat = new THREE.MeshLambertMaterial({ color: 0x8fa0b3, map: tiledClone(metalTexture, 1, 1) });
  const trimMat = new THREE.MeshLambertMaterial({ color: 0x1e1f22, map: tiledClone(metalTexture, 1, 1) });
  const headMat = new THREE.MeshLambertMaterial({ color: 0xe82626 });

  function part(mat, x, y, z, sx, sy, sz, parent) {
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(sx, sy, sz), mat);
    mesh.position.set(x, y, z);
    (parent || group).add(mesh);
    return mesh;
  }

  part(trimMat, -0.15, -0.82, 0.02, 0.3, 0.18, 0.34);
  part(trimMat, 0.15, -0.82, 0.02, 0.3, 0.18, 0.34);
  part(armorMat, -0.15, -0.45, 0, 0.26, 0.6, 0.26);
  part(armorMat, 0.15, -0.45, 0, 0.26, 0.6, 0.26);
  part(armorMat, 0, 0.22, 0, 0.6, 0.65, 0.34);
  part(trimMat, 0, -0.1, 0, 0.62, 0.1, 0.36);
  part(trimMat, -0.4, 0.52, 0, 0.2, 0.16, 0.38);
  part(trimMat, 0.4, 0.52, 0, 0.2, 0.16, 0.38);

  const leftArm = new THREE.Group();
  leftArm.position.set(-0.42, 0.46, 0);
  group.add(leftArm);
  part(armorMat, 0, -0.25, 0, 0.18, 0.46, 0.18, leftArm);
  part(trimMat, 0, -0.52, 0, 0.16, 0.14, 0.16, leftArm);

  const rightArm = new THREE.Group();
  rightArm.position.set(0.42, 0.46, 0);
  group.add(rightArm);
  part(armorMat, 0, -0.25, 0, 0.18, 0.46, 0.18, rightArm);
  part(trimMat, 0, -0.52, 0, 0.16, 0.14, 0.16, rightArm);

  part(headMat, 0, 0.72, 0, 0.36, 0.34, 0.36);

  return group;
}

// group.position represents the model's "center" (~0.9 below eye height)
export const EYE_OFFSET = 0.9;

export function lerpAngle(a, b, t) {
  let diff = ((b - a + Math.PI) % (Math.PI * 2)) - Math.PI;
  if (diff < -Math.PI) diff += Math.PI * 2;
  return a + diff * t;
}

