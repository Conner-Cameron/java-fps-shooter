package com.conner.fps.game;

import com.conner.fps.data.Weapons;
import com.conner.fps.world.World;
import com.conner.fps.engine.Shader;
import com.conner.fps.render.CubeMesh;
import com.conner.fps.render.Texture;
import com.conner.fps.util.Ray;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Aim Training's target blocks (port of the web client's practice.js): each
 * block rolls its own size, hitpoints (between the weakest and strongest
 * weapon's damage, so one-shots aren't guaranteed) and drift/bob speeds at
 * spawn, holds them for its lifetime, and respawns elsewhere when destroyed.
 */
public final class Practice {
    private static final float SIZE_MIN = 0.6f, SIZE_MAX = 1.2f;
    private static final int COUNT = 6;
    private static final float ARENA_X = 34f, ARENA_Z_NEAR = -2f, ARENA_Z_FAR = 26f;

    public static final class Target {
        public float size;
        public int hp, maxHp;
        public final Vector3f home = new Vector3f();
        public final Vector3f position = new Vector3f();
        float horizSpeed, horizRadius, vertSpeed, vertRadius, phase, age;
        double flashUntil = 0.0;
    }

    private final Random random = new Random();
    private World scenery; // the map the blocks must keep clear of (set by spawnAll)
    private final List<Target> targets = new ArrayList<>();
    private final Matrix3f normalScratch = new Matrix3f();

    /** Spawns the blocks; they are placed clear of the world's trees, rocks and buildings (kept for respawns). */
    public void spawnAll(World world) {
        scenery = world;
        targets.clear();
        for (int i = 0; i < COUNT; i++) targets.add(newTarget());
    }

    public void clear() {
        targets.clear();
    }

    public List<Target> targets() {
        return targets;
    }

    private Target newTarget() {
        Target t = new Target();
        reroll(t);
        return t;
    }

    private void reroll(Target t) {
        t.size = SIZE_MIN + random.nextFloat() * (SIZE_MAX - SIZE_MIN);
        int lo = Weapons.minDamage(), hi = Weapons.maxDamage();
        t.maxHp = lo + random.nextInt(hi - lo + 1);
        t.hp = t.maxHp;
        t.horizSpeed = 0.3f + random.nextFloat() * 1.5f;
        t.horizRadius = 1f + random.nextFloat() * 3f;
        t.vertSpeed = 0.4f + random.nextFloat() * 2.0f;
        t.vertRadius = 0.3f + random.nextFloat() * 0.9f;
        t.phase = random.nextFloat() * (float) (Math.PI * 2);
        placeClear(t);
        t.age = 0f;
        t.flashUntil = 0f;
        move(t);
    }

    // A home where the block and its whole drift path (horizRadius, vertRadius, plus half its size) clear all scenery.
    private void placeClear(Target t) {
        float edge = t.size / 2f + 0.3f, mv = t.vertRadius + edge;
        for (int attempt = 0; attempt < 200; attempt++) {
            float y = 1f + random.nextFloat() * 3.5f;
            float x = (random.nextFloat() - 0.5f) * ARENA_X;
            float z = ARENA_Z_NEAR - random.nextFloat() * ARENA_Z_FAR;
            if (scenery == null || scenery.clearOf(x, y, z, edge, mv, edge)) {
                if (scenery != null) t.horizRadius = Math.max(0f, Math.min(t.horizRadius, scenery.sceneryGap(x, y, z, edge, mv)));
                t.home.set(x, y, z);
                return;
            }
        }
        t.horizRadius = 0.3f;
        t.home.set((random.nextFloat() - 0.5f) * ARENA_X, 1f + random.nextFloat() * 3.5f, ARENA_Z_NEAR - random.nextFloat() * ARENA_Z_FAR);
    }

    private void move(Target t) {
        float phased = t.age + t.phase;
        float dx = (float) Math.sin(phased * t.horizSpeed) * t.horizRadius;
        float dz = (float) Math.cos(phased * t.horizSpeed * 0.8f) * t.horizRadius;
        float dy = (float) Math.sin(phased * t.vertSpeed) * t.vertRadius;
        t.position.set(t.home.x + dx, Math.max(t.size / 2f + 0.1f, t.home.y + dy), t.home.z + dz);
    }

    public void update(float dt, double now) {
        for (Target t : targets) {
            if (t.flashUntil > 0 && now >= t.flashUntil) t.flashUntil = 0f;
            t.age += dt;
            move(t);
        }
    }

    /** Nearest target along the ray and its distance, or null. */
    public Target raycast(Vector3f origin, Vector3f dir, float maxDist, float[] outDistance) {
        Target best = null;
        float bestDist = Float.MAX_VALUE;
        for (Target t : targets) {
            float h = t.size / 2f;
            Float d = Ray.intersectAABB(origin, dir,
                    new Vector3f(t.position).sub(h, h, h), new Vector3f(t.position).add(h, h, h));
            if (d != null && d <= maxDist && d < bestDist) {
                bestDist = d;
                best = t;
            }
        }
        if (best != null) outDistance[0] = bestDist;
        return best;
    }

    /** Applies damage; returns true if it destroyed the block (which then respawns elsewhere with fresh stats). */
    public boolean damage(Target t, int amount, double now) {
        t.hp -= amount;
        if (t.hp <= 0) {
            reroll(t);
            return true;
        }
        t.flashUntil = (float) (now + 0.12); // briefly glow so a hit that didn't destroy it still reads as "damaged"
        return false;
    }

    public void render(Shader shader, CubeMesh cube, Texture hazard) {
        hazard.bind(0);
        for (Target t : targets) {
            float uv = Math.max(t.size / 1.2f, 0.5f);
            shader.setVec2("uvScale", uv, uv);
            shader.setVec3("color", 1f, 1f, 1f);
            if (t.flashUntil > 0) shader.setVec3("emissive", 0.33f, 0.27f, 0f);
            Matrix4f m = new Matrix4f().translate(t.position).scale(t.size);
            shader.setMat4("model", m);
            shader.setMat3("normalMatrix", m.normal(normalScratch));
            cube.render();
            if (t.flashUntil > 0) shader.setVec3("emissive", 0f, 0f, 0f);
        }
    }
}
