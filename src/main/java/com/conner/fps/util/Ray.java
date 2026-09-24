package com.conner.fps.util;

import org.joml.Vector3f;

/**
 * Ray-vs-axis-aligned-bounding-box intersection (slab method), used for
 * hit-scan shooting against target boxes.
 */
public final class Ray {

    private Ray() {
    }

    /**
     * @return the distance along the ray to the intersection, or {@code null} if the
     *         ray (starting at {@code origin} in front of the camera) misses the box.
     */
    public static Float intersectAABB(Vector3f origin, Vector3f dir, Vector3f min, Vector3f max) {
        float tmin = (min.x - origin.x) / dir.x;
        float tmax = (max.x - origin.x) / dir.x;
        if (tmin > tmax) {
            float tmp = tmin;
            tmin = tmax;
            tmax = tmp;
        }

        float tymin = (min.y - origin.y) / dir.y;
        float tymax = (max.y - origin.y) / dir.y;
        if (tymin > tymax) {
            float tmp = tymin;
            tymin = tymax;
            tymax = tmp;
        }

        if (tmin > tymax || tymin > tmax) {
            return null;
        }
        if (tymin > tmin) tmin = tymin;
        if (tymax < tmax) tmax = tymax;

        float tzmin = (min.z - origin.z) / dir.z;
        float tzmax = (max.z - origin.z) / dir.z;
        if (tzmin > tzmax) {
            float tmp = tzmin;
            tzmin = tzmax;
            tzmax = tmp;
        }

        if (tmin > tzmax || tzmin > tmax) {
            return null;
        }
        if (tzmin > tmin) tmin = tzmin;

        if (tmin < 0) {
            return null; // box is behind the ray origin
        }

        return tmin;
    }
}
