package com.conner.fps.engine;

import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/** Saves the current framebuffer as a PNG (used by the scripted self-test to check what's on screen). */
public final class Screenshot {
    private Screenshot() {
    }

    public static void save(int width, int height, File file) {
        ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 3);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadBuffer(GL_BACK);
        glReadPixels(0, 0, width, height, GL_RGB, GL_UNSIGNED_BYTE, buf);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = ((height - 1 - y) * width + x) * 3;
                int r = buf.get(i) & 255, g = buf.get(i + 1) & 255, b = buf.get(i + 2) & 255;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        try {
            file.getParentFile().mkdirs();
            ImageIO.write(img, "png", file);
        } catch (IOException e) {
            System.err.println("Could not save screenshot " + file + ": " + e);
        }
    }
}
