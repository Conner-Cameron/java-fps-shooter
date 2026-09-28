package com.conner.fps.render;

import org.lwjgl.BufferUtils;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL30.*;

/**
 * A 2D RGBA texture uploaded from a raw pixel buffer (procedural textures,
 * see {@link TextureGenerator}) or decoded from a {@link BufferedImage}
 * (photo crops, glTF images) via ImageIO.
 */
public class Texture {
    private final int id;

    public Texture(int width, int height, ByteBuffer rgba) {
        this(width, height, rgba, true, true);
    }

    public Texture(int width, int height, ByteBuffer rgba, boolean repeat, boolean mipmaps) {
        id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        int wrap = repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, mipmaps ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        if (mipmaps) glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    public static Texture fromImage(BufferedImage image, boolean repeat, boolean mipmaps) {
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int p : argb) {
            buf.put((byte) ((p >> 16) & 255)).put((byte) ((p >> 8) & 255)).put((byte) (p & 255)).put((byte) ((p >>> 24) & 255));
        }
        buf.flip();
        return new Texture(w, h, buf, repeat, mipmaps);
    }

    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, id);
    }

    public int id() {
        return id;
    }

    public void cleanup() {
        glDeleteTextures(id);
    }
}
