package com.conner.fps.world;

import org.joml.Vector3f;

/**
 * A shootable cube target. When hit it gets relocated by the game rather
 * than removed, so there's always something to shoot at.
 */
public class Target {
    public Vector3f position;
    public float size = 1.2f;
    public boolean alive = true;
    public final float[] color = {0.9f, 0.15f, 0.15f};

    public Target(Vector3f position) {
        this.position = position;
    }

    public Vector3f getMin() {
        float half = size / 2f;
        return new Vector3f(position).sub(half, half, half);
    }

    public Vector3f getMax() {
        float half = size / 2f;
        return new Vector3f(position).add(half, half, half);
    }
}
