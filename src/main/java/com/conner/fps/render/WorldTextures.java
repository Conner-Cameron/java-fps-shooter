package com.conner.fps.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * The web game's procedural surface textures (metal panels, rock, bark,
 * foliage, hazard stripes, grass ground), painted with Java2D instead of a
 * canvas -- same recipes, so the two clients share one look.
 */
public final class WorldTextures {
    public final Texture metal;
    public final Texture rock;
    public final Texture bark;
    public final Texture foliage;
    public final Texture hazard;
    public final Texture ground;
    public final Texture white;

    public WorldTextures() {
        Random rnd = new Random(42);
        metal = paint(256, rnd, WorldTextures::paintMetal);
        rock = paint(256, rnd, WorldTextures::paintRock);
        bark = paint(128, rnd, WorldTextures::paintBark);
        foliage = paint(128, rnd, WorldTextures::paintFoliage);
        hazard = paint(128, rnd, WorldTextures::paintHazard);
        ground = paint(256, rnd, WorldTextures::paintGround);
        white = TextureGenerator.white();
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

    private static void paintRock(Graphics2D g, int size, Random r) {
        g.setColor(new Color(0x9a9a92));
        g.fillRect(0, 0, size, size);
        for (int i = 0; i < 3500; i++) {
            double a = r.nextDouble() * 0.18;
            g.setColor(r.nextBoolean() ? rgba(35, 32, 28, a) : rgba(215, 210, 198, a));
            double rad = 1 + r.nextDouble() * 3;
            double x = r.nextDouble() * size, y = r.nextDouble() * size;
            g.fill(new Ellipse2D.Double(x - rad, y - rad, rad * 2, rad * 2));
        }
        g.setColor(rgba(30, 28, 24, 0.35));
        g.setStroke(new BasicStroke(1));
        for (int i = 0; i < 24; i++) {
            double x = r.nextDouble() * size, y = r.nextDouble() * size;
            g.drawLine((int) x, (int) y, (int) (x + (r.nextDouble() - 0.5) * 50), (int) (y + (r.nextDouble() - 0.5) * 50));
        }
    }

    private static void paintBark(Graphics2D g, int size, Random r) {
        g.setColor(new Color(0x4a3020));
        g.fillRect(0, 0, size, size);
        for (int x = 0; x < size; x += 3) {
            double shade = r.nextDouble() * 35;
            g.setColor(rgba((int) (40 + shade), (int) (24 + shade * 0.6), (int) (12 + shade * 0.3), 0.35 + r.nextDouble() * 0.35));
            g.fillRect(x, 0, 2, size);
        }
    }

    private static void paintFoliage(Graphics2D g, int size, Random r) {
        g.setColor(new Color(0x356b35));
        g.fillRect(0, 0, size, size);
        for (int i = 0; i < 1200; i++) {
            double a = r.nextDouble() * 0.35;
            g.setColor(r.nextBoolean() ? rgba(15, 45, 15, a) : rgba(95, 155, 75, a));
            g.fillRect((int) (r.nextDouble() * size), (int) (r.nextDouble() * size), 3, 3);
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

    private static void paintGround(Graphics2D g, int size, Random r) {
        g.setColor(new Color(0x4d8c4d));
        g.fillRect(0, 0, size, size);
        for (int i = 0; i < 4000; i++) {
            int gr = 90 + r.nextInt(60);
            g.setColor(rgba(gr - 60, gr, gr - 60, 0.5));
            g.fillRect((int) (r.nextDouble() * size), (int) (r.nextDouble() * size), 2, 2);
        }
        for (int i = 0; i < 40; i++) {
            double rad = 6 + r.nextDouble() * 14;
            double x = r.nextDouble() * size, y = r.nextDouble() * size;
            AffineTransform saved = g.getTransform();
            g.translate(x, y);
            g.rotate(r.nextDouble() * Math.PI);
            g.setColor(rgba(120, 100, 60, 0.25));
            g.fill(new Ellipse2D.Double(-rad, -rad * 0.6, rad * 2, rad * 1.2));
            g.setTransform(saved);
        }
    }

    public void cleanup() {
        metal.cleanup();
        rock.cleanup();
        bark.cleanup();
        foliage.cleanup();
        hazard.cleanup();
        ground.cleanup();
        white.cleanup();
    }
}
