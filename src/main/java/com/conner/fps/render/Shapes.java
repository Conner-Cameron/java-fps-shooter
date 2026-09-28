package com.conner.fps.render;

import java.util.ArrayList;
import java.util.List;

/** Generators for the few non-cube shapes the world needs (trees, rocks, mountains). */
public final class Shapes {
    private Shapes() {
    }

    /** Unit-height cone: base circle at y=-0.5, apex at y=+0.5, radius 0.5 (scale it to size). */
    public static ShapeMesh cone(int sides) {
        return cylinder(sides, 0f, 0.5f);
    }

    /** Height-1 truncated cylinder centered on the origin; radii are given at the top and bottom. */
    public static ShapeMesh cylinder(int sides, float topRadius, float bottomRadius) {
        List<Float> v = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        float slope = (bottomRadius - topRadius); // normal tilt for the side surface (height = 1)
        for (int i = 0; i <= sides; i++) {
            float a = (float) (i * 2 * Math.PI / sides);
            float cx = (float) Math.cos(a), cz = (float) Math.sin(a);
            float nx = cx, ny = slope, nz = cz;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= nl; ny /= nl; nz /= nl;
            float u = (float) i / sides;
            add(v, cx * topRadius, 0.5f, cz * topRadius, nx, ny, nz, u, 1f);
            add(v, cx * bottomRadius, -0.5f, cz * bottomRadius, nx, ny, nz, u, 0f);
        }
        for (int i = 0; i < sides; i++) {
            int t0 = i * 2, b0 = i * 2 + 1, t1 = (i + 1) * 2, b1 = (i + 1) * 2 + 1;
            idx.add(t0); idx.add(b0); idx.add(b1);
            idx.add(t0); idx.add(b1); idx.add(t1);
        }
        // bottom cap
        int center = v.size() / 8;
        add(v, 0, -0.5f, 0, 0, -1, 0, 0.5f, 0.5f);
        int ringStart = v.size() / 8;
        for (int i = 0; i <= sides; i++) {
            float a = (float) (i * 2 * Math.PI / sides);
            add(v, (float) Math.cos(a) * bottomRadius, -0.5f, (float) Math.sin(a) * bottomRadius, 0, -1, 0,
                    0.5f + 0.5f * (float) Math.cos(a), 0.5f + 0.5f * (float) Math.sin(a));
        }
        for (int i = 0; i < sides; i++) {
            idx.add(center); idx.add(ringStart + i + 1); idx.add(ringStart + i);
        }
        if (topRadius > 0f) {
            int tc = v.size() / 8;
            add(v, 0, 0.5f, 0, 0, 1, 0, 0.5f, 0.5f);
            int ts = v.size() / 8;
            for (int i = 0; i <= sides; i++) {
                float a = (float) (i * 2 * Math.PI / sides);
                add(v, (float) Math.cos(a) * topRadius, 0.5f, (float) Math.sin(a) * topRadius, 0, 1, 0,
                        0.5f + 0.5f * (float) Math.cos(a), 0.5f + 0.5f * (float) Math.sin(a));
            }
            for (int i = 0; i < sides; i++) {
                idx.add(tc); idx.add(ts + i); idx.add(ts + i + 1);
            }
        }
        return new ShapeMesh(toArray(v), toIntArray(idx));
    }

    /** A flat-shaded icosahedron of radius 0.5 -- a chunky low-poly rock (scale it to size). */
    public static ShapeMesh rock() {
        float t = (float) ((1.0 + Math.sqrt(5.0)) / 2.0);
        float[][] p = {
            {-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0},
            {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t},
            {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}
        };
        int[][] f = {
            {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
            {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
            {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
            {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
        };
        List<Float> v = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (int[] tri : f) {
            float[] a = norm(p[tri[0]]), b = norm(p[tri[1]]), c = norm(p[tri[2]]);
            float[] n = norm(new float[]{a[0] + b[0] + c[0], a[1] + b[1] + c[1], a[2] + b[2] + c[2]});
            int base = v.size() / 8;
            add(v, a[0] * 0.5f, a[1] * 0.5f, a[2] * 0.5f, n[0], n[1], n[2], 0f, 0f);
            add(v, b[0] * 0.5f, b[1] * 0.5f, b[2] * 0.5f, n[0], n[1], n[2], 1f, 0f);
            add(v, c[0] * 0.5f, c[1] * 0.5f, c[2] * 0.5f, n[0], n[1], n[2], 0.5f, 1f);
            idx.add(base); idx.add(base + 1); idx.add(base + 2);
        }
        return new ShapeMesh(toArray(v), toIntArray(idx));
    }

    private static float[] norm(float[] a) {
        float l = (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new float[]{a[0] / l, a[1] / l, a[2] / l};
    }

    private static void add(List<Float> v, float x, float y, float z, float nx, float ny, float nz, float u, float w) {
        v.add(x); v.add(y); v.add(z); v.add(nx); v.add(ny); v.add(nz); v.add(u); v.add(w);
    }

    private static float[] toArray(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static int[] toIntArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
