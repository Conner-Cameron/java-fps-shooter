package com.conner.fps.data;

/**
 * Weapon stat table -- mirrors web/js/weapons.js and GameServer.java's
 * per-weapon tables. The server re-checks damage, fire rate, ammo and reload
 * authoritatively; this copy drives local feel (animation, HUD, ADS, spread).
 * Index: 0 pistol, 1 rifle, 2 sniper, 3 SMG, 4 knife (secondary melee).
 */
public final class Weapons {
    public static final class Spec {
        public final String name;
        public final int damage;
        public final int cooldownMs;
        public final int magSize;
        public final int reloadMs;
        public final boolean automatic;
        public final float adsFov;        // camera FOV while aiming
        public final float adsSpeed;      // how fast the zoom transitions
        public final float adsMoveMult;   // movement speed multiplier while aiming
        public final boolean scope;       // full circular scope overlay instead of just a tighter FOV
        public final float hipSpread;     // shot cone half-angle (degrees) at 0% aimed
        public final float adsSpread;     // ... at 100% aimed
        public final boolean melee;
        public final float meleeRange;
        public final String description;  // loadout-card blurb

        Spec(String name, int damage, int cooldownMs, int magSize, int reloadMs, boolean automatic,
             float adsFov, float adsSpeed, float adsMoveMult, boolean scope, float hipSpread, float adsSpread,
             boolean melee, float meleeRange, String description) {
            this.name = name;
            this.damage = damage;
            this.cooldownMs = cooldownMs;
            this.magSize = magSize;
            this.reloadMs = reloadMs;
            this.automatic = automatic;
            this.adsFov = adsFov;
            this.adsSpeed = adsSpeed;
            this.adsMoveMult = adsMoveMult;
            this.scope = scope;
            this.hipSpread = hipSpread;
            this.adsSpread = adsSpread;
            this.melee = melee;
            this.meleeRange = meleeRange;
            this.description = description;
        }
    }

    public static final Spec[] ALL = {
        new Spec("Pistol", 20, 150, 8, 1000, false, 55, 12, 0.8f, false, 1.2f, 0.1f, false, 0,
                "Quick, semi-automatic sidearm. Reliable at any range with tight hip-fire spread."),
        new Spec("Rifle", 34, 300, 24, 1600, false, 45, 9, 0.7f, false, 3.0f, 0.1f, false, 0,
                "Balanced, semi-automatic all-rounder. Solid damage and a full mag for sustained mid-range fights."),
        new Spec("Sniper", 100, 1000, 5, 2200, false, 15, 6, 0.35f, true, 6.0f, 0.05f, false, 0,
                "One-shot kill through a real scope. Devastating at range, but slow to fire and reload."),
        new Spec("SMG", 14, 100, 20, 1300, true, 58, 14, 0.85f, false, 1.8f, 0.15f, false, 0,
                "Fully automatic. High fire rate for close-quarters pressure."),
        // Secondary melee weapon, available to every class: no ammo/ADS, short-range one-shot swing.
        new Spec("Knife", 100, 600, 0, 0, false, 0, 10, 1f, false, 0, 0, true, 2.2f, "")
    };

    public static final int KNIFE_INDEX = ALL.length - 1;
    public static final int LOADOUT_COUNT = 4; // the knife isn't a loadout pick

    public static int minDamage() {
        int m = Integer.MAX_VALUE;
        for (Spec s : ALL) m = Math.min(m, s.damage);
        return m;
    }

    public static int maxDamage() {
        int m = 0;
        for (Spec s : ALL) m = Math.max(m, s.damage);
        return m;
    }

    private Weapons() {
    }
}
