package com.conner.fps.world;

import com.conner.fps.data.MapData;
import com.conner.fps.engine.Shader;
import com.conner.fps.render.CubeMesh;
import com.conner.fps.render.Drawable;
import com.conner.fps.render.ShapeMesh;
import com.conner.fps.render.Surface;
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
        final Surface surface;
        final float[] tint;
        final float uvX, uvY;
        final float alpha;
        final float cutout; // > 0: leaf-card cutout threshold (see surface_fragment.glsl)

        Prop(Drawable mesh, Matrix4f model, Surface surface, float[] tint, float uvX, float uvY, float alpha) {
            this(mesh, model, surface, tint, uvX, uvY, alpha, 0f);
        }

        Prop(Drawable mesh, Matrix4f model, Surface surface, float[] tint, float uvX, float uvY, float alpha, float cutout) {
            this.mesh = mesh;
            this.model = model;
            this.surface = surface;
            this.tint = tint;
            this.uvX = uvX;
            this.uvY = uvY;
            this.alpha = alpha;
            this.cutout = cutout;
        }
    }

    private static final float[] WHITE = {1f, 1f, 1f};
    // Web client's tint values (web/js/world.js): grass ground, and map-box colors pulled toward white.
    private static final float[] GRASS_TINT = {0.706f, 0.878f, 0.478f};
    private static final float TINT_SOFTEN = 0.4f;
    private static final int CANOPY_CARDS = 70;
    private static final float CANOPY_CENTER_Y = 2.5f;
    private static final float LEAF_CUTOUT = 0.5f;
    // The leaf photo is bright yellow-green under the desktop lights; pulled down toward the web client's deeper canopy.
    private static final float[] LEAF_TINT = {0.55f, 0.72f, 0.5f};

    private final List<Solid> solids = new ArrayList<>();
    private final List<Solid> bulletOnly = new ArrayList<>(); // trees and rocks: stop gunfire but aren't walked into
    private final List<Ramp> ramps = new ArrayList<>();
    private final List<MapData.Portal> portals = new ArrayList<>();
    private final List<Climbable> climbables = new ArrayList<>();
    private final List<Prop> opaque = new ArrayList<>();
    private final List<Prop> translucent = new ArrayList<>();
    private final List<ShapeMesh> ownedMeshes = new ArrayList<>();
    private final List<CubeMesh> ownedCubes = new ArrayList<>();
    private final Matrix3f normalScratch = new Matrix3f();

    public World(MapData map, WorldTextures tex) {
        CubeMesh unit = ownCube(new CubeMesh());
        ShapeMesh trunk = own(Shapes.cylinder(6, 0.15f, 0.22f));
        ShapeMesh rock = own(Shapes.rock());

        for (MapData.Box b : map.boxes) {
            solids.add(new Solid(b.center, b.size, b.blocksBullets));
            Matrix4f m = new Matrix4f().translate(b.center[0], b.center[1], b.center[2]).scale(b.size[0], b.size[1], b.size[2]);
            if (b.ground) {
                // One grass tile every ~6 units, matching the web client (its UVs are baked into the cube).
                CubeMesh floor = ownCube(new CubeMesh(b.size[0], b.size[1], b.size[2], 6f));
                opaque.add(new Prop(floor, m, tex.grass, GRASS_TINT, 1f, 1f, 1f));
            } else if (b.glass) {
                translucent.add(new Prop(unit, m, tex.plain, new float[]{0.56f, 0.82f, 0.90f}, 1f, 1f, 0.28f));
            } else {
                // Same surface choice as the web client: towers brushed concrete, the building plaster, the rest concrete.
                Surface surface = b.climbMs > 0 ? tex.brushed : b.building ? tex.plaster : tex.concrete;
                CubeMesh solid = ownCube(new CubeMesh(b.size[0], b.size[1], b.size[2], Math.max(b.tile, 0.01f)));
                opaque.add(new Prop(solid, m, surface, softenTint(b.color), 1f, 1f, 1f));
            }
            if (b.climbMs > 0) climbables.add(new Climbable(b.center, b.size, b.climbMs));
        }

        for (MapData.Ramp r : map.ramps) {
            Ramp ramp = new Ramp(r.a, r.b, r.width, r.thickness);
            ramps.add(ramp);
            CubeMesh rampMesh = ownCube(new CubeMesh(r.width, r.thickness, ramp.length, 2.5f));
            opaque.add(new Prop(rampMesh, ramp.model, tex.concrete, new float[]{0.706f, 0.694f, 0.659f}, 1f, 1f, 1f));
        }

        for (float[] t : map.trees) {
            float x = t[0], z = t[1];
            opaque.add(new Prop(trunk, new Matrix4f().translate(x, 0.8f, z).scale(1f, 1.6f, 1f), tex.bark, WHITE, 1f, 2f, 1f));
            ShapeMesh crown = own(Shapes.leafCanopy(treeSeed(x, z), CANOPY_CARDS, 1f));
            opaque.add(new Prop(crown, new Matrix4f().translate(x, CANOPY_CENTER_Y, z), tex.leaf, LEAF_TINT, 1f, 1f, 1f, LEAF_CUTOUT));
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
            opaque.add(new Prop(portalShape, pad, tex.plain, glow, 1f, 1f, 1f));
            Matrix4f beam = new Matrix4f().translate(p.center[0], feetY + 1.6f, p.center[2]).scale(p.radius * 0.55f, 3.2f, p.radius * 0.55f);
            translucent.add(new Prop(portalShape, beam, tex.plain, glow, 1f, 1f, 0.22f));
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
            opaque.add(new Prop(cones[i % 3], m, tex.rock, new float[]{0.541f, 0.572f, 0.666f}, r / 3f, h / 3f, 1f));
        }
    }

    private static float[] softenTint(float[] c) {
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = c[i] + (1f - c[i]) * TINT_SOFTEN;
        return out;
    }

    private static long treeSeed(float x, float z) {
        return Math.round(x * 100) * 1000L + Math.round(z * 100);
    }

    private CubeMesh ownCube(CubeMesh c) {
        ownedCubes.add(c);
        return c;
    }

    private ShapeMesh own(ShapeMesh m) {
        ownedMeshes.add(m);
        return m;
    }

    // ------------------------------------------------------------------ rendering

    /** Draws opaque geometry, then the translucent window glass. The scene shader must already be set up. */
    public void render(Shader shader) {
        shader.use();
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
        shader.setFloat("cutout", p.cutout);
        p.surface.bind();
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
    /**
     * If the standing body at (x, feetY, z) overlaps a solid box (say, it dropped past the side of a
     * block it walked off), returns the nearest horizontally clear spot -- the smallest push out of
     * that box -- as {x, z}; or null if nothing is overlapped. Lets the player slide free of a block's
     * side instead of being snapped back onto it. Same overlap test as {@link #collidesAt}.
     */
    public float[] pushOutOfSolids(float x, float feetY, float z) {
        float px = x, pz = z;
        boolean moved = false;
        for (int pass = 0; pass < 3; pass++) {
            Solid hit = overlappingSolid(px, feetY, pz);
            if (hit == null) break;
            moved = true;
            float r = PLAYER_RADIUS + 0.002f;
            float[][] options = {
                {hit.min[0] - r, pz}, {hit.max[0] + r, pz},
                {px, hit.min[2] - r}, {px, hit.max[2] + r}
            };
            float[] best = options[0];
            float bestD = Float.MAX_VALUE;
            for (float[] o : options) {
                float d = (float) Math.hypot(o[0] - px, o[1] - pz);
                if (d < bestD) { bestD = d; best = o; }
            }
            px = best[0];
            pz = best[1];
        }
        return moved ? new float[]{px, pz} : null;
    }

    private Solid overlappingSolid(float x, float feetY, float z) {
        float headY = feetY + PLAYER_HEIGHT;
        float minX = x - PLAYER_RADIUS, maxX = x + PLAYER_RADIUS;
        float minY = feetY + STAND_CLEARANCE, minZ = z - PLAYER_RADIUS, maxZ = z + PLAYER_RADIUS;
        for (Solid s : solids) {
            if (maxX < s.min[0] || minX > s.max[0]) continue;
            if (headY < s.min[1] || minY > s.max[1]) continue;
            if (maxZ < s.min[2] || minZ > s.max[2]) continue;
            return s;
        }
        return null;
    }

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
        for (CubeMesh c : ownedCubes) c.cleanup();
    }
}
