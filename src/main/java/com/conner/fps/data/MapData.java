package com.conner.fps.data;

import com.conner.fps.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shared world definition (web/map.json, packaged as /map.json): walls,
 * the multi-floor building, the spiral ramp, trees and rocks -- the same file
 * the browser client and GameServer load, so all three agree on the level.
 */
public final class MapData {
    public static final class Box {
        public final float[] center;
        public final float[] size;
        public final float[] color; // tint (rgb 0..1); ground/glass ignore it
        public final float tile;
        public final boolean ground;
        public final boolean glass;
        public final boolean blocksBullets;

        Box(Map<String, Object> o) {
            center = Json.floats(o.get("c"));
            size = Json.floats(o.get("s"));
            String hex = o.get("color") instanceof String ? (String) o.get("color") : "#ffffff";
            int rgb = Integer.parseInt(hex.substring(1), 16);
            color = new float[]{((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f};
            tile = (float) Json.num(o.get("tile"), 2.5);
            ground = "ground".equals(o.get("kind"));
            glass = Boolean.TRUE.equals(o.get("glass"));
            blocksBullets = !Boolean.FALSE.equals(o.get("bullets"));
        }
    }

    public static final class Ramp {
        public final float[] a, b;
        public final float width, thickness;

        Ramp(Map<String, Object> o) {
            a = Json.floats(o.get("a"));
            b = Json.floats(o.get("b"));
            width = (float) Json.num(o.get("w"), 2.5);
            thickness = (float) Json.num(o.get("t"), 0.3);
        }
    }

    /** A one-way teleport trigger: standing within {@code radius} (horizontal) of {@code center} moves the player to {@code to}. */
    public static final class Portal {
        public final float[] center;
        public final float radius;
        public final float[] to;

        Portal(Map<String, Object> o) {
            center = Json.floats(o.get("c"));
            radius = (float) Json.num(o.get("r"), 1.5);
            to = Json.floats(o.get("to"));
        }
    }

    public final List<Box> boxes = new ArrayList<>();
    public final List<float[]> trees = new ArrayList<>(); // {x, z}
    public final List<float[]> rocks = new ArrayList<>(); // {x, z, scale}
    public final List<Ramp> ramps = new ArrayList<>();
    public final List<Portal> portals = new ArrayList<>();

    public static MapData load() {
        try (InputStream in = MapData.class.getResourceAsStream("/map.json")) {
            if (in == null) throw new RuntimeException("map.json not found on the classpath");
            Map<String, Object> root = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            MapData m = new MapData();
            for (Object o : Json.list(root.get("boxes"))) m.boxes.add(new Box(Json.obj(o)));
            for (Object o : Json.list(root.get("trees"))) m.trees.add(Json.floats(o));
            for (Object o : Json.list(root.get("rocks"))) m.rocks.add(Json.floats(o));
            for (Object o : Json.list(root.get("ramps"))) m.ramps.add(new Ramp(Json.obj(o)));
            for (Object o : Json.list(root.get("portals"))) m.portals.add(new Portal(Json.obj(o)));
            return m;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read map.json", e);
        }
    }

    private MapData() {
    }
}
