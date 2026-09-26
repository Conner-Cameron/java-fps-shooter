package com.conner.fps.render;

import static org.lwjgl.opengl.GL30.*;

/**
 * A single reusable unit cube (1x1x1, centered at the origin), now with a
 * per-face normal and UV coordinate so it can be lit and textured -- every
 * solid in the world (floor, walls, enemies, the weapon) is this same mesh
 * drawn with a different model matrix, texture and tint.
 */
public class CubeMesh {
    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;

    public CubeMesh() {
        // Each face needs its own 4 vertices (shared corners would average
        // normals across faces, which looks wrong on a cube), so this is
        // 6 faces * 4 verts, each vert = pos(3) + normal(3) + uv(2).
        float[] vertices = {
                // Front (+Z)
                -0.5f, -0.5f,  0.5f,  0f, 0f, 1f,  0f, 0f,
                 0.5f, -0.5f,  0.5f,  0f, 0f, 1f,  1f, 0f,
                 0.5f,  0.5f,  0.5f,  0f, 0f, 1f,  1f, 1f,
                -0.5f,  0.5f,  0.5f,  0f, 0f, 1f,  0f, 1f,

                // Back (-Z)
                 0.5f, -0.5f, -0.5f,  0f, 0f, -1f,  0f, 0f,
                -0.5f, -0.5f, -0.5f,  0f, 0f, -1f,  1f, 0f,
                -0.5f,  0.5f, -0.5f,  0f, 0f, -1f,  1f, 1f,
                 0.5f,  0.5f, -0.5f,  0f, 0f, -1f,  0f, 1f,

                // Left (-X)
                -0.5f, -0.5f, -0.5f,  -1f, 0f, 0f,  0f, 0f,
                -0.5f, -0.5f,  0.5f,  -1f, 0f, 0f,  1f, 0f,
                -0.5f,  0.5f,  0.5f,  -1f, 0f, 0f,  1f, 1f,
                -0.5f,  0.5f, -0.5f,  -1f, 0f, 0f,  0f, 1f,

                // Right (+X)
                 0.5f, -0.5f,  0.5f,  1f, 0f, 0f,  0f, 0f,
                 0.5f, -0.5f, -0.5f,  1f, 0f, 0f,  1f, 0f,
                 0.5f,  0.5f, -0.5f,  1f, 0f, 0f,  1f, 1f,
                 0.5f,  0.5f,  0.5f,  1f, 0f, 0f,  0f, 1f,

                // Top (+Y)
                -0.5f,  0.5f,  0.5f,  0f, 1f, 0f,  0f, 0f,
                 0.5f,  0.5f,  0.5f,  0f, 1f, 0f,  1f, 0f,
                 0.5f,  0.5f, -0.5f,  0f, 1f, 0f,  1f, 1f,
                -0.5f,  0.5f, -0.5f,  0f, 1f, 0f,  0f, 1f,

                // Bottom (-Y)
                -0.5f, -0.5f, -0.5f,  0f, -1f, 0f,  0f, 0f,
                 0.5f, -0.5f, -0.5f,  0f, -1f, 0f,  1f, 0f,
                 0.5f, -0.5f,  0.5f,  0f, -1f, 0f,  1f, 1f,
                -0.5f, -0.5f,  0.5f,  0f, -1f, 0f,  0f, 1f,
        };

        int[] indices = new int[36];
        for (int face = 0; face < 6; face++) {
            int v = face * 4;
            int i = face * 6;
            indices[i] = v;
            indices[i + 1] = v + 1;
            indices[i + 2] = v + 2;
            indices[i + 3] = v + 2;
            indices[i + 4] = v + 3;
            indices[i + 5] = v;
        }

        indexCount = indices.length;

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        int stride = 8 * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glEnableVertexAttribArray(2);

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
