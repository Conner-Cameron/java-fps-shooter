package com.conner.fps.util;

import org.joml.Vector3f;

/**
 * Ray/segment vs. box tests (slab method). Zero direction components are
 * handled explicitly rather than divided by, so a ray lying on a face can't
 * produce NaNs.
 */
public final class Ray {
    private Ray() {
    }

    /**
     * Slab test of o + t*d against the box [min,max] (in whatever frame all four are given).
     * Returns {tEnter, tExit} (unbounded along the line -- callers clamp) or null if they don't overlap.
     */
    public static float[] slab(float[] o, float[] d, float[] min, float[] max) {
        float tmin = Float.NEGATIVE_INFINITY, tmax = Float.POSITIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9f) {
                if (o[i] < min[i] || o[i] > max[i]) return null;
            } else {
                float t1 = (min[i] - o[i]) / d[i], t2 = (max[i] - o[i]) / d[i];
                if (t1 > t2) { float t = t1; t1 = t2; t2 = t; }
                if (t1 > tmin) tmin = t1;
                if (t2 < tmax) tmax = t2;
                if (tmin > tmax) return null;
            }
        }
        return new float[]{tmin, tmax};
    }

    /** Distance to the first hit of an axis-aligned box in front of the origin (0 if the origin is inside), or -1. */
    public static float aabbDistance(float[] o, float[] d, float[] min, float[] max) {
        float[] t = slab(o, d, min, max);
        if (t == null || t[1] < 0) return -1f;
        return Math.max(t[0], 0f);
    }

    /** Like {@link #aabbDistance} but only counts hits from outside the box (a ray starting inside sees nothing) -- matches a raycast against front faces. */
    public static float aabbEntryDistance(float[] o, float[] d, float[] min, float[] max) {
        float[] t = slab(o, d, min, max);
        if (t == null || t[0] < 0) return -1f;
        return t[0];
    }

    /** Legacy helper for target hit-testing: distance to the box in front of the origin, or null. */
    public static Float intersectAABB(Vector3f origin, Vector3f dir, Vector3f min, Vector3f max) {
        float t = aabbEntryDistance(new float[]{origin.x, origin.y, origin.z}, new float[]{dir.x, dir.y, dir.z},
                new float[]{min.x, min.y, min.z}, new float[]{max.x, max.y, max.z});
        return t < 0 ? null : t;
    }
}
