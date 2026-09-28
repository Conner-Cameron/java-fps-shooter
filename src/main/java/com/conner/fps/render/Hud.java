package com.conner.fps.render;

import java.awt.Font;
import java.util.List;

/**
 * The in-game HUD, drawn with {@link Ui} -- layout, text and colors follow
 * the web client's HUD. Pure rendering: {@link Data} carries whatever the
 * game wants shown this frame.
 */
public final class Hud {
    /** Everything the HUD needs for one frame. */
    public static final class Data {
        public boolean pvp;
        public int kills, killLimit, playerCount;
        public int score;                      // Aim Training
        public int health, maxHealth;
        public String weaponName = "";
        public int weaponDamage;
        public boolean melee;
        public int ammo, magSize;
        public boolean reloading;
        public String status = "";
        public List<String[]> scoreboard = List.of();
        public float hipSpreadPixels;          // half-gap of the hip-fire crosshair
        public boolean aiming, adsDot, scoped;
        public float hitMarkerAge = 99f;       // seconds since the last hit (marker shows for HIT_MARKER_TIME)
        public boolean dead;
        public String banner;                  // match banner text, or null
        public float bannerAlpha;
    }

    public static final float HIT_MARKER_TIME = 0.2f;
    private static final float HIT_PUNCH_TIME = 0.06f;

    private final FontAtlas font15 = new FontAtlas("SansSerif", Font.BOLD, 15);
    private final FontAtlas font18 = new FontAtlas("SansSerif", Font.BOLD, 18);
    private final FontAtlas font12 = new FontAtlas("SansSerif", Font.PLAIN, 12);
    private final FontAtlas font28 = new FontAtlas("SansSerif", Font.BOLD, 28);
    private final FontAtlas font42 = new FontAtlas("SansSerif", Font.BOLD, 42);

    public void draw(Ui ui, Data d) {
        int w = ui.width(), h = ui.height();
        float cx = w / 2f, cy = h / 2f;

        if (d.scoped) {
            drawScope(ui, w, h);
        } else {
            drawCrosshair(ui, d, cx, cy);
        }
        drawHitMarker(ui, d, cx, cy);

        // top-left: mode counters
        String top = d.pvp
                ? "Kills: " + d.kills + " / " + d.killLimit + "   •   Players: " + d.playerCount
                : "Practice score: " + d.score;
        ui.textShadow(font18, top, 16, 16, 1, 1, 1, 1);

        // top-right: scoreboard
        if (d.pvp) {
            float y = 16;
            for (String[] row : d.scoreboard) {
                ui.textRight(font15, row[0] + ": " + row[1], w - 16, y, 1, 1, 1, 1);
                y += 20;
            }
        }

        // bottom-left: health
        if (d.pvp) {
            ui.textShadow(font15, "Health: " + Math.max(0, d.health) + "/" + d.maxHealth, 20, h - 78, 1, 1, 1, 1);
            ui.rect(20, h - 52, 160, 10, 0, 0, 0, 0.4f);
            float frac = Math.max(0f, Math.min(1f, (float) d.health / d.maxHealth));
            float[] c = d.health > 50 ? new float[]{0.37f, 0.76f, 0.37f} : d.health > 25 ? new float[]{0.88f, 0.73f, 0.24f} : new float[]{0.83f, 0.26f, 0.24f};
            ui.rect(20, h - 52, 160 * frac, 10, c[0], c[1], c[2], 1);
        }
        if (!d.status.isEmpty()) ui.text(font12, d.status, 16, h - 18, 1, 1, 1, 0.7f);

        // bottom-right: weapon + ammo
        float ry = h - 84;
        ui.textRight(font15, "Weapon: " + d.weaponName + " (" + d.weaponDamage + " dmg)", w - 20, ry, 1, 1, 1, 1);
        String ammo = d.melee ? "Ammo: —/—" : "Ammo: " + Math.max(0, d.ammo) + "/" + d.magSize + (d.reloading ? " — Reloading…" : "");
        ui.textRight(font15, ammo, w - 20, ry + 22, 1, 1, 1, 1);
        ui.textRight(font12, "[R] Reload   [RMB] Aim   [Scroll] Knife", w - 20, ry + 44, 1, 1, 1, 0.75f);

        if (d.dead) {
            ui.rect(0, 0, w, h, 0.47f, 0f, 0f, 0.55f);
            ui.textCentered(font28, "Eliminated — respawning…", cx, cy - 16, 1, 1, 1, 1);
        }
        if (d.banner != null && d.bannerAlpha > 0f) {
            ui.textCentered(font42, d.banner, cx, h * 0.3f - 24, 1f, 0.835f, 0.29f, d.bannerAlpha);
        }
    }

    /** Four arms whose gap follows the current hip-fire spread; a small dot when aiming down sights. */
    private void drawCrosshair(Ui ui, Data d, float cx, float cy) {
        if (d.aiming) {
            if (d.adsDot) {
                ui.circle(cx, cy, 4.5f, 0, 0, 0, 0.5f);
                ui.circle(cx, cy, 3f, 1, 1, 1, 1);
            }
            return;
        }
        float gap = Math.max(d.hipSpreadPixels, 2f); // arms start this far from the center
        float len = 8f, t = 2f;
        // dark backing for contrast, then white arms
        for (int pass = 0; pass < 2; pass++) {
            float grow = pass == 0 ? 1f : 0f;
            float r = pass == 0 ? 0f : 1f, a = pass == 0 ? 0.75f : 1f;
            ui.rect(cx - t / 2 - grow, cy - gap - len - grow, t + 2 * grow, len + 2 * grow, r, r, r, a);
            ui.rect(cx - t / 2 - grow, cy + gap - grow, t + 2 * grow, len + 2 * grow, r, r, r, a);
            ui.rect(cx - gap - len - grow, cy - t / 2 - grow, len + 2 * grow, t + 2 * grow, r, r, r, a);
            ui.rect(cx + gap - grow, cy - t / 2 - grow, len + 2 * grow, t + 2 * grow, r, r, r, a);
        }
    }

    /** The classic white X that punches in and fades -- the reticle itself never recolors. */
    private void drawHitMarker(Ui ui, Data d, float cx, float cy) {
        if (d.hitMarkerAge >= HIT_MARKER_TIME) return;
        float fade = 1f - d.hitMarkerAge / HIT_MARKER_TIME;
        float punch = 1f - Math.min(1f, d.hitMarkerAge / HIT_PUNCH_TIME);
        float s = (1f + 0.5f * punch * punch) * 13f;
        float inner = s * 0.35f;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                ui.line(cx + sx * inner, cy + sy * inner, cx + sx * s, cy + sy * s, 4f, 0, 0, 0, 0.6f * fade);
                ui.line(cx + sx * inner, cy + sy * inner, cx + sx * s, cy + sy * s, 2f, 1, 1, 1, fade);
            }
        }
    }

    /** Sniper scope: black outside a circular lens (60% of the short side), thin dark reticle lines, center dot. */
    private void drawScope(Ui ui, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) * 0.30f;
        ui.scopeMask(cx, cy, radius);
        float lens = radius * 0.92f;
        float rr = 0.03f, rg = 0.055f, rb = 0.03f, ra = 0.92f;
        ui.rect(cx - 1, cy - lens, 2, lens * 0.8f, rr, rg, rb, ra);
        ui.rect(cx - 1, cy + lens * 0.2f, 2, lens * 0.8f, rr, rg, rb, ra);
        ui.rect(cx - lens, cy - 1, lens * 0.8f, 2, rr, rg, rb, ra);
        ui.rect(cx + lens * 0.2f, cy - 1, lens * 0.8f, 2, rr, rg, rb, ra);
        ui.circle(cx, cy, 2.5f, rr, rg, rb, ra);
    }

    public FontAtlas titleFont() {
        return font42;
    }

    public FontAtlas headingFont() {
        return font28;
    }

    public FontAtlas bodyFont() {
        return font15;
    }

    public FontAtlas smallFont() {
        return font12;
    }

    public FontAtlas mediumFont() {
        return font18;
    }

    public void cleanup() {
        font12.cleanup();
        font15.cleanup();
        font18.cleanup();
        font28.cleanup();
        font42.cleanup();
    }
}
