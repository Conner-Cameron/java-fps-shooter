// WebSocket transport for PvP: connection lifecycle and JSON framing only.
// What each server message *means* is game logic and lives in game.js; this
// module just delivers parsed messages and sends objects as JSON.
let ws = null;
let connected = false;

function wsUrl() {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  return `${proto}//${location.host}/ws`;
}

export function isConnected() {
  return connected;
}

// No-op until the socket is open (or after it closes).
export function sendMessage(obj) {
  if (connected) ws.send(JSON.stringify(obj));
}

/**
 * Opens the connection and joins under `playerName`.
 * onMessage(msg) gets every parsed server message; onStatus(text) gets
 * connection problems ("Disconnected from server", "Connection error").
 */
// Leaves the match on purpose: the server sees the socket close and tells the others "playerLeft".
export function disconnect() {
  if (!ws) return;
  ws.onclose = null; // an intentional close isn't a "Disconnected from server" error
  ws.onerror = null;
  ws.onmessage = null;
  ws.close();
  ws = null;
  connected = false;
}

export function connect(playerName, onMessage, onStatus) {
  ws = new WebSocket(wsUrl());
  ws.onopen = () => {
    connected = true;
    sendMessage({ type: "join", name: playerName });
  };
  ws.onclose = () => {
    connected = false;
    onStatus("Disconnected from server");
  };
  ws.onerror = () => onStatus("Connection error");
  ws.onmessage = (event) => {
    let msg;
    try {
      msg = JSON.parse(event.data);
    } catch (e) {
      return;
    }
    onMessage(msg);
  };
}
