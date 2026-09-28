// Pre-game menu flow: mode select -> loadout -> mode setup -> play.
//
// Every screen is a <div> inside #overlay; exactly one is visible at a time.
// To add a screen (settings, keybinds, customization, ...): give it an
// element in index.html, register its id in SCREENS below, and route to it
// with showScreen(). game.js never touches the menu DOM -- it only hears
// decisions ("loadout chosen", "start PvP"), via the callbacks passed to
// initMenus().
import { WEAPONS } from "./weapons.js";

const SCREENS = {
  mode: "modeSelect",
  loadout: "weaponSelect",
  pvp: "pvpSetup",
  training: "trainingSetup",
  pause: "pauseMenu"
};

const overlay = document.getElementById("overlay");
const screenEls = {};
for (const [name, id] of Object.entries(SCREENS)) screenEls[name] = document.getElementById(id);

export function showScreen(name) {
  for (const [key, el] of Object.entries(screenEls)) el.hidden = key !== name;
}

export function isScreenVisible(name) {
  return !screenEls[name].hidden;
}

// The whole menu layer is hidden while playing (pointer locked).
export function setOverlayVisible(visible) {
  overlay.hidden = !visible;
}

/**
 * Wires up the menu buttons.
 *  - onLoadoutChosen(weaponIdx): a weapon card was clicked
 *  - onStartPvp(name):           "Click to Play" on the PvP setup screen
 *  - onStartTraining():          "Click to Play" on the Aim Training setup screen
 *  - onResume():                 "Resume" on the pause screen
 *  - onLeave():                  "Leave Game" on the pause screen
 */
export function initMenus({ onLoadoutChosen, onStartPvp, onStartTraining, onResume, onLeave }) {
  let pendingMode = null; // which mode's setup screen follows the loadout screen

  document.getElementById("pvpModeBtn").addEventListener("click", () => {
    pendingMode = "pvp";
    showScreen("loadout");
  });
  document.getElementById("trainingModeBtn").addEventListener("click", () => {
    pendingMode = "training";
    showScreen("loadout");
  });

  // Damage / magazine numbers come straight from WEAPONS so the cards can't drift from the game.
  document.querySelectorAll(".weaponCardStats").forEach((el) => {
    const w = WEAPONS[parseInt(el.dataset.weaponStats, 10)];
    el.textContent = `${w.damage} damage  •  ${w.magSize} rounds`;
  });

  document.querySelectorAll(".weaponCard").forEach((card) => {
    card.addEventListener("click", () => {
      onLoadoutChosen(parseInt(card.dataset.weapon, 10));
      showScreen(pendingMode);
    });
  });

  const nameInput = document.getElementById("nameInput");
  document.getElementById("startBtn").addEventListener("click", () => {
    const name = (nameInput.value || "").trim() || `Player${Math.floor(Math.random() * 1000)}`;
    onStartPvp(name);
  });
  document.getElementById("startTrainingBtn").addEventListener("click", () => onStartTraining());
  document.getElementById("resumeBtn").addEventListener("click", () => onResume());
  document.getElementById("leaveBtn").addEventListener("click", () => onLeave());
}
