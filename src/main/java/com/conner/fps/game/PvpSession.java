package com.conner.fps.game;

import com.conner.fps.net.GameClient;
import com.conner.fps.util.Json;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side view of a PvP match: who's in it, scores, our own hitpoints,
 * and how each server message changes them (port of the web client's
 * handleServerMessage). Effects that belong to the game itself -- teleporting
 * the player, hit feedback, ammo/reload sync -- go out through {@link Hooks}.
 */
public final class PvpSession {
    public static final int MAX_HP = 100;
    /** Remote avatars are centered this far below the reported eye position. */
    public static final float EYE_OFFSET = 0.9f;

    public static final class Remote {
        public final Vector3f position = new Vector3f(0, 1.7f - EYE_OFFSET, 0);
        public final Vector3f target = new Vector3f(0, 1.7f - EYE_OFFSET, 0);
        public float yaw, targetYaw;
        public String name;
        public int kills;
        public boolean alive = true;
    }

    public interface Hooks {
        void teleport(float x, float y, float z);
        void hitFeedback();
        void ammo(int weapon, int rounds);
        void reload(int weapon, int durationMs);
        void died();
        void respawned(float x, float y, float z);
        void portalTeleport(float x, float y, float z);
        void banner(String text);
        void hideBanner();
    }

    private final GameClient client;
    private final String playerName;
    public final Map<Integer, Remote> remotes = new LinkedHashMap<>();
    public int myId = -1;
    public int killLimit = 10;
    public int myKills = 0;
    public int myHp = MAX_HP;
    public boolean dead = false;
    public String roomCode = "";
    public boolean roomPublic = true;
    /** Set when the server refused the join (unknown/full room); the game returns to the setup screen. */
    public String joinError = null;

    public PvpSession(GameClient client, String playerName) {
        this.client = client;
        this.playerName = playerName;
    }

    public GameClient client() {
        return client;
    }

    public String statusText() {
        if (client.isConnected() && myId >= 0) {
            return "Connected as " + playerName + (roomPublic ? "" : " - private room " + roomCode + ", share the code");
        }
        return client.status();
    }

    private Remote ensure(int id, String name) {
        Remote r = remotes.computeIfAbsent(id, k -> {
            Remote nr = new Remote();
            nr.name = "Player" + k;
            return nr;
        });
        if (name != null) r.name = name;
        return r;
    }

    /** Drains and applies everything the server has sent since the last frame. */
    public void poll(Hooks hooks) {
        Map<String, Object> msg;
        while ((msg = client.poll()) != null) handle(msg, hooks);
    }

    /** Feeds a synthetic server message through the normal handler (self-test only). */
    public void injectForTest(Map<String, Object> msg, Hooks hooks) {
        handle(msg, hooks);
    }

    private static int i(Object o) {
        return (int) Json.num(o, -1);
    }

    private static float[] pos(Object o) {
        return Json.floats(o);
    }

    private void handle(Map<String, Object> msg, Hooks hooks) {
        Object type = msg.get("type");
        if (!(type instanceof String)) return;
        switch ((String) type) {
            case "correct": {
                if (!dead) {
                    float[] p = pos(msg.get("pos"));
                    hooks.teleport(p[0], p[1], p[2]);
                }
                break;
            }
            case "welcome": {
                myId = i(msg.get("id"));
                killLimit = (int) Json.num(msg.get("killLimit"), 10);
                if (msg.get("room") instanceof String) roomCode = (String) msg.get("room");
                roomPublic = !Boolean.FALSE.equals(msg.get("roomPublic"));
                if (msg.get("pos") != null) {
                    float[] p = pos(msg.get("pos"));
                    hooks.teleport(p[0], p[1], p[2]);
                }
                for (Object o : Json.list(msg.get("players"))) {
                    Map<String, Object> pl = Json.obj(o);
                    Remote r = ensure(i(pl.get("id")), (String) pl.get("name"));
                    r.kills = i(pl.get("kills"));
                    float[] p = pos(pl.get("pos"));
                    r.target.set(p[0], p[1] - EYE_OFFSET, p[2]);
                    r.position.set(r.target);
                    r.targetYaw = -(float) Json.num(pl.get("yaw"), 0);
                    r.yaw = r.targetYaw;
                }
                break;
            }
            case "error": {
                joinError = msg.get("reason") instanceof String ? (String) msg.get("reason") : "Couldn't join that room";
                break;
            }
            case "playerJoined": {
                if (i(msg.get("id")) != myId) ensure(i(msg.get("id")), (String) msg.get("name"));
                break;
            }
            case "playerLeft": {
                remotes.remove(i(msg.get("id")));
                break;
            }
            case "state": {
                int id = i(msg.get("id"));
                if (id == myId) break;
                Remote r = ensure(id, null);
                float[] p = pos(msg.get("pos"));
                r.target.set(p[0], p[1] - EYE_OFFSET, p[2]);
                r.targetYaw = -(float) Json.num(msg.get("yaw"), 0);
                break;
            }
            case "damage": {
                // Every landed hit, fatal or not, gives the shooter a hit marker/tick.
                if (i(msg.get("shooterId")) == myId) hooks.hitFeedback();
                if (i(msg.get("victimId")) == myId) myHp = i(msg.get("victimHp"));
                break;
            }
            case "ammo": {
                if (msg.get("weapon") instanceof Number && msg.get("ammo") instanceof Number) {
                    hooks.ammo(i(msg.get("weapon")), i(msg.get("ammo")));
                }
                break;
            }
            case "reload": {
                if (msg.get("weapon") instanceof Number) hooks.reload(i(msg.get("weapon")), (int) Json.num(msg.get("durationMs"), 0));
                break;
            }
            case "kill": {
                int shooter = i(msg.get("shooterId")), victim = i(msg.get("victimId"));
                if (shooter == myId) myKills = i(msg.get("shooterKills"));
                else {
                    Remote r = remotes.get(shooter);
                    if (r != null) r.kills = i(msg.get("shooterKills"));
                }
                if (victim == myId) {
                    dead = true;
                    hooks.died();
                } else {
                    Remote r = remotes.get(victim);
                    if (r != null) r.alive = false;
                }
                break;
            }
            case "respawn": {
                int id = i(msg.get("id"));
                float[] p = pos(msg.get("pos"));
                if (id == myId) {
                    myHp = msg.get("hp") instanceof Number ? i(msg.get("hp")) : MAX_HP;
                    dead = false;
                    hooks.respawned(p[0], p[1], p[2]);
                } else {
                    Remote r = remotes.get(id);
                    if (r != null) {
                        r.alive = true;
                        r.target.set(p[0], p[1] - EYE_OFFSET, p[2]);
                        r.position.set(r.target);
                    }
                }
                break;
            }
            // A portal: the server's own authoritative version of a teleport the client already
            // predicted on contact (see Player.update -> World.portalAt). Deliberately its own
            // message rather than "correct" -- this isn't an error correction, so it shouldn't
            // count as one (self-tests assert zero corrections during ordinary movement).
            case "teleport": {
                int id = i(msg.get("id"));
                float[] p = pos(msg.get("pos"));
                if (id == myId) {
                    hooks.portalTeleport(p[0], p[1], p[2]);
                } else {
                    Remote r = remotes.get(id);
                    if (r != null) {
                        r.target.set(p[0], p[1] - EYE_OFFSET, p[2]);
                        r.position.set(r.target);
                    }
                }
                break;
            }
            case "matchOver": {
                boolean won = i(msg.get("winnerId")) == myId;
                int score = i(msg.get("score"));
                hooks.banner(won ? "YOU WIN! (" + score + " kills)" : msg.get("winnerName") + " WINS! (" + score + " kills)");
                break;
            }
            case "matchReset": {
                myKills = 0;
                for (Remote r : remotes.values()) r.kills = 0;
                hooks.hideBanner();
                break;
            }
            default:
                break;
        }
    }

    /** Eases remote avatars toward their last reported spot (frame-rate independent version of the web client's lerp). */
    public void interpolate(float dt) {
        float t = 1f - (float) Math.pow(0.75, dt * 60.0);
        for (Remote r : remotes.values()) {
            r.position.lerp(r.target, t);
            float diff = ((r.targetYaw - r.yaw + (float) Math.PI) % (float) (Math.PI * 2)) - (float) Math.PI;
            if (diff < -Math.PI) diff += (float) (Math.PI * 2);
            r.yaw += diff * t;
        }
    }

    /** Scoreboard rows sorted by kills, You first on ties. */
    public List<String[]> scoreboard() {
        java.util.ArrayList<String[]> rows = new java.util.ArrayList<>();
        rows.add(new String[]{"You", String.valueOf(myKills)});
        for (Remote r : remotes.values()) rows.add(new String[]{r.name, String.valueOf(r.kills)});
        rows.sort((a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
        return rows;
    }
}
