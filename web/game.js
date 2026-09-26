(function () {
  "use strict";

  // ================================================================
  // Renderer / scene / camera
  // ================================================================
  const renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(window.devicePixelRatio);
  renderer.setSize(window.innerWidth, window.innerHeight);
  document.body.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0xb8d4dc);
  scene.fog = new THREE.Fog(0xb8d4dc, 20, 95);

  const camera = new THREE.PerspectiveCamera(70, window.innerWidth / window.innerHeight, 0.1, 200);
  camera.position.set(0, 1.7, 8);
  scene.add(camera); // needed so the view-model gun (parented to the camera below) gets rendered

  scene.add(new THREE.AmbientLight(0xffffff, 0.6));
  const sun = new THREE.DirectionalLight(0xffffff, 0.8);
  sun.position.set(10, 20, 10);
  scene.add(sun);

  window.addEventListener("resize", () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
  });

  // ================================================================
  // World: same arena layout as the desktop version, now dressed with a
  // gradient sky, a textured ground, a distant mountain ring (so the
  // arena doesn't feel like it's floating in a void), and scattered
  // trees/rocks for foreground detail.
  // ================================================================
  const targets = []; // local practice bots -- shootable, respawn on hit, not networked

  function addBox(position, size, color, texture, tileSize) {
    const geo = new THREE.BoxGeometry(size[0], size[1], size[2]);
    const matOptions = { color };
    if (texture) {
      const t = Math.max(tileSize || 2.5, 0.01);
      matOptions.map = tiledClone(texture, Math.max(size[0] / t, 0.5), Math.max(size[1] / t, 0.5));
    }
    const mat = new THREE.MeshLambertMaterial(matOptions);
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.set(position[0], position[1], position[2]);
    scene.add(mesh);
    return mesh;
  }

  // ================================================================
  // Procedural textures (canvas-painted, no image assets) shared across
  // walls, targets, terrain, player models, and the gun -- each is
  // generated once and reused via tiledClone() with per-object repeat
  // counts, mirroring the desktop build's "one texture, many tints"
  // approach.
  // ================================================================
  function makeCanvasTexture(size, draw) {
    const canvas = document.createElement("canvas");
    canvas.width = size;
    canvas.height = size;
    draw(canvas.getContext("2d"), size);
    const tex = new THREE.CanvasTexture(canvas);
    tex.wrapS = THREE.RepeatWrapping;
    tex.wrapT = THREE.RepeatWrapping;
    return tex;
  }

  function tiledClone(baseTexture, repeatX, repeatY) {
    const tex = baseTexture.clone();
    tex.needsUpdate = true;
    tex.repeat.set(repeatX, repeatY);
    return tex;
  }

  function makeMetalTexture() {
    return makeCanvasTexture(256, (ctx, size) => {
      ctx.fillStyle = "#aab0bb";
      ctx.fillRect(0, 0, size, size);
      for (let i = 0; i < 3000; i++) {
        const x = Math.random() * size, y = Math.random() * size;
        const a = Math.random() * 0.06;
        ctx.fillStyle = Math.random() < 0.5 ? `rgba(0,0,0,${a})` : `rgba(255,255,255,${a})`;
        ctx.fillRect(x, y, 2, 2);
      }
      const tile = size / 4;
      ctx.strokeStyle = "rgba(30,30,35,0.35)";
      ctx.lineWidth = 2;
      for (let x = 0; x <= size; x += tile) {
        ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, size); ctx.stroke();
      }
      for (let y = 0; y <= size; y += tile) {
        ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(size, y); ctx.stroke();
      }
      ctx.fillStyle = "rgba(230,230,235,0.9)";
      for (let x = 0; x <= size; x += tile) {
        for (let y = 0; y <= size; y += tile) {
          ctx.beginPath(); ctx.arc(x + 6, y + 6, 2.5, 0, Math.PI * 2); ctx.fill();
          ctx.beginPath(); ctx.arc(x + tile - 6, y + 6, 2.5, 0, Math.PI * 2); ctx.fill();
        }
      }
    });
  }

  function makeRockTexture() {
    return makeCanvasTexture(256, (ctx, size) => {
      ctx.fillStyle = "#9a9a92";
      ctx.fillRect(0, 0, size, size);
      for (let i = 0; i < 3500; i++) {
        const x = Math.random() * size, y = Math.random() * size;
        const a = Math.random() * 0.18;
        ctx.fillStyle = Math.random() < 0.5 ? `rgba(35,32,28,${a})` : `rgba(215,210,198,${a})`;
        const r = 1 + Math.random() * 3;
        ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
      }
      ctx.strokeStyle = "rgba(30,28,24,0.35)";
      ctx.lineWidth = 1;
      for (let i = 0; i < 24; i++) {
        const x = Math.random() * size, y = Math.random() * size;
        ctx.beginPath();
        ctx.moveTo(x, y);
        ctx.lineTo(x + (Math.random() - 0.5) * 50, y + (Math.random() - 0.5) * 50);
        ctx.stroke();
      }
    });
  }

  function makeBarkTexture() {
    return makeCanvasTexture(128, (ctx, size) => {
      ctx.fillStyle = "#4a3020";
      ctx.fillRect(0, 0, size, size);
      for (let x = 0; x < size; x += 3) {
        const shade = Math.random() * 35;
        ctx.fillStyle = `rgba(${40 + shade},${24 + shade * 0.6},${12 + shade * 0.3},${0.35 + Math.random() * 0.35})`;
        ctx.fillRect(x, 0, 2, size);
      }
    });
  }

  function makeFoliageTexture() {
    return makeCanvasTexture(128, (ctx, size) => {
      ctx.fillStyle = "#356b35";
      ctx.fillRect(0, 0, size, size);
      for (let i = 0; i < 1200; i++) {
        const x = Math.random() * size, y = Math.random() * size;
        const a = Math.random() * 0.35;
        ctx.fillStyle = Math.random() < 0.5 ? `rgba(15,45,15,${a})` : `rgba(95,155,75,${a})`;
        ctx.fillRect(x, y, 3, 3);
      }
    });
  }

  function makeHazardTexture() {
    return makeCanvasTexture(128, (ctx, size) => {
      ctx.fillStyle = "#c81e1e";
      ctx.fillRect(0, 0, size, size);
      ctx.save();
      ctx.translate(size / 2, size / 2);
      ctx.rotate(Math.PI / 4);
      ctx.translate(-size, -size);
      ctx.fillStyle = "#1a1a1a";
      const stripeW = size / 5;
      for (let x = 0; x < size * 3; x += stripeW * 2) {
        ctx.fillRect(x, 0, stripeW, size * 2);
      }
      ctx.restore();
    });
  }

  const metalTexture = makeMetalTexture();
  const rockTexture = makeRockTexture();
  const barkTexture = makeBarkTexture();
  const foliageTexture = makeFoliageTexture();
  const hazardTexture = makeHazardTexture();

  // ---- Sky dome: vertical gradient instead of a single flat color ----
  function addSkyDome() {
    const geo = new THREE.SphereGeometry(300, 24, 16);
    const mat = new THREE.ShaderMaterial({
      uniforms: {
        topColor: { value: new THREE.Color(0x4d76b3) },
        bottomColor: { value: new THREE.Color(0xb8d4dc) },
        offset: { value: 20 },
        exponent: { value: 0.6 }
      },
      vertexShader: `
        varying vec3 vWorldPosition;
        void main() {
          vec4 worldPosition = modelMatrix * vec4(position, 1.0);
          vWorldPosition = worldPosition.xyz;
          gl_Position = projectionMatrix * viewMatrix * worldPosition;
        }
      `,
      fragmentShader: `
        uniform vec3 topColor;
        uniform vec3 bottomColor;
        uniform float offset;
        uniform float exponent;
        varying vec3 vWorldPosition;
        void main() {
          float h = normalize(vWorldPosition + vec3(0.0, offset, 0.0)).y;
          gl_FragColor = vec4(mix(bottomColor, topColor, max(pow(max(h, 0.0), exponent), 0.0)), 1.0);
        }
      `,
      side: THREE.BackSide,
      fog: false
    });
    scene.add(new THREE.Mesh(geo, mat));
  }
  addSkyDome();

  // ---- Procedurally-painted ground texture (no image assets used) ----
  function makeGroundTexture() {
    const size = 256;
    const canvas = document.createElement("canvas");
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext("2d");

    ctx.fillStyle = "#4d8c4d";
    ctx.fillRect(0, 0, size, size);

    for (let i = 0; i < 4000; i++) {
      const x = Math.random() * size;
      const y = Math.random() * size;
      const g = 90 + Math.floor(Math.random() * 60);
      ctx.fillStyle = `rgba(${g - 60}, ${g}, ${g - 60}, 0.5)`;
      ctx.fillRect(x, y, 2, 2);
    }
    for (let i = 0; i < 40; i++) {
      const x = Math.random() * size;
      const y = Math.random() * size;
      const r = 6 + Math.random() * 14;
      ctx.fillStyle = "rgba(120, 100, 60, 0.25)";
      ctx.beginPath();
      ctx.ellipse(x, y, r, r * 0.6, Math.random() * Math.PI, 0, Math.PI * 2);
      ctx.fill();
    }

    const texture = new THREE.CanvasTexture(canvas);
    texture.wrapS = THREE.RepeatWrapping;
    texture.wrapT = THREE.RepeatWrapping;
    texture.repeat.set(20, 20);
    return texture;
  }

  function addGround() {
    const geo = new THREE.BoxGeometry(60, 1, 60);
    const mat = new THREE.MeshLambertMaterial({ map: makeGroundTexture() });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.set(0, -0.5, 0);
    scene.add(mesh);
  }
  addGround();

  addBox([-6, 1.5, -5], [2, 3, 2], 0x9aa0ab, metalTexture);
  addBox([6, 1.5, -8], [2, 3, 2], 0x9aa0ab, metalTexture);
  addBox([0, 1.5, -14], [8, 3, 1], 0x8b909c, metalTexture);
  addBox([-11, 1.5, -18], [2, 3, 2], 0x9aa0ab, metalTexture);
  addBox([11, 1.5, -18], [2, 3, 2], 0x9aa0ab, metalTexture);

  // ---- Distant mountain ring -- breaks the "floating in a void" look ----
  function addMountains() {
    const count = 16;
    const radius = 78;
    for (let i = 0; i < count; i++) {
      const angle = (i / count) * Math.PI * 2;
      const dist = radius + ((i * 37) % 17) - 8;
      const x = Math.cos(angle) * dist;
      const z = Math.sin(angle) * dist;
      const h = 20 + ((i * 53) % 20);
      const r = 10 + ((i * 29) % 8);
      const sides = 5 + (i % 3);
      const mat = new THREE.MeshLambertMaterial({
        color: 0x5b6a86,
        map: tiledClone(rockTexture, r / 3, h / 3)
      });
      const mesh = new THREE.Mesh(new THREE.ConeGeometry(r, h, sides), mat);
      mesh.position.set(x, h / 2 - 2, z);
      mesh.rotation.y = i * 0.7;
      scene.add(mesh);
    }
  }
  addMountains();

  // ---- Scattered trees and rocks for foreground detail ----
  function addTree(x, z) {
    const group = new THREE.Group();
    const trunk = new THREE.Mesh(
      new THREE.CylinderGeometry(0.15, 0.22, 1.6, 6),
      new THREE.MeshLambertMaterial({ color: 0xffffff, map: tiledClone(barkTexture, 1, 1) })
    );
    trunk.position.y = 0.8;
    group.add(trunk);
    const leaves = new THREE.Mesh(
      new THREE.ConeGeometry(1.1, 2.4, 7),
      new THREE.MeshLambertMaterial({ color: 0xffffff, map: tiledClone(foliageTexture, 1, 1) })
    );
    leaves.position.y = 2.4;
    group.add(leaves);
    group.position.set(x, 0, z);
    scene.add(group);
  }

  function addRock(x, z, scale) {
    const rock = new THREE.Mesh(
      new THREE.DodecahedronGeometry(0.6 * scale, 0),
      new THREE.MeshLambertMaterial({ color: 0x8a8a86, map: tiledClone(rockTexture, 1, 1) })
    );
    rock.position.set(x, 0.3 * scale, z);
    rock.rotation.set(scale * 1.3, scale * 2.1, 0);
    scene.add(rock);
  }

  [[-16, -3], [16, -4], [-14, -10], [14, -11], [-3, -22], [3, -23],
   [-17, -20], [17, -21], [-8, -26], [8, -27]].forEach(([x, z]) => addTree(x, z));

  [[-4, -2, 1], [4, -3, 0.8], [-9, -12, 1.2], [9, -13, 0.9],
   [-2, -17, 0.7], [2, -18, 1.1], [-13, -24, 1], [13, -25, 0.85]]
    .forEach(([x, z, s]) => addRock(x, z, s));

  const TARGET_SIZE = 1.2;
  const TARGET_COUNT = 6;
  const ARENA_X = 34, ARENA_Z_NEAR = -2, ARENA_Z_FAR = 26;

  function randomArenaPosition(y) {
    const x = (Math.random() - 0.5) * ARENA_X;
    const z = ARENA_Z_NEAR - Math.random() * ARENA_Z_FAR;
    return [x, y, z];
  }

  for (let i = 0; i < TARGET_COUNT; i++) {
    const mesh = addBox(randomArenaPosition(1 + Math.random() * 3.5), [TARGET_SIZE, TARGET_SIZE, TARGET_SIZE], 0xffffff, hazardTexture, 1.2);
    targets.push({ mesh });
  }

  // ================================================================
  // Blocky humanoid model, shared by all remote players
  // ================================================================
  function createPlayerModel() {
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
  const EYE_OFFSET = 0.9;

  function lerpAngle(a, b, t) {
    let diff = ((b - a + Math.PI) % (Math.PI * 2)) - Math.PI;
    if (diff < -Math.PI) diff += Math.PI * 2;
    return a + diff * t;
  }

  // ================================================================
  // First-person weapon view-model (parented to the camera, so it stays
  // anchored to the screen exactly like a real FPS gun) -- mirrors the
  // desktop build's GunModel: idle sway, a walk bob, a recoil kick and a
  // muzzle flash on firing. Without this the local player was just a bare
  // floating camera with no sense of a body/weapon in their own view.
  // ================================================================
  function createGunModel() {
    const group = new THREE.Group();
    const metalMat = new THREE.MeshLambertMaterial({ color: 0x6b6f78, map: tiledClone(metalTexture, 1, 1) });
    const accentMat = new THREE.MeshLambertMaterial({ color: 0x17181a, map: tiledClone(metalTexture, 1, 1) });
    const flashMat = new THREE.MeshBasicMaterial({ color: 0xfff2b0 });

    function part(mat, x, y, z, sx, sy, sz) {
      const mesh = new THREE.Mesh(new THREE.BoxGeometry(sx, sy, sz), mat);
      mesh.position.set(x, y, z);
      group.add(mesh);
      return mesh;
    }

    part(metalMat, 0, -0.02, 0.1, 0.12, 0.12, 0.55);   // body/receiver
    part(metalMat, 0, 0.02, -0.35, 0.05, 0.05, 0.35);  // barrel
    part(accentMat, 0, -0.14, 0.2, 0.08, 0.18, 0.1);   // grip
    part(accentMat, 0, -0.1, 0.02, 0.06, 0.14, 0.22);  // magazine
    part(metalMat, 0, 0.02, 0.42, 0.09, 0.1, 0.22);    // stock

    const flash = part(flashMat, 0, 0.02, -0.56, 0.16, 0.16, 0.16);
    flash.visible = false;
    group.userData.flash = flash;
    return group;
  }

  const GUN_BASE_POS = { x: 0.32, y: -0.32, z: -0.6 };
  const GUN_RECOIL_DURATION = 0.18;
  const GUN_MUZZLE_FLASH_DURATION = 0.05;

  const gunGroup = createGunModel();
  gunGroup.position.set(GUN_BASE_POS.x, GUN_BASE_POS.y, GUN_BASE_POS.z);
  gunGroup.rotation.y = THREE.MathUtils.degToRad(8);
  camera.add(gunGroup);

  let gunIdleTime = 0;
  let gunWalkTime = 0;
  let gunMovingFactor = 0;
  let gunRecoilTimer = 0;

  function triggerGunFire() {
    gunRecoilTimer = GUN_RECOIL_DURATION;
  }

  function updateGunModel(dt, moving) {
    gunIdleTime += dt;
    gunMovingFactor += ((moving ? 1 : 0) - gunMovingFactor) * Math.min(1, dt * 8);
    if (moving) gunWalkTime += dt * 9;
    if (gunRecoilTimer > 0) gunRecoilTimer = Math.max(0, gunRecoilTimer - dt);

    const idleSwayX = Math.sin(gunIdleTime * 0.6) * 0.012;
    const idleSwayY = Math.sin(gunIdleTime * 1.1) * 0.008;
    const walkBobX = Math.sin(gunWalkTime) * 0.02 * gunMovingFactor;
    const walkBobY = Math.abs(Math.sin(gunWalkTime)) * 0.018 * gunMovingFactor;
    const recoilT = gunRecoilTimer / GUN_RECOIL_DURATION;

    gunGroup.position.set(
      GUN_BASE_POS.x + idleSwayX + walkBobX,
      GUN_BASE_POS.y + idleSwayY + walkBobY,
      GUN_BASE_POS.z + recoilT * 0.12
    );
    gunGroup.rotation.y = THREE.MathUtils.degToRad(8);
    gunGroup.rotation.x = -THREE.MathUtils.degToRad(recoilT * 10);
    gunGroup.userData.flash.visible = gunRecoilTimer > GUN_RECOIL_DURATION - GUN_MUZZLE_FLASH_DURATION;
  }

  // ================================================================
  // Networking
  // ================================================================
  const remotePlayers = new Map(); // id -> { group, targetPos, targetYaw, name, kills, alive }
  let ws = null;
  let myId = null;
  let killLimit = 10;
  let myKills = 0;
  let connected = false;

  function wsUrl() {
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    return `${proto}//${location.host}/ws`;
  }

  function connect(playerName) {
    ws = new WebSocket(wsUrl());
    ws.onopen = () => {
      connected = true;
      ws.send(JSON.stringify({ type: "join", name: playerName }));
    };
    ws.onclose = () => {
      connected = false;
      setStatus("Disconnected from server");
    };
    ws.onerror = () => setStatus("Connection error");
    ws.onmessage = (event) => {
      let msg;
      try {
        msg = JSON.parse(event.data);
      } catch (e) {
        return;
      }
      handleServerMessage(msg);
    };
  }

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
      case "welcome": {
        myId = msg.id;
        killLimit = msg.killLimit;
        for (const p of msg.players) {
          const rp = ensureRemotePlayer(p.id, p.name);
          rp.kills = p.kills;
          rp.targetPos.set(p.pos[0], p.pos[1] - EYE_OFFSET, p.pos[2]);
          rp.group.position.copy(rp.targetPos);
          rp.targetYaw = -p.yaw;
        }
        setStatus(`Connected as ${playerNameInput.value || "you"}`);
        updateHud();
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
      case "hit": {
        if (msg.shooterId === myId) {
          myKills = msg.shooterKills;
          triggerHitFeedback();
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
        hideMatchBanner();
        break;
      }
      default:
        break;
    }
  }

  let lastSentState = 0;
  function sendStateIfDue(now) {
    if (!connected || isDead) return;
    if (now - lastSentState < 50) return;
    lastSentState = now;
    ws.send(JSON.stringify({
      type: "state",
      pos: [camera.position.x, camera.position.y, camera.position.z],
      yaw, pitch
    }));
  }

  // ================================================================
  // Input: pointer-lock mouse look + WASD fly movement
  // ================================================================
  const overlay = document.getElementById("overlay");
  const startBtn = document.getElementById("startBtn");
  const playerNameInput = document.getElementById("nameInput");
  const canvas = renderer.domElement;

  const keys = Object.create(null);
  window.addEventListener("keydown", (e) => { keys[e.code] = true; });
  window.addEventListener("keyup", (e) => { keys[e.code] = false; });

  let yaw = -Math.PI / 2;
  let pitch = 0;
  const MOUSE_SENSITIVITY = 0.0022;
  const MOVE_SPEED = 6.0;
  let started = false;
  let isDead = false;

  function isLocked() {
    return document.pointerLockElement === canvas;
  }

  startBtn.addEventListener("click", () => {
    if (!started) {
      started = true;
      const name = (playerNameInput.value || "").trim() || `Player${Math.floor(Math.random() * 1000)}`;
      connect(name);
      if (audioCtx.state === "suspended") audioCtx.resume();
    }
    canvas.requestPointerLock();
  });

  document.addEventListener("pointerlockchange", () => {
    overlay.hidden = isLocked();
  });

  document.addEventListener("mousemove", (e) => {
    if (!isLocked()) return;
    yaw += e.movementX * MOUSE_SENSITIVITY;
    pitch -= e.movementY * MOUSE_SENSITIVITY;
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

  canvas.addEventListener("mousedown", (e) => {
    if (!isLocked() || e.button !== 0 || isDead) return;
    shoot();
  });

  // ================================================================
  // Shooting: local raycast against practice-bot targets, plus a
  // networked "shoot" message the server resolves authoritatively
  // against other players' positions.
  // ================================================================
  const raycaster = new THREE.Raycaster();
  const scoreEl = document.getElementById("score");
  let score = 0;

  function shoot() {
    triggerGunFire();

    const forward = getForward();
    raycaster.set(camera.position, forward);

    const hits = raycaster.intersectObjects(targets.map((t) => t.mesh));
    if (hits.length > 0) {
      const hitMesh = hits[0].object;
      const target = targets.find((t) => t.mesh === hitMesh);
      const [x, y, z] = randomArenaPosition(1 + Math.random() * 3.5);
      target.mesh.position.set(x, y, z);
      score++;
      scoreEl.textContent = String(score);
      triggerHitFeedback();
    }

    if (connected && !isDead) {
      ws.send(JSON.stringify({
        type: "shoot",
        origin: [camera.position.x, camera.position.y, camera.position.z],
        dir: [forward.x, forward.y, forward.z]
      }));
    }
  }

  // ================================================================
  // Hit marker + tick sound (matches the desktop build's MW2-style feel)
  // ================================================================
  const hitMarkerEl = document.getElementById("hitmarker");
  let hitMarkerTimeout = null;

  function triggerHitFeedback() {
    hitMarkerEl.classList.remove("show");
    // Force reflow so the CSS transition restarts on rapid consecutive hits.
    void hitMarkerEl.offsetWidth;
    hitMarkerEl.classList.add("show");
    clearTimeout(hitMarkerTimeout);
    hitMarkerTimeout = setTimeout(() => hitMarkerEl.classList.remove("show"), 200);
    playHitTick();
  }

  const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  function playHitTick() {
    const duration = 0.045;
    const sampleRate = audioCtx.sampleRate;
    const frameCount = Math.floor(sampleRate * duration);
    const buffer = audioCtx.createBuffer(1, frameCount, sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < frameCount; i++) {
      const t = i / sampleRate;
      const toneEnv = Math.exp(-90 * t);
      const tone = Math.sin(2 * Math.PI * 1900 * t) * toneEnv;
      const clickEnv = Math.exp(-4000 * t);
      const click = (Math.random() * 2 - 1) * clickEnv;
      data[i] = Math.max(-1, Math.min(1, tone * 0.75 + click * 0.5));
    }
    const source = audioCtx.createBufferSource();
    source.buffer = buffer;
    source.connect(audioCtx.destination);
    source.start();
  }

  // ================================================================
  // Death / respawn + match-over banner
  // ================================================================
  const deathOverlay = document.getElementById("deathOverlay");
  const matchBanner = document.getElementById("matchBanner");

  function triggerLocalDeath() {
    isDead = true;
    deathOverlay.classList.add("show");
  }

  function clearLocalDeath(pos) {
    isDead = false;
    deathOverlay.classList.remove("show");
    if (pos) camera.position.set(pos[0], pos[1], pos[2]);
  }

  let matchBannerTimeout = null;
  function showMatchBanner(msg) {
    const youWon = msg.winnerId === myId;
    matchBanner.textContent = youWon
      ? `YOU WIN! (${msg.score} kills)`
      : `${msg.winnerName} WINS! (${msg.score} kills)`;
    matchBanner.classList.add("show");
    clearTimeout(matchBannerTimeout);
    matchBannerTimeout = setTimeout(hideMatchBanner, 6000);
  }

  function hideMatchBanner() {
    matchBanner.classList.remove("show");
  }

  // ================================================================
  // HUD / scoreboard
  // ================================================================
  const killsEl = document.getElementById("kills");
  const killLimitEl = document.getElementById("killLimit");
  const playerCountEl = document.getElementById("playerCount");
  const scoreboardEl = document.getElementById("scoreboard");
  const statusEl = document.getElementById("status");

  function setStatus(text) {
    statusEl.textContent = text;
  }

  function updateHud() {
    killsEl.textContent = String(myKills);
    killLimitEl.textContent = String(killLimit);
    playerCountEl.textContent = String(remotePlayers.size + 1);

    const rows = [{ name: "You", kills: myKills }];
    for (const rp of remotePlayers.values()) rows.push({ name: rp.name, kills: rp.kills });
    rows.sort((a, b) => b.kills - a.kills);
    scoreboardEl.innerHTML = rows.map((r) => `<div>${r.name}: ${r.kills}</div>`).join("");
  }

  // ================================================================
  // Game loop
  // ================================================================
  let lastTime = performance.now();

  function tick(now) {
    const dt = Math.min((now - lastTime) / 1000, 0.1);
    lastTime = now;

    let moving = false;
    if (isLocked() && !isDead) {
      const forward = getForward();
      const flatForward = new THREE.Vector3(forward.x, 0, forward.z);
      if (flatForward.lengthSq() > 0.0001) flatForward.normalize();
      const right = new THREE.Vector3().crossVectors(flatForward, new THREE.Vector3(0, 1, 0));

      const velocity = MOVE_SPEED * dt;
      moving = keys["KeyW"] || keys["KeyS"] || keys["KeyD"] || keys["KeyA"];
      if (keys["KeyW"]) camera.position.addScaledVector(flatForward, velocity);
      if (keys["KeyS"]) camera.position.addScaledVector(flatForward, -velocity);
      if (keys["KeyD"]) camera.position.addScaledVector(right, velocity);
      if (keys["KeyA"]) camera.position.addScaledVector(right, -velocity);
      if (keys["Space"]) camera.position.y += velocity;
      if (keys["ShiftLeft"] || keys["ShiftRight"]) camera.position.y -= velocity;

      camera.lookAt(
        camera.position.x + forward.x,
        camera.position.y + forward.y,
        camera.position.z + forward.z
      );
    }

    updateGunModel(dt, moving);
    sendStateIfDue(now);

    for (const rp of remotePlayers.values()) {
      rp.group.position.lerp(rp.targetPos, 0.25);
      rp.group.rotation.y = lerpAngle(rp.group.rotation.y, rp.targetYaw, 0.25);
    }

    renderer.render(scene, camera);
    requestAnimationFrame(tick);
  }

  requestAnimationFrame(tick);
})();
