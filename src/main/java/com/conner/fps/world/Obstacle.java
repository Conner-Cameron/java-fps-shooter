package com.conner.fps.world;

import org.joml.Vector3f;

/**
 * A static, solid box in the level -- used for the floor and for cover walls.
 */
public class Obstacle {
    public final Vector3f position;
    public final Vector3f size;
    public final float[] color;

    public Obstacle(Vector3f position, Vector3f size, float[] color) {
        this.position = position;
        this.size = size;
        this.color = color;
    }
}
