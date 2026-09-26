package com.conner.fps.world;

import org.joml.Vector3f;

/**
 * A static, solid box in the level -- used for the floor and for cover walls.
 * {@link Material} picks which procedural texture and UV tiling the
 * renderer uses for it.
 */
public class Obstacle {
    public enum Material {
        FLOOR, WALL
    }

    public final Vector3f position;
    public final Vector3f size;
    public final float[] color;
    public final Material material;

    public Obstacle(Vector3f position, Vector3f size, float[] color, Material material) {
        this.position = position;
        this.size = size;
        this.color = color;
        this.material = material;
    }
}
