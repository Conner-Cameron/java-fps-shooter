package com.conner.fps.render;

import com.conner.fps.data.Weapons;
import com.conner.fps.engine.Shader;
import org.joml.Matrix3f;
import com.conner.fps.util.Json;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * First-person weapon models. Each weapon is a handful of boxes (the web
 * client's original shapes) skinned with crops of the reference photos -- or,
 * where a glTF model exists for it (see {@link GltfWeapons}), that model
 * instead. Also owns the view-model animation: idle sway, walk/sprint bob,
 * recoil kick and the muzzle flash, blended toward the ADS pose.
 */
public final class GunModels {
    private static final float[] WHITE = {1f, 1f, 1f};
    // Fallback tints for weapons with no photo skin (the knife), same as the web client's plain metal/accent.
    private static final float[] PLAIN_METAL = {0.42f, 0.435f, 0.47f};
    private static final float[] PLAIN_ACCENT = {0.09f, 0.094f, 0.10f};
    // Rifle and SMG: code-built boxes in plain colors (no photo sheet for them).
    private static final float[] RIFLE_METAL = {0.357f, 0.376f, 0.416f};
    private static final float[] RIFLE_ACCENT = {0.114f, 0.122f, 0.137f};
    // SMG: the concept-art skin (WorldTextures.smgSkin). SKIN_TINT is its own array, not WHITE, so cleanup()
    // never frees the shared texture; the accent parts take the same skin darkened.
    private static final float[] SKIN_TINT = {1f, 1f, 1f};
    private static final float[] SMG_ACCENT_TINT = {0.55f, 0.55f, 0.55f};

    public static final float RECOIL_DURATION = 0.18f;
    private static final float FLASH_DURATION = 0.05f;
    private static final float[] BASE_POS = {0.32f, -0.32f, -0.6f};
    private static final float[] ADS_POS = {0.02f, -0.16f, -0.45f};

    // {x, y, z, sx, sy, sz, material} with material 0 = metal, 1 = accent; the last row of each is the muzzle-flash position.
    private static final float[][][] PARTS = {
        { // 0 pistol
            {0, -0.02f, 0.05f, 0.1f, 0.1f, 0.3f, 0}, {0, 0.01f, -0.18f, 0.04f, 0.04f, 0.18f, 0},
            {0, -0.16f, 0.14f, 0.08f, 0.2f, 0.09f, 1}, {0, -0.24f, 0.1f, 0.06f, 0.12f, 0.08f, 1}
        },
        { // 1 rifle: AR-style lower/upper receiver, carry handle, handguard, barrel, grip, magazine, stock
            {0, -0.03f, 0.05f, 0.075f, 0.11f, 0.36f, 1}, {0, 0.05f, -0.02f, 0.075f, 0.075f, 0.42f, 0},
            {0, 0.1f, -0.02f, 0.03f, 0.035f, 0.2f, 0}, {0, 0.0f, -0.3f, 0.085f, 0.085f, 0.3f, 1},
            {0, 0.02f, -0.5f, 0.03f, 0.03f, 0.22f, 0}, {0, 0.08f, -0.42f, 0.015f, 0.04f, 0.015f, 0},
            {0, -0.13f, 0.2f, 0.06f, 0.17f, 0.07f, 1, -0.25f}, {0, -0.2f, -0.02f, 0.05f, 0.2f, 0.09f, 0, 0.12f},
            {0, -0.03f, 0.42f, 0.06f, 0.1f, 0.28f, 1}, {0, -0.03f, 0.57f, 0.065f, 0.11f, 0.03f, 1},
            {0, 0.02f, 0.36f, 0.07f, 0.04f, 0.2f, 0}
        },
        { // 2 sniper
            {0, -0.02f, 0.15f, 0.1f, 0.1f, 0.6f, 0}, {0, 0.01f, -0.5f, 0.04f, 0.04f, 0.55f, 0},
            {0, 0.09f, 0.05f, 0.06f, 0.06f, 0.3f, 1}, {0, -0.16f, 0.28f, 0.08f, 0.18f, 0.1f, 1},
            {0, 0.02f, 0.55f, 0.08f, 0.09f, 0.3f, 0}
        },
        { // 3 SMG: MP5-style receiver, deep handguard, barrel shroud, long magazine, folding stock
            {0, 0.0f, 0.02f, 0.09f, 0.12f, 0.36f, 0}, {0, -0.01f, -0.22f, 0.1f, 0.09f, 0.2f, 1},
            {0, 0.01f, -0.4f, 0.035f, 0.035f, 0.2f, 0}, {0, 0.01f, -0.36f, 0.06f, 0.06f, 0.12f, 0},
            {0, 0.09f, 0.1f, 0.02f, 0.03f, 0.04f, 0}, {0, -0.15f, 0.2f, 0.07f, 0.16f, 0.07f, 1, -0.2f},
            {0, -0.22f, -0.04f, 0.06f, 0.22f, 0.08f, 1, 0.06f}, {0, 0.04f, 0.36f, 0.05f, 0.04f, 0.2f, 0},
            {0, 0.0f, 0.36f, 0.05f, 0.1f, 0.03f, 1}
        },
        { // 4 knife: blade, crossguard, handle
            {0, 0, -0.15f, 0.02f, 0.03f, 0.32f, 0}, {0, 0, 0.03f, 0.07f, 0.025f, 0.02f, 1},
            {0, -0.01f, 0.13f, 0.035f, 0.035f, 0.17f, 1}
        }
    };
    private static final float[][] MUZZLE = {
        {0, 0.01f, -0.3f}, {0, 0.02f, -0.66f}, {0, 0.01f, -0.8f}, {0, 0.01f, -0.55f}, {0, 0, 0.3f}
    };

    private final Texture[] metal = new Texture[PARTS.length];
    private final Texture[] accent = new Texture[PARTS.length];
    private final float[][] metalTint = new float[PARTS.length][];
    private final float[][] accentTint = new float[PARTS.length][];
    private final Texture flashTexture;
    private final Matrix3f normalScratch = new Matrix3f();

    // Animation state
    private float idleTime, walkTime, movingFactor, sprintFactor, recoilTimer;

    /** glTF replacement for a weapon (from assets/manifest.json), plus its placement. */
    private static final class GlbWeapon {
        GlbModel model;
        float scale = 1f;
        float[] position = {0, 0, 0};
        float rotationY = 0f;
        float[] muzzle = {0, 0, -0.3f};
        float iconRotationY = (float) Math.toRadians(20);
    }

    private final Shader pbr;
    private final Texture whiteTexture;
    private final GlbWeapon[] glb = new GlbWeapon[PARTS.length];

    public GunModels(WorldTextures worlds, Shader pbrShader) {
        this.pbr = pbrShader;
        this.whiteTexture = worlds.white;
        loadManifest();
        flashTexture = worlds.white;
        BufferedImage pistolSheet = readImage("/assets/pistol_reference.png");
        BufferedImage sniperSheet = readImage("/assets/sniper_reference.png");

        // Crops of the reference sheets -- same regions as the web client's weapon skins.
        skin(0, pistolSheet, new int[]{550, 90, 300, 300}, new int[]{740, 350, 180, 180}, worlds);
        plainSkin(1, RIFLE_METAL, RIFLE_ACCENT, worlds);
        skin(2, sniperSheet, new int[]{640, 75, 220, 220}, new int[]{100, 400, 220, 220}, worlds);
        metal[3] = worlds.smgSkin;
        accent[3] = worlds.smgSkin;
        metalTint[3] = SKIN_TINT;
        accentTint[3] = SMG_ACCENT_TINT;
        metal[4] = worlds.metal;
        accent[4] = worlds.metal;
        metalTint[4] = PLAIN_METAL;
        accentTint[4] = PLAIN_ACCENT;
    }

    private void plainSkin(int idx, float[] metalTone, float[] accentTone, WorldTextures worlds) {
        metal[idx] = worlds.metal;
        accent[idx] = worlds.metal;
        metalTint[idx] = metalTone;
        accentTint[idx] = accentTone;
    }

    private void skin(int idx, BufferedImage sheet, int[] metalCrop, int[] accentCrop, WorldTextures worlds) {
        if (sheet == null) {
            metal[idx] = worlds.metal;
            accent[idx] = worlds.metal;
            metalTint[idx] = PLAIN_METAL;
            accentTint[idx] = PLAIN_ACCENT;
            return;
        }
        metal[idx] = crop(sheet, metalCrop);
        accent[idx] = crop(sheet, accentCrop);
        metalTint[idx] = WHITE;
        accentTint[idx] = WHITE;
    }

    private static BufferedImage readImage(String path) {
        try (InputStream in = GunModels.class.getResourceAsStream(path)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static Texture crop(BufferedImage sheet, int[] r) {
        BufferedImage out = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(sheet, 0, 0, 256, 256, r[0], r[1], r[0] + r[2], r[1] + r[3], null);
        g.dispose();
        return Texture.fromImage(out, false, true);
    }

    // ------------------------------------------------------------------ animation

    public void update(float dt, boolean moving, boolean sprinting) {
        idleTime += dt;
        movingFactor += ((moving ? 1f : 0f) - movingFactor) * Math.min(1f, dt * 8f);
        sprintFactor += ((sprinting && moving ? 1f : 0f) - sprintFactor) * Math.min(1f, dt * 8f);
        // Sprinting quickens the cadence on top of the normal walk pace.
        if (moving) walkTime += dt * (9f + sprintFactor * 5f);
        if (recoilTimer > 0f) recoilTimer = Math.max(0f, recoilTimer - dt);
    }

    public void triggerFire() {
        recoilTimer = RECOIL_DURATION;
    }

    /** Draws the equipped weapon as a screen-anchored view-model (the caller sets identity view + cleared depth). */
    public void renderViewModel(Shader shader, CubeMesh cube, int weaponIdx, float adsBlend, boolean scoped) {
        if (scoped) return; // a scope shows the view through the tube, not the gun's body
        float steady = 1f - adsBlend;
        float idleSwayX = (float) Math.sin(idleTime * 0.6) * 0.012f * steady;
        float idleSwayY = (float) Math.sin(idleTime * 1.1) * 0.008f * steady;
        float bobAmpX = 0.02f + sprintFactor * 0.028f;
        float bobAmpY = 0.018f + sprintFactor * 0.022f;
        float walkBobX = (float) Math.sin(walkTime) * bobAmpX * movingFactor * steady;
        float walkBobY = (float) Math.abs(Math.sin(walkTime)) * bobAmpY * movingFactor * steady;
        float sprintTiltZ = (float) (Math.sin(walkTime) * Math.toRadians(3) * sprintFactor * movingFactor * steady);
        float recoilT = recoilTimer / RECOIL_DURATION;

        float x = BASE_POS[0] + (ADS_POS[0] - BASE_POS[0]) * adsBlend + idleSwayX + walkBobX;
        float y = BASE_POS[1] + (ADS_POS[1] - BASE_POS[1]) * adsBlend + idleSwayY + walkBobY;
        float z = BASE_POS[2] + (ADS_POS[2] - BASE_POS[2]) * adsBlend + recoilT * 0.12f;

        Matrix4f base = new Matrix4f().translate(x, y, z)
                .rotateY((float) Math.toRadians(8.0) * steady)
                .rotateX(-(float) Math.toRadians(recoilT * 10f))
                .rotateZ(sprintTiltZ);
        boolean flash = recoilTimer > RECOIL_DURATION - FLASH_DURATION;
        renderModel(shader, cube, weaponIdx, base, flash);
    }

    /** Reads assets/manifest.json (shared with the web client) and loads every listed glTF; anything that fails keeps its box model. */
    private void loadManifest() {
        try (InputStream in = GunModels.class.getResourceAsStream("/assets/manifest.json")) {
            if (in == null) return;
            Map<String, Object> manifest = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            for (Map.Entry<String, Object> e : Json.obj(manifest.get("weapons")).entrySet()) {
                int idx = Integer.parseInt(e.getKey());
                Map<String, Object> spec = Json.obj(e.getValue());
                try (InputStream glbIn = GunModels.class.getResourceAsStream("/assets/" + spec.get("model"))) {
                    if (glbIn == null || idx < 0 || idx >= glb.length) continue;
                    GlbWeapon w = new GlbWeapon();
                    w.model = GlbModel.load(glbIn.readAllBytes());
                    w.scale = (float) Json.num(spec.get("scale"), 1);
                    if (spec.get("position") != null) w.position = Json.floats(spec.get("position"));
                    w.rotationY = (float) Json.num(spec.get("rotationY"), 0);
                    if (spec.get("muzzle") != null) w.muzzle = Json.floats(spec.get("muzzle"));
                    w.iconRotationY = (float) Json.num(spec.get("iconRotationY"), Math.toRadians(20));
                    glb[idx] = w;
                } catch (Exception ex) {
                    System.err.println("Weapon model " + spec.get("model") + " failed to load; using the box model: " + ex);
                }
            }
        } catch (Exception e) {
            System.err.println("No asset manifest; using box models: " + e);
        }
    }

    /**
     * A loadout-card preview: the weapon fitted to the preview frame and turning (box models spin;
     * real models sway around their best-looking side, like the web client's icons).
     */
    public void renderPreview(Shader scene, CubeMesh cube, int weaponIdx, double time) {
        GlbWeapon w = glb[weaponIdx];
        if (w == null) {
            float[] fit = PREVIEW_FIT[weaponIdx];
            Matrix4f base = new Matrix4f().rotateY((float) (Math.toRadians(20) + time * 0.4))
                    .scale(fit[0]).translate(fit[1], fit[2], fit[3]);
            renderModel(scene, cube, weaponIdx, base, false);
            return;
        }
        float fitScale = 0.62f / w.model.maxExtent();
        Vector3f c = w.model.center();
        Matrix4f base = new Matrix4f().rotateY(w.iconRotationY + (float) Math.sin(time * 0.9) * 0.5f)
                .scale(fitScale).translate(-c.x, -c.y - 0.02f / fitScale, -c.z);
        drawGlb(scene, w, base);
    }

    /** {scale, dx, dy, dz} to center each box model in a preview frame. */
    private static final float[][] PREVIEW_FIT = {
        {1.5f, 0f, 0.10f, -0.03f},   // pistol
        {0.95f, 0f, 0.02f, -0.10f},  // rifle
        {0.55f, 0f, 0.0f, -0.05f},   // sniper
        {1.15f, 0f, 0.04f, -0.08f},  // SMG
        {1.0f, 0f, 0f, 0f}           // knife
    };

    /** Draws a weapon at an arbitrary transform -- the in-hand view-model. */
    public void renderModel(Shader shader, CubeMesh cube, int weaponIdx, Matrix4f base, boolean flash) {
        GlbWeapon w = glb[weaponIdx];
        if (w != null) {
            Matrix4f placed = new Matrix4f(base).translate(w.position[0], w.position[1], w.position[2])
                    .rotateY(w.rotationY).scale(w.scale);
            drawGlb(shader, w, placed);
            if (flash && weaponIdx != Weapons.KNIFE_INDEX) {
                flashTexture.bind(0);
                shader.setVec2("uvScale", 1f, 1f);
                draw(shader, cube, base, w.muzzle[0], w.muzzle[1], w.muzzle[2], 0.16f, 0.16f, 0.16f, 3.2f, 2.6f, 0.6f);
            }
            return;
        }
        shader.setVec2("uvScale", 1f, 1f);
        for (float[] p : PARTS[weaponIdx]) {
            boolean acc = p[6] == 1f;
            (acc ? accent : metal)[weaponIdx].bind(0);
            float[] tint = acc ? accentTint[weaponIdx] : metalTint[weaponIdx];
            draw(shader, cube, base, p[0], p[1], p[2], p[3], p[4], p[5], tint[0], tint[1], tint[2], p.length > 7 ? p[7] : 0f);
        }
        if (flash && weaponIdx != Weapons.KNIFE_INDEX) {
            flashTexture.bind(0);
            float[] m = MUZZLE[weaponIdx];
            draw(shader, cube, base, m[0], m[1], m[2], 0.16f, 0.16f, 0.16f, 3.2f, 2.6f, 0.6f);
        }
    }

    /** Draws a glTF weapon with the PBR shader, then hands the GL state back to the scene shader. */
    private void drawGlb(Shader scene, GlbWeapon w, Matrix4f placed) {
        pbr.use();
        pbr.setInt("baseMap", 0);
        pbr.setInt("normalMap", 1);
        pbr.setInt("mrMap", 2);
        pbr.setVec2("uvScale", 1f, 1f);
        for (GlbModel.Part p : w.model.parts) {
            Matrix4f model = new Matrix4f(placed).mul(p.transform);
            pbr.setMat4("model", model);
            pbr.setMat3("normalMatrix", model.normal(normalScratch));
            pbr.setVec4("baseFactor", p.baseFactor[0], p.baseFactor[1], p.baseFactor[2], p.baseFactor[3]);
            pbr.setFloat("metallicFactor", p.metallic);
            pbr.setFloat("roughnessFactor", p.roughness);
            pbr.setFloat("normalScale", p.normalScale);
            pbr.setInt("hasNormal", p.normal != null ? 1 : 0);
            pbr.setInt("hasMR", p.metalRough != null ? 1 : 0);
            (p.base != null ? p.base : whiteTexture).bind(0);
            if (p.normal != null) p.normal.bind(1);
            if (p.metalRough != null) p.metalRough.bind(2);
            p.mesh.render();
        }
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        scene.use();
    }

    private void draw(Shader shader, CubeMesh cube, Matrix4f base, float x, float y, float z,
                      float sx, float sy, float sz, float r, float g, float b) {
        draw(shader, cube, base, x, y, z, sx, sy, sz, r, g, b, 0f);
    }

    /** As above, tilted about the X axis by rx radians (for angled grips and magazines). */
    private void draw(Shader shader, CubeMesh cube, Matrix4f base, float x, float y, float z,
                      float sx, float sy, float sz, float r, float g, float b, float rx) {
        Matrix4f model = new Matrix4f(base).translate(x, y, z).rotateX(rx).scale(sx, sy, sz);
        shader.setMat4("model", model);
        shader.setMat3("normalMatrix", model.normal(normalScratch));
        shader.setVec3("color", r, g, b);
        cube.render();
    }

    public void cleanup() {
        for (int i = 0; i < PARTS.length; i++) {
            if (metal[i] != null && metalTint[i] == WHITE) metal[i].cleanup();
            if (accent[i] != null && accentTint[i] == WHITE) accent[i].cleanup();
        }
        for (GlbWeapon w : glb) if (w != null) w.model.cleanup();
    }
}
