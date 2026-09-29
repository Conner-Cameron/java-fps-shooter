package com.conner.fps;

import com.conner.fps.audio.Sounds;
import com.conner.fps.data.MapData;
import com.conner.fps.data.Weapons;
import com.conner.fps.engine.Input;
import com.conner.fps.engine.Shader;
import com.conner.fps.engine.Window;
import com.conner.fps.game.Menus;
import com.conner.fps.game.Player;
import com.conner.fps.game.Practice;
import com.conner.fps.game.PvpSession;
import com.conner.fps.game.WeaponState;
import com.conner.fps.net.GameClient;
import com.conner.fps.net.ServerInfo;
import com.conner.fps.render.CubeMesh;
import com.conner.fps.render.EnemyModel;
import com.conner.fps.render.GunModels;
import com.conner.fps.render.HitEffect;
import com.conner.fps.render.Hud;
import com.conner.fps.render.SkyGradient;
import com.conner.fps.render.Ui;
import com.conner.fps.render.WorldTextures;
import com.conner.fps.util.Json;
import com.conner.fps.world.World;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * The desktop client: menus (mode -> loadout -> setup), then either Aim
 * Training (offline) or PvP Deathmatch (against GameServer), with the same
 * weapons, movement, HUD, sounds and world as the browser game.
 */
public class Game implements PvpSession.Hooks {
    public enum State { MENU, PLAYING, PAUSED }

    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final Vector3f LIGHT_DIR = new Vector3f(-0.4f, -1f, -0.35f).normalize();
    private static final Vector3f LIGHT_COLOR = new Vector3f(1f, 0.97f, 0.88f);
    private static final Vector3f AMBIENT_COLOR = new Vector3f(0.50f, 0.52f, 0.57f);
    private static final Vector3f SKY_TOP = new Vector3f(0.30f, 0.45f, 0.70f);
    private static final Vector3f SKY_BOTTOM = new Vector3f(0.72f, 0.80f, 0.86f);
    public static final String DEFAULT_SERVER = "wss://java-fps-shooter-pvp.onrender.com/ws";

    // ---- engine / rendering
    private Window window;
    private Shader sceneShader;
    private Shader skyShader;
    private Shader pbrShader;
    private CubeMesh cube;
    private SkyGradient sky;
    private Ui ui;
    private Hud hud;
    private WorldTextures textures;
    private World world;
    private GunModels gunModels;
    private Sounds sounds;
    private Menus menus;
    private Menus.Fonts menuFonts;
    private final Matrix3f normalScratch = new Matrix3f();

    // ---- game state
    private State state = State.MENU;
    private String gameMode = null; // "pvp" | "training"
    private final Player player = new Player();
    private final WeaponState weapons = new WeaponState();
    private final Practice practice = new Practice();
    private final List<HitEffect> hitEffects = new ArrayList<>();
    private final Random random = new Random();
    private PvpSession pvp;
    private int score = 0;
    private float hitMarkerAge = 99f;
    private boolean mouseHeld = false;
    private double now = 0;
    private double lastStateSent = 0;
    private String bannerText = null;
    private String menuHint = "";              // problem shown on the PvP setup screen (refused join, failed connection)
    private Menus.Screen lastMenuScreen = Menus.Screen.NONE;
    private double bannerUntil = 0;
    private final String serverDefault;
    private double last;
    private ScriptHook hook;
    private int teleportCount = 0;

    /** Test hook: {@code before} runs at the start of a frame (so scripted input is seen by that frame's update); {@code after} runs once the frame is rendered (so screenshots show it). */
    public interface ScriptHook {
        void before(Game game, int frame);

        void after(Game game, int frame);
    }

    public Game(String serverDefault) {
        this.serverDefault = serverDefault;
    }

    public void setScriptHook(ScriptHook hook) {
        this.hook = hook;
    }

    // ------------------------------------------------------------------ lifecycle

    public void run() {
        init();
        int frame = 0;
        while (!window.shouldClose()) {
            now = glfwGetTime();
            float dt = Math.min((float) (now - last), 0.1f);
            if (hook != null) hook.before(this, frame);
            last = now;

            update(dt);
            render();
            if (hook != null) hook.after(this, frame);
            frame++;
            window.swapBuffers();
            Input.endFrame();
            window.pollEvents();
        }
        cleanup();
    }

    private void init() {
        window = new Window(WINDOW_WIDTH, WINDOW_HEIGHT, "Java FPS Shooter");
        window.init();
        window.setEscapeHandler(this::onEscape);

        sceneShader = new Shader("/shaders/vertex.glsl", "/shaders/fragment.glsl");
        skyShader = new Shader("/shaders/sky_vertex.glsl", "/shaders/sky_fragment.glsl");
        pbrShader = new Shader("/shaders/vertex.glsl", "/shaders/pbr_fragment.glsl");
        cube = new CubeMesh();
        sky = new SkyGradient();
        ui = new Ui();
        hud = new Hud();
        textures = new WorldTextures();
        world = new World(MapData.load(), cube, textures);
        gunModels = new GunModels(textures, pbrShader);
        sounds = new Sounds();
        menus = new Menus(serverDefault);
        menuFonts = new Menus.Fonts(hud.headingFont(), hud.mediumFont(), hud.bodyFont(), hud.smallFont());

        window.setCursorCaptured(false);
        last = glfwGetTime();
    }

    private void cleanup() {
        if (pvp != null) pvp.client().close();
        sounds.cleanup();
        gunModels.cleanup();
        world.cleanup();
        textures.cleanup();
        hud.cleanup();
        ui.cleanup();
        sky.cleanup();
        cube.cleanup();
        sceneShader.cleanup();
        skyShader.cleanup();
        pbrShader.cleanup();
        window.destroy();
    }

    private void onEscape() {
        switch (state) {
            case PLAYING:
                pause();
                break;
            case PAUSED:
                resume();
                break;
            default:
                if (!menus.back()) window.close();
        }
    }

    private void pause() {
        state = State.PAUSED;
        mouseHeld = false;
        weapons.aiming = false;
        window.setCursorCaptured(false);
        menus.show(Menus.Screen.PAUSED);
    }

    private void resume() {
        state = State.PLAYING;
        menus.show(Menus.Screen.NONE);
        window.setCursorCaptured(true);
    }

    // ------------------------------------------------------------------ update

    private void update(float dt) {
        if (pvp != null) {
            pvp.poll(this);
            pvp.interpolate(dt);
            if (pvp.joinError != null) failJoin(pvp.joinError);
            else if (pvp.myId < 0 && pvp.client().failed()) failJoin(pvp.client().status());
        }
        weapons.finishReloadIfDue(now);
        hitMarkerAge += dt;
        hitEffects.removeIf(e -> !e.update(dt));

        boolean playing = state == State.PLAYING;
        // Until the server has placed us in a room (welcome), input is ignored -- same as being dead.
        boolean dead = pvp != null && (pvp.dead || pvp.myId < 0);
        if ("training".equals(gameMode)) practice.update(dt, now);

        boolean moving = false, sprinting = false;
        if (playing) {
            player.look(Input.mouseDeltaX, Input.mouseDeltaY, weapons.fov);
            Input.resetMouseDelta();
            handleCombatInput(dead);
            if (!dead) {
                player.update(world, dt,
                        Input.keys[GLFW_KEY_W], Input.keys[GLFW_KEY_S], Input.keys[GLFW_KEY_A], Input.keys[GLFW_KEY_D],
                        Input.keys[GLFW_KEY_SPACE], Input.keys[GLFW_KEY_LEFT_SHIFT] || Input.keys[GLFW_KEY_RIGHT_SHIFT],
                        weapons.aiming, weapons.spec().adsMoveMult, Weapons.sprintMult(weapons.current));
                moving = player.moving;
                sprinting = player.sprinting;
            }
            weapons.updateAds(dt, !dead);
            sendStateIfDue();
        } else {
            weapons.updateAds(dt, false);
            Input.resetMouseDelta();
        }
        gunModels.update(dt, moving, sprinting);
    }

    private void handleCombatInput(boolean dead) {
        if (dead) {
            mouseHeld = false;
            weapons.aiming = false;
            return;
        }
        if (Input.mousePressed[GLFW_MOUSE_BUTTON_LEFT]) {
            mouseHeld = true;
            shoot();
        }
        if (!Input.mouseButtons[GLFW_MOUSE_BUTTON_LEFT]) mouseHeld = false;
        // Automatic weapons keep firing every frame the button is held, gated by shoot()'s own cooldown.
        if (mouseHeld && weapons.spec().automatic) shoot();

        weapons.aiming = Input.mouseButtons[GLFW_MOUSE_BUTTON_RIGHT] && !weapons.spec().melee && !weapons.reloading;

        if (Input.keyPressed[GLFW_KEY_R]) requestReload();

        // Knife toggle: the one exception to "loadout is locked in". Two slots, so either direction just flips.
        if (Input.scrollY != 0) selectWeapon(weapons.knifeToggleTarget());
    }

    private void selectWeapon(int idx) {
        if (weapons.select(idx)) sendMessage(Json.obj("type", "weapon", "id", idx));
    }

    private void requestReload() {
        if (weapons.requestReload(now)) {
            sounds.reload(weapons.current);
            sendMessage(Json.obj("type", "reload"));
        }
    }

    // ------------------------------------------------------------------ combat

    private void shoot() {
        Weapons.Spec spec = weapons.spec();
        if (spec.melee) {
            meleeAttack(spec);
            return;
        }
        if (weapons.reloading) return;
        if (weapons.ammo[weapons.current] <= 0) {
            requestReload();
            return;
        }
        if (now - weapons.lastShot < spec.cooldownMs / 1000.0) return;
        weapons.lastShot = now;
        weapons.ammo[weapons.current]--;

        gunModels.triggerFire();
        sounds.gunshot(weapons.current);

        Vector3f dir = applySpread(player.forward(), weapons.spreadDegrees());
        resolveShot(dir, Float.POSITIVE_INFINITY, spec);

        if (weapons.ammo[weapons.current] <= 0) requestReload();
        sendShot(dir);
    }

    /** No ammo, no spread, no ADS -- a short-range swing (the server independently caps its reach). */
    private void meleeAttack(Weapons.Spec spec) {
        if (now - weapons.lastShot < spec.cooldownMs / 1000.0) return;
        weapons.lastShot = now;
        gunModels.triggerFire(); // the forward-punch kick doubles as the swing motion
        sounds.meleeSwing();
        Vector3f dir = player.forward();
        resolveShot(dir, spec.meleeRange, spec);
        sendShot(dir);
    }

    /** Local Aim Training hit resolution: the nearest target counts only if nothing solid is in front of it. */
    private void resolveShot(Vector3f dir, float range, Weapons.Spec spec) {
        if (!"training".equals(gameMode)) return;
        float blocker = world.nearestBlocker(player.position, dir);
        float[] dist = new float[1];
        Practice.Target t = practice.raycast(player.position, dir, range, dist);
        if (t == null || dist[0] >= blocker) return;
        Vector3f where = new Vector3f(t.position);
        hitFeedback();
        if (practice.damage(t, spec.damage, now)) {
            score++;
            hitEffects.add(new HitEffect(where, random));
            window.setTitle("Java FPS Shooter | Score: " + score);
        }
    }

    private void sendShot(Vector3f dir) {
        if (pvp != null && !pvp.dead) {
            sendMessage(Json.obj("type", "shoot",
                    "origin", new float[]{player.position.x, player.position.y, player.position.z},
                    "dir", new float[]{dir.x, dir.y, dir.z}));
        }
    }

    /** Perturbs a direction within a random cone (uniform over the cone's disc). */
    private Vector3f applySpread(Vector3f dir, float spreadDegrees) {
        if (spreadDegrees <= 0f) return dir;
        double r = Math.tan(Math.toRadians(spreadDegrees)) * Math.sqrt(random.nextDouble());
        double phi = random.nextDouble() * Math.PI * 2;
        Vector3f upHint = Math.abs(dir.y) < 0.99f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        Vector3f right = new Vector3f(dir).cross(upHint).normalize();
        Vector3f up = new Vector3f(right).cross(dir).normalize();
        return new Vector3f(dir)
                .fma((float) (r * Math.cos(phi)), right)
                .fma((float) (r * Math.sin(phi)), up)
                .normalize();
    }

    private void sendStateIfDue() {
        if (pvp == null || pvp.dead || pvp.myId < 0 || !pvp.client().isConnected()) return;
        if (now - lastStateSent < 0.05) return;
        lastStateSent = now;
        sendMessage(Json.obj("type", "state",
                "pos", new float[]{player.position.x, player.position.y, player.position.z},
                "yaw", player.yaw, "pitch", player.pitch));
    }

    private void sendMessage(String json) {
        if (pvp != null) pvp.client().send(json);
    }

    // ------------------------------------------------------------------ PvpSession.Hooks

    @Override
    public void teleport(float x, float y, float z) {
        teleportCount++;
        player.teleport(x, y, z);
    }

    @Override
    public void hitFeedback() {
        hitMarkerAge = 0f;
        sounds.hitTick();
    }

    @Override
    public void ammo(int weapon, int rounds) {
        if (weapon >= 0 && weapon < weapons.ammo.length) weapons.ammo[weapon] = rounds;
    }

    @Override
    public void reload(int weapon, int durationMs) {
        if (weapon == weapons.current) {
            weapons.reloading = true;
            weapons.reloadEnd = now + (durationMs > 0 ? durationMs : weapons.spec().reloadMs) / 1000.0;
        }
    }

    @Override
    public void died() {
        weapons.aiming = false;
    }

    @Override
    public void respawned(float x, float y, float z) {
        weapons.reloading = false;
        player.teleport(x, y, z);
    }

    @Override
    public void banner(String text) {
        bannerText = text;
        bannerUntil = now + 6.0;
    }

    @Override
    public void hideBanner() {
        bannerUntil = 0;
    }

    // ------------------------------------------------------------------ menu actions

    private void applyMenuAction(Menus.Action a) {
        switch (a.type) {
            case Menus.Action.LOADOUT:
                weapons.primary = a.weapon;
                weapons.select(a.weapon);
                break;
            case Menus.Action.START_TRAINING:
                gameMode = "training";
                practice.spawnAll();
                score = 0;
                window.setTitle("Java FPS Shooter | Score: 0");
                beginPlaying();
                break;
            case Menus.Action.START_PVP:
                gameMode = "pvp";
                GameClient client = new GameClient();
                pvp = new PvpSession(client, a.name);
                String url = a.server.isEmpty() ? serverDefault : a.server;
                menuHint = "";
                client.connect(url, a.name, a.roomMode, a.roomCode);
                beginPlaying();
                break;
            case Menus.Action.RESUME:
                resume();
                break;
            case Menus.Action.LEAVE:
                leaveGame();
                break;
            case Menus.Action.QUIT:
                window.close();
                break;
            default:
                break;
        }
    }

    /** The server refused the join, or the connection never came up: back to the PvP setup screen with the reason. */
    private void failJoin(String reason) {
        pvp.client().close();
        pvp = null;
        gameMode = null;
        mouseHeld = false;
        lastStateSent = 0;
        int keep = weapons.primary;
        weapons.reset();
        weapons.primary = keep;
        weapons.select(keep);
        player.reset();
        state = State.MENU;
        menus.show(Menus.Screen.PVP_SETUP);
        menuHint = reason;
        window.setCursorCaptured(false);
        window.setTitle("Java FPS Shooter");
    }

    /** Full-screen "connecting" card while a PvP match is starting (covers waking a sleeping host, which can take a minute). */
    private void drawConnecting(int w, int h) {
        if (pvp == null || pvp.myId >= 0 || state == State.MENU) return;
        ui.rect(0, 0, w, h, 0f, 0f, 0f, 0.7f);
        ui.textCentered(menuFonts.title, "Connecting2026", w / 2f, h / 2f - 50, 1, 1, 1, 1);
        ui.textCentered(menuFonts.body, pvp.statusText(), w / 2f, h / 2f + 6, 1, 1, 1, 0.85f);
        ui.textCentered(menuFonts.small, "Esc: pause / leave", w / 2f, h / 2f + 40, 1, 1, 1, 0.55f);
    }

    /** Leaves the current game for good -- drops the match connection, resets all game state, and returns to mode select. */
    private void leaveGame() {
        if (pvp != null) {
            pvp.client().close();
            pvp = null;
        }
        practice.clear();
        hitEffects.clear();
        score = 0;
        gameMode = null;
        bannerText = null;
        bannerUntil = 0;
        hitMarkerAge = 99f;
        mouseHeld = false;
        lastStateSent = 0;
        weapons.reset();
        player.reset();
        state = State.MENU;
        menus.reset();
        window.setCursorCaptured(false);
        window.setTitle("Java FPS Shooter");
    }

    private void beginPlaying() {
        state = State.PLAYING;
        menus.show(Menus.Screen.NONE);
        window.setCursorCaptured(true);
        mouseHeld = false;
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        int w = window.getWidth(), h = window.getHeight();
        glViewport(0, 0, w, h);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        glDisable(GL_DEPTH_TEST);
        skyShader.use();
        skyShader.setVec3("topColor", SKY_TOP.x, SKY_TOP.y, SKY_TOP.z);
        skyShader.setVec3("bottomColor", SKY_BOTTOM.x, SKY_BOTTOM.y, SKY_BOTTOM.z);
        sky.render();
        glEnable(GL_DEPTH_TEST);

        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(weapons.fov), (float) w / h, 0.1f, 200f);
        setupScene(projection, player.viewMatrix(), player.position);
        world.render(sceneShader);
        renderDynamic();

        // Weapon view-model: fresh depth buffer + identity view, so it draws over the world and stays anchored to the screen.
        if (state != State.MENU) {
            glClear(GL_DEPTH_BUFFER_BIT);
            sceneShader.setMat4("view", new Matrix4f());
            sceneShader.setVec3("viewPos", 0f, 0f, 0f);
            pbrShader.use();
            pbrShader.setMat4("view", new Matrix4f());
            pbrShader.setVec3("viewPos", 0f, 0f, 0f);
            sceneShader.use();
            boolean hideGun = weapons.spec().scope && weapons.adsBlend > 0.5f;
            gunModels.renderViewModel(sceneShader, cube, weapons.current, weapons.adsBlend, hideGun);
        }

        ui.begin(w, h);
        if (state != State.MENU) drawHud(h);
        Menus.Action action = new Menus.Action();
        if (menus.screen() != Menus.Screen.NONE) {
            String hint = menus.screen() != Menus.Screen.PVP_SETUP ? "" : pvp != null ? pvp.statusText() : menuHint;
            if (menus.screen() == Menus.Screen.PVP_SETUP && lastMenuScreen != Menus.Screen.PVP_SETUP) {
                Menus menusRef = menus;
                ServerInfo.leaderboard(menus.serverUrl().isBlank() ? serverDefault : menus.serverUrl()).thenAccept(menusRef::setLeaders);
            }
            action = menus.draw(ui, menuFonts, window.cursorX(), window.cursorY(), hint);
        }
        lastMenuScreen = menus.screen();
        drawConnecting(w, h);
        ui.end();

        renderLoadoutPreviews(w, h);
        applyMenuAction(action);
    }

    private void setupScene(Matrix4f projection, Matrix4f view, Vector3f eye) {
        sceneShader.use();
        sceneShader.setMat4("projection", projection);
        sceneShader.setMat4("view", view);
        sceneShader.setVec3("viewPos", eye.x, eye.y, eye.z);
        sceneShader.setVec3("lightDir", LIGHT_DIR.x, LIGHT_DIR.y, LIGHT_DIR.z);
        sceneShader.setVec3("lightColor", LIGHT_COLOR.x, LIGHT_COLOR.y, LIGHT_COLOR.z);
        sceneShader.setVec3("ambientColor", AMBIENT_COLOR.x, AMBIENT_COLOR.y, AMBIENT_COLOR.z);
        sceneShader.setVec3("fogColor", SKY_BOTTOM.x, SKY_BOTTOM.y, SKY_BOTTOM.z);
        sceneShader.setFloat("fogNear", 20f);
        sceneShader.setFloat("fogFar", 95f);
        sceneShader.setFloat("alpha", 1f);
        sceneShader.setVec3("emissive", 0f, 0f, 0f);
        sceneShader.setInt("tex", 0);

        pbrShader.use();
        pbrShader.setMat4("projection", projection);
        pbrShader.setMat4("view", view);
        pbrShader.setVec3("viewPos", eye.x, eye.y, eye.z);
        pbrShader.setVec3("lightDir", LIGHT_DIR.x, LIGHT_DIR.y, LIGHT_DIR.z);
        pbrShader.setVec3("lightColor", LIGHT_COLOR.x, LIGHT_COLOR.y, LIGHT_COLOR.z);
        pbrShader.setVec3("ambientColor", AMBIENT_COLOR.x, AMBIENT_COLOR.y, AMBIENT_COLOR.z);
        pbrShader.setVec3("skyTop", SKY_TOP.x, SKY_TOP.y, SKY_TOP.z);
        pbrShader.setVec3("skyBottom", SKY_BOTTOM.x, SKY_BOTTOM.y, SKY_BOTTOM.z);
        pbrShader.setVec3("fogColor", SKY_BOTTOM.x, SKY_BOTTOM.y, SKY_BOTTOM.z);
        pbrShader.setFloat("fogNear", 20f);
        pbrShader.setFloat("fogFar", 95f);
        sceneShader.use();
    }

    /** Practice blocks, remote players and hit particles. */
    private void renderDynamic() {
        if ("training".equals(gameMode)) practice.render(sceneShader, cube, textures.hazard);

        if (pvp != null) {
            for (PvpSession.Remote r : pvp.remotes.values()) {
                if (!r.alive) continue;
                EnemyModel.render(sceneShader, cube, textures.metal, textures.white, r.position, r.yaw);
            }
        }

        textures.white.bind(0);
        sceneShader.setVec2("uvScale", 1f, 1f);
        for (HitEffect effect : hitEffects) {
            float fade = 1f - effect.progress();
            sceneShader.setVec3("color", 1f, 1f, 0.85f + 0.15f * fade);
            for (int i = 0; i < HitEffect.PARTICLE_COUNT; i++) {
                Matrix4f model = new Matrix4f().translate(effect.particlePosition(i)).scale(effect.particleScale());
                sceneShader.setMat4("model", model);
                sceneShader.setMat3("normalMatrix", model.normal(normalScratch));
                cube.render();
            }
        }
    }

    private void drawHud(int h) {
        Hud.Data d = new Hud.Data();
        boolean isPvp = pvp != null;
        Weapons.Spec spec = weapons.spec();
        d.pvp = isPvp;
        d.score = score;
        if (isPvp) {
            d.kills = pvp.myKills;
            d.killLimit = pvp.killLimit;
            d.playerCount = pvp.remotes.size() + 1;
            d.health = pvp.myHp;
            d.maxHealth = PvpSession.MAX_HP;
            d.scoreboard = pvp.scoreboard();
            d.status = pvp.statusText();
            d.roomCode = pvp.roomCode;
            d.dead = pvp.dead && state == State.PLAYING;
        }
        d.weaponName = spec.name;
        d.weaponDamage = spec.damage;
        d.melee = spec.melee;
        d.ammo = weapons.ammo[weapons.current];
        d.magSize = spec.magSize;
        d.reloading = weapons.reloading;
        d.hipSpreadPixels = (float) (Math.tan(Math.toRadians(spec.hipSpread)) / Math.tan(Math.toRadians(Player.BASE_FOV / 2.0)) * (h / 2.0));
        d.aiming = weapons.aiming || weapons.adsBlend > 0.4f;
        d.adsDot = weapons.aiming && !spec.scope && weapons.adsBlend > 0.4f;
        d.scoped = weapons.scopedIn();
        d.hitMarkerAge = hitMarkerAge;
        if (bannerText != null && now < bannerUntil) {
            d.banner = bannerText;
            d.bannerAlpha = (float) Math.min(1.0, Math.min(bannerUntil - now, 0.3) / 0.3);
        }
        hud.draw(ui, d);
    }

    /** The loadout screen's weapon previews: each card's model rendered into its own scissored viewport. */
    private void renderLoadoutPreviews(int w, int h) {
        if (menus.screen() != Menus.Screen.LOADOUT) return;
        glEnable(GL_SCISSOR_TEST);
        for (Menus.PreviewRect r : menus.previews()) {
            int px = Math.round(r.x), pw = Math.round(r.w), ph = Math.round(r.h);
            int py = h - Math.round(r.y) - ph;
            glViewport(px, py, pw, ph);
            glScissor(px, py, pw, ph);
            glClear(GL_DEPTH_BUFFER_BIT);
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(32.0), (float) pw / ph, 0.05f, 10f);
            Vector3f eye = new Vector3f(0.55f, 0.22f, 0.75f);
            Matrix4f view = new Matrix4f().lookAt(eye, new Vector3f(0, -0.02f, 0), new Vector3f(0, 1, 0));
            setupScene(projection, view, eye);
            gunModels.renderPreview(sceneShader, cube, r.weapon, glfwGetTime());
        }
        glDisable(GL_SCISSOR_TEST);
        glViewport(0, 0, w, h);
    }

    // ------------------------------------------------------------------ script access (self-test)

    public Window window() {
        return window;
    }

    public State state() {
        return state;
    }

    public Menus menus() {
        return menus;
    }

    public WeaponState weapons() {
        return weapons;
    }

    public Player player() {
        return player;
    }

    public int teleportCount() {
        return teleportCount;
    }

    public void resetTeleportCount() {
        teleportCount = 0;
    }

    public void pauseForTest() {
        onEscape();
    }

    public double now() {
        return now;
    }

    public PvpSession pvp() {
        return pvp;
    }

    public int score() {
        return score;
    }

    public Practice practice() {
        return practice;
    }

    public World world() {
        return world;
    }

    public void chooseLoadout(int weapon) {
        Menus.Action a = new Menus.Action();
        a.type = Menus.Action.LOADOUT;
        a.weapon = weapon;
        applyMenuAction(a);
    }

    public void startTraining() {
        Menus.Action a = new Menus.Action();
        a.type = Menus.Action.START_TRAINING;
        applyMenuAction(a);
    }

    public void startPvp(String name, String server) {
        startPvp(name, server, "quick", "");
    }

    public void startPvp(String name, String server, String roomMode, String roomCode) {
        Menus.Action a = new Menus.Action();
        a.type = Menus.Action.START_PVP;
        a.name = name;
        a.server = server;
        a.roomMode = roomMode;
        a.roomCode = roomCode;
        applyMenuAction(a);
    }

    /** Test hooks. */
    public String menuHintForTest() {
        return menuHint;
    }

    public Menus.Screen menuScreenForTest() {
        return menus.screen();
    }
}
