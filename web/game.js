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

  const BASE_FOV = 70;
  const camera = new THREE.PerspectiveCamera(BASE_FOV, window.innerWidth / window.innerHeight, 0.1, 200);
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

  // Weapon stats are duplicated from GameServer.java's WEAPON_DAMAGE /
  // WEAPON_COOLDOWN_MS / WEAPON_MAG_SIZE / WEAPON_RELOAD_MS for local UI/
  // animation timing -- the server re-checks all of it authoritatively
  // (ammo and reload included), so this copy only affects how the local
  // view feels, never the actual outcome of a shot. Declared up here
  // (rather than down by the gun-model code) because the practice-target
  // HP range below is derived from it.
  // adsFov: camera FOV while aiming (lower = more zoom); adsSpeed: how fast
  // the zoom transitions in/out; adsMoveMult: movement speed while aiming
  // (aiming steadies your shot at the cost of mobility, standard FPS
  // convention); scope: true gives the full circular scope overlay
  // treatment instead of just a tighter FOV/repositioned gun model.
  //
  // hipSpread/adsSpread: half-angle (degrees) of the random cone each shot
  // is perturbed within -- hipSpread applies at 0% aimed, adsSpread at
  // 100%, blended by adsBlend in between (see shoot()). At distance D, a
  // spread of theta degrees offsets a shot by roughly D*tan(theta) --
  // sized against the ~0.4-unit player hitbox half-width so hip-fire stays
  // reliable at close range but increasingly unreliable at longer range,
  // pushing toward ADS's near-pinpoint accuracy instead.
  const WEAPONS = [
    { name: "Pistol", damage: 20, cooldown: 150, magSize: 8, reloadMs: 1000,
      adsFov: 55, adsSpeed: 12, adsMoveMult: 0.8, hipSpread: 1.2, adsSpread: 0.1 },
    { name: "Rifle", damage: 34, cooldown: 300, magSize: 24, reloadMs: 1600,
      adsFov: 45, adsSpeed: 9, adsMoveMult: 0.7, hipSpread: 3.0, adsSpread: 0.1 },
    { name: "Sniper", damage: 100, cooldown: 1000, magSize: 5, reloadMs: 2200,
      adsFov: 15, adsSpeed: 6, adsMoveMult: 0.35, scope: true, hipSpread: 6.0, adsSpread: 0.05 },
    { name: "SMG", damage: 14, cooldown: 100, magSize: 20, reloadMs: 1300, automatic: true,
      adsFov: 58, adsSpeed: 14, adsMoveMult: 0.85, hipSpread: 1.8, adsSpread: 0.15 }
  ];

  // ================================================================
  // World: same arena layout as the desktop version, now dressed with a
  // gradient sky, a textured ground, a distant mountain ring (so the
  // arena doesn't feel like it's floating in a void), and scattered
  // trees/rocks for foreground detail.
  // ================================================================
  const targets = []; // local practice bots -- shootable, respawn on hit, not networked
  const collidables = []; // meshes the ground-detection raycast can land the player on (see the jump/gravity code)
  const solidBoxes = []; // precomputed Box3s for horizontal + ceiling collision against static structure

  // Registers a static mesh as real solid structure: standable from above
  // (via `collidables`, used by the downward ground raycast) AND blocking
  // horizontally / from below (via `solidBoxes`, used by the horizontal
  // slide + ceiling checks). Moving objects (practice targets) deliberately
  // don't go through this -- their Box3 would need recomputing every frame.
  function registerSolid(mesh) {
    collidables.push(mesh);
    solidBoxes.push(new THREE.Box3().setFromObject(mesh));
    return mesh;
  }

  // Rotated ramps need a precise oriented check, not a loose world-axis
  // Box3 (see registerRamp below for why). Stored as the ramp's inverse
  // world matrix + its exact local half-extents, so a query point can be
  // transformed into the ramp's own local frame and checked exactly,
  // regardless of which way it's tilted.
  const rampColliders = [];

  function registerRamp(mesh, width, thickness, length) {
    collidables.push(mesh);
    mesh.updateMatrixWorld(true);
    rampColliders.push({
      invMatrix: new THREE.Matrix4().copy(mesh.matrixWorld).invert(),
      halfW: width / 2,
      halfT: thickness / 2,
      halfL: length / 2
    });
    return mesh;
  }

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
    registerSolid(mesh);
  }
  addGround();

  registerSolid(addBox([-6, 1.5, -5], [2, 3, 2], 0x9aa0ab, metalTexture));
  registerSolid(addBox([6, 1.5, -8], [2, 3, 2], 0x9aa0ab, metalTexture));
  registerSolid(addBox([0, 1.5, -14], [8, 3, 1], 0x8b909c, metalTexture));
  registerSolid(addBox([-11, 1.5, -18], [2, 3, 2], 0x9aa0ab, metalTexture));
  registerSolid(addBox([11, 1.5, -18], [2, 3, 2], 0x9aa0ab, metalTexture));

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

  // ---- Multi-floor building overlooking the wall/tree cluster ----
  // Placed on open grass east of the tree/rock scatter (which tops out
  // around x=17). Its west-facing wall (facing the arena) is built from
  // sill/lintel/mullion segments with real open gaps for windows -- not
  // a solid wall with a decal -- so there's an actual sightline through
  // each window down into the existing wall/tree area. It's real solid
  // structure (walls, mullions, and even the glass all block movement),
  // so the windows are a view, not an entrance -- the roof, reached via
  // the spiral ramp below, is the way in.
  function addBuilding() {
    const originX = 24, originZ = -10;
    const width = 8, length = 12;
    const floors = 4, floorHeight = 3.4;
    const sillHeight = 1.0, windowHeight = 1.8;
    const lintelHeight = floorHeight - sillHeight - windowHeight;
    const bays = 3, bayWidth = length / bays, mullionWidth = 0.4;
    const windowWidth = bayWidth - mullionWidth;
    const doorWidth = 2.4, doorHeight = 2.2;

    // Floor slabs (including the roof cap) -- what the player stands on.
    for (let f = 0; f <= floors; f++) {
      registerSolid(addBox([originX, f * floorHeight, originZ], [width, 0.3, length], 0x9a968c, metalTexture, 3));
    }

    for (let f = 0; f < floors; f++) {
      const floorBaseY = f * floorHeight;
      const y = floorBaseY + floorHeight / 2;
      // Solid east (back) and north walls
      registerSolid(addBox([originX + width / 2 - 0.15, y, originZ], [0.3, floorHeight, length], 0xb9b6ac, metalTexture, 2));
      registerSolid(addBox([originX, y, originZ - length / 2 + 0.15], [width, floorHeight, 0.3], 0xb9b6ac, metalTexture, 2));

      // South wall (facing the staircase): a doorway gap instead of one
      // solid slab, so each floor is actually enterable from the ramp.
      const southZ = originZ + length / 2 - 0.15;
      const sideWidth = (width - doorWidth) / 2;
      if (sideWidth > 0.05) {
        registerSolid(addBox([originX - width / 2 + sideWidth / 2, y, southZ], [sideWidth, floorHeight, 0.3], 0xb9b6ac, metalTexture, 2));
        registerSolid(addBox([originX + width / 2 - sideWidth / 2, y, southZ], [sideWidth, floorHeight, 0.3], 0xb9b6ac, metalTexture, 2));
      }
      const headerHeight = floorHeight - doorHeight;
      if (headerHeight > 0.05) {
        registerSolid(addBox([originX, floorBaseY + doorHeight + headerHeight / 2, southZ], [doorWidth, headerHeight, 0.3], 0xb9b6ac, metalTexture, 2));
      }

      // West wall (facing the arena): sill + lintel run the full length,
      // with open gaps between mullion pillars for the window bays.
      const wx = originX - width / 2 + 0.15;
      registerSolid(addBox([wx, floorBaseY + sillHeight / 2, originZ], [0.3, sillHeight, length], 0xb9b6ac, metalTexture, 2));
      registerSolid(addBox([wx, floorBaseY + sillHeight + windowHeight + lintelHeight / 2, originZ], [0.3, lintelHeight, length], 0xb9b6ac, metalTexture, 2));

      for (let b = 0; b <= bays; b++) {
        const bz = originZ - length / 2 + b * bayWidth;
        registerSolid(addBox([wx, floorBaseY + sillHeight + windowHeight / 2, bz], [0.3, windowHeight, mullionWidth], 0x2b2b2e, metalTexture, 1));
      }

      // Tinted glass in each opening -- translucent (so the arena is still
      // visible through it), but still solid, same as a real window.
      for (let b = 0; b < bays; b++) {
        const bz = originZ - length / 2 + b * bayWidth + bayWidth / 2;
        const glass = new THREE.Mesh(
          new THREE.BoxGeometry(0.05, windowHeight, windowWidth),
          new THREE.MeshLambertMaterial({ color: 0x8fd0e6, transparent: true, opacity: 0.28 })
        );
        glass.position.set(wx, floorBaseY + sillHeight + windowHeight / 2, bz);
        scene.add(glass);
        registerSolid(glass);
      }
    }

    return { originX, originZ, width, length, floors, floorHeight };
  }
  const building = addBuilding();

  // ---- Spiral ramp up to the roof, so it's actually reachable on foot.
  // A sharp-cornered switchback was tried first, but once real horizontal
  // and ceiling collision exist (see below), sharp 90-180 degree turns
  // create overhangs and corner-snags that block movement -- a continuous
  // gently-curving spiral (built from many short straight segments, each
  // turning only a few degrees) avoids sharp corners entirely, so there's
  // always clean headroom and a continuous walkable surface.
  // General start/end ramp builder: position it at the segment's midpoint
  // and orient it with lookAt so the math works for any direction/slope
  // without hand-computing rotation angles.
  function addRamp(start, end, width, thickness) {
    const startV = new THREE.Vector3(...start);
    const endV = new THREE.Vector3(...end);
    const length = Math.max(startV.distanceTo(endV), 0.01);
    const mid = new THREE.Vector3().addVectors(startV, endV).multiplyScalar(0.5);
    const mat = new THREE.MeshLambertMaterial({
      color: 0x9a968c,
      map: tiledClone(metalTexture, Math.max(width / 2, 0.5), Math.max(length / 2, 0.5))
    });
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(width, thickness, length), mat);
    mesh.position.copy(mid);
    mesh.lookAt(startV);
    scene.add(mesh);
    // Deliberately NOT registerSolid(): an inclined mesh's axis-aligned Box3
    // has to span its full rise (loose bounding box around a rotated
    // shape), which would block the player from walking along the ramp at
    // almost any point on it. registerRamp() instead checks collision in
    // the ramp's own local (unrotated) frame, so it can tell "standing on
    // this ramp's surface" apart from "a different ramp/level happens to
    // pass through this same space at head height" -- which a simple
    // world-axis box couldn't distinguish at all.
    registerRamp(mesh, width, thickness, length);
    return mesh;
  }

  // One full revolution per floor, starting at the angle that faces the
  // building's south (doorway) side -- so every time the spiral completes
  // a revolution, it's back at that same angle, exactly at the next
  // floor's height, and a short branch can lead straight into that
  // floor's doorway (see addBuilding's south-wall doorway gaps above).
  function addSpiralRamp(b) {
    const cx = b.originX;
    const cz = b.originZ + b.length / 2 + 5.5; // clear ground south of the building
    const radius = 3.5;
    const rampWidth = 2.5, rampThickness = 0.3;
    const roofY = b.floors * b.floorHeight;
    const segmentsPerRevolution = 16;
    const totalSegments = segmentsPerRevolution * b.floors;
    const angleStep = (Math.PI * 2) / segmentsPerRevolution;
    const riseStep = roofY / totalSegments;
    const startAngle = -Math.PI / 2; // faces the building from the start
    const doorZ = b.originZ + b.length / 2; // building's south face / doorway threshold

    function pointAt(i) {
      const angle = startAngle + i * angleStep;
      return [cx + radius * Math.cos(angle), i * riseStep, cz + radius * Math.sin(angle)];
    }

    let prev = pointAt(0);
    addRamp(prev, [b.originX, 0, doorZ], rampWidth, rampThickness); // ground-floor doorway

    for (let i = 1; i <= totalSegments; i++) {
      const next = pointAt(i);
      addRamp(prev, next, rampWidth, rampThickness);
      prev = next;

      if (i % segmentsPerRevolution === 0) {
        // Back at the building-facing angle -- branch into that floor's
        // doorway (or, on the final revolution, onto the open roof).
        addRamp(next, [b.originX, next[1], doorZ], rampWidth, rampThickness);
      }
    }
  }
  addSpiralRamp(building);

  const TARGET_SIZE_MIN = 0.6;
  const TARGET_SIZE_MAX = 1.2;
  const TARGET_COUNT = 6;
  const ARENA_X = 34, ARENA_Z_NEAR = -2, ARENA_Z_FAR = 26;

  function randomArenaPosition(y) {
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

  function randomTargetHp() {
    return MIN_WEAPON_DAMAGE + Math.floor(Math.random() * (MAX_WEAPON_DAMAGE - MIN_WEAPON_DAMAGE + 1));
  }

  // Each block gets its own horizontal drift and vertical bob speed/amplitude,
  // rolled once at spawn and held fixed for that block's lifetime -- only
  // re-rolled when it's destroyed and respawns elsewhere. Sine-based drift
  // around a fixed "home" point (rather than bouncing off boundaries) avoids
  // ever needing collision logic against the arena's walls/terrain.
  function randomTargetMotion() {
    return {
      horizSpeed: 0.3 + Math.random() * 1.5,
      horizRadius: 1 + Math.random() * 3,
      vertSpeed: 0.4 + Math.random() * 2.0,
      vertRadius: 0.3 + Math.random() * 0.9,
      phase: Math.random() * Math.PI * 2
    };
  }

  function randomTargetSize() {
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
  function createPracticeTargets() {
    for (let i = 0; i < TARGET_COUNT; i++) {
      targets.push(spawnTarget());
    }
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
  let currentWeapon = 1;
  let lastShotTime = 0;
  let ammo = WEAPONS.map((w) => w.magSize);
  let reloading = false;
  let reloadEndTime = 0;

  function requestReload() {
    if (reloading || ammo[currentWeapon] >= WEAPONS[currentWeapon].magSize) return;
    reloading = true;
    aiming = false; // lower the weapon to reload, same as most FPS games
    reloadEndTime = performance.now() + WEAPONS[currentWeapon].reloadMs;
    updateAmmoHud();
    if (connected) ws.send(JSON.stringify({ type: "reload" }));
  }

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

  const gunModels = [createGunModel(0, pistolMats), createGunModel(1), createGunModel(2, sniperMats), createGunModel(3)];
  gunModels.forEach((g, i) => {
    g.position.set(GUN_BASE_POS.x, GUN_BASE_POS.y, GUN_BASE_POS.z);
    g.rotation.y = THREE.MathUtils.degToRad(8);
    g.visible = i === currentWeapon;
    camera.add(g);
  });

  let gunIdleTime = 0;
  let gunWalkTime = 0;
  let gunMovingFactor = 0;
  let gunRecoilTimer = 0;

  function triggerGunFire() {
    gunRecoilTimer = GUN_RECOIL_DURATION;
  }

  function selectWeapon(idx) {
    if (idx === currentWeapon || idx < 0 || idx >= WEAPONS.length) return;
    gunModels[currentWeapon].visible = false;
    currentWeapon = idx;
    gunModels[currentWeapon].visible = true;
    reloading = false; // switching holsters any in-progress reload, same as the server
    aiming = false; // re-raise and re-aim fresh each time you switch weapons
    adsBlend = 0; // snap out of any aimed pose/zoom immediately, don't ease out
    scopeOverlayEl.classList.remove("show"); // force the scope off the instant you switch away
    adsCrosshairEl.classList.remove("show");
    updateWeaponHud();
    updateAmmoHud();
    if (connected) ws.send(JSON.stringify({ type: "weapon", id: idx }));
  }

  let gunSprintFactor = 0;

  function updateGunModel(dt, moving, adsBlendAmount, sprinting) {
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

    const activeGun = gunModels[currentWeapon];
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
    activeGun.visible = !(WEAPONS[currentWeapon].scope && adsBlendAmount > 0.5);
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

  const MAX_HP = 100;
  let myHp = MAX_HP;

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
  const modeSelect = document.getElementById("modeSelect");
  const pvpSetup = document.getElementById("pvpSetup");
  const trainingSetup = document.getElementById("trainingSetup");
  const startBtn = document.getElementById("startBtn");
  const startTrainingBtn = document.getElementById("startTrainingBtn");
  const playerNameInput = document.getElementById("nameInput");
  const canvas = renderer.domElement;

  let gameMode = null; // "pvp" | "training"

  document.getElementById("pvpModeBtn").addEventListener("click", () => {
    modeSelect.hidden = true;
    pvpSetup.hidden = false;
  });
  document.getElementById("trainingModeBtn").addEventListener("click", () => {
    modeSelect.hidden = true;
    trainingSetup.hidden = false;
  });

  const keys = Object.create(null);
  window.addEventListener("keydown", (e) => {
    keys[e.code] = true;
    if (isLocked()) {
      if (e.code === "Digit1") selectWeapon(0);
      else if (e.code === "Digit2") selectWeapon(1);
      else if (e.code === "Digit3") selectWeapon(2);
      else if (e.code === "Digit4") selectWeapon(3);
      else if (e.code === "KeyR") requestReload();
    }
  });
  window.addEventListener("keyup", (e) => { keys[e.code] = false; });

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
  const GROUND_PROBE_UP = 0.4;   // cast the ray from this far above current feet
  const GROUND_SNAP_MARGIN = 0.1; // a little slack beyond this frame's fall distance
  const DOWN = new THREE.Vector3(0, -1, 0);
  const groundProbeOrigin = new THREE.Vector3();
  const groundRaycaster = new THREE.Raycaster();
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

  // Casts down from just above the player's current feet, far enough to
  // cover however much they're about to fall/step down this frame -- not
  // from high above, which would incorrectly detect a floor several
  // stories up as "the ground" while standing on the level beneath it.
  function findGroundY(feetY, fallDistance) {
    groundProbeOrigin.set(camera.position.x, feetY + GROUND_PROBE_UP, camera.position.z);
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
  const PLAYER_HEIGHT = 1.8;
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

  function collidesWithRamps(x, feetY, headY, z) {
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

  function collidesAt(x, feetY, z) {
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
  function findCeilingY(x, z, headY, proposedHeadY) {
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

  function isLocked() {
    return document.pointerLockElement === canvas;
  }

  startBtn.addEventListener("click", () => {
    if (!started) {
      started = true;
      gameMode = "pvp";
      const name = (playerNameInput.value || "").trim() || `Player${Math.floor(Math.random() * 1000)}`;
      connect(name);
      document.getElementById("hud").hidden = false;
      document.getElementById("healthPanel").hidden = false;
      if (audioCtx.state === "suspended") audioCtx.resume();
    }
    canvas.requestPointerLock();
  });

  startTrainingBtn.addEventListener("click", () => {
    if (!started) {
      started = true;
      gameMode = "training";
      // No connect() call at all -- Aim Training never opens a WebSocket,
      // so there is no way for another player to ever appear here.
      createPracticeTargets();
      document.getElementById("trainingHud").hidden = false;
      if (audioCtx.state === "suspended") audioCtx.resume();
    }
    canvas.requestPointerLock();
  });

  document.addEventListener("pointerlockchange", () => {
    overlay.hidden = isLocked();
    if (!isLocked()) {
      mouseHeld = false;
      aiming = false;
    }
  });

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
    } else if (e.button === 2) {
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
  const scoreEl = document.getElementById("score");
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

  function shoot() {
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

    const aimDir = getForward();
    const weaponSpec = WEAPONS[currentWeapon];
    const spreadDegrees = weaponSpec.hipSpread + (weaponSpec.adsSpread - weaponSpec.hipSpread) * adsBlend;
    const forward = applySpread(aimDir, spreadDegrees);
    raycaster.set(camera.position, forward);

    const hits = raycaster.intersectObjects(targets.map((t) => t.mesh));
    if (hits.length > 0) {
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
        scoreEl.textContent = String(score);
      } else {
        // Briefly glow so a hit that didn't destroy the block still reads
        // as "damaged" rather than looking like nothing happened.
        target.mesh.material.emissive.setHex(0x554400);
        target.flashUntil = performance.now() + 120;
      }
    }

    if (ammo[currentWeapon] <= 0) requestReload();

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
  const crosshairEl = document.getElementById("crosshair");
  const adsCrosshairEl = document.getElementById("adsCrosshair");
  const scopeOverlayEl = document.getElementById("scopeOverlay");
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
    aiming = false;
    deathOverlay.classList.add("show");
  }

  function clearLocalDeath(pos) {
    isDead = false;
    deathOverlay.classList.remove("show");
    if (pos) camera.position.set(pos[0], pos[1], pos[2]);
    verticalVelocity = 0;
    grounded = true;
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
  const healthEl = document.getElementById("health");
  const healthBarEl = document.getElementById("healthBar");
  const weaponNameEl = document.getElementById("weaponName");
  const weaponDamageEl = document.getElementById("weaponDamage");
  const ammoCountEl = document.getElementById("ammoCount");
  const ammoMaxEl = document.getElementById("ammoMax");
  const reloadIndicatorEl = document.getElementById("reloadIndicator");

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

  function updateHealthHud() {
    const hp = Math.max(0, myHp);
    healthEl.textContent = String(hp);
    healthBarEl.style.width = `${(hp / MAX_HP) * 100}%`;
    healthBarEl.style.background = hp > 50 ? "#5ec25e" : hp > 25 ? "#e0b93c" : "#d4433c";
  }

  function updateAmmoHud() {
    ammoCountEl.textContent = String(Math.max(0, ammo[currentWeapon]));
    ammoMaxEl.textContent = String(WEAPONS[currentWeapon].magSize);
    reloadIndicatorEl.classList.toggle("hidden", !reloading);
  }

  function updateWeaponHud() {
    const w = WEAPONS[currentWeapon];
    weaponNameEl.textContent = w.name;
    weaponDamageEl.textContent = String(w.damage);
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
        const surfaceY = findGroundY(feetY, fallDistance);
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
    scopeOverlayEl.classList.toggle("show", scopedIn);
    // Pistol/rifle/SMG get a tighter precision reticle once mostly raised
    // into their ADS pose; the sniper uses its scope overlay instead, so
    // it's deliberately excluded here.
    const showAdsCrosshair = !!(aiming && !activeWeapon.scope && adsBlend > 0.4);
    adsCrosshairEl.classList.toggle("show", showAdsCrosshair);
    crosshairEl.classList.toggle("hidden", aiming);
    // Visualizes the current weapon's hip-fire bloom -- wider gap = less
    // accurate. Only actually visible while not aiming (the crosshair
    // hides instantly otherwise), so this doesn't need to track adsBlend.
    // spreadDegreesToPixels() is the SAME angle->pixel conversion used by
    // shot spread itself, so this is an honest picture: a target that
    // visually fills this gap genuinely has good hit odds, not just a
    // vaguely-proportional decoration.
    crosshairEl.style.setProperty("--gap", spreadDegreesToPixels(activeWeapon.hipSpread) + "px");

    updateGunModel(dt, moving, adsBlend, sprinting);
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
