package com.conner.fps;

import com.conner.fps.audio.HitSound;
import com.conner.fps.engine.Camera;
import com.conner.fps.engine.Input;
import com.conner.fps.engine.Shader;
import com.conner.fps.engine.Window;
import com.conner.fps.render.CubeMesh;
import com.conner.fps.render.Crosshair;
import com.conner.fps.render.HitEffect;
import com.conner.fps.util.Ray;
import com.conner.fps.world.Obstacle;
import com.conner.fps.world.Target;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Owns the game loop: input -> update -> render. This is intentionally a
 * small, single-arena "shooting range" style FPS -- a solid starting point
 * to build levels, enemies, weapons, etc. on top of.
 */
public class Game {
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final int TARGET_COUNT = 6;
    private static final float HIT_FLASH_DURATION = 0.15f;

    private Window window;
    private Camera camera;
    private Shader sceneShader;
    private Shader crosshairShader;
    private CubeMesh cubeMesh;
    private Crosshair crosshair;
    private HitSound hitSound;

    private final List<Obstacle> obstacles = new ArrayList<>();
    private final List<Target> targets = new ArrayList<>();
    private final List<HitEffect> hitEffects = new ArrayList<>();
    private final Random random = new Random();

    private int score = 0;
    private float hitFlashTimer = 0f;
    private double lastFrameTime;

    public void run() {
        init();
        loop();
        cleanup();
    }

    private void init() {
        window = new Window(WINDOW_WIDTH, WINDOW_HEIGHT, "Java FPS Shooter");
        window.init();

        camera = new Camera(new Vector3f(0, 1.7f, 8));
        sceneShader = new Shader("/shaders/vertex.glsl", "/shaders/fragment.glsl");
        crosshairShader = new Shader("/shaders/crosshair_vertex.glsl", "/shaders/crosshair_fragment.glsl");
        cubeMesh = new CubeMesh();
        crosshair = new Crosshair();
        hitSound = new HitSound();

        buildWorld();

        lastFrameTime = glfwGetTime();
        updateTitle();
    }

    private void buildWorld() {
        // Ground plane
        obstacles.add(new Obstacle(new Vector3f(0, -0.5f, 0), new Vector3f(60, 1, 60),
                new float[]{0.30f, 0.55f, 0.30f}));

        // Cover walls scattered around the arena
        obstacles.add(new Obstacle(new Vector3f(-6, 1.5f, -5), new Vector3f(2, 3, 2), new float[]{0.5f, 0.5f, 0.55f}));
        obstacles.add(new Obstacle(new Vector3f(6, 1.5f, -8), new Vector3f(2, 3, 2), new float[]{0.5f, 0.5f, 0.55f}));
        obstacles.add(new Obstacle(new Vector3f(0, 1.5f, -14), new Vector3f(8, 3, 1), new float[]{0.45f, 0.45f, 0.5f}));
        obstacles.add(new Obstacle(new Vector3f(-11, 1.5f, -18), new Vector3f(2, 3, 2), new float[]{0.5f, 0.5f, 0.55f}));
        obstacles.add(new Obstacle(new Vector3f(11, 1.5f, -18), new Vector3f(2, 3, 2), new float[]{0.5f, 0.5f, 0.55f}));

        for (int i = 0; i < TARGET_COUNT; i++) {
            targets.add(new Target(randomTargetPosition()));
        }
    }

    private Vector3f randomTargetPosition() {
        float x = (random.nextFloat() - 0.5f) * 34f;
        float y = 1f + random.nextFloat() * 3.5f;
        float z = -2f - random.nextFloat() * 26f;
        return new Vector3f(x, y, z);
    }

    private void loop() {
        while (!window.shouldClose()) {
            double now = glfwGetTime();
            float deltaTime = (float) (now - lastFrameTime);
            lastFrameTime = now;

            processInput(deltaTime);
            update(deltaTime);
            render();

            window.swapBuffers();
            window.pollEvents();
        }
    }

    private void processInput(float deltaTime) {
        camera.processMouseMovement((float) Input.mouseDeltaX, (float) Input.mouseDeltaY);
        Input.resetMouseDelta();

        boolean forward = Input.keys[GLFW_KEY_W];
        boolean backward = Input.keys[GLFW_KEY_S];
        boolean left = Input.keys[GLFW_KEY_A];
        boolean right = Input.keys[GLFW_KEY_D];
        boolean ascend = Input.keys[GLFW_KEY_SPACE];
        boolean descend = Input.keys[GLFW_KEY_LEFT_SHIFT];

        camera.processKeyboard(forward, backward, left, right, ascend, descend, deltaTime);

        if (Input.leftClicked) {
            Input.leftClicked = false;
            shoot();
        }
    }

    private void shoot() {
        Vector3f origin = new Vector3f(camera.position);
        Vector3f dir = camera.getFront();

        Target closestHit = null;
        float closestDistance = Float.MAX_VALUE;

        for (Target target : targets) {
            if (!target.alive) continue;
            Float distance = Ray.intersectAABB(origin, dir, target.getMin(), target.getMax());
            if (distance != null && distance < closestDistance) {
                closestDistance = distance;
                closestHit = target;
            }
        }

        if (closestHit != null) {
            score++;
            hitEffects.add(new HitEffect(closestHit.position, random));
            hitSound.play();
            hitFlashTimer = HIT_FLASH_DURATION;
            closestHit.position = randomTargetPosition();
            updateTitle();
        }
    }

    private void update(float deltaTime) {
        if (hitFlashTimer > 0f) {
            hitFlashTimer = Math.max(0f, hitFlashTimer - deltaTime);
        }
        hitEffects.removeIf(effect -> !effect.update(deltaTime));
    }

    private void render() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0),
                (float) window.getWidth() / (float) window.getHeight(),
                0.1f, 200f);
        Matrix4f view = camera.getViewMatrix();

        sceneShader.use();
        sceneShader.setMat4("projection", projection);
        sceneShader.setMat4("view", view);

        for (Obstacle obstacle : obstacles) {
            Matrix4f model = new Matrix4f().translate(obstacle.position).scale(obstacle.size);
            sceneShader.setMat4("model", model);
            sceneShader.setVec3("color", obstacle.color[0], obstacle.color[1], obstacle.color[2]);
            cubeMesh.render();
        }

        for (Target target : targets) {
            if (!target.alive) continue;
            Matrix4f model = new Matrix4f().translate(target.position).scale(target.size);
            sceneShader.setMat4("model", model);
            sceneShader.setVec3("color", target.color[0], target.color[1], target.color[2]);
            cubeMesh.render();
        }

        for (HitEffect effect : hitEffects) {
            float fade = 1f - effect.progress();
            sceneShader.setVec3("color", 1f, 0.85f, 0.25f * fade + 0.1f);
            for (int i = 0; i < HitEffect.PARTICLE_COUNT; i++) {
                Matrix4f model = new Matrix4f()
                        .translate(effect.particlePosition(i))
                        .scale(effect.particleScale());
                sceneShader.setMat4("model", model);
                cubeMesh.render();
            }
        }

        glDisable(GL_DEPTH_TEST);
        crosshairShader.use();
        if (hitFlashTimer > 0f) {
            crosshairShader.setVec3("color", 0.25f, 1f, 0.4f);
        } else {
            crosshairShader.setVec3("color", 1f, 1f, 1f);
        }
        crosshair.render();
        if (hitFlashTimer > 0f) {
            crosshairShader.setVec3("color", 1f, 0.85f, 0.2f);
            crosshair.renderHitMarker();
        }
        glEnable(GL_DEPTH_TEST);
    }

    private void updateTitle() {
        window.setTitle("Java FPS Shooter | Score: " + score);
    }

    private void cleanup() {
        cubeMesh.cleanup();
        crosshair.cleanup();
        hitSound.cleanup();
        sceneShader.cleanup();
        crosshairShader.cleanup();
        window.destroy();
    }
}
