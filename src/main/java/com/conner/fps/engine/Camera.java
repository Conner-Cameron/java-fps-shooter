package com.conner.fps.engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * First-person fly camera: WASD to move relative to look direction,
 * mouse to look around, space/shift to rise/descend.
 */
public class Camera {
    public final Vector3f position;
    public float yaw = -90f;   // facing -Z initially
    public float pitch = 0f;

    public float moveSpeed = 6.0f;
    public float mouseSensitivity = 0.1f;

    private Vector3f front = new Vector3f(0, 0, -1);
    private Vector3f right = new Vector3f(1, 0, 0);
    private Vector3f up = new Vector3f(0, 1, 0);
    private final Vector3f worldUp = new Vector3f(0, 1, 0);

    public Camera(Vector3f startPosition) {
        this.position = startPosition;
        updateVectors();
    }

    public void processMouseMovement(float dx, float dy) {
        yaw += dx * mouseSensitivity;
        pitch -= dy * mouseSensitivity;
        pitch = Math.max(-89f, Math.min(89f, pitch));
        updateVectors();
    }

    public void processKeyboard(boolean forward, boolean backward, boolean left, boolean right,
                                 boolean ascend, boolean descend, float deltaTime) {
        float velocity = moveSpeed * deltaTime;

        Vector3f flatFront = new Vector3f(front.x, 0, front.z);
        if (flatFront.lengthSquared() > 0.0001f) {
            flatFront.normalize();
        }

        if (forward) position.add(new Vector3f(flatFront).mul(velocity));
        if (backward) position.sub(new Vector3f(flatFront).mul(velocity));
        if (left) position.sub(new Vector3f(this.right).mul(velocity));
        if (right) position.add(new Vector3f(this.right).mul(velocity));
        if (ascend) position.y += velocity;
        if (descend) position.y -= velocity;
    }

    private void updateVectors() {
        Vector3f newFront = new Vector3f();
        newFront.x = (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        newFront.y = (float) Math.sin(Math.toRadians(pitch));
        newFront.z = (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));

        front = newFront.normalize();
        right = new Vector3f(front).cross(worldUp).normalize();
        up = new Vector3f(right).cross(front).normalize();
    }

    public Vector3f getFront() {
        return new Vector3f(front);
    }

    public Matrix4f getViewMatrix() {
        Vector3f target = new Vector3f(position).add(front);
        return new Matrix4f().lookAt(position, target, up);
    }
}
