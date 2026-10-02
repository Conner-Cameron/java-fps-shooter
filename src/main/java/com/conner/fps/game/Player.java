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
    }

    /**
     * @param adsMoveMult movement multiplier while aiming (only used if {@code aiming})
     * @param sprintMult movement multiplier while sprinting (only used if {@code shift} and not aiming)
     */
    public void update(World world, float dt, boolean fwd, boolean back, boolean left, boolean right,
                       boolean jump, boolean shift, boolean aiming, float adsMoveMult, float sprintMult) {
        float currentFeetY = position.y - World.EYE_HEIGHT;
        if (world.collidesAt(position.x, currentFeetY, position.z)) {
            position.set(lastSafeX, lastSafeY, lastSafeZ);
            verticalVelocity = 0f;
            grounded = false; // let ground detection sort out standing vs falling next frame
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
    }
}
