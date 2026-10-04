// In-game HUD: every function here only renders values it is handed into
// the page's HUD elements -- no game state lives in this module, so game.js
// stays the single owner of state and the HUD stays easy to restyle/extend.
import { playHitTick } from "./audio.js";

const $ = (id) => document.getElementById(id);

const hitMarkerEl = $("hitmarker");
const crosshairEl = $("crosshair");
const adsCrosshairEl = $("adsCrosshair");
const redDotEl = $("redDot");
const scopeOverlayEl = $("scopeOverlay");
const deathOverlayEl = $("deathOverlay");
const matchBannerEl = $("matchBanner");
const killsEl = $("kills");
const killLimitEl = $("killLimit");
const playerCountEl = $("playerCount");
const scoreboardEl = $("scoreboard");
const statusEl = $("status");
const healthEl = $("health");
const healthBarEl = $("healthBar");
const weaponNameEl = $("weaponName");
const weaponDamageEl = $("weaponDamage");
const ammoCountEl = $("ammoCount");
const ammoMaxEl = $("ammoMax");
const reloadIndicatorEl = $("reloadIndicator");
const trainingScoreEl = $("score");

// ---- hit feedback (matches the desktop build's MW2-style feel) ----
let hitMarkerTimeout = null;

export function showHitMarker() {
  hitMarkerEl.classList.remove("show");
  // Force reflow so the CSS transition restarts on rapid consecutive hits.
  void hitMarkerEl.offsetWidth;
  hitMarkerEl.classList.add("show");
  clearTimeout(hitMarkerTimeout);
  hitMarkerTimeout = setTimeout(() => hitMarkerEl.classList.remove("show"), 200);
  playHitTick();
}

// ---- crosshair / scope ----
export function setScopeVisible(visible) {
  scopeOverlayEl.classList.toggle("show", visible);
}

export function setAdsCrosshairVisible(visible) {
  adsCrosshairEl.classList.toggle("show", visible);
}

export function setRedDotVisible(visible) {
  redDotEl.classList.toggle("show", visible);
}

export function setCrosshairHidden(hidden) {
  crosshairEl.classList.toggle("hidden", hidden);
}

export function setCrosshairGap(pixels) {
  crosshairEl.style.setProperty("--gap", pixels + "px");
}

// Snap all aim-down-sights UI off at once (weapon switch).
export function clearAimUi() {
  scopeOverlayEl.classList.remove("show");
  adsCrosshairEl.classList.remove("show");
  redDotEl.classList.remove("show");
}

// ---- death / match banner ----
export function setDeathOverlay(visible) {
  deathOverlayEl.classList.toggle("show", visible);
}

let bannerTimeout = null;

export function showBanner(text, durationMs = 6000) {
  matchBannerEl.textContent = text;
  matchBannerEl.classList.add("show");
  clearTimeout(bannerTimeout);
  bannerTimeout = setTimeout(hideBanner, durationMs);
}

export function hideBanner() {
  matchBannerEl.classList.remove("show");
}

// ---- status line, scoreboard, health, weapon/ammo ----
export function setStatus(text) {
  statusEl.textContent = text;
}

// Shows the PvP room code in the HUD (null hides it).
export function setRoomCode(code) {
  $("roomInfo").hidden = !code;
  $("roomCode").textContent = code || "";
}

// Names come from other players: build the rows with textContent, never as markup.
export function renderScoreboard({ myKills, killLimit, playerCount, rows }) {
  killsEl.textContent = String(myKills);
  killLimitEl.textContent = String(killLimit);
  playerCountEl.textContent = String(playerCount);
  scoreboardEl.replaceChildren(
    ...rows.map((r) => {
      const row = document.createElement("div");
      row.textContent = `${r.name}: ${r.kills}`;
      return row;
    })
  );
}

export function renderHealth(hp, maxHp) {
  const shown = Math.max(0, hp);
  healthEl.textContent = String(shown);
  healthBarEl.style.width = `${(shown / maxHp) * 100}%`;
  healthBarEl.style.background = shown > 50 ? "#5ec25e" : shown > 25 ? "#e0b93c" : "#d4433c";
}

export function renderWeapon(weapon) {
  weaponNameEl.textContent = weapon.name;
  weaponDamageEl.textContent = String(weapon.damage);
}

// Melee weapons have no ammo or reload -- show dashes instead.
export function renderAmmo(weapon, roundsLeft, reloading) {
  if (weapon.melee) {
    ammoCountEl.textContent = "—";
    ammoMaxEl.textContent = "—";
    reloadIndicatorEl.classList.add("hidden");
    return;
  }
  ammoCountEl.textContent = String(Math.max(0, roundsLeft));
  ammoMaxEl.textContent = String(weapon.magSize);
  reloadIndicatorEl.classList.toggle("hidden", !reloading);
}

export function renderTrainingScore(score) {
  trainingScoreEl.textContent = String(score);
}
