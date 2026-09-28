package com.conner.fps.render;

import com.conner.fps.util.Json;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A minimal binary glTF (.glb) loader -- meshes, PBR material factors and
 * textures (base color, normal, metallic-roughness) embedded in the file,
 * and the node hierarchy's transforms. That is everything the weapon models
 * use; animations, skins, cameras and extensions are ignored.
 */
public final class GlbModel {
    /** One drawable primitive with its material and placement. */
    public static final class Part {
        public final ShapeMesh mesh;
        public final Matrix4f transform;
        public final Texture base, normal, metalRough;
        public final float[] baseFactor;
        public final float metallic, roughness, normalScale;

        Part(ShapeMesh mesh, Matrix4f transform, Texture base, Texture normal, Texture metalRough,
             float[] baseFactor, float metallic, float roughness, float normalScale) {
            this.mesh = mesh;
            this.transform = transform;
            this.base = base;
            this.normal = normal;
            this.metalRough = metalRough;
            this.baseFactor = baseFactor;
            this.metallic = metallic;
            this.roughness = roughness;
            this.normalScale = normalScale;
        }
    }

    public final List<Part> parts = new ArrayList<>();
    public final Vector3f boundsMin = new Vector3f(Float.MAX_VALUE);
    public final Vector3f boundsMax = new Vector3f(-Float.MAX_VALUE);
    private final List<Texture> textures = new ArrayList<>();

    // ---- parse state
    private Map<String, Object> json;
    private ByteBuffer bin;

    public static GlbModel load(byte[] data) throws IOException {
        GlbModel m = new GlbModel();
        m.parse(data);
        return m;
    }

    private void parse(byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46546C67) throw new IOException("Not a GLB file");
        int pos = 12;
        while (pos + 8 <= data.length) {
            int len = b.getInt(pos), type = b.getInt(pos + 4);
            pos += 8;
            if (type == 0x4E4F534A) json = Json.parseObject(new String(data, pos, len, StandardCharsets.UTF_8));
            else if (type == 0x004E4942) bin = ByteBuffer.wrap(data, pos, len).slice().order(ByteOrder.LITTLE_ENDIAN);
            pos += (len + 3) & ~3;
        }
        if (json == null || bin == null) throw new IOException("GLB is missing its JSON or BIN chunk");

        List<Object> scenes = Json.list(json.get("scenes"));
        int sceneIdx = (int) Json.num(json.get("scene"), 0);
        List<Object> nodes = Json.list(json.get("nodes"));
        for (Object n : Json.list(Json.obj(scenes.get(sceneIdx)).get("nodes"))) {
            visit((int) Json.num(n, 0), nodes, new Matrix4f());
        }
    }

    private void visit(int nodeIdx, List<Object> nodes, Matrix4f parent) throws IOException {
        Map<String, Object> node = Json.obj(nodes.get(nodeIdx));
        Matrix4f local = new Matrix4f();
        if (node.get("matrix") != null) {
            float[] m = Json.floats(node.get("matrix"));
            local.set(m);
        } else {
            if (node.get("translation") != null) {
                float[] t = Json.floats(node.get("translation"));
                local.translate(t[0], t[1], t[2]);
            }
            if (node.get("rotation") != null) {
                float[] q = Json.floats(node.get("rotation"));
                local.rotate(new Quaternionf(q[0], q[1], q[2], q[3]));
            }
            if (node.get("scale") != null) {
                float[] s = Json.floats(node.get("scale"));
                local.scale(s[0], s[1], s[2]);
            }
        }
        Matrix4f world = new Matrix4f(parent).mul(local);
        if (node.get("mesh") != null) addMesh((int) Json.num(node.get("mesh"), 0), world);
        for (Object c : Json.list(node.get("children"))) visit((int) Json.num(c, 0), nodes, world);
    }

    private void addMesh(int meshIdx, Matrix4f world) throws IOException {
        Map<String, Object> mesh = Json.obj(Json.list(json.get("meshes")).get(meshIdx));
        for (Object po : Json.list(mesh.get("primitives"))) {
            Map<String, Object> prim = Json.obj(po);
            Map<String, Object> attrs = Json.obj(prim.get("attributes"));
            float[] pos = readFloats((int) Json.num(attrs.get("POSITION"), 0), 3);
            int count = pos.length / 3;
            float[] nrm = attrs.get("NORMAL") != null ? readFloats((int) Json.num(attrs.get("NORMAL"), 0), 3) : new float[count * 3];
            float[] uv = attrs.get("TEXCOORD_0") != null ? readFloats((int) Json.num(attrs.get("TEXCOORD_0"), 0), 2) : new float[count * 2];
            int[] idx = prim.get("indices") != null ? readIndices((int) Json.num(prim.get("indices"), 0)) : sequential(count);

            float[] inter = new float[count * 8];
            for (int i = 0; i < count; i++) {
                inter[i * 8] = pos[i * 3];
                inter[i * 8 + 1] = pos[i * 3 + 1];
                inter[i * 8 + 2] = pos[i * 3 + 2];
                inter[i * 8 + 3] = nrm[i * 3];
                inter[i * 8 + 4] = nrm[i * 3 + 1];
                inter[i * 8 + 5] = nrm[i * 3 + 2];
                inter[i * 8 + 6] = uv[i * 2];
                inter[i * 8 + 7] = uv[i * 2 + 1];
                Vector3f p = world.transformPosition(new Vector3f(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]));
                boundsMin.min(p);
                boundsMax.max(p);
            }

            Map<String, Object> mat = prim.get("material") != null
                    ? Json.obj(Json.list(json.get("materials")).get((int) Json.num(prim.get("material"), 0))) : Map.of();
            Map<String, Object> pbr = mat.get("pbrMetallicRoughness") != null ? Json.obj(mat.get("pbrMetallicRoughness")) : Map.of();
            float[] baseFactor = pbr.get("baseColorFactor") != null ? Json.floats(pbr.get("baseColorFactor")) : new float[]{1, 1, 1, 1};
            Texture base = texture(pbr.get("baseColorTexture"));
            Texture mr = texture(pbr.get("metallicRoughnessTexture"));
            Texture normal = texture(mat.get("normalTexture"));
            float normalScale = mat.get("normalTexture") != null ? (float) Json.num(Json.obj(mat.get("normalTexture")).get("scale"), 1) : 1f;
            parts.add(new Part(new ShapeMesh(inter, idx), world, base, normal, mr, baseFactor,
                    (float) Json.num(pbr.get("metallicFactor"), 1), (float) Json.num(pbr.get("roughnessFactor"), 1), normalScale));
        }
    }

    private Texture texture(Object textureInfo) throws IOException {
        if (textureInfo == null) return null;
        int texIdx = (int) Json.num(Json.obj(textureInfo).get("index"), 0);
        Map<String, Object> tex = Json.obj(Json.list(json.get("textures")).get(texIdx));
        Map<String, Object> image = Json.obj(Json.list(json.get("images")).get((int) Json.num(tex.get("source"), 0)));
        Map<String, Object> view = Json.obj(Json.list(json.get("bufferViews")).get((int) Json.num(image.get("bufferView"), 0)));
        int off = (int) Json.num(view.get("byteOffset"), 0), len = (int) Json.num(view.get("byteLength"), 0);
        byte[] bytes = new byte[len];
        bin.duplicate().position(off).get(bytes, 0, len);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw new IOException("Unreadable embedded image");
        Texture t = Texture.fromImage(img, false, true);
        textures.add(t);
        return t;
    }

    private float[] readFloats(int accessorIdx, int components) {
        Map<String, Object> acc = Json.obj(Json.list(json.get("accessors")).get(accessorIdx));
        Map<String, Object> view = Json.obj(Json.list(json.get("bufferViews")).get((int) Json.num(acc.get("bufferView"), 0)));
        int count = (int) Json.num(acc.get("count"), 0);
        int off = (int) Json.num(view.get("byteOffset"), 0) + (int) Json.num(acc.get("byteOffset"), 0);
        int stride = (int) Json.num(view.get("byteStride"), 0);
        if (stride == 0) stride = components * 4;
        float[] out = new float[count * components];
        for (int i = 0; i < count; i++)
            for (int c = 0; c < components; c++) out[i * components + c] = bin.getFloat(off + i * stride + c * 4);
        return out;
    }

    private int[] readIndices(int accessorIdx) {
        Map<String, Object> acc = Json.obj(Json.list(json.get("accessors")).get(accessorIdx));
        Map<String, Object> view = Json.obj(Json.list(json.get("bufferViews")).get((int) Json.num(acc.get("bufferView"), 0)));
        int count = (int) Json.num(acc.get("count"), 0);
        int type = (int) Json.num(acc.get("componentType"), 5125);
        int off = (int) Json.num(view.get("byteOffset"), 0) + (int) Json.num(acc.get("byteOffset"), 0);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            if (type == 5125) out[i] = bin.getInt(off + i * 4);
            else if (type == 5123) out[i] = bin.getShort(off + i * 2) & 0xFFFF;
            else out[i] = bin.get(off + i) & 0xFF;
        }
        return out;
    }

    private static int[] sequential(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        return a;
    }

    /** Longest side of the model's bounding box (used to fit previews). */
    public float maxExtent() {
        return Math.max(boundsMax.x - boundsMin.x, Math.max(boundsMax.y - boundsMin.y, boundsMax.z - boundsMin.z));
    }

    public Vector3f center() {
        return new Vector3f(boundsMin).add(boundsMax).mul(0.5f);
    }

    public void cleanup() {
        for (Part p : parts) p.mesh.cleanup();
        for (Texture t : textures) t.cleanup();
    }
}
