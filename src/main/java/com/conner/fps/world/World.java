package com.conner.fps.world;

import com.conner.fps.data.MapData;
import com.conner.fps.engine.Shader;
import com.conner.fps.render.CubeMesh;
import com.conner.fps.render.Drawable;
import com.conner.fps.render.ShapeMesh;
import com.conner.fps.render.Shapes;
import com.conner.fps.render.Texture;
import com.conner.fps.render.WorldTextures;
import com.conner.fps.util.Ray;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

/**
 * The static level, built from the shared map.json: ground, cover walls, the
 * multi-floor building, the spiral ramp, trees, rocks and the mountain ring.
 * Owns both the things to draw and the collision/bullet queries against
 * them (a port of the web client's collision.js, so movement feels alike).
 */
public final class World {
    public static final float PLAYER_RADIUS = 0.35f;
    public static final float PLAYER_HEIGHT = 1.8f;
    public static final float EYE_HEIGHT = 1.7f;
    private static final float STAND_CLEARANCE = 0.05f;
    private static final float RAMP_STAND_CLEARANCE = 0.08f;
    private static final float GROUND_PROBE_UP = 0.4f;
    private static final float GROUND_SNAP_MARGIN = 0.1f;
    private static final float PORTAL_VERT_TOLERANCE = 1.2f;
    private static final float CLIMB_RANGE = 1.3f;        // horizontal reach, from the box's nearest face
    private static final float CLIMB_LAND_MARGIN = 0.3f;  // once feet are this close to the top, it's "climbed" already

    /** A climbable box: pressing Space within {@link #CLIMB_RANGE} of it (see {@link #climbableAt}) starts a timed climb onto it. */
    public static final class Climbable {
        public final float minX, maxX, minZ, maxZ, topY, centerX, centerZ;
        public final long durationMs;

        Climbable(float[] c, float[] s, long durationMs) {
            minX = c[0] - s[0] / 2;
            maxX = c[0] + s[0] / 2;
            minZ = c[2] - s[2] / 2;
            maxZ = c[2] + s[2] / 2;
            topY = c[1] + s[1] / 2;
            centerX = c[0];
            centerZ = c[2];
            this.durationMs = durationMs;
        }
    }

    /** An axis-aligned solid: walkable-on / blocks movement, and (optionally) stops bullets. */
    private static final class Solid {
        final float[] min = new float[3];
        final float[] max = new float[3];
        final boolean blocksBullets;

        Solid(float[] c, float[] s, boolean blocksBullets) {
            for (int i = 0; i < 3; i++) {
                min[i] = c[i] - s[i] / 2f;
                max[i] = c[i] + s[i] / 2f;
            }
            this.blocksBullets = blocksBullets;
        }
    }

    private static final class Prop {
        final Drawable mesh;
        final Matrix4f model;
        final Texture texture;
        final float[] tint;
        final float uvX, uvY;
        final float alpha;

        Prop(Drawable mesh, Matrix4f model, Texture texture, float[] tint, float uvX, float uvY, float alpha) {
            this.mesh = mesh;
            this.model = model;
            this.texture = texture;
            this.tint = tint;
            this.uvX = uvX;
            this.uvY = uvY;
            this.alpha = alpha;
        }
    }

    private final List<Solid> solids = new ArrayList<>();
    private final List<Solid> bulletOnly = new ArrayList<>(); // trees and rocks: stop gunfire but aren't walked into
    private final List<Ramp> ramps = new ArrayList<>();
    private final List<MapData.Portal> portals = new ArrayList<>();
    private final List<Climbable> climbables = new ArrayList<>();
    private final List<Prop> opaque = new ArrayList<>();
    private final List<Prop> translucent = new ArrayList<>();
    private final List<ShapeMesh> ownedMeshes = new ArrayList<>();
    private final CubeMesh cube;
    private final Matrix3f normalScratch = new Matrix3f();

    public World(MapData map, CubeMesh cube, WorldTextures tex) {
        this.cube = cube;
        ShapeMesh trunk = own(Shapes.cylinder(6, 0.15f, 0.22f));
        ShapeMesh leaves = own(Shapes.cone(7));
        ShapeMesh rock = own(Shapes.rock());

        for (MapData.Box b : map.boxes) {
            solids.add(new Solid(b.center, b.size, b.blocksBullets));
            Matrix4f m = new Matrix4f().translate(b.center[0], b.center[1], b.center[2]).scale(b.size[0], b.size[1], b.size[2]);
            if (b.ground) {
                opaque.add(new Prop(cube, m, tex.ground, new float[]{1, 1, 1}, 20f, 20f, 1f));
            } else if (b.glass) {
                translucent.add(new Prop(cube, m, tex.white, new float[]{0.56f, 0.82f, 0.90f}, 1f, 1f, 0.28f));
            } else {
                float t = Math.max(b.tile, 0.01f);
                opaque.add(new Prop(cube, m, tex.metal, b.color, Math.max(b.size[0] / t, 0.5f), Math.max(b.size[1] / t, 0.5f), 1f));
            }
            if (b.climbMs > 0) climbables.add(new Climbable(b.center, b.size, b.climbMs));
        }

        for (MapData.Ramp r : map.ramps) {
            Ramp ramp = new Ramp(r.a, r.b, r.width, r.thickness);
            ramps.add(ramp);
            opaque.add(new Prop(cube, ramp.model, tex.metal, new float[]{0.604f, 0.588f, 0.549f},
                    Math.max(r.width / 2f, 0.5f), Math.max(ramp.length / 2f, 0.5f), 1f));
        }

        for (float[] t : map.trees) {
            float x = t[0], z = t[1];
            opaque.add(new Prop(trunk, new Matrix4f().translate(x, 0.8f, z).scale(1f, 1.6f, 1f), tex.bark, new float[]{1, 1, 1}, 1f, 1f, 1f));
            opaque.add(new Prop(leaves, new Matrix4f().translate(x, 2.4f, z).scale(2.2f, 2.4f, 2.2f), tex.foliage, new float[]{1, 1, 1}, 1f, 1f, 1f));
            bulletOnly.add(new Solid(new float[]{x, 1.8f, z}, new float[]{1.6f, 3.6f, 1.6f}, true));
        }
        for (float[] r : map.rocks) {
            float x = r[0], z = r[1], s = r[2];
            Matrix4f m = new Matrix4f().translate(x, 0.3f * s, z).rotateXYZ(s * 1.3f, s * 2.1f, 0f).scale(1.2f * s);
            opaque.add(new Prop(rock, m, tex.rock, new float[]{0.541f, 0.541f, 0.525f}, 1f, 1f, 1f));
            bulletOnly.add(new Solid(new float[]{x, 0.3f * s, z}, new float[]{1.2f * s, 1.2f * s, 1.2f * s}, true));
        }

        // Portals: a glowing floor pad plus an energy column, built from the same uniform-radius
        // cylinder (radius/height 1, scaled to fit) -- purely visual markers, not solid or bullet-blocking;
        // the teleport itself is handled by portalAt() below (and authoritatively by the server).
        ShapeMesh portalShape = own(Shapes.cylinder(20, 1f, 1f));
        for (MapData.Portal p : map.portals) {
            portals.add(p);
            float feetY = p.center[1] - EYE_HEIGHT;
            float[] glow = {0.33f, 0.80f, 0.95f};
            Matrix4f pad = new Matrix4f().translate(p.center[0], feetY + 0.03f, p.center[2]).scale(p.radius, 0.06f, p.radius);
            opaque.add(new Prop(portalShape, pad, tex.white, glow, 1f, 1f, 1f));
            Matrix4f beam = new Matrix4f().translate(p.center[0], feetY + 1.6f, p.center[2]).scale(p.radius * 0.55f, 3.2f, p.radius * 0.55f);
            translucent.add(new Prop(portalShape, beam, tex.white, glow, 1f, 1f, 0.22f));
        }

        // Distant mountain ring so the arena doesn't float in a void.
        ShapeMesh[] cones = {own(Shapes.cone(5)), own(Shapes.cone(6)), own(Shapes.cone(7))};
        for (int i = 0; i < 16; i++) {
            double angle = (i / 16.0) * Math.PI * 2;
            float dist = 78 + ((i * 37) % 17) - 8;
            float h = 20 + ((i * 53) % 20);
            float r = 10 + ((i * 29) % 8);
            Matrix4f m = new Matrix4f()
                    .translate((float) Math.cos(angle) * dist, h / 2f - 2f, (float) Math.sin(angle) * dist)
                    .rotateY(i * 0.7f).scale(r * 2f, h, r * 2f);
            opaque.add(new Prop(cones[i % 3], m, tex.rock, new float[]{0.357f, 0.416f, 0.525f}, r / 3f, h / 3f, 1f));
        }
    }

    private ShapeMesh own(ShapeMesh m) {
        ownedMeshes.add(m);
        return m;
    }

    // ------------------------------------------------------------------ rendering

    /** Draws opaque geometry, then the translucent window glass. The scene shader must already be set up. */
    public void render(Shader shader) {
        for (Prop p : opaque) draw(shader, p);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        for (Prop p : translucent) draw(shader, p);
        glDepthMask(true);
        glDisable(GL_BLEND);
        shader.setFloat("alpha", 1f);
    }

    private void draw(Shader shader, Prop p) {
        shader.setMat4("model", p.model);
        shader.setMat3("normalMatrix", p.model.normal(normalScratch));
        shader.setVec2("uvScale", p.uvX, p.uvY);
        shader.setVec3("color", p.tint[0], p.tint[1], p.tint[2]);
        shader.setFloat("alpha", p.alpha);
        p.texture.bind(0);
        p.mesh.render();
    }

    // ------------------------------------------------------------------ collision (port of web/js/collision.js)

    /**
     * Casts down from just above the player's feet, far enough to cover this
     * frame's fall, and returns the surface Y it lands on (NaN if none) --
     * not from high above, which would find a floor several stories up.
     */
    public float findGroundY(float x, float z, float feetY, float fallDistance) {
        float[] o = {x, feetY + GROUND_PROBE_UP, z};
        float[] d = {0, -1, 0};
        float far = GROUND_PROBE_UP + fallDistance + GROUND_SNAP_MARGIN;
        float best = Float.MAX_VALUE;
        for (Solid s : solids) {
            float t = Ray.aabbEntryDistance(o, d, s.min, s.max);
            if (t >= 0 && t <= far && t < best) best = t;
        }
        for (Ramp r : ramps) {
            float t = r.rayDistance(o, d, false);
            if (t >= 0 && t <= far && t < best) best = t;
        }
        return best == Float.MAX_VALUE ? Float.NaN : o[1] - best;
    }

    /** Does a standing player at (x, feetY, z) overlap any wall/floor/ramp? */
    public boolean collidesAt(float x, float feetY, float z) {
        float headY = feetY + PLAYER_HEIGHT;
        float minX = x - PLAYER_RADIUS, maxX = x + PLAYER_RADIUS;
        float minY = feetY + STAND_CLEARANCE, minZ = z - PLAYER_RADIUS, maxZ = z + PLAYER_RADIUS;
        for (Solid s : solids) {
            if (maxX < s.min[0] || minX > s.max[0]) continue;
            if (headY < s.min[1] || minY > s.max[1]) continue;
            if (maxZ < s.min[2] || minZ > s.max[2]) continue;
            return true;
        }
        return collidesWithRamps(x, feetY, headY, z);
    }

    /**
     * Ramps get a precise check in each ramp's own local frame: standing on a
     * ramp's own surface (local Y at/above its top face) is told apart from
     * another loop of the spiral passing through this region at the wrong height.
     */
    public boolean collidesWithRamps(float x, float feetY, float headY, float z) {
        Vector3f feet = new Vector3f();
        Vector3f head = new Vector3f();
        for (Ramp r : ramps) {
            feet.set(x, feetY, z);
            r.inverse.transformPosition(feet);
            if (Math.abs(feet.x) > r.halfW + PLAYER_RADIUS) continue;
            if (Math.abs(feet.z) > r.halfL + PLAYER_RADIUS) continue;
            if (feet.y >= r.halfT - RAMP_STAND_CLEARANCE) continue;

            head.set(x, headY, z);
            r.inverse.transformPosition(head);
            if (head.y <= -r.halfT) continue;
            return true;
        }
        return false;
    }

    /** The lowest ceiling underside between the player's head and where it would end up this frame, or NaN. */
    public float findCeilingY(float x, float z, float headY, float proposedHeadY) {
        float closest = Float.NaN;
        for (Solid s : solids) {
            if (x + PLAYER_RADIUS < s.min[0] || x - PLAYER_RADIUS > s.max[0]) continue;
            if (z + PLAYER_RADIUS < s.min[2] || z - PLAYER_RADIUS > s.max[2]) continue;
            if (s.min[1] >= headY && s.min[1] <= proposedHeadY) {
                if (Float.isNaN(closest) || s.min[1] < closest) closest = s.min[1];
            }
        }
        return closest;
    }

    /**
     * The portal whose trigger volume (eye position, x/z radius + a generous vertical reach) contains
     * {@code eyePos}, or null. No input needed to use one -- overlapping it is enough, same as the
     * server's own check, which has final say in PvP (see GameServer's handleState).
     */
    public MapData.Portal portalAt(Vector3f eyePos) {
        for (MapData.Portal p : portals) {
            float dx = eyePos.x - p.center[0], dz = eyePos.z - p.center[2];
            if (dx * dx + dz * dz <= p.radius * p.radius && Math.abs(eyePos.y - p.center[1]) <= PORTAL_VERT_TOLERANCE) return p;
        }
        return null;
    }

    /** The climbable box {@code eyePos} is within reach of and still below the top of, or null. */
    public Climbable climbableAt(Vector3f eyePos) {
        float feetY = eyePos.y - EYE_HEIGHT;
        for (Climbable c : climbables) {
            if (feetY >= c.topY - CLIMB_LAND_MARGIN) continue;
            float nearestX = Math.max(c.minX, Math.min(eyePos.x, c.maxX));
            float nearestZ = Math.max(c.minZ, Math.min(eyePos.z, c.maxZ));
            float dx = eyePos.x - nearestX, dz = eyePos.z - nearestZ;
            if (dx * dx + dz * dz <= CLIMB_RANGE * CLIMB_RANGE) return c;
        }
        return null;
    }

    /** Distance to the nearest thing that stops a bullet/knife along the ray (walls, building, ramps, trees, rocks, ground), or +infinity. */
    public float nearestBlocker(Vector3f origin, Vector3f dir) {
        float[] o = {origin.x, origin.y, origin.z};
        float[] d = {dir.x, dir.y, dir.z};
        float best = Float.POSITIVE_INFINITY;
        for (Solid s : solids) {
            if (!s.blocksBullets) continue;
            float t = Ray.aabbDistance(o, d, s.min, s.max);
            if (t >= 0 && t < best) best = t;
        }
        for (Solid s : bulletOnly) {
            float t = Ray.aabbDistance(o, d, s.min, s.max);
            if (t >= 0 && t < best) best = t;
        }
        for (Ramp r : ramps) {
            float t = r.rayDistance(o, d, true);
            if (t >= 0 && t < best) best = t;
        }
        return best;
    }

    public void cleanup() {
        for (ShapeMesh m : ownedMeshes) m.cleanup();
    }
}
