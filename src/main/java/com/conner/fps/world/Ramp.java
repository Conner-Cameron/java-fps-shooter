package com.conner.fps.world;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A sloped ramp segment: a box oriented exactly like the web client's
 * mesh.lookAt(start) (local +Z runs from the midpoint toward `a`; local
 * X = up x Z; local Y = Z x X), so the desktop and browser levels match.
 */
public final class Ramp {
    public final Matrix4f model;      // unit-cube -> world (includes the box scale)
    public final Matrix4f inverse;    // world -> the ramp's own (unscaled) local frame
    public final float halfW, halfT, halfL;
    public final float width, thickness, length;
    public final Vector3f center = new Vector3f();
    public final Vector3f axisX = new Vector3f(), axisY = new Vector3f(), axisZ = new Vector3f();

    public Ramp(float[] a, float[] b, float width, float thickness) {
        Vector3f start = new Vector3f(a[0], a[1], a[2]);
        Vector3f end = new Vector3f(b[0], b[1], b[2]);
        this.length = Math.max(start.distance(end), 0.01f);
        this.width = width;
        this.thickness = thickness;
        halfW = width / 2f;
        halfT = thickness / 2f;
        halfL = length / 2f;

        center.set(start).add(end).mul(0.5f);
        axisZ.set(start).sub(center).normalize();
        axisX.set(0, 1, 0).cross(axisZ);
        if (axisX.lengthSquared() < 1e-8f) axisX.set(1, 0, 0);
        axisX.normalize();
        axisY.set(axisZ).cross(axisX);

        Matrix4f rigid = new Matrix4f(
                axisX.x, axisX.y, axisX.z, 0,
                axisY.x, axisY.y, axisY.z, 0,
                axisZ.x, axisZ.y, axisZ.z, 0,
                center.x, center.y, center.z, 1);
        inverse = new Matrix4f(rigid).invert();
        model = new Matrix4f(rigid).scale(width, thickness, length);
    }

    /** Ray hit distance against this ramp's slab (front hits only), or -1. */
    public float rayDistance(float[] o, float[] d, boolean allowInside) {
        float[] rel = {o[0] - center.x, o[1] - center.y, o[2] - center.z};
        float[] lo = {rel[0] * axisX.x + rel[1] * axisX.y + rel[2] * axisX.z,
                      rel[0] * axisY.x + rel[1] * axisY.y + rel[2] * axisY.z,
                      rel[0] * axisZ.x + rel[1] * axisZ.y + rel[2] * axisZ.z};
        float[] ld = {d[0] * axisX.x + d[1] * axisX.y + d[2] * axisX.z,
                      d[0] * axisY.x + d[1] * axisY.y + d[2] * axisY.z,
                      d[0] * axisZ.x + d[1] * axisZ.y + d[2] * axisZ.z};
        float[] min = {-halfW, -halfT, -halfL}, max = {halfW, halfT, halfL};
        return allowInside ? com.conner.fps.util.Ray.aabbDistance(lo, ld, min, max)
                           : com.conner.fps.util.Ray.aabbEntryDistance(lo, ld, min, max);
    }
}
