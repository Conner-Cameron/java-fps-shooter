// Procedural canvas textures (no image assets) shared across the world, targets, player models and guns.
// Procedural textures (canvas-painted, no image assets) shared across
// walls, targets, terrain, player models, and the gun -- each is
// generated once and reused via tiledClone() with per-object repeat
// counts, mirroring the desktop build's "one texture, many tints"
// approach.
// ================================================================
export function makeCanvasTexture(size, draw) {
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  draw(canvas.getContext("2d"), size);
  const tex = new THREE.CanvasTexture(canvas);
  tex.wrapS = THREE.RepeatWrapping;
  tex.wrapT = THREE.RepeatWrapping;
  return tex;
}

export function tiledClone(baseTexture, repeatX, repeatY) {
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

export const metalTexture = makeMetalTexture();
export const rockTexture = makeRockTexture();
export const barkTexture = makeBarkTexture();
export const foliageTexture = makeFoliageTexture();
export const hazardTexture = makeHazardTexture();

// ---- Procedurally-painted ground texture (no image assets used) ----
export function makeGroundTexture() {
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
