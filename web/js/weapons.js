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
// sprintMult: how much faster than walking Shift-sprint is with this weapon out. Smaller
// magazine = lighter loadout = faster sprint (walking speed is the same for everyone).
// Mirrored in GameServer.java (WEAPON_SPRINT_MULT) and the desktop Weapons.SPRINT_MULT.
export const WEAPONS = [
  { name: "Pistol", damage: 20, cooldown: 150, magSize: 8, reloadMs: 1000,
    adsFov: 55, adsSpeed: 12, adsMoveMult: 0.8, sprintMult: 1.65, hipSpread: 1.2, adsSpread: 0.1 },
  { name: "Rifle", damage: 34, cooldown: 300, magSize: 24, reloadMs: 1600,
    adsFov: 45, adsSpeed: 9, adsMoveMult: 0.7, sprintMult: 1.5, hipSpread: 3.0, adsSpread: 0.1 },
  { name: "Sniper", damage: 100, cooldown: 1000, magSize: 5, reloadMs: 2200,
    adsFov: 15, adsSpeed: 6, adsMoveMult: 0.35, sprintMult: 1.75, scope: true, hipSpread: 6.0, adsSpread: 0.05 },
  { name: "SMG", damage: 14, cooldown: 100, magSize: 20, reloadMs: 1300, automatic: true,
    adsFov: 58, adsSpeed: 14, adsMoveMult: 0.85, sprintMult: 1.55, hipSpread: 1.8, adsSpread: 0.15 },
  // Secondary melee weapon, available to every class -- no ammo/ADS, just
  // a short-range one-shot swing. Not one of the loadout cards; equipped
  // via mouse-wheel toggle against whatever primary was actually picked.
  { name: "Knife", damage: 100, cooldown: 600, melee: true, meleeRange: 2.2, sprintMult: 1.8 }
];
export const KNIFE_INDEX = WEAPONS.length - 1;
