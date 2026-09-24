package com.conner.fps.engine;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Tracks keyboard/mouse state via GLFW callbacks so the rest of the game
 * can just poll simple static state each frame.
 */
public class Input {
    public static final boolean[] keys = new boolean[350];

    public static double mouseDeltaX = 0;
    public static double mouseDeltaY = 0;
    public static boolean firstMouse = true;
    public static boolean leftClicked = false;

    private static double lastX = 0;
    private static double lastY = 0;

    public static void keyCallback(int key, int action) {
        if (key < 0 || key >= keys.length) return;
        if (action == GLFW_PRESS) {
            keys[key] = true;
        } else if (action == GLFW_RELEASE) {
            keys[key] = false;
        }
    }

    public static void cursorPosCallback(double xpos, double ypos) {
        if (firstMouse) {
            lastX = xpos;
            lastY = ypos;
            firstMouse = false;
        }
        mouseDeltaX = xpos - lastX;
        mouseDeltaY = ypos - lastY;
        lastX = xpos;
        lastY = ypos;
    }

    public static void resetMouseDelta() {
        mouseDeltaX = 0;
        mouseDeltaY = 0;
    }

    public static void mouseButtonCallback(int button, int action) {
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            leftClicked = true;
        }
    }
}
