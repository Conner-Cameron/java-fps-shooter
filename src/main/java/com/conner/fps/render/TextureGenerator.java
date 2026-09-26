package com.conner.fps.render;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Small tileable textures generated in code instead of loaded from image
 * files -- there's no asset pipeline (no image decoder, no art files) in
 * this project, so surface detail (grain, panel seams, rivets) comes from a
 * procedural pass instead of an artist-authored texture.
 */
public final class TextureGenerator {
    private TextureGenerator() {
    }

    /**
     * Pale, speckled concrete with faint expansion-joint seams -- used for
     * the ground. Values are kept mid-to-high brightness (this multiplies
     * an object's tint color, and lighting multiplies the result again) so
     * textured surfaces don't end up reading as near-black.
     */
    public static Texture floor(int size) {
        Random rnd = new Random(1001);
        ByteBuffer buffer = BufferUtils.createByteBuffer(size * size * 4);
        int tile = Math.max(1, size / 4);

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float shade = 0.72f + (rnd.nextFloat() - 0.5f) * 0.06f;
                if (x % tile == 0 || y % tile == 0) {
                    shade -= 0.1f;
                }
                putPixel(buffer, shade * 1.02f, shade, shade * 0.97f);
            }
        }
        buffer.flip();
        return new Texture(size, size, buffer);
    }

    /** Brushed gunmetal detailing (panel seams, rivets) -- used for walls, enemy armor, and the gun. */
    public static Texture metal(int size) {
        Random rnd = new Random(2002);
        ByteBuffer buffer = BufferUtils.createByteBuffer(size * size * 4);
        int tile = Math.max(1, size / 4);

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float shade = 0.64f + (rnd.nextFloat() - 0.5f) * 0.05f;
                int tx = x % tile;
                int ty = y % tile;
                if (tx == 0 || ty == 0) {
                    shade -= 0.14f;
                }
                if (tx < 3 && ty < 3) {
                    shade += 0.16f;
                }
                putPixel(buffer, shade * 0.95f, shade, shade * 1.08f);
            }
        }
        buffer.flip();
        return new Texture(size, size, buffer);
    }

    /** Flat 1x1 white texture so flat-tinted geometry can share the same textured shader path. */
    public static Texture white() {
        ByteBuffer buffer = BufferUtils.createByteBuffer(4);
        putPixel(buffer, 1f, 1f, 1f);
        buffer.flip();
        return new Texture(1, 1, buffer);
    }

    private static void putPixel(ByteBuffer buffer, float r, float g, float b) {
        buffer.put(toByte(r)).put(toByte(g)).put(toByte(b)).put((byte) 255);
    }

    private static byte toByte(float v) {
        int i = Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
        return (byte) i;
    }
}
