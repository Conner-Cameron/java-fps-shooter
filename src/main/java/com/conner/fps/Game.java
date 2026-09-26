package com.conner.fps;

import com.conner.fps.audio.HitSound;
import com.conner.fps.engine.Camera;
import com.conner.fps.engine.Input;
import com.conner.fps.engine.Shader;
import com.conner.fps.engine.Window;
import com.conner.fps.render.CubeMesh;
import com.conner.fps.render.Crosshair;
import com.conner.fps.render.EnemyModel;
import com.conner.fps.render.GunModel;
import com.conner.fps.render.HitEffect;
import com.conner.fps.render.SkyGradient;
import com.conner.fps.render.Texture;
import com.conner.fps.render.TextureGenerator;
import com.conner.fps.util.Ray;
import com.conner.fps.world.Obstacle;
import com.conner.fps.world.Target;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
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
    private static final float RECOIL_DURATION = 0.18f;
    private static final float TILE_WORLD_SIZE = 2.5f;

    private static final Vector3f LIGHT_DIR = new Vector3f(-0.4f, -1f, -0.35f).normalize();
    private static final Vector3f LIGHT_COLOR = new Vector3f(1f, 0.97f, 0.88f);
    private static final Vector3f AMBIENT_COLOR = new Vector3f(0.38f, 0.4f, 0.45f);
    private static final Vector3f SKY_TOP = new Vector3f(0.30f, 0.45f, 0.70f);
    private static final Vector3f SKY_BOTTOM = new Vector3f(0.72f, 0.80f, 0.86f);
    private static final float FOG_DENSITY = 0.018f;

    private Window window;
    private Camera camera;
    private Shader sceneShader;
    private Shader crosshairShader;
    private Shader skyShader;
    private CubeMesh cubeMesh;
    private Crosshair crosshair;
    private SkyGradient sky;
    private HitSound hitSound;

    private Texture floorTexture;
    private Texture metalTexture;
    private Texture whiteTexture;

    private final List<Obstacle> obstacles = new ArrayList<>();
    private final List<Target> targets = new ArrayList<>();
    private final List<HitEffect> hitEffects = new ArrayList<>();
    private final Random random = new Random();

    private int score = 0;
    private float hitFlashTimer = 0f;
    private float recoilTimer = 0f;
    private float elapsedTime = 0f;
    private float walkTime = 0f;
    private float movingFactor = 0f;
    private boolean isMoving = false;
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
        skyShader = new Shader("/shaders/sky_vertex.glsl", "/shaders/sky_fragment.glsl");
        cubeMesh = new CubeMesh();
        crosshair = new Crosshair();
        sky = new SkyGradient();
        hitSound = new HitSound();

        floorTexture = TextureGenerator.floor(256);
        metalTexture = TextureGenerator.metal(256);
        whiteTexture = TextureGenerator.white();

        buildWorld();

        lastFrameTime = glfwGetTime();
        updateTitle();
    }

    private void buildWorld() {
        // Ground plane
        obstacles.add(new Obstacle(new Vector3f(0, -0.5f, 0), new Vector3f(60, 1, 60),
                new float[]{0.85f, 0.86f, 0.8f}, Obstacle.Material.FLOOR));

        // Cover walls scattered around the arena
        obstacles.add(new Obstacle(new Vector3f(-6, 1.5f, -5), new Vector3f(2, 3, 2),
                new float[]{0.55f, 0.56f, 0.6f}, Obstacle.Material.WALL));
        obstacles.add(new Obstacle(new Vector3f(6, 1.5f, -8), new Vector3f(2, 3, 2),
                new float[]{0.55f, 0.56f, 0.6f}, Obstacle.Material.WALL));
        obstacles.add(new Obstacle(new Vector3f(0, 1.5f, -14), new Vector3f(8, 3, 1),
                new float[]{0.5f, 0.51f, 0.55f}, Obstacle.Material.WALL));
        obstacles.add(new Obstacle(new Vector3f(-11, 1.5f, -18), new Vector3f(2, 3, 2),
                new float[]{0.55f, 0.56f, 0.6f}, Obstacle.Material.WALL));
        obstacles.add(new Obstacle(new Vector3f(11, 1.5f, -18), new Vector3f(2, 3, 2),
                new float[]{0.55f, 0.56f, 0.6f}, Obstacle.Material.WALL));

        for (int i = 0; i < TARGET_COUNT; i++) {
            targets.add(new Target(randomTargetPosition(), random.nextFloat() * 10f));
        }
    }

    private Vector3f randomTargetPosition() {
        float x = (random.nextFloat() - 0.5f) * 34f;
        float z = -2f - random.nextFloat() * 26f;
        float y = Target.BOUNDS.y / 2f; // stand on the ground plane (top surface at y=0)
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

        isMoving = forward || backward || left || right;

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

        recoilTimer = RECOIL_DURATION;

        if (closestHit != null) {
            score++;
            hitEffects.add(new HitEffect(closestHit.getPosition(), random));
            hitSound.play();
            hitFlashTimer = HIT_FLASH_DURATION;
            closestHit.respawnAt(randomTargetPosition());
            updateTitle();
        }
    }

    private void update(float deltaTime) {
        elapsedTime += deltaTime;

        if (hitFlashTimer > 0f) {
            hitFlashTimer = Math.max(0f, hitFlashTimer - deltaTime);
        }
        if (recoilTimer > 0f) {
            recoilTimer = Math.max(0f, recoilTimer - deltaTime);
        }

        // Smooth toward moving/idle so the walk bob doesn't pop in or out.
        float targetFactor = isMoving ? 1f : 0f;
        movingFactor += (targetFactor - movingFactor) * Math.min(1f, deltaTime * 8f);
        if (isMoving) {
            walkTime += deltaTime * 9f;
        }

        hitEffects.removeIf(effect -> !effect.update(deltaTime));
        for (Target target : targets) {
            target.update(deltaTime);
        }
    }

    private void render() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        glDisable(GL_DEPTH_TEST);
        skyShader.use();
        skyShader.setVec3("topColor", SKY_TOP.x, SKY_TOP.y, SKY_TOP.z);
        skyShader.setVec3("bottomColor", SKY_BOTTOM.x, SKY_BOTTOM.y, SKY_BOTTOM.z);
        sky.render();
        glEnable(GL_DEPTH_TEST);

        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0),
                (float) window.getWidth() / (float) window.getHeight(),
                0.1f, 200f);
        Matrix4f view = camera.getViewMatrix();

        sceneShader.use();
        sceneShader.setMat4("projection", projection);
        sceneShader.setMat4("view", view);
        sceneShader.setVec3("viewPos", camera.position.x, camera.position.y, camera.position.z);
        sceneShader.setVec3("lightDir", LIGHT_DIR.x, LIGHT_DIR.y, LIGHT_DIR.z);
        sceneShader.setVec3("lightColor", LIGHT_COLOR.x, LIGHT_COLOR.y, LIGHT_COLOR.z);
        sceneShader.setVec3("ambientColor", AMBIENT_COLOR.x, AMBIENT_COLOR.y, AMBIENT_COLOR.z);
        sceneShader.setVec3("fogColor", SKY_BOTTOM.x, SKY_BOTTOM.y, SKY_BOTTOM.z);
        sceneShader.setFloat("fogDensity", FOG_DENSITY);
        sceneShader.setInt("tex", 0);

        for (Obstacle obstacle : obstacles) {
            Matrix4f model = new Matrix4f().translate(obstacle.position).scale(obstacle.size);
            sceneShader.setMat4("model", model);
            sceneShader.setMat3("normalMatrix", model.normal(new Matrix3f()));

            Vector2f uvScale = obstacle.material == Obstacle.Material.FLOOR
                    ? new Vector2f(obstacle.size.x, obstacle.size.z)
                    : new Vector2f(obstacle.size.x, obstacle.size.y);
            uvScale.div(TILE_WORLD_SIZE);
            sceneShader.setVec2("uvScale", uvScale.x, uvScale.y);

            (obstacle.material == Obstacle.Material.FLOOR ? floorTexture : metalTexture).bind(0);
            sceneShader.setVec3("color", obstacle.color[0], obstacle.color[1], obstacle.color[2]);
            cubeMesh.render();
        }

        for (Target target : targets) {
            if (!target.alive) continue;
            EnemyModel.render(sceneShader, cubeMesh, metalTexture, whiteTexture, target.getPosition(), target.getAnimTime());
        }

        whiteTexture.bind(0);
        sceneShader.setVec2("uvScale", 1f, 1f);
        for (HitEffect effect : hitEffects) {
            float fade = 1f - effect.progress();
            sceneShader.setVec3("color", 1f, 0.85f, 0.25f * fade + 0.1f);
            for (int i = 0; i < HitEffect.PARTICLE_COUNT; i++) {
                Matrix4f model = new Matrix4f()
                        .translate(effect.particlePosition(i))
                        .scale(effect.particleScale());
                sceneShader.setMat4("model", model);
                sceneShader.setMat3("normalMatrix", model.normal(new Matrix3f()));
                cubeMesh.render();
            }
        }

        // Weapon view-model: fresh depth buffer + identity view so it renders on
        // top of the world and stays anchored to the screen, like a real FPS HUD gun.
        glClear(GL_DEPTH_BUFFER_BIT);
        sceneShader.setMat4("view", new Matrix4f());
        sceneShader.setVec3("viewPos", 0f, 0f, 0f);
        GunModel.render(sceneShader, cubeMesh, metalTexture, whiteTexture,
                elapsedTime, walkTime, movingFactor, recoilTimer, RECOIL_DURATION);

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
        sky.cleanup();
        hitSound.cleanup();
        floorTexture.cleanup();
        metalTexture.cleanup();
        whiteTexture.cleanup();
        sceneShader.cleanup();
        crosshairShader.cleanup();
        skyShader.cleanup();
        window.destroy();
    }
}
