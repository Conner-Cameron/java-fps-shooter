package com.conner.fps.engine;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Tracks keyboard/mouse state via GLFW callbacks so the rest of the game can
 * just poll simple static state each frame. "Held" state persists; "pressed"
 * edges, the scroll wheel and typed characters are one-frame events that the
 * game clears with {@link #endFrame()}. A scripted self-test can drive the
 * game by writing to these same fields.
 */
public final class Input {
    public static final boolean[] keys = new boolean[350];
    public static final boolean[] keyPressed = new boolean[350];
    public static final boolean[] mouseButtons = new boolean[8];
    public static final boolean[] mousePressed = new boolean[8];

    public static double mouseDeltaX = 0;
    public static double mouseDeltaY = 0;
    public static double mouseX = 0; // window pixels, top-left origin (valid while the cursor is free)
    public static double mouseY = 0;
    public static double scrollY = 0;
    public static final StringBuilder typed = new StringBuilder();
    public static boolean backspacePressed = false;

    private static boolean firstMouse = true;
    private static double lastX = 0;
    private static double lastY = 0;

    private Input() {
    }

    public static void keyCallback(int key, int action) {
        if (key < 0 || key >= keys.length) return;
        if (action == GLFW_PRESS) {
            keys[key] = true;
            keyPressed[key] = true;
            if (key == GLFW_KEY_BACKSPACE) backspacePressed = true;
        } else if (action == GLFW_RELEASE) {
            keys[key] = false;
        } else if (action == GLFW_REPEAT && key == GLFW_KEY_BACKSPACE) {
            backspacePressed = true;
        }
    }

    public static void charCallback(int codepoint) {
        if (codepoint >= 32 && codepoint < 127) typed.append((char) codepoint);
    }

    public static void cursorPosCallback(double xpos, double ypos) {
        mouseX = xpos;
        mouseY = ypos;
        if (firstMouse) {
            lastX = xpos;
            lastY = ypos;
            firstMouse = false;
        }
        mouseDeltaX += xpos - lastX;
        mouseDeltaY += ypos - lastY;
        lastX = xpos;
        lastY = ypos;
    }

    public static void mouseButtonCallback(int button, int action) {
        if (button < 0 || button >= mouseButtons.length) return;
        if (action == GLFW_PRESS) {
            mouseButtons[button] = true;
            mousePressed[button] = true;
        } else if (action == GLFW_RELEASE) {
            mouseButtons[button] = false;
        }
    }

    public static void scrollCallback(double dy) {
        scrollY += dy;
    }

    /** Called when the cursor is captured/released so the first movement afterwards doesn't jump. */
    public static void resetMouseTracking() {
        firstMouse = true;
        mouseDeltaX = 0;
        mouseDeltaY = 0;
    }

    public static void resetMouseDelta() {
        mouseDeltaX = 0;
        mouseDeltaY = 0;
    }

    /** Clears the one-frame events; call once at the end of each frame. */
    public static void endFrame() {
        java.util.Arrays.fill(keyPressed, false);
        java.util.Arrays.fill(mousePressed, false);
        scrollY = 0;
        typed.setLength(0);
        backspacePressed = false;
    }
}
