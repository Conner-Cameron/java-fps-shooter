import { WEAPONS } from "./weapons.js";

const audioCtx = new (window.AudioContext || window.webkitAudioContext)();

// Gunshot + reload sound effects -- synthesized the same way as the hit
// tick above (raw sample buffers, no audio files) so every weapon has a
// distinct voice. Each shot plays once per bullet actually fired (called
// from the same spot in shoot() where ammo is decremented), so holding
// the SMG's trigger down re-triggers it at the weapon's real fire rate.
const WEAPON_SOUND_PROFILES = [
  { duration: 0.09, lowFreq: 90, lowDecay: 70, snapDecay: 220, noiseMix: 0.65, subMix: 0.5 }, // Pistol
  { duration: 0.13, lowFreq: 70, lowDecay: 45, snapDecay: 150, noiseMix: 0.6, subMix: 0.65 }, // Rifle
  { duration: 0.28, lowFreq: 55, lowDecay: 12, snapDecay: 90, noiseMix: 0.55, subMix: 0.85 }, // Sniper
  { duration: 0.06, lowFreq: 110, lowDecay: 90, snapDecay: 300, noiseMix: 0.7, subMix: 0.4 }  // SMG
];

export function playGunshot(weaponIdx) {
  const profile = WEAPON_SOUND_PROFILES[weaponIdx];
  const sampleRate = audioCtx.sampleRate;
  const frameCount = Math.floor(sampleRate * profile.duration);
  const buffer = audioCtx.createBuffer(1, frameCount, sampleRate);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < frameCount; i++) {
    const t = i / sampleRate;
    const snapEnv = Math.exp(-profile.snapDecay * t);
    const noise = (Math.random() * 2 - 1) * snapEnv;
    const subEnv = Math.exp(-profile.lowDecay * t);
    const sub = Math.sin(2 * Math.PI * profile.lowFreq * t) * subEnv;
    data[i] = Math.max(-1, Math.min(1, noise * profile.noiseMix + sub * profile.subMix));
  }
  const source = audioCtx.createBufferSource();
  source.buffer = buffer;
  source.connect(audioCtx.destination);
  source.start();
}

// Reload sound: a few mechanical "clunks" (mag-out, mag-in, chamber/bolt)
// spread across a duration derived from the weapon's magazine size --
// fewer rounds means a slower, more deliberate reload (the sniper's
// bolt-action feels the longest; the rifle's quick mag-swap the shortest).
// Each mechanical event layers three things: a burst of noise (the
// metallic "snap" of the transient), a fast-damped high tone (the brief
// resonant "ring" real metal-on-metal/polymer impacts have), and an
// optional low sine "thunk" for the weight of a part settling -- a pure
// decaying tone alone reads as a synth beep, not hardware.
// A shared burst of raw white noise, reused (never mutated) as the raw
// material for every mechanical event below. Real weapon-handling sounds
// are broadband impacts, not musical pitches, so every event here is
// this noise pushed through a real BiquadFilterNode -- a resonant
// bandpass reads as a metal/polymer "clack", a lowpass reads as a dull
// "thump" -- instead of a decaying sine tone, which is what made the
// previous version sound like a synth pluck rather than hardware.
const mechanicalNoiseBuffer = (() => {
  const frameCount = Math.floor(audioCtx.sampleRate * 0.5);
  const buffer = audioCtx.createBuffer(1, frameCount, audioCtx.sampleRate);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < frameCount; i++) data[i] = Math.random() * 2 - 1;
  return buffer;
})();

// filterType "lowpass" (freq ~150-250) reads as a dull body thump;
// "bandpass" with a higher Q reads as a sharper metallic clack/knock.
function playFilteredClick(startTime, { filterType, freq, q, gain, decay }) {
  const source = audioCtx.createBufferSource();
  source.buffer = mechanicalNoiseBuffer;
  const filter = audioCtx.createBiquadFilter();
  filter.type = filterType;
  filter.frequency.value = freq * (0.94 + Math.random() * 0.12); // slight jitter so repeats don't sound identical
  filter.Q.value = q;
  const gainNode = audioCtx.createGain();
  gainNode.gain.setValueAtTime(gain, startTime);
  gainNode.gain.exponentialRampToValueAtTime(0.001, startTime + decay);
  source.connect(filter);
  filter.connect(gainNode);
  gainNode.connect(audioCtx.destination);
  source.start(startTime);
  source.stop(startTime + decay + 0.05);
}

// Reload sound: mag drops free -> fresh mag slaps home -> bolt/slide is
// released -> bolt/slide slams into battery, spaced across a duration
// derived from magazine size -- fewer rounds means a slower, more
// deliberate reload (bolt-action sniper longest, rifle mag-swap shortest).
export function playReloadSound(weaponIdx) {
  const magSize = WEAPONS[weaponIdx].magSize;
  const durationSec = Math.max(0.3, Math.min(1.0, 1.0 - (magSize - 5) / 19 * 0.6));
  const now = audioCtx.currentTime;

  const magOutAt = now + 0.04 * durationSec;
  const magInAt = now + 0.55 * durationSec;
  const boltReleaseAt = now + 0.78 * durationSec;
  const boltHomeAt = boltReleaseAt + 0.035;

  playFilteredClick(magOutAt, { filterType: "lowpass", freq: 220, q: 0.7, gain: 0.5, decay: 0.09 }); // mag drops free

  playFilteredClick(magInAt, { filterType: "bandpass", freq: 750, q: 1.4, gain: 0.7, decay: 0.05 }); // fresh mag slaps home
  playFilteredClick(magInAt, { filterType: "lowpass", freq: 180, q: 0.7, gain: 0.4, decay: 0.08 });

  playFilteredClick(boltReleaseAt, { filterType: "bandpass", freq: 2400, q: 7, gain: 0.5, decay: 0.03 }); // bolt/slide released

  playFilteredClick(boltHomeAt, { filterType: "bandpass", freq: 1300, q: 5, gain: 0.6, decay: 0.045 }); // bolt slams into battery
  playFilteredClick(boltHomeAt, { filterType: "lowpass", freq: 200, q: 0.7, gain: 0.35, decay: 0.06 });
}

// Knife swing: a quick bandpass-filtered noise sweep (high frequency
// ramping down fast) rather than any gunshot/mechanical-clack sound --
// reads as air being cut, not a weapon firing. Whether it actually lands
// is signaled separately by the normal hit-tick/marker via resolveTargetHit.
export function playMeleeSwing() {
  const source = audioCtx.createBufferSource();
  source.buffer = mechanicalNoiseBuffer;
  const filter = audioCtx.createBiquadFilter();
  filter.type = "bandpass";
  filter.Q.value = 1.1;
  const now = audioCtx.currentTime;
  filter.frequency.setValueAtTime(2400, now);
  filter.frequency.exponentialRampToValueAtTime(350, now + 0.12);
  const gainNode = audioCtx.createGain();
  gainNode.gain.setValueAtTime(0.5, now);
  gainNode.gain.exponentialRampToValueAtTime(0.001, now + 0.14);
  source.connect(filter);
  filter.connect(gainNode);
  gainNode.connect(audioCtx.destination);
  source.start(now);
  source.stop(now + 0.18);
}

export function playHitTick() {
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

export function resumeAudio() {
  if (audioCtx.state === "suspended") audioCtx.resume();
}
