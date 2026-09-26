package com.conner.fps.render;

import static org.lwjgl.opengl.GL30.*;

/**
 * A full-screen vertical gradient drawn first, with depth testing disabled,
 * so every pixel the real geometry doesn't cover reads as sky instead of a
 * flat clear color.
 */
public class SkyGradient {
    private final int vao;
    private final int vbo;

    public SkyGradient() {
        float[] vertices = {
                -1f, -1f,
                 1f, -1f,
                 1f,  1f,
                -1f, -1f,
                 1f,  1f,
                -1f,  1f
        };

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);
    }

    public void render() {
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
    }

    public void cleanup() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
    }
}
