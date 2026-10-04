import * as THREE from "three";
// Renderer, scene, camera and lights -- created once at import time and shared by every other module.
// ================================================================
// Renderer / scene / camera
// ================================================================
export const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(window.devicePixelRatio);
renderer.setSize(window.innerWidth, window.innerHeight);
document.body.appendChild(renderer.domElement);

export const scene = new THREE.Scene();
scene.background = new THREE.Color(0xb8d4dc);
scene.fog = new THREE.Fog(0xb8d4dc, 20, 95);

export const BASE_FOV = 70;
export const camera = new THREE.PerspectiveCamera(BASE_FOV, window.innerWidth / window.innerHeight, 0.1, 200);
camera.position.set(0, 1.7, 8);
scene.add(camera); // needed so the view-model gun (parented to the camera below) gets rendered

scene.add(new THREE.AmbientLight(0xffffff, 0.9));
// Sky-to-ground fill: the photo PBR surfaces (see pbr.js) rely on bounce light to read as lit, not as black.
scene.add(new THREE.HemisphereLight(0xdfe9f5, 0x5a5444, 1.3));
const sun = new THREE.DirectionalLight(0xffffff, 1.1);
sun.position.set(10, 20, 10);
scene.add(sun);

window.addEventListener("resize", () => {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});
