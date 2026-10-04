package com.conner.fps.game;

import com.conner.fps.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The local player's body: look angles, walking/sprinting, jumping and
 * gravity against the {@link World}. A straight port of the web client's
 * movement loop (same speeds, same collision order, same anti-stuck net) so
 * both clients feel identical -- and stay inside what GameServer accepts.
 */
public final class Player {
    public static final float MOVE_SPEED = 6.0f;
    public static final float JUMP_SPEED = 7.0f;
    public static final float GRAVITY = 18.0f;
    public static final float MOUSE_SENSITIVITY = 0.0022f;
    public static final float BASE_FOV = 70f;
    private static final float WORLD_EDGE = 29.5f; // the ground plane is 60 wide; stay a little inside its edge
    private static final float VOID_Y = -15f;      // below this the player has fallen out of the world

    public final Vector3f position = new Vector3f(0, World.EYE_HEIGHT, 8);
    public float yaw = (float) (-Math.PI / 2);
    public float pitch = 0f;

    public boolean moving;
    public boolean sprinting;

    private float verticalVelocity = 0f;
    private boolean grounded = true;
    // Safety net: if the player ever ends up inside geometry (however that happens),
    // snap back to the last spot that wasn't -- "prevent entry" collision can't recover on its own.
    private float lastSafeX = 0f, lastSafeY = World.EYE_HEIGHT, lastSafeZ = 8f;

    // ---- climbing ----
    // Pressing Space within reach of a climbable box (and below its top -- see World.climbableAt)
    // starts a short, scripted rise onto it instead of a normal jump: WASD/jump/shooting are
    // suppressed (see Game's isClimbing() checks) and position eases from where it started to
    // standing on top over the box's own duration. A local prediction -- in PvP the server runs the
    // same check independently and its own "teleport" message (the same one the portal uses) is
    // what actually lands everyone.
    private boolean climbing = false;
    private boolean climbStartedThisFrame = false;
    private final Vector3f climbStart = new Vector3f();
    private final Vector3f climbTarget = new Vector3f();
    private float climbElapsedMs = 0f;
    private long climbDurationMs = 0;

    public boolean isClimbing() {
        return climbing;
    }

    /** True exactly once, the frame a climb starts -- Game reads this to send the "climb" network message. */
    public boolean consumeClimbStarted() {
        boolean v = climbStartedThisFrame;
        climbStartedThisFrame = false;
        return v;
    }

    private void startClimb(World.Climbable box) {
        climbing = true;
        climbStartedThisFrame = true;
        climbStart.set(position);
        climbTarget.set(box.centerX, box.topY + World.EYE_HEIGHT, box.centerZ);
        climbElapsedMs = 0f;
        climbDurationMs = box.durationMs;
        verticalVelocity = 0f;
        grounded = false;
    }

    /** A death (or anything else that force-places the player) cancels a climb in progress. */
    public void cancelClimb() {
        climbing = false;
    }

    // Smoothstep ease (slow -> fast -> slow) so the rise reads as a deliberate pull-up, not a linear slide.
    private void advanceClimb(float dtMs) {
        climbElapsedMs += dtMs;
        float t = Math.min(1f, climbElapsedMs / climbDurationMs);
        float eased = t * t * (3f - 2f * t);
        position.set(
                climbStart.x + (climbTarget.x - climbStart.x) * eased,
                climbStart.y + (climbTarget.y - climbStart.y) * eased,
                climbStart.z + (climbTarget.z - climbStart.z) * eased);
        if (t >= 1f) {
            climbing = false;
            lastSafeX = position.x;
            lastSafeY = position.y;
            lastSafeZ = position.z;
            verticalVelocity = 0f;
            grounded = true;
        }
    }

    public Vector3f forward() {
        return new Vector3f(
                (float) (Math.cos(yaw) * Math.cos(pitch)),
                (float) Math.sin(pitch),
                (float) (Math.sin(yaw) * Math.cos(pitch))).normalize();
    }

    /** Mouse look; sensitivity scales with the current (possibly zoomed) FOV so a zoomed-in scope isn't twitchy. */
    public void look(double dx, double dy, float currentFov) {
        float sens = currentFov / BASE_FOV;
        yaw += (float) dx * MOUSE_SENSITIVITY * sens;
        pitch -= (float) dy * MOUSE_SENSITIVITY * sens;
        float limit = (float) (Math.PI / 2 - 0.01);
        pitch = Math.max(-limit, Math.min(limit, pitch));
    }

    /** Back to the default start spot, facing forward, at rest. */
    public void reset() {
        position.set(0, World.EYE_HEIGHT, 8);
        yaw = (float) (-Math.PI / 2);
        pitch = 0f;
        moving = false;
        sprinting = false;
        climbing = false;
        teleport(0, World.EYE_HEIGHT, 8);
    }

    public Matrix4f viewMatrix() {
        Vector3f target = new Vector3f(position).add(forward());
        return new Matrix4f().lookAt(position, target, new Vector3f(0, 1, 0));
    }

    /** Server-assigned position (spawn, respawn, or a correction): go there and reset motion. */
    public void teleport(float x, float y, float z) {
        position.set(x, y, z);
        lastSafeX = x;
        lastSafeY = y;
        lastSafeZ = z;
        verticalVelocity = 0f;
        grounded = true;
        climbing = false; // any force-placement (death, portal, the climb's own landing) supersedes a climb in progress
    }

    /**
     * @param adsMoveMult movement multiplier while aiming (only used if {@code aiming})
     * @param sprintMult movement multiplier while sprinting (only used if {@code shift} and not aiming)
     */
    public void update(World world, float dt, boolean fwd, boolean back, boolean left, boolean right,
                       boolean jump, boolean shift, boolean aiming, float adsMoveMult, float sprintMult) {
        if (climbing) {
            advanceClimb(dt * 1000f);
            moving = false;
            sprinting = false;
            return;
        }

        float currentFeetY = position.y - World.EYE_HEIGHT;
        if (world.collidesAt(position.x, currentFeetY, position.z)) {
            // Usually this is just the body having dropped past the side of the block it walked off:
            // slide out to the nearest clear spot rather than snapping back onto the top.
            float[] out = world.pushOutOfSolids(position.x, currentFeetY, position.z);
            if (out != null && !world.collidesAt(out[0], currentFeetY, out[1])) {
                position.x = out[0];
                position.z = out[1];
            } else {
                position.set(lastSafeX, lastSafeY, lastSafeZ);
                verticalVelocity = 0f;
                grounded = false; // let ground detection sort out standing vs falling next frame
            }
        } else {
            lastSafeX = position.x;
            lastSafeY = position.y;
            lastSafeZ = position.z;
        }

        Vector3f forward = forward();
        Vector3f flatForward = new Vector3f(forward.x, 0, forward.z);
        if (flatForward.lengthSquared() > 0.0001f) flatForward.normalize();
        Vector3f rightVec = new Vector3f(flatForward).cross(0, 1, 0);

        // Aiming and sprinting are mutually exclusive -- aiming always wins.
        sprinting = !aiming && shift;
        float speedMult = 1f;
        if (aiming) speedMult = adsMoveMult;
        else if (sprinting) speedMult = sprintMult;
        float velocity = MOVE_SPEED * speedMult * dt;
        moving = fwd || back || left || right;

        float moveX = 0, moveZ = 0;
        if (fwd) { moveX += flatForward.x * velocity; moveZ += flatForward.z * velocity; }
        if (back) { moveX -= flatForward.x * velocity; moveZ -= flatForward.z * velocity; }
        if (right) { moveX += rightVec.x * velocity; moveZ += rightVec.z * velocity; }
        if (left) { moveX -= rightVec.x * velocity; moveZ -= rightVec.z * velocity; }

        // Resolve X and Z separately so walking diagonally into a wall slides along it.
        float feetYForXZ = position.y - World.EYE_HEIGHT;
        if (moveX != 0 && !world.collidesAt(position.x + moveX, feetYForXZ, position.z)) position.x += moveX;
        if (moveZ != 0 && !world.collidesAt(position.x, feetYForXZ, position.z + moveZ)) position.z += moveZ;

        // Space either climbs (within reach of a climbable box, and not already on top of it) or,
        // failing that, does a normal jump.
        World.Climbable climbable = jump ? world.climbableAt(position) : null;
        if (climbable != null) {
            startClimb(climbable);
            return;
        }
        if (jump && grounded) {
            verticalVelocity = JUMP_SPEED;
            grounded = false;
        }
        verticalVelocity -= GRAVITY * dt;

        float feetY = position.y - World.EYE_HEIGHT;
        float proposedFeetY = feetY + verticalVelocity * dt;

        if (verticalVelocity > 0) {
            float headY = feetY + World.PLAYER_HEIGHT;
            float proposedHeadY = proposedFeetY + World.PLAYER_HEIGHT;
            float ceilingY = world.findCeilingY(position.x, position.z, headY, proposedHeadY);
            // Ramps aren't in the plain-box ceiling list, so check them too -- otherwise jumping
            // into a ramp's underside could clip the player up into its solid interior.
            if (Float.isNaN(ceilingY) && world.collidesWithRamps(position.x, proposedFeetY, proposedHeadY, position.z)) {
                ceilingY = headY;
            }
            if (!Float.isNaN(ceilingY)) {
                position.y = ceilingY - World.PLAYER_HEIGHT + World.EYE_HEIGHT;
                verticalVelocity = 0f;
            } else {
                position.y = proposedFeetY + World.EYE_HEIGHT;
            }
            grounded = false;
        } else {
            float fallDistance = Math.max(0f, feetY - proposedFeetY);
            float surfaceY = world.findGroundY(position.x, position.z, feetY, fallDistance);
            if (!Float.isNaN(surfaceY)) {
                position.y = surfaceY + World.EYE_HEIGHT;
                verticalVelocity = 0f;
                grounded = true;
            } else {
                position.y = proposedFeetY + World.EYE_HEIGHT;
                grounded = false;
            }
        }

        // No input needed -- stepping into a portal's trigger volume teleports on contact, same as
        // the web client. In PvP this is only a prediction: GameServer makes the same check against
        // its own authoritative position and is what actually moves the player for everyone else.
        com.conner.fps.data.MapData.Portal portal = world.portalAt(position);
        if (portal != null) teleport(portal.to[0], portal.to[1], portal.to[2]);

        // The ground ends at +-30 and nothing lies below it: without these, walking off the edge
        // falls forever. Keep the player on the ground, and if they somehow end up below the
        // world, put them back at their last safe spot (same as the web client).
        position.x = Math.max(-WORLD_EDGE, Math.min(WORLD_EDGE, position.x));
        position.z = Math.max(-WORLD_EDGE, Math.min(WORLD_EDGE, position.z));
        if (position.y < VOID_Y) teleport(lastSafeX, lastSafeY, lastSafeZ);
    }
}
