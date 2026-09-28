package com.conner.fps.game;

import com.conner.fps.data.Weapons;

/**
 * Everything about the equipped weapon that changes while playing: which
 * weapon is out, per-weapon ammo, reload progress, fire cooldown, and the
 * aim-down-sights blend/zoom. The class weapon ("primary") is fixed at the
 * loadout screen; the knife is the one secondary you can flip to.
 */
public final class WeaponState {
    public int current = 1;
    public int primary = 1;
    public final int[] ammo = new int[Weapons.ALL.length];
    public boolean reloading = false;
    public double reloadEnd = 0;
    public double lastShot = 0;
    public boolean aiming = false;
    public float adsBlend = 0f;   // 0 = hip-fire, 1 = fully aimed
    public float fov = Player.BASE_FOV;

    public WeaponState() {
        for (int i = 0; i < ammo.length; i++) ammo[i] = Weapons.ALL[i].magSize;
    }

    public Weapons.Spec spec() {
        return Weapons.ALL[current];
    }

    /** Equips a weapon (holsters any reload, drops out of ADS). Returns false if it's already equipped or invalid. */
    public boolean select(int idx) {
        if (idx == current || idx < 0 || idx >= Weapons.ALL.length) return false;
        current = idx;
        reloading = false;
        aiming = false;
        adsBlend = 0f;
        return true;
    }

    /** Toggles between the picked primary and the knife. */
    public int knifeToggleTarget() {
        return current == Weapons.KNIFE_INDEX ? primary : Weapons.KNIFE_INDEX;
    }

    /** Starts a reload if there's anything to reload. Returns true if one began. */
    public boolean requestReload(double now) {
        Weapons.Spec s = spec();
        if (s.melee || reloading || ammo[current] >= s.magSize) return false;
        reloading = true;
        aiming = false; // lower the weapon to reload
        reloadEnd = now + s.reloadMs / 1000.0;
        return true;
    }

    /** Local fallback in case the server's completion message is late: the reload finishes on its own timer. */
    public void finishReloadIfDue(double now) {
        if (reloading && now >= reloadEnd) {
            reloading = false;
            ammo[current] = spec().magSize;
        }
    }

    /** Smooths the zoom (FOV) and the ADS blend toward whether the player is aiming right now. */
    public void updateAds(float dt, boolean canAim) {
        Weapons.Spec s = spec();
        float speed = s.adsSpeed > 0 ? s.adsSpeed : 10f;
        boolean on = aiming && canAim && !s.melee;
        float targetFov = on ? s.adsFov : Player.BASE_FOV;
        fov += (targetFov - fov) * Math.min(1f, dt * speed);
        adsBlend += ((on ? 1f : 0f) - adsBlend) * Math.min(1f, dt * speed);
    }

    /** Fully scoped-in sniper: draw the scope overlay instead of the gun. */
    public boolean scopedIn() {
        return spec().scope && adsBlend > 0.85f;
    }

    /** Current shot-cone half-angle in degrees, blended between hip-fire and ADS. */
    public float spreadDegrees() {
        Weapons.Spec s = spec();
        return s.hipSpread + (s.adsSpread - s.hipSpread) * adsBlend;
    }
}
