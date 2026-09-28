// Pre-game menu flow: mode select -> loadout -> mode setup -> play.
//
// Every screen is a <div> inside #overlay; exactly one is visible at a time.
// To add a screen (settings, keybinds, customization, ...): give it an
// element in index.html, register its id in SCREENS below, and route to it
// with showScreen(). game.js never touches the menu DOM -- it only hears
// decisions ("loadout chosen", "start PvP"), via the callbacks passed to
// initMenus().
import { WEAPONS } from "./weapons.js";
import { fetchLeaderboard } from "./net.js";

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
  if (name === "pvp") refreshLeaderboard();
}

// Lifetime top players, shown under the PvP setup form (hidden until the server has some).
async function refreshLeaderboard() {
  const box = document.getElementById("leaderboard");
  const rows = (await fetchLeaderboard()).slice(0, 5);
  box.hidden = rows.length === 0;
  const title = document.createElement("h2");
  title.textContent = "All-time leaders";
  box.replaceChildren(
    title,
    ...rows.map((r) => {
      const row = document.createElement("div");
      row.className = "lbRow";
      for (const [cls, text] of [["lbName", r.name], ["lbNum", `${r.kills} kills`], ["lbNum", `${r.wins} wins`]]) {
        const cell = document.createElement("span");
        cell.className = cls;
        cell.textContent = text;
        row.appendChild(cell);
      }
      return row;
    })
  );
}

// Shows (or, with no text, clears) the problem line on the PvP setup screen.
export function setPvpError(text) {
  const el = document.getElementById("pvpError");
  el.textContent = text || "";
  el.hidden = !text;
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
 *  - onStartPvp(name, room):     "Click to Play" on the PvP setup screen; room = { mode, code }
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
  const codeInput = document.getElementById("roomCodeInput");
  const modeHint = document.getElementById("roomModeHint");
  const HINTS = {
    quick: "Jump into the fullest public match with room for you",
    create: "Start a private room and share its code with friends",
    code: "Enter the 4-letter code a friend gave you"
  };
  const selectedRoomMode = () => document.querySelector('input[name="roomMode"]:checked').value;

  document.querySelectorAll('input[name="roomMode"]').forEach((radio) => {
    radio.addEventListener("change", () => {
      const mode = selectedRoomMode();
      codeInput.hidden = mode !== "code";
      modeHint.textContent = HINTS[mode];
      setPvpError("");
      if (mode === "code") codeInput.focus();
    });
  });

  document.getElementById("startBtn").addEventListener("click", () => {
    const name = (nameInput.value || "").trim() || `Player${Math.floor(Math.random() * 1000)}`;
    const mode = selectedRoomMode();
    const code = codeInput.value.trim().toUpperCase();
    if (mode === "code" && code.length !== 4) {
      setPvpError("Enter the 4-letter room code");
      return;
    }
    setPvpError("");
    onStartPvp(name, { mode, code });
  });
  document.getElementById("startTrainingBtn").addEventListener("click", () => onStartTraining());
  document.getElementById("resumeBtn").addEventListener("click", () => onResume());
  document.getElementById("leaveBtn").addEventListener("click", () => onLeave());
}
