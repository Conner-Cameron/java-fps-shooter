package com.conner.fps.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Random;
import javax.imageio.ImageIO;
import java.nio.ByteBuffer;

/**
 * The web game's surface textures. Metal panels and hazard stripes are procedural (painted with
 * Java2D, same recipes as the web client). The map's ground, walls, towers, building, rocks,
 * mountains and trunks use the photo sets from web/assets/textures (Poly Haven, CC0), and the
 * tree canopies use the leaf photo (ambientCG, CC0); see {@link Surface} for the maps each carries.
 */
public final class WorldTextures {
    public final Texture metal;
    public final Texture hazard;
    public final Texture white;
    /** Untextured surfaces (portal pads, window glass): white color, flat normal, no occlusion. */
    public final Surface plain;
    private final Texture flatNormal = solid(128, 128, 255);

    public final Surface grass;
    public final Surface concrete;
    public final Surface brushed;
    public final Surface plaster;
    public final Surface rock;
    public final Surface bark;
    public final Surface leaf;

    public WorldTextures() {
        Random rnd = new Random(42);
        metal = paint(256, rnd, WorldTextures::paintMetal);
        hazard = paint(128, rnd, WorldTextures::paintHazard);
        white = TextureGenerator.white();
        plain = new Surface(white, flatNormal, white, white);
        grass = photoSet("sparse_grass", white);
        concrete = photoSet("concrete_panels", white);
        brushed = photoSet("brushed_concrete_03", white);
        plaster = photoSet("plastered_wall_02", white);
        rock = photoSet("rock_face_03", white);
        bark = photoSet("knotted_pine_bark", white);
        leaf = new Surface(photo("leaf/color.jpg"), flatNormal, white, photo("leaf/opacity.jpg"));
    }

    /** A color, normal and ARM set from web/assets/textures/<set>/. */
    private static Surface photoSet(String set, Texture noOpacity) {
        return new Surface(photo(set + "/color.jpg"), photo(set + "/normal.jpg"), photo(set + "/arm.jpg"), noOpacity);
    }

    private static Texture photo(String path) {
        try (InputStream in = WorldTextures.class.getResourceAsStream("/assets/textures/" + path)) {
            if (in == null) throw new IOException("missing texture " + path);
            return Texture.fromImage(ImageIO.read(in), true, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Texture solid(int r, int g, int b) {
        ByteBuffer buf = ByteBuffer.allocateDirect(4).put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
        buf.flip();
        return new Texture(1, 1, buf, false, false);
    }

    private interface Painter {
        void paint(Graphics2D g, int size, Random r);
    }

    private static Texture paint(int size, Random rnd, Painter painter) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        painter.paint(g, size, rnd);
        g.dispose();
        return Texture.fromImage(img, true, true);
    }

    private static Color rgba(int r, int g, int b, double a) {
        return new Color(clamp(r), clamp(g), clamp(b), clamp((int) Math.round(a * 255)));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static void paintMetal(Graphics2D g, int size, Random r) {
        g.setColor(new Color(0xaab0bb));
        g.fillRect(0, 0, size, size);
        for (int i = 0; i < 3000; i++) {
            double a = r.nextDouble() * 0.06;
            g.setColor(r.nextBoolean() ? rgba(0, 0, 0, a) : rgba(255, 255, 255, a));
            g.fillRect((int) (r.nextDouble() * size), (int) (r.nextDouble() * size), 2, 2);
        }
        int tile = size / 4;
        g.setColor(rgba(30, 30, 35, 0.35));
        g.setStroke(new BasicStroke(2));
        for (int x = 0; x <= size; x += tile) g.drawLine(x, 0, x, size);
        for (int y = 0; y <= size; y += tile) g.drawLine(0, y, size, y);
        g.setColor(rgba(230, 230, 235, 0.9));
        for (int x = 0; x <= size; x += tile) {
            for (int y = 0; y <= size; y += tile) {
                g.fill(new Ellipse2D.Double(x + 6 - 2.5, y + 6 - 2.5, 5, 5));
                g.fill(new Ellipse2D.Double(x + tile - 6 - 2.5, y + 6 - 2.5, 5, 5));
            }
        }
    }

    private static void paintHazard(Graphics2D g, int size, Random r) {
        g.setColor(new Color(0xc81e1e));
        g.fillRect(0, 0, size, size);
        AffineTransform saved = g.getTransform();
        g.translate(size / 2.0, size / 2.0);
        g.rotate(Math.PI / 4);
        g.translate(-size, -size);
        g.setColor(new Color(0x1a1a1a));
        double stripe = size / 5.0;
        for (double x = 0; x < size * 3; x += stripe * 2) g.fillRect((int) x, 0, (int) stripe, size * 2);
        g.setTransform(saved);
    }

    public void cleanup() {
        metal.cleanup();
        hazard.cleanup();
        white.cleanup();
        flatNormal.cleanup();
        for (Surface s : new Surface[]{grass, concrete, brushed, plaster, rock, bark, leaf}) {
            s.color.cleanup();
            s.normal.cleanup();
            s.arm.cleanup();
            s.opacity.cleanup();
        }
    }
}
