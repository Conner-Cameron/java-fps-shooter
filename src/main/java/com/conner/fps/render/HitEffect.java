package com.conner.fps.render;

import org.joml.Vector3f;

import java.util.Random;

/**
 * A short-lived burst of small cubes spawned where a target was hit -- gives
 * the player a clear "you got it" pop at the hit location, on top of the
 * score counter and hit marker.
 */
public class HitEffect {
    public static final int PARTICLE_COUNT = 10;

    private static final float DURATION = 0.35f;
    private static final float SPREAD_DISTANCE = 2.2f;
    private static final float START_SIZE = 0.28f;

    private final Vector3f origin;
    private final Vector3f[] directions = new Vector3f[PARTICLE_COUNT];
    private float age = 0f;

    public HitEffect(Vector3f origin, Random random) {
        this.origin = new Vector3f(origin);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Vector3f dir = new Vector3f(
                    random.nextFloat() * 2f - 1f,
                    random.nextFloat() * 2f - 1f,
                    random.nextFloat() * 2f - 1f);
            if (dir.lengthSquared() < 0.0001f) {
                dir.set(0, 1, 0);
            }
            directions[i] = dir.normalize();
        }
    }

    /** Ages the effect; returns false once it's finished and should be discarded. */
    public boolean update(float deltaTime) {
        age += deltaTime;
        return age < DURATION;
    }

    public boolean isFinished() {
        return age >= DURATION;
    }

    /** 0 at spawn, 1 once fully expired. */
    public float progress() {
        return Math.min(age / DURATION, 1f);
    }

    public Vector3f particlePosition(int index) {
        float t = progress();
        return new Vector3f(origin).fma(t * SPREAD_DISTANCE, directions[index]);
    }

    public float particleScale() {
        return START_SIZE * (1f - progress());
    }
}
