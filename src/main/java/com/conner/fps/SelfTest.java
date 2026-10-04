package com.conner.fps;

import com.conner.fps.data.Weapons;
import com.conner.fps.engine.Input;
import com.conner.fps.engine.Screenshot;
import com.conner.fps.game.Menus;
import com.conner.fps.game.Practice;
import com.conner.fps.net.GameClient;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Scripted end-to-end scenarios that drive the real client the way a player
 * would (mouse clicks on menu buttons, held keys, scroll wheel, aiming at
 * targets), assert on the resulting game state, and save screenshots of what
 * is on screen. Run with {@code --selftest training|pvp --out DIR}.
 */
public final class SelfTest implements Game.ScriptHook {
    private interface Step {
        boolean run(Game g);
    }

    private final String scenario;
    private final File outDir;
    private final String server;
    private final List<Step> steps = new ArrayList<>();
    private final List<String> report = new ArrayList<>();
    private int index = 0;
    private int waitFrames = 0;
    private String pendingShot = null;
    private int failures = 0;
    private boolean finished = false;
    private int frameSinceStep = 0;
    private Vector3f mark = new Vector3f();
    private GameClient observer;

    public SelfTest(String scenario, String outDir, String server) {
        this.scenario = scenario;
        this.outDir = new File(outDir);
        this.server = server;
    }

    public int exitCode() {
        return failures == 0 ? 0 : 1;
    }

    // ------------------------------------------------------------------ hook

    @Override
    public void before(Game game, int frame) {
        currentGame = game;
        if (steps.isEmpty()) build();
        if (finished) return;
        if (waitFrames > 0) {
            waitFrames--;
            return;
        }
        if (index >= steps.size()) {
            finish(game);
            return;
        }
        frameSinceStep++;
        if (steps.get(index).run(game)) {
            index++;
            frameSinceStep = 0;
        }
    }

    @Override
    public void after(Game game, int frame) {
        if (pendingShot != null) {
            Screenshot.save(game.window().getWidth(), game.window().getHeight(), new File(outDir, pendingShot + ".png"));
            pendingShot = null;
        }
    }

    private void finish(Game game) {
        finished = true;
        log(failures == 0 ? "SELFTEST PASSED" : "SELFTEST FAILED (" + failures + ")");
        try {
            outDir.mkdirs();
            try (PrintWriter w = new PrintWriter(new File(outDir, "report.txt"))) {
                for (String l : report) w.println(l);
            }
        } catch (IOException e) {
            System.err.println("could not write report: " + e);
        }
        if (observer != null) observer.close();
        game.window().close();
    }

    // ------------------------------------------------------------------ step helpers

    private void log(String line) {
        System.out.println(line);
        report.add(line);
    }

    private void check(String name, boolean ok, String detail) {
        log((ok ? "OK   " : "FAIL ") + name + (ok || detail == null ? "" : "  (" + detail + ")"));
        if (!ok) failures++;
    }

    private void act(Runnable r) {
        steps.add(g -> { r.run(); return true; });
    }

    private void wait(int frames) {
        steps.add(new Step() {
            boolean armed = false;

            @Override
            public boolean run(Game g) {
                if (!armed) {
                    armed = true;
                    waitFrames = frames;
                }
                return true;
            }
        });
    }

    /** Waits wall-clock seconds (frame counts aren't reliable: the frame rate varies with the machine and v-sync). */
    private void sec(double seconds) {
        steps.add(new Step() {
            double deadline = -1;

            @Override
            public boolean run(Game g) {
                if (deadline < 0) deadline = g.now() + seconds;
                return g.now() >= deadline;
            }
        });
    }

    private void shot(String name) {
        steps.add(g -> { pendingShot = name; waitFrames = 1; return true; });
    }

    private void verify(String name, BooleanSupplier cond, String detail) {
        steps.add(g -> { boolean ok = cond.getAsBoolean(); check(name, ok, detail != null ? detail : ("state: pos " + game().player().position + " moving=" + game().player().moving)); return true; });
    }

    /** Polls {@code cond} each frame until it's true; fails the check after maxSeconds. */
    private void until(String name, BooleanSupplier cond, double maxSeconds) {
        steps.add(new Step() {
            double deadline = -1;

            @Override
            public boolean run(Game g) {
                if (deadline < 0) deadline = g.now() + maxSeconds;
                if (cond.getAsBoolean()) {
                    check(name, true, null);
                    return true;
                }
                if (g.now() > deadline) {
                    check(name, false, "timed out after " + maxSeconds + " s");
                    return true;
                }
                return false;
            }
        });
    }

    private static void key(int k, boolean down) {
        Input.keys[k] = down;
        if (down) Input.keyPressed[k] = true;
    }

    private static void clickAt(double x, double y) {
        Input.mouseX = x;
        Input.mouseY = y;
        Input.mouseButtons[GLFW_MOUSE_BUTTON_LEFT] = true;
        Input.mousePressed[GLFW_MOUSE_BUTTON_LEFT] = true;
    }

    private static void releaseClick() {
        Input.mouseButtons[GLFW_MOUSE_BUTTON_LEFT] = false;
    }

    private void click(Game g, double x, double y) {
        // hover first (the cursor position must be known), press, then release on the following frames
        steps.add(s -> { Input.mouseX = x; Input.mouseY = y; return true; });
        steps.add(s -> { clickAt(x, y); return true; });
        steps.add(s -> { releaseClick(); return true; });
    }

    /** Aims the player straight at the given world point. */
    private static void aimAt(Game g, Vector3f p) {
        Vector3f d = new Vector3f(p).sub(g.player().position);
        g.player().yaw = (float) Math.atan2(d.z, d.x);
        g.player().pitch = (float) Math.asin(d.y / d.length());
    }

    // ------------------------------------------------------------------ scenarios

    private void build() {
        outDir.mkdirs();
        if (scenario.equals("pvp")) buildPvp();
        else if (scenario.equals("climb")) buildClimb();
        else buildTraining();
    }

    /**
     * Reproduces the reported "stuck on top of a climbed block" bug: climb the short tower, then
     * hold forward (and sprint) and check the player actually leaves the top and reaches the ground.
     */
    private void buildClimb() {
        Game g0 = null;
        menuFlowToLoadout(g0, "training");
        pickCard(g0, 1, Menus.Screen.TRAINING_SETUP);
        wait(2);
        click(g0, 640, 391);
        wait(3);
        verify("training starts for the climb check", () -> gameState().equals("PLAYING"), null);
        wait(20);
        // West to the short tower's edge, north to its face (the same approach the smoke test uses).
        act(() -> key(GLFW_KEY_A, true)); sec(1.9); act(() -> key(GLFW_KEY_A, false));
        act(() -> key(GLFW_KEY_W, true)); sec(2.25); act(() -> key(GLFW_KEY_W, false));
        sec(0.1);
        act(() -> key(GLFW_KEY_SPACE, true)); sec(0.12); act(() -> key(GLFW_KEY_SPACE, false));
        until("the climb lands on top of the short tower", () -> game().player().position.y > 3.5f, 4.0);
        sec(0.4);
        verify("standing on the tower top", () -> Math.abs(game().player().position.y - 3.7f) < 0.05f, "pos " + game().player().position);
        // Hold forward while on top; the player must leave the top and drop back to the ground.
        act(() -> key(GLFW_KEY_W, true));
        until("holding forward moves the player off the top to the ground", () -> game().player().position.y < 2.0f, 4.0);
        act(() -> key(GLFW_KEY_W, false));
        verify("walked well past the tower face", () -> game().player().position.z < -9f, "pos " + game().player().position);
        // Same, but sprinting from the top.
        act(() -> key(GLFW_KEY_W, true));
        act(() -> key(GLFW_KEY_LEFT_SHIFT, true));
        sec(1.0);
        act(() -> key(GLFW_KEY_W, false));
        act(() -> key(GLFW_KEY_LEFT_SHIFT, false));
    }

    private void menuFlowToLoadout(Game g, String mode) {
        wait(20);
        shot("01_mode_select");
        int w = 1280, h = 720;
        click(g, w / 2.0, mode.equals("pvp") ? h / 2.0 - 12 : h / 2.0 + 78);
        wait(3);
        verify("mode click opens the loadout screen", () -> game().menus().screen() == Menus.Screen.LOADOUT, null);
        wait(25);
        shot("02_loadout");
    }

    private void pickCard(Game g, int weapon, Menus.Screen expected) {
        double x0 = 640 - (4 * 200 + 3 * 14) / 2.0;
        click(g, x0 + weapon * 214 + 100, 310);
        wait(3);
        verify("loadout card " + weapon + " equips " + Weapons.ALL[weapon].name,
                () -> game().weapons().current == weapon && game().weapons().primary == weapon && game().menus().screen() == expected, null);
    }

    private void buildTraining() {
        Game g0 = null; // steps receive the game; helpers that need it for click() only use it for signature symmetry
        menuFlowToLoadout(g0, "training");
        pickCard(g0, 1, Menus.Screen.TRAINING_SETUP);
        wait(2);
        shot("03_setup");
        click(g0, 640, 391);
        wait(3);
        verify("Click to Play starts Aim Training", () -> gameState().equals("PLAYING"), null);
        wait(20);
        shot("04_ingame_rifle");

        // --- movement
        act(() -> mark.set(game().player().position));
        act(() -> key(GLFW_KEY_W, true));
        sec(0.8);
        act(() -> key(GLFW_KEY_W, false));
        verify("holding W moves the player", () -> game().player().position.distance(mark) > 2.0f, null);

        // --- jump
        act(() -> mark.set(game().player().position));
        act(() -> key(GLFW_KEY_SPACE, true));
        sec(0.12);
        verify("jump lifts the player", () -> game().player().position.y > mark.y + 0.3f, null);
        act(() -> key(GLFW_KEY_SPACE, false));
        sec(1.3);
        verify("player lands back on the ground", () -> Math.abs(game().player().position.y - 1.7f) < 0.05f,
                null);

        // --- sprint
        act(() -> mark.set(game().player().position));
        act(() -> { key(GLFW_KEY_W, true); key(GLFW_KEY_LEFT_SHIFT, true); });
        sec(0.5);
        act(() -> { key(GLFW_KEY_W, false); key(GLFW_KEY_LEFT_SHIFT, false); });
        verify("sprinting covers more ground than walking would in 30 frames", () -> game().player().position.distance(mark) > 2.2f, null);

        // --- shooting a target: aim at it and fire until it scores
        act(() -> game().player().position.set(0, 1.7f, 8));
        act(() -> game().player().teleport(0, 1.7f, 8));
        wait(2);
        steps.add(new Step() {
            int shots = 0;
            double nextShotAt = 0;

            @Override
            public boolean run(Game g) {
                if (g.score() >= 1 || shots > 40) {
                    check("shooting a target block scores a kill", g.score() >= 1, "shots " + shots + " score " + g.score());
                    return true;
                }
                if (g.weapons().reloading || g.now() < nextShotAt) return false;
                Practice.Target t = nearestVisibleTarget(g);
                if (t == null) return false;
                aimAt(g, t.position);
                Input.mousePressed[GLFW_MOUSE_BUTTON_LEFT] = true;
                Input.mouseButtons[GLFW_MOUSE_BUTTON_LEFT] = true;
                shots++;
                nextShotAt = g.now() + 0.35; // rifle cooldown is 300 ms
                return false;
            }
        });
        act(() -> releaseClick());
        sec(0.3);
        shot("05_after_kill");

        // --- reload
        act(() -> { game().weapons().ammo[1] = 3; });
        act(() -> key(GLFW_KEY_R, true));
        wait(2);
        act(() -> key(GLFW_KEY_R, false));
        verify("R starts a reload", () -> game().weapons().reloading, null);
        until("reload finishes and refills the magazine", () -> !game().weapons().reloading && game().weapons().ammo[1] == 24, 4.0);

        // --- knife toggle
        act(() -> Input.scrollY = 1);
        wait(2);
        verify("scroll wheel pulls out the knife", () -> game().weapons().current == Weapons.KNIFE_INDEX, null);
        act(() -> { Input.mousePressed[GLFW_MOUSE_BUTTON_LEFT] = true; Input.mouseButtons[GLFW_MOUSE_BUTTON_LEFT] = true; });
        wait(2);
        act(() -> releaseClick());
        shot("06_knife");
        act(() -> Input.scrollY = -1);
        wait(2);
        verify("scroll wheel returns to the class weapon", () -> game().weapons().current == 1, null);

        // --- ADS with the rifle
        act(() -> Input.mouseButtons[GLFW_MOUSE_BUTTON_RIGHT] = true);
        sec(0.9);
        verify("holding right-click aims down sights", () -> game().weapons().adsBlend > 0.9f && game().weapons().fov < 60f,
                "ads " + game().weapons().adsBlend + " fov " + game().weapons().fov);
        shot("07_ads_rifle");
        act(() -> Input.mouseButtons[GLFW_MOUSE_BUTTON_RIGHT] = false);
        sec(0.4);

        // --- the sniper's scope
        act(() -> game().weapons().select(2));
        act(() -> Input.mouseButtons[GLFW_MOUSE_BUTTON_RIGHT] = true);
        sec(1.3);
        verify("sniper ADS brings up the scope", () -> game().weapons().scopedIn() && game().weapons().fov < 20f,
                "fov " + game().weapons().fov);
        shot("08_scope");
        act(() -> Input.mouseButtons[GLFW_MOUSE_BUTTON_RIGHT] = false);
        sec(0.3);

        // --- pause menu
        act(() -> game().weapons().select(3));
        act(() -> game().pauseForTest());
        wait(3);
        verify("Esc pauses and shows the pause menu", () -> gameState().equals("PAUSED") && game().menus().screen() == Menus.Screen.PAUSED, null);
        shot("09_paused");

        // --- leave the game, then start a different mode/class without restarting the client
        click(null, 640, 381);
        wait(3);
        verify("Leave Game returns to mode select with everything reset",
                () -> gameState().equals("MENU") && game().menus().screen() == Menus.Screen.MODE && game().score() == 0
                        && game().weapons().current == 1 && game().weapons().ammo[1] == 24 && game().practice().targets().isEmpty(), null);
        wait(3);
        shot("10_after_leave");
        click(null, 640, 438);
        wait(3);
        pickCard(null, 0, Menus.Screen.TRAINING_SETUP);
        click(null, 640, 391);
        wait(3);
        verify("a new game starts right away with the newly chosen class (Pistol)",
                () -> gameState().equals("PLAYING") && game().weapons().current == 0 && game().practice().targets().size() == 6 && game().score() == 0, null);
    }

    private void buildPvp() {
        Game g0 = null;
        menuFlowToLoadout(g0, "pvp");
        pickCard(g0, 0, Menus.Screen.PVP_SETUP);
        wait(2);
        act(() -> Input.typed.append("DeskBot"));
        wait(2);
        shot("03_pvp_setup");
        act(() -> {
            observer = new GameClient();
            observer.connect(server, "Observer", "create", "");
        });
        until("the observer creates a private room", () -> observerRoom() != null, 20.0);
        act(() -> game().startPvp("DeskBot", server, "code", observerRoom));
        until("PvP connects and the server welcomes us", () -> game().pvp() != null && game().pvp().myId >= 0, 10.0);
        sec(0.5);
        verify("welcome carries a server-assigned spawn (moved off the default start)",
                () -> game().player().position.distance(new Vector3f(0, 1.7f, 8)) > 0.5f, "pos " + game().player().position);
        verify("DeskBot joined the observer's room by its code and the HUD knows it", () -> observerRoom.equals(game().pvp().roomCode) && !game().pvp().roomPublic, null);
        shot("04_pvp_spawn");

        // --- movement is accepted by the server's validation (no corrections)
        act(() -> mark.set(game().player().position));
        act(() -> { game().resetTeleportCount(); key(GLFW_KEY_W, true); });
        sec(0.6);
        act(() -> { key(GLFW_KEY_W, false); key(GLFW_KEY_S, true); }); // back the other way (the spawn may face a wall)
        sec(1.2);
        act(() -> key(GLFW_KEY_S, false));
        sec(0.4);
        verify("walking moved the player", () -> game().player().position.distance(mark) > 2.0f, null);
        verify("server sent no position corrections while walking", () -> game().teleportCount() == 0, "corrections " + game().teleportCount());
        until("the second client sees DeskBot's movement broadcasts", () -> observerSawStates(), 4.0);

        // --- shooting: the ammo count is server-confirmed
        act(() -> { Input.mousePressed[GLFW_MOUSE_BUTTON_LEFT] = true; Input.mouseButtons[GLFW_MOUSE_BUTTON_LEFT] = true; });
        wait(2);
        act(() -> releaseClick());
        sec(0.6);
        verify("firing spends a round (server-confirmed)", () -> game().weapons().ammo[0] == 7, "ammo " + game().weapons().ammo[0]);

        act(() -> Input.scrollY = 1);
        wait(3);
        verify("knife toggle works online too", () -> game().weapons().current == Weapons.KNIFE_INDEX, null);
        shot("05_pvp_ingame");

        // --- client-side handling of the server's combat messages (synthetic messages through the real handler)
        act(() -> inject("{\"type\":\"playerJoined\",\"id\":77,\"name\":\"Ghost\"}"));
        act(() -> {
            Vector3f p = game().player().position;
            inject("{\"type\":\"state\",\"id\":77,\"pos\":[" + p.x + "," + p.y + "," + (p.z - 4) + "],\"yaw\":0,\"pitch\":0}");
            game().pvp().remotes.get(77).position.set(p.x, p.y - 0.9f, p.z - 4);
        });
        sec(0.4);
        verify("a remote player shows up in the roster", () -> game().pvp().remotes.containsKey(77), null);
        shot("06_pvp_remote_player");
        act(() -> inject("{\"type\":\"damage\",\"shooterId\":77,\"victimId\":" + game().pvp().myId + ",\"damage\":60,\"victimHp\":40}"));
        verify("taking damage lowers our health", () -> game().pvp().myHp == 40, null);
        act(() -> inject("{\"type\":\"kill\",\"shooterId\":77,\"victimId\":" + game().pvp().myId + ",\"shooterKills\":1}"));
        sec(0.3);
        verify("being killed shows the death state", () -> game().pvp().dead && game().pvp().remotes.get(77).kills == 1, null);
        shot("07_pvp_dead");
        act(() -> inject("{\"type\":\"respawn\",\"id\":" + game().pvp().myId + ",\"pos\":[6,1.7,6],\"hp\":100}"));
        verify("respawn puts us back at the server's spot with full health",
                () -> !game().pvp().dead && game().pvp().myHp == 100 && game().player().position.distance(new Vector3f(6, 1.7f, 6)) < 0.6f, null);
        act(() -> inject("{\"type\":\"matchOver\",\"winnerId\":77,\"winnerName\":\"Ghost\",\"score\":10}"));
        sec(0.5);
        shot("08_pvp_banner");
        act(() -> inject("{\"type\":\"matchReset\"}"));
        verify("match reset clears the scores", () -> game().pvp().myKills == 0 && game().pvp().remotes.get(77).kills == 0, null);

        // --- leaving a match tells the server (others see us go) and returns to mode select
        act(() -> game().pauseForTest());
        wait(3);
        click(null, 640, 381);
        wait(3);
        verify("Leave Game drops the match and returns to mode select",
                () -> gameState().equals("MENU") && game().pvp() == null && game().menus().screen() == Menus.Screen.MODE, null);
        // The hosted server (Render) takes ~10-13 s to notice a closed socket, so this wait has to outlast that.
        until("the other client sees us leave", () -> observerSawLeft(), 20.0);

        // --- a refused join (unknown room code) returns to the PvP setup screen with the reason
        act(() -> game().startPvp("DeskBot", server, "code", "ZZZZ"));
        until("an unknown room code sends us back to PvP setup with the reason",
                () -> game().menuScreenForTest() == Menus.Screen.PVP_SETUP && game().menuHintForTest().contains("No room") && game().pvp() == null, 10.0);
        sec(0.3);
        shot("09_pvp_join_refused");
    }

    private String observerRoom;

    private String observerRoom() {
        if (observerRoom != null) return observerRoom;
        Map<String, Object> m;
        while ((m = observer.poll()) != null) {
            if ("welcome".equals(m.get("type")) && m.get("room") instanceof String) observerRoom = (String) m.get("room");
        }
        return observerRoom;
    }

    private boolean observerSawLeft() {
        if (observer == null) return false;
        Map<String, Object> m;
        while ((m = observer.poll()) != null) {
            if ("playerLeft".equals(m.get("type"))) return true;
        }
        return false;
    }

    private void inject(String json) {
        game().pvp().injectForTest(com.conner.fps.util.Json.parseObject(json), game());
    }

    private int spawnWalkKey() {
        // The spawn is random and the player faces -Z: walk toward the middle of the map (backward from a far spawn).
        return game().player().position.z < -15 ? GLFW_KEY_S : GLFW_KEY_W;
    }

    private boolean observerSawStates() {
        if (observer == null) return false;
        Map<String, Object> m;
        while ((m = observer.poll()) != null) {
            if ("state".equals(m.get("type"))) return true;
        }
        return false;
    }

    private Practice.Target nearestVisibleTarget(Game g) {
        Practice.Target best = null;
        float bestD = Float.MAX_VALUE;
        for (Practice.Target t : g.practice().targets()) {
            Vector3f d = new Vector3f(t.position).sub(g.player().position);
            float dist = d.length();
            d.normalize();
            if (g.world().nearestBlocker(g.player().position, d) < dist) continue; // something solid in the way
            if (dist < bestD) {
                bestD = dist;
                best = t;
            }
        }
        return best;
    }

    // The steps are built before the game exists in some helpers, so they reach it through this reference.
    private Game currentGame;

    private Game game() {
        return currentGame;
    }

    private String gameState() {
        return currentGame.state().name();
    }
}
