package com.conner.fps.render;

import static org.lwjgl.opengl.GL30.*;

/**
 * A tiny screen-space "+" drawn directly in normalized device coordinates,
 * rendered last with depth testing disabled so it always sits on top. Also
 * owns a larger "X" hit-marker shape the game briefly shows on top of it
 * whenever a shot lands.
 */
public class Crosshair {
    private static final float SIZE = 0.018f;
    private static final float HIT_MARKER_SIZE = 0.032f;

    private final int vao;
    private final int vbo;
    private final int hitMarkerVao;
    private final int hitMarkerVbo;

    public Crosshair() {
        float[] vertices = {
                -SIZE, 0f,   SIZE, 0f,  // horizontal stroke
                 0f, -SIZE,  0f, SIZE   // vertical stroke
        };

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);

        float[] hitMarkerVertices = {
                -HIT_MARKER_SIZE, -HIT_MARKER_SIZE,  HIT_MARKER_SIZE,  HIT_MARKER_SIZE, // "\" stroke
                -HIT_MARKER_SIZE,  HIT_MARKER_SIZE,  HIT_MARKER_SIZE, -HIT_MARKER_SIZE  // "/" stroke
        };

        hitMarkerVao = glGenVertexArrays();
        glBindVertexArray(hitMarkerVao);

        hitMarkerVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, hitMarkerVbo);
        glBufferData(GL_ARRAY_BUFFER, hitMarkerVertices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);
    }

    public void render() {
        glBindVertexArray(vao);
        glDrawArrays(GL_LINES, 0, 4);
        glBindVertexArray(0);
    }

    public void renderHitMarker() {
        glBindVertexArray(hitMarkerVao);
        glDrawArrays(GL_LINES, 0, 4);
        glBindVertexArray(0);
    }

    public void cleanup() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteBuffers(hitMarkerVbo);
        glDeleteVertexArrays(hitMarkerVao);
    }
}
