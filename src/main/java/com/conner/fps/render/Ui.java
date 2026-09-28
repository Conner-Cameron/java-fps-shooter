package com.conner.fps.render;

import com.conner.fps.engine.Shader;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Immediate-mode 2D drawing in framebuffer pixels (origin top-left): filled
 * rectangles, lines, soft circles, text, textured quads, and the sniper
 * scope mask. Each call streams its vertices straight to the GPU, which is
 * plenty for a HUD and a few menu screens.
 */
public final class Ui {
    private static final int MODE_SOLID = 0, MODE_FONT = 1, MODE_SCOPE = 2, MODE_TEXTURE = 3, MODE_CIRCLE = 4;
    private static final int FLOATS_PER_VERTEX = 8; // pos(2) uv(2) color(4)

    private final Shader shader;
    private final int vao;
    private final int vbo;
    private final FloatBuffer buffer = BufferUtils.createFloatBuffer(6 * FLOATS_PER_VERTEX * 2048);
    private int screenW;
    private int screenH;

    public Ui() {
        shader = new Shader("/shaders/ui_vertex.glsl", "/shaders/ui_fragment.glsl");
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);
    }

    /** Starts a 2D pass: depth test off, alpha blending on. */
    public void begin(int width, int height) {
        screenW = width;
        screenH = height;
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        shader.use();
        shader.setVec2("screen", width, height);
        shader.setInt("tex", 0);
    }

    public void end() {
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    public int width() {
        return screenW;
    }

    public int height() {
        return screenH;
    }

    // ------------------------------------------------------------------ shapes

    public void rect(float x, float y, float w, float h, float r, float g, float b, float a) {
        buffer.clear();
        quad(x, y, x + w, y + h, 0, 0, 1, 1, r, g, b, a);
        flush(MODE_SOLID, null);
    }

    public void rectOutline(float x, float y, float w, float h, float t, float r, float g, float b, float a) {
        rect(x, y, w, t, r, g, b, a);
        rect(x, y + h - t, w, t, r, g, b, a);
        rect(x, y, t, h, r, g, b, a);
        rect(x + w - t, y, t, h, r, g, b, a);
    }

    /** A thick line as a rotated quad. */
    public void line(float x1, float y1, float x2, float y2, float thickness, float r, float g, float b, float a) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.0001f) return;
        float nx = -dy / len * thickness / 2f, ny = dx / len * thickness / 2f;
        buffer.clear();
        vertex(x1 + nx, y1 + ny, 0, 0, r, g, b, a);
        vertex(x2 + nx, y2 + ny, 1, 0, r, g, b, a);
        vertex(x2 - nx, y2 - ny, 1, 1, r, g, b, a);
        vertex(x1 + nx, y1 + ny, 0, 0, r, g, b, a);
        vertex(x2 - nx, y2 - ny, 1, 1, r, g, b, a);
        vertex(x1 - nx, y1 - ny, 0, 1, r, g, b, a);
        flush(MODE_SOLID, null);
    }

    /** A filled circle with a softly anti-aliased edge. */
    public void circle(float cx, float cy, float radius, float r, float g, float b, float a) {
        buffer.clear();
        quad(cx - radius - 1, cy - radius - 1, cx + radius + 1, cy + radius + 1, 0, 0, 1, 1, r, g, b, a);
        flush(MODE_CIRCLE, null);
    }

    /** Full-screen sniper scope: black everywhere but a lens of the given radius, with a soft vignette on its rim. */
    public void scopeMask(float cx, float cy, float radius) {
        shader.setVec2("center", cx, cy);
        shader.setFloat("radius", radius);
        buffer.clear();
        quad(0, 0, screenW, screenH, 0, 0, 1, 1, 0, 0, 0, 1);
        flush(MODE_SCOPE, null);
    }

    public void texturedRect(Texture tex, float x, float y, float w, float h, float r, float g, float b, float a) {
        buffer.clear();
        quad(x, y, x + w, y + h, 0, 0, 1, 1, r, g, b, a);
        flush(MODE_TEXTURE, tex);
    }

    // ------------------------------------------------------------------ text

    /** Draws a string with its top-left at (x, y); returns its pixel width. */
    public float text(FontAtlas font, String s, float x, float y, float r, float g, float b, float a) {
        buffer.clear();
        float pen = x;
        for (int i = 0; i < s.length(); i++) {
            FontAtlas.Glyph gl = font.glyph(s.charAt(i));
            if (buffer.remaining() < 6 * FLOATS_PER_VERTEX) {
                flush(MODE_FONT, font.texture());
                buffer.clear();
            }
            if (s.charAt(i) != ' ') {
                quad(pen, y, pen + gl.cellWidth, y + font.lineHeight, gl.u0, gl.v0, gl.u1, gl.v1, r, g, b, a);
            }
            pen += gl.advance;
        }
        if (buffer.position() > 0) flush(MODE_FONT, font.texture());
        return pen - x;
    }

    public float textShadow(FontAtlas font, String s, float x, float y, float r, float g, float b, float a) {
        text(font, s, x + 1.5f, y + 1.5f, 0, 0, 0, a * 0.7f);
        return text(font, s, x, y, r, g, b, a);
    }

    public void textCentered(FontAtlas font, String s, float cx, float y, float r, float g, float b, float a) {
        textShadow(font, s, cx - font.width(s) / 2f, y, r, g, b, a);
    }

    public void textRight(FontAtlas font, String s, float rightX, float y, float r, float g, float b, float a) {
        textShadow(font, s, rightX - font.width(s), y, r, g, b, a);
    }

    // ------------------------------------------------------------------ internals

    private void quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
                      float r, float g, float b, float a) {
        vertex(x0, y0, u0, v0, r, g, b, a);
        vertex(x1, y0, u1, v0, r, g, b, a);
        vertex(x1, y1, u1, v1, r, g, b, a);
        vertex(x0, y0, u0, v0, r, g, b, a);
        vertex(x1, y1, u1, v1, r, g, b, a);
        vertex(x0, y1, u0, v1, r, g, b, a);
    }

    private void vertex(float x, float y, float u, float v, float r, float g, float b, float a) {
        buffer.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a);
    }

    private void flush(int mode, Texture tex) {
        if (buffer.position() == 0) return;
        buffer.flip();
        shader.setInt("mode", mode);
        if (tex != null) tex.bind(0);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, buffer.limit() / FLOATS_PER_VERTEX);
        glBindVertexArray(0);
        buffer.clear();
    }

    public void cleanup() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.cleanup();
    }
}
