package com.conner.fps.render;

import static org.lwjgl.opengl.GL30.*;

/**
 * A single reusable unit cube (1x1x1, centered at the origin). Every solid
 * in the world -- floor, walls, targets -- is this same mesh drawn with a
 * different model matrix and color uniform.
 */
public class CubeMesh {
    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;

    public CubeMesh() {
        // 8 corners of a unit cube
        float[] vertices = {
                -0.5f, -0.5f, -0.5f, // 0
                 0.5f, -0.5f, -0.5f, // 1
                 0.5f,  0.5f, -0.5f, // 2
                -0.5f,  0.5f, -0.5f, // 3
                -0.5f, -0.5f,  0.5f, // 4
                 0.5f, -0.5f,  0.5f, // 5
                 0.5f,  0.5f,  0.5f, // 6
                -0.5f,  0.5f,  0.5f  // 7
        };

        int[] indices = {
                0, 1, 2, 2, 3, 0, // back
                4, 5, 6, 6, 7, 4, // front
                4, 0, 3, 3, 7, 4, // left
                1, 5, 6, 6, 2, 1, // right
                3, 2, 6, 6, 7, 3, // top
                4, 5, 1, 1, 0, 4  // bottom
        };

        indexCount = indices.length;

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);
    }

    public void render() {
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    public void cleanup() {
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteVertexArrays(vao);
    }
}
