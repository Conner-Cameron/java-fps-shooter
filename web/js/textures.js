import * as THREE from "three";
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

// SMG skin, after the concept art (Downloads/SMG.jpg): a charcoal body with angular chamfered
// panels, a chevron-ended amber band with black ticks, amber rails and caret glyphs, a vent, and scuffs.
// Mirrored in WorldTextures.paintSmgSkin on desktop; keep the two in step.
function chamferPath(ctx, x, y, w, h, c) {
  ctx.beginPath();
  ctx.moveTo(x + c, y);
  ctx.lineTo(x + w - c, y);
  ctx.lineTo(x + w, y + c);
  ctx.lineTo(x + w, y + h - c);
  ctx.lineTo(x + w - c, y + h);
  ctx.lineTo(x + c, y + h);
  ctx.lineTo(x, y + h - c);
  ctx.lineTo(x, y + c);
  ctx.closePath();
}

function makeSmgSkinTexture() {
  return makeCanvasTexture(256, (ctx, size) => {
    // Lit from above: lighter charcoal at the top, near-black at the bottom.
    const body = ctx.createLinearGradient(0, 0, 0, size);
    body.addColorStop(0, "#3d4149");
    body.addColorStop(1, "#1d1f24");
    ctx.fillStyle = body;
    ctx.fillRect(0, 0, size, size);
    for (let i = 0; i < 1500; i++) {
      ctx.fillStyle = Math.random() < 0.5 ? "rgba(0,0,0,0.12)" : "rgba(255,255,255,0.05)";
      ctx.fillRect(Math.random() * size, Math.random() * size, 1 + Math.random() * 2, 1 + Math.random() * 2);
    }
    // Angular panels: a recessed face with a light outer edge and a dark inner bevel.
    const panels = [[8, 8, 120, 70, 10], [136, 10, 112, 64, 12], [8, 92, 84, 58, 8],
                    [104, 84, 144, 72, 12], [8, 164, 104, 80, 14], [124, 166, 124, 78, 10]];
    for (const [x, y, w, h, c] of panels) {
      chamferPath(ctx, x, y, w, h, c);
      ctx.fillStyle = "#2a2d33";
      ctx.fill();
      ctx.strokeStyle = "#646b76";
      ctx.lineWidth = 2;
      ctx.stroke();
      chamferPath(ctx, x + 3, y + 3, w - 6, h - 6, c - 2);
      ctx.strokeStyle = "rgba(0,0,0,0.5)";
      ctx.lineWidth = 2;
      ctx.stroke();
    }
    // Vent slits in the lower right.
    ctx.fillStyle = "#0c0d0f";
    for (let i = 0; i < 7; i++) ctx.fillRect(140, 180 + i * 9, 90, 3);
    // Main amber band: chevron ends, with black ticks across it.
    ctx.beginPath();
    ctx.moveTo(18, 112);
    ctx.lineTo(206, 112);
    ctx.lineTo(228, 122);
    ctx.lineTo(206, 132);
    ctx.lineTo(18, 132);
    ctx.closePath();
    ctx.fillStyle = "#f0a21c";
    ctx.fill();
    ctx.fillStyle = "#1a1b1f";
    for (let x = 34; x < 200; x += 22) ctx.fillRect(x, 116, 6, 12);
    // Thin amber rails.
    ctx.fillStyle = "#e6951a";
    ctx.fillRect(150, 38, 90, 3);
    ctx.fillRect(18, 200, 84, 3);
    // Caret glyphs.
    ctx.strokeStyle = "#f0a21c";
    ctx.lineWidth = 3;
    for (const x of [22, 38, 54]) {
      ctx.beginPath();
      ctx.moveTo(x, 184);
      ctx.lineTo(x + 6, 176);
      ctx.lineTo(x + 12, 184);
      ctx.stroke();
    }
    // Scuffs catching the light.
    ctx.strokeStyle = "rgba(255,255,255,0.18)";
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(160, 70); ctx.lineTo(210, 64); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(60, 150); ctx.lineTo(96, 146); ctx.stroke();
  });
}
export const smgSkinTexture = makeSmgSkinTexture();

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

// AR skin, after the concept art (Downloads/AR.jpg): blue-black armor built from faceted plates with
// thin dark seams, slanted vent slots, and a few small red accents. Mirrored in WorldTextures.paintArSkin.
const AR_PLATES = [
  [[6, 20], [120, 8], [150, 40], [120, 70], [10, 78]],
  [[140, 14], [248, 22], [246, 78], [168, 70], [140, 46]],
  [[6, 96], [90, 100], [110, 140], [70, 176], [8, 160]],
  [[120, 100], [246, 92], [236, 150], [150, 160], [118, 130]],
  [[10, 190], [92, 182], [124, 214], [100, 250], [14, 246]],
  [[140, 186], [240, 176], [248, 240], [160, 250], [130, 220]]
];

function makeArSkinTexture() {
  return makeCanvasTexture(256, (ctx, size) => {
    const body = ctx.createLinearGradient(0, 0, 0, size);
    body.addColorStop(0, "#5f646e");
    body.addColorStop(1, "#3f434b");
    ctx.fillStyle = body;
    ctx.fillRect(0, 0, size, size);
    for (let i = 0; i < 1200; i++) {
      ctx.fillStyle = Math.random() < 0.5 ? "rgba(0,0,0,0.14)" : "rgba(255,255,255,0.04)";
      ctx.fillRect(Math.random() * size, Math.random() * size, 1 + Math.random() * 2, 1 + Math.random() * 2);
    }
    // Faceted plates: a lit top edge, a dark seam all round.
    for (const plate of AR_PLATES) {
      ctx.beginPath();
      plate.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
      ctx.closePath();
      ctx.fillStyle = "#4f545d";
      ctx.fill();
      ctx.strokeStyle = "#1c1e22";
      ctx.lineWidth = 3;
      ctx.stroke();
      ctx.strokeStyle = "#7d848f";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(plate[0][0], plate[0][1]);
      ctx.lineTo(plate[1][0], plate[1][1]);
      ctx.stroke();
    }
    // Slanted vent slots in the mid plate.
    ctx.fillStyle = "#0b0c0e";
    for (let i = 0; i < 6; i++) {
      const x = 170 + i * 12;
      ctx.beginPath();
      ctx.moveTo(x, 104);
      ctx.lineTo(x + 8, 104);
      ctx.lineTo(x - 2, 116);
      ctx.lineTo(x - 10, 116);
      ctx.closePath();
      ctx.fill();
    }
    // Small red accents.
    ctx.fillStyle = "#b3262e";
    ctx.fillRect(14, 206, 40, 4);
    ctx.fillRect(150, 130, 26, 3);
    ctx.fillRect(206, 232, 18, 3);
    // Wear.
    ctx.strokeStyle = "rgba(255,255,255,0.12)";
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(40, 40); ctx.lineTo(96, 34); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(180, 220); ctx.lineTo(226, 214); ctx.stroke();
  });
}
export const arSkinTexture = makeArSkinTexture();
