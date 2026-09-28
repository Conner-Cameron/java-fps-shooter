package com.conner.fps.engine;

import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_MULTISAMPLE;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Owns the GLFW window/context and wires input callbacks into {@link Input}.
 * The cursor is captured while playing and free while a menu is open.
 */
public class Window {
    private long handle;
    private int width;   // framebuffer pixels
    private int height;
    private int windowWidth;  // window (screen-coordinate) size, for cursor positions
    private int windowHeight;
    private final String title;
    private boolean cursorCaptured = false;
    private Runnable escapeHandler = () -> glfwSetWindowShouldClose(handle, true);

    public Window(int width, int height, String title) {
        this.width = width;
        this.height = height;
        this.windowWidth = width;
        this.windowHeight = height;
        this.title = title;
    }

    public void init() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_SAMPLES, 4);

        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        glfwSetKeyCallback(handle, (win, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                escapeHandler.run();
            }
            Input.keyCallback(key, action);
        });
        glfwSetCharCallback(handle, (win, codepoint) -> Input.charCallback(codepoint));
        glfwSetCursorPosCallback(handle, (win, xpos, ypos) -> Input.cursorPosCallback(xpos, ypos));
        glfwSetMouseButtonCallback(handle, (win, button, action, mods) -> Input.mouseButtonCallback(button, action));
        glfwSetScrollCallback(handle, (win, dx, dy) -> Input.scrollCallback(dy));
        glfwSetFramebufferSizeCallback(handle, (win, w, h) -> {
            this.width = w;
            this.height = h;
            glViewport(0, 0, w, h);
        });
        glfwSetWindowSizeCallback(handle, (win, w, h) -> {
            this.windowWidth = w;
            this.windowHeight = h;
        });

        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(handle, pWidth, pHeight);
            windowWidth = pWidth.get(0);
            windowHeight = pHeight.get(0);

            IntBuffer fbW = stack.mallocInt(1);
            IntBuffer fbH = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, fbW, fbH);
            width = fbW.get(0);
            height = fbH.get(0);

            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (vidmode != null) {
                glfwSetWindowPos(
                        handle,
                        (vidmode.width() - pWidth.get(0)) / 2,
                        (vidmode.height() - pHeight.get(0)) / 2
                );
            }
        }

        glfwMakeContextCurrent(handle);
        glfwSwapInterval(1); // v-sync

        glfwShowWindow(handle);
        GL.createCapabilities();

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_MULTISAMPLE);
        glClearColor(0.53f, 0.80f, 0.92f, 1.0f); // sky blue
    }

    /** What Esc does (default: close the window); the game swaps in pause/back handling. */
    public void setEscapeHandler(Runnable handler) {
        this.escapeHandler = handler;
    }

    public void setCursorCaptured(boolean captured) {
        if (captured == cursorCaptured) return;
        cursorCaptured = captured;
        glfwSetInputMode(handle, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        Input.resetMouseTracking();
    }

    public boolean isCursorCaptured() {
        return cursorCaptured;
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public void close() {
        glfwSetWindowShouldClose(handle, true);
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    public void pollEvents() {
        glfwPollEvents();
    }

    public void setTitle(String newTitle) {
        glfwSetWindowTitle(handle, newTitle);
    }

    public void destroy() {
        Callbacks.glfwFreeCallbacks(handle);
        glfwDestroyWindow(handle);
        glfwTerminate();
        GLFWErrorCallback previous = glfwSetErrorCallback(null);
        if (previous != null) {
            previous.free();
        }
    }

    public long getHandle() {
        return handle;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Cursor position in framebuffer pixels (the UI's coordinate space). */
    public double cursorX() {
        return windowWidth > 0 ? Input.mouseX * width / windowWidth : Input.mouseX;
    }

    public double cursorY() {
        return windowHeight > 0 ? Input.mouseY * height / windowHeight : Input.mouseY;
    }
}
