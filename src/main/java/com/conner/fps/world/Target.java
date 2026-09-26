package com.conner.fps.world;

import org.joml.Vector3f;

/**
 * A shootable humanoid enemy. Idles in place with a small bob/wander so it
 * reads as alive rather than a static prop; when hit it's relocated
 * elsewhere in the arena rather than removed, so there's always something to
 * shoot at. Position (and therefore the hit box) tracks the same idle
 * animation the renderer draws, so hits always match what's on screen.
 */
public class Target {
    /** Bounding box size used for hit detection and to size the humanoid model. */
    public static final Vector3f BOUNDS = new Vector3f(1.1f, 1.8f, 0.7f);

    private static final float BOB_AMOUNT = 0.06f;
    private static final float BOB_SPEED = 1.6f;
    private static final float WANDER_RADIUS = 0.35f;
    private static final float WANDER_SPEED = 0.5f;

    public boolean alive = true;

    private final Vector3f home;
    private final float phase;
    private float animTime;

    public Target(Vector3f homePosition, float phase) {
        this.home = homePosition;
        this.phase = phase;
    }

    public void update(float deltaTime) {
        animTime += deltaTime;
    }

    public void respawnAt(Vector3f newHome) {
        home.set(newHome);
        animTime = 0f;
    }

    /** World position including idle bob/wander -- used for both hit testing and rendering. */
    public Vector3f getPosition() {
        float t = animTime + phase;
        float bob = (float) Math.sin(t * BOB_SPEED) * BOB_AMOUNT;
        float driftX = (float) Math.sin(t * WANDER_SPEED) * WANDER_RADIUS;
        float driftZ = (float) Math.cos(t * WANDER_SPEED * 0.8f) * WANDER_RADIUS;
        return new Vector3f(home.x + driftX, home.y + bob, home.z + driftZ);
    }

    /** Local animation clock (phase-offset so enemies don't move in lockstep). */
    public float getAnimTime() {
        return animTime + phase;
    }

    public Vector3f getMin() {
        return getPosition().sub(BOUNDS.x / 2f, BOUNDS.y / 2f, BOUNDS.z / 2f);
    }

    public Vector3f getMax() {
        return getPosition().add(BOUNDS.x / 2f, BOUNDS.y / 2f, BOUNDS.z / 2f);
    }
}
