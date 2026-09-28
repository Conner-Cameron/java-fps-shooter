package com.conner.fps.render;

import org.lwjgl.BufferUtils;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * A glyph atlas rasterized at startup with Java2D (the JDK's fonts -- no font
 * files are bundled). Every glyph is white-with-alpha so the UI shader can
 * tint it; text is laid out with plain advance widths.
 */
public final class FontAtlas {
    private static final String EXTRA_GLYPHS = "•—…·→";

    public static final class Glyph {
        float u0, v0, u1, v1;
        int advance;
        int cellWidth;
    }

    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Texture texture;
    public final int lineHeight;
    public final int size;

    public FontAtlas(String family, int style, int pixelSize) {
        this.size = pixelSize;
        Font font = new Font(family, style, pixelSize);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        lineHeight = fm.getHeight();
        int ascent = fm.getAscent();

        StringBuilder chars = new StringBuilder();
        for (char c = 32; c < 127; c++) chars.append(c);
        chars.append(EXTRA_GLYPHS);

        int atlasW = 1024, atlasH = 1024;
        BufferedImage atlas = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.WHITE);

        int x = 1, y = 1;
        for (int k = 0; k < chars.length(); k++) {
            char c = chars.charAt(k);
            int adv = Math.max(1, fm.charWidth(c));
            int cell = adv + 2;
            if (x + cell + 1 > atlasW) {
                x = 1;
                y += lineHeight + 2;
            }
            g.drawString(String.valueOf(c), x + 1, y + ascent);
            Glyph gl = new Glyph();
            gl.u0 = (float) (x + 1) / atlasW;
            gl.v0 = (float) y / atlasH;
            gl.u1 = (float) (x + 1 + adv) / atlasW;
            gl.v1 = (float) (y + lineHeight) / atlasH;
            gl.advance = adv;
            gl.cellWidth = adv;
            glyphs.put((int) c, gl);
            x += cell + 1;
        }
        g.dispose();
        pg.dispose();

        int[] argb = atlas.getRGB(0, 0, atlasW, atlasH, null, 0, atlasW);
        ByteBuffer buf = BufferUtils.createByteBuffer(atlasW * atlasH * 4);
        for (int p : argb) {
            buf.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) ((p >>> 24) & 255));
        }
        buf.flip();
        texture = new Texture(atlasW, atlasH, buf, false, false);
    }

    public Glyph glyph(char c) {
        Glyph g = glyphs.get((int) c);
        return g != null ? g : glyphs.get((int) '?');
    }

    public float width(String s) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) w += glyph(s.charAt(i)).advance;
        return w;
    }

    public Texture texture() {
        return texture;
    }

    public void cleanup() {
        texture.cleanup();
    }
}
