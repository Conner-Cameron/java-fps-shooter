import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Stand-in model generator: turns a SIDE-VIEW picture of a weapon into a
 * PBR glTF (.glb) by extruding the picture's silhouette a few centimetres and
 * wrapping the photo around it as base color. A normal map and a
 * metallic-roughness map are derived from the photo's luminance so the model
 * goes through the same PBR path a real artist-made model would.
 *
 * It is a placeholder for proper modelled assets, not a substitute: the
 * result is a thick cut-out (correct profile, painted-on detail).
 *
 *   java tools/SilhouetteGlb.java <image> <cropX> <cropY> <cropW> <cropH> <out.glb>
 *        <lengthMeters> <thicknessMeters> [threshold=32] [cellPx=3] [flip=0|1] [closeRadius=3]
 *
 * The image is cropped to the weapon's side view; its muzzle should point to
 * the LEFT (it becomes -Z in the model; +Y is up, origin at the crop center).
 * flip=1 mirrors the picture horizontally first (for right-pointing views).
 */
public class SilhouetteGlb {
    public static void main(String[] a) throws Exception {
        BufferedImage full = ImageIO.read(new File(a[0]));
        int cx = Integer.parseInt(a[1]), cy = Integer.parseInt(a[2]), cw = Integer.parseInt(a[3]), ch = Integer.parseInt(a[4]);
        File out = new File(a[5]);
        double lengthM = Double.parseDouble(a[6]);
        double thickM = Double.parseDouble(a[7]);
        int threshold = a.length > 8 ? Integer.parseInt(a[8]) : 32;
        int cell = a.length > 9 ? Integer.parseInt(a[9]) : 3;
        boolean flip = a.length > 10 && a[10].equals("1");
        int closeR = a.length > 11 ? Integer.parseInt(a[11]) : 3;

        BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < ch; y++)
            for (int x = 0; x < cw; x++)
                img.setRGB(flip ? cw - 1 - x : x, y, full.getRGB(cx + x, cy + y) & 0xFFFFFF);

        double[][] lum = new double[ch][cw];
        for (int y = 0; y < ch; y++)
            for (int x = 0; x < cw; x++) {
                int p = img.getRGB(x, y);
                lum[y][x] = (((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255)) / 3.0;
            }

        // ---- silhouette mask: threshold, close small gaps, keep the largest blob ----
        boolean[][] mask = new boolean[ch][cw];
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) mask[y][x] = lum[y][x] > threshold;
        mask = dilate(mask, closeR);
        mask = erode(mask, closeR);
        mask = largestComponent(mask);
        mask = fillSmallHoles(mask, 2500);

        // ---- cell grid ----
        int gw = cw / cell, gh = ch / cell;
        boolean[][] solid = new boolean[gh][gw];
        int solidCount = 0;
        for (int gy = 0; gy < gh; gy++)
            for (int gx = 0; gx < gw; gx++) {
                int on = 0;
                for (int y = 0; y < cell; y++) for (int x = 0; x < cell; x++) if (mask[gy * cell + y][gx * cell + x]) on++;
                solid[gy][gx] = on * 2 >= cell * cell;
                if (solid[gy][gx]) solidCount++;
            }
        System.out.println("crop " + cw + "x" + ch + ", grid " + gw + "x" + gh + ", solid cells " + solidCount);

        // Debug preview of the mask
        BufferedImage prev = new BufferedImage(gw * 2, gh * 2, BufferedImage.TYPE_INT_RGB);
        for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++)
            for (int k = 0; k < 4; k++) prev.setRGB(gx * 2 + (k & 1), gy * 2 + (k >> 1), solid[gy][gx] ? 0xFFFFFF : 0x202020);
        File maskFile = new File(System.getProperty("java.io.tmpdir"), out.getName() + ".mask.png");
        ImageIO.write(prev, "png", maskFile);
        System.out.println("silhouette preview: " + maskFile);

        // ---- geometry ----
        double metersPerPx = lengthM / cw;
        double halfT = thickM / 2;
        List<float[]> pos = new ArrayList<>(), nrm = new ArrayList<>(), uv = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        // world x = thickness (-x face shows the picture), y = up, z = along the gun (image right = +z)
        for (int gy = 0; gy < gh; gy++) {
            int gx = 0;
            while (gx < gw) {
                if (!solid[gy][gx]) { gx++; continue; }
                int start = gx;
                while (gx < gw && solid[gy][gx]) gx++;
                addFace(pos, nrm, uv, idx, start, gx, gy, gy + 1, -halfT, -1, cell, cw, ch, metersPerPx);
                addFace(pos, nrm, uv, idx, start, gx, gy, gy + 1, +halfT, +1, cell, cw, ch, metersPerPx);
                // horizontal walls (top/bottom) along the run
                for (int x = start; x < gx; x++) {
                    if (gy == 0 || !solid[gy - 1][x]) addWall(pos, nrm, uv, idx, x, x + 1, gy, gy, halfT, 0, +1, cell, cw, ch, metersPerPx);
                    if (gy == gh - 1 || !solid[gy + 1][x]) addWall(pos, nrm, uv, idx, x, x + 1, gy + 1, gy + 1, halfT, 0, -1, cell, cw, ch, metersPerPx);
                }
                addWall(pos, nrm, uv, idx, start, start, gy, gy + 1, halfT, -1, 0, cell, cw, ch, metersPerPx); // run's left end
                addWall(pos, nrm, uv, idx, gx, gx, gy, gy + 1, halfT, +1, 0, cell, cw, ch, metersPerPx);       // run's right end
            }
        }
        System.out.println("vertices " + pos.size() + ", triangles " + idx.size() / 3);

        // ---- textures ----
        byte[] basePng = jpg(img, 0.92f); // photo/smooth maps as JPEG: ~5x smaller than PNG
        byte[] normalPng = jpg(normalMap(lum, cw, ch, 2.2), 0.9f);
        byte[] mrPng = jpg(metalRoughMap(lum, cw, ch), 0.92f);

        writeGlb(out, pos, nrm, uv, idx, basePng, normalPng, mrPng);
        System.out.println("wrote " + out + " (" + out.length() / 1024 + " KB)");
    }

    // A face spanning cells [gx0,gx1) x [gy0,gy1) at a given x plane; normalSign = -1 (faces -x) or +1.
    static void addFace(List<float[]> pos, List<float[]> nrm, List<float[]> uv, List<Integer> idx,
                        int gx0, int gx1, int gy0, int gy1, double x, int normalSign, int cell, int cw, int ch, double mpp) {
        double[][] c = {
            {gx0 * cell, gy0 * cell}, {gx1 * cell, gy0 * cell}, {gx1 * cell, gy1 * cell}, {gx0 * cell, gy1 * cell}
        };
        int base = pos.size();
        for (double[] p : c) {
            pos.add(new float[]{(float) x, (float) (-(p[1] - ch / 2.0) * mpp), (float) ((p[0] - cw / 2.0) * mpp)});
            nrm.add(new float[]{normalSign, 0, 0});
            uv.add(new float[]{(float) (p[0] / cw), (float) (p[1] / ch)});
        }
        // corners: 0=TL 1=TR 2=BR 3=BL in image space. For the -x face viewed from -x (right = +z = image right) that is CCW as listed.
        if (normalSign < 0) { idx.addAll(List.of(base, base + 3, base + 2, base, base + 2, base + 1)); }
        else { idx.addAll(List.of(base, base + 1, base + 2, base, base + 2, base + 3)); }
    }

    // A wall quad for the boundary between cells; runs along gx (horizontal, ny = +-1) or gy (vertical, nz = +-1).
    static void addWall(List<float[]> pos, List<float[]> nrm, List<float[]> uv, List<Integer> idx,
                        int gx0, int gx1, int gy0, int gy1, double halfT, int nz, int ny, int cell, int cw, int ch, double mpp) {
        double px0 = gx0 * cell, px1 = gx1 * cell, py0 = gy0 * cell, py1 = gy1 * cell;
        double u = ((px0 + px1) / 2) / cw, v = ((py0 + py1) / 2) / ch;
        // nudge the sample point just inside the solid cell so the wall picks up the gun's color, not the background
        if (nz < 0) u = (px0 + cell / 2.0) / cw; else if (nz > 0) u = (px0 - cell / 2.0) / cw;
        if (ny > 0) v = (py0 + cell / 2.0) / ch; else if (ny < 0) v = (py0 - cell / 2.0) / ch;
        double[][] c = {{px0, py0, -halfT}, {px1, py1, -halfT}, {px1, py1, halfT}, {px0, py0, halfT}};
        int base = pos.size();
        for (double[] p : c) {
            pos.add(new float[]{(float) p[2], (float) (-(p[1] - ch / 2.0) * mpp), (float) ((p[0] - cw / 2.0) * mpp)});
            nrm.add(new float[]{0, ny, nz});
            uv.add(new float[]{(float) u, (float) v});
        }
        // choose winding so the geometric normal agrees with (0, ny, nz)
        float[] p0 = pos.get(base), p1 = pos.get(base + 1), p2 = pos.get(base + 2);
        float[] e1 = {p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]}, e2 = {p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]};
        float[] n = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
        boolean ok = n[1] * ny + n[2] * nz >= 0;
        if (ok) idx.addAll(List.of(base, base + 1, base + 2, base, base + 2, base + 3));
        else idx.addAll(List.of(base, base + 2, base + 1, base, base + 3, base + 2));
    }

    // ---------------- image helpers ----------------
    static boolean[][] dilate(boolean[][] m, int r) {
        int h = m.length, w = m[0].length;
        boolean[][] o = new boolean[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean v = false;
            for (int dy = -r; dy <= r && !v; dy++) for (int dx = -r; dx <= r && !v; dx++) {
                int yy = y + dy, xx = x + dx;
                if (yy >= 0 && yy < h && xx >= 0 && xx < w && m[yy][xx]) v = true;
            }
            o[y][x] = v;
        }
        return o;
    }

    static boolean[][] erode(boolean[][] m, int r) {
        int h = m.length, w = m[0].length;
        boolean[][] o = new boolean[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean v = true;
            for (int dy = -r; dy <= r && v; dy++) for (int dx = -r; dx <= r && v; dx++) {
                int yy = y + dy, xx = x + dx;
                if (yy < 0 || yy >= h || xx < 0 || xx >= w || !m[yy][xx]) v = false;
            }
            o[y][x] = v;
        }
        return o;
    }

    static boolean[][] largestComponent(boolean[][] m) {
        int h = m.length, w = m[0].length;
        int[][] label = new int[h][w];
        int next = 0, best = 0, bestSize = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (!m[y][x] || label[y][x] != 0) continue;
            next++;
            int size = 0;
            ArrayDeque<int[]> q = new ArrayDeque<>();
            q.add(new int[]{x, y});
            label[y][x] = next;
            while (!q.isEmpty()) {
                int[] p = q.poll();
                size++;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int xx = p[0] + d[0], yy = p[1] + d[1];
                    if (xx >= 0 && xx < w && yy >= 0 && yy < h && m[yy][xx] && label[yy][xx] == 0) { label[yy][xx] = next; q.add(new int[]{xx, yy}); }
                }
            }
            if (size > bestSize) { bestSize = size; best = next; }
        }
        boolean[][] o = new boolean[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) o[y][x] = label[y][x] == best;
        return o;
    }

    // Fills background pockets smaller than maxArea pixels (specks inside the gun) but leaves big holes (trigger guard).
    static boolean[][] fillSmallHoles(boolean[][] m, int maxArea) {
        int h = m.length, w = m[0].length;
        boolean[][] seen = new boolean[h][w];
        boolean[][] o = new boolean[h][w];
        for (int y = 0; y < h; y++) o[y] = m[y].clone();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (m[y][x] || seen[y][x]) continue;
            List<int[]> comp = new ArrayList<>();
            ArrayDeque<int[]> q = new ArrayDeque<>();
            q.add(new int[]{x, y});
            seen[y][x] = true;
            boolean touchesEdge = false;
            while (!q.isEmpty()) {
                int[] p = q.poll();
                comp.add(p);
                if (p[0] == 0 || p[1] == 0 || p[0] == w - 1 || p[1] == h - 1) touchesEdge = true;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int xx = p[0] + d[0], yy = p[1] + d[1];
                    if (xx >= 0 && xx < w && yy >= 0 && yy < h && !m[yy][xx] && !seen[yy][xx]) { seen[yy][xx] = true; q.add(new int[]{xx, yy}); }
                }
            }
            if (!touchesEdge && comp.size() < maxArea) for (int[] p : comp) o[p[1]][p[0]] = true;
        }
        return o;
    }

    // Tangent-space normal map from luminance treated as a height field (OpenGL convention: +Y = up in the image).
    static BufferedImage normalMap(double[][] lum, int w, int h, double strength) {
        double[][] s = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double sum = 0; int n = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int yy = Math.min(h - 1, Math.max(0, y + dy)), xx = Math.min(w - 1, Math.max(0, x + dx));
                sum += lum[yy][xx]; n++;
            }
            s[y][x] = sum / n / 255.0;
        }
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double hx = (s[y][Math.min(w - 1, x + 1)] - s[y][Math.max(0, x - 1)]) * 0.5;
            double hy = (s[Math.min(h - 1, y + 1)][x] - s[Math.max(0, y - 1)][x]) * 0.5;
            double nx = -hx * strength * 8, ny = hy * strength * 8, nz = 1;
            double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= l; ny /= l; nz /= l;
            int r = (int) Math.round((nx * 0.5 + 0.5) * 255), g = (int) Math.round((ny * 0.5 + 0.5) * 255), b = (int) Math.round((nz * 0.5 + 0.5) * 255);
            o.setRGB(x, y, (r << 16) | (g << 8) | b);
        }
        return o;
    }

    // glTF metallic-roughness: G = roughness, B = metallic. Brighter (worn/bare metal) = shinier and more metallic.
    static BufferedImage metalRoughMap(double[][] lum, int w, int h) {
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double l = Math.min(1.0, lum[y][x] / 140.0);
            int rough = (int) Math.round((0.75 - 0.4 * l) * 255);
            int metal = (int) Math.round((0.15 + 0.6 * l) * 255);
            o.setRGB(x, y, (255 << 16) | (rough << 8) | metal);
        }
        return o;
    }

    static byte[] jpg(BufferedImage img, float quality) throws Exception {
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(b)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return b.toByteArray();
    }

    static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "png", b);
        return b.toByteArray();
    }

    // ---------------- GLB writer ----------------
    static void writeGlb(File out, List<float[]> pos, List<float[]> nrm, List<float[]> uv, List<Integer> idx,
                         byte[] base, byte[] normal, byte[] mr) throws Exception {
        int nv = pos.size(), ni = idx.size();
        ByteBuffer pb = ByteBuffer.allocate(nv * 12).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer nb = ByteBuffer.allocate(nv * 12).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer ub = ByteBuffer.allocate(nv * 8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer ib = ByteBuffer.allocate(ni * 4).order(ByteOrder.LITTLE_ENDIAN);
        float[] mn = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, mx = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < nv; i++) {
            for (int k = 0; k < 3; k++) {
                pb.putFloat(pos.get(i)[k]); nb.putFloat(nrm.get(i)[k]);
                mn[k] = Math.min(mn[k], pos.get(i)[k]); mx[k] = Math.max(mx[k], pos.get(i)[k]);
            }
            ub.putFloat(uv.get(i)[0]); ub.putFloat(uv.get(i)[1]);
        }
        for (int v : idx) ib.putInt(v);

        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        int[] offs = new int[7], lens = new int[7];
        byte[][] parts = {pb.array(), nb.array(), ub.array(), ib.array(), base, normal, mr};
        for (int i = 0; i < parts.length; i++) {
            while (bin.size() % 4 != 0) bin.write(0);
            offs[i] = bin.size(); lens[i] = parts[i].length;
            bin.write(parts[i]);
        }
        while (bin.size() % 4 != 0) bin.write(0);

        String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"SilhouetteGlb\"},"
            + "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0,\"name\":\"weapon\"}],"
            + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},\"indices\":3,\"material\":0}]}],"
            + "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0},\"metallicRoughnessTexture\":{\"index\":2},"
            + "\"metallicFactor\":1.0,\"roughnessFactor\":1.0},\"normalTexture\":{\"index\":1,\"scale\":1.0}}],"
            + "\"textures\":[{\"source\":0,\"sampler\":0},{\"source\":1,\"sampler\":0},{\"source\":2,\"sampler\":0}],"
            + "\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":33071,\"wrapT\":33071}],"
            + "\"images\":[{\"bufferView\":4,\"mimeType\":\"image/jpeg\"},{\"bufferView\":5,\"mimeType\":\"image/jpeg\"},{\"bufferView\":6,\"mimeType\":\"image/jpeg\"}],"
            + "\"accessors\":["
            + "{\"bufferView\":0,\"componentType\":5126,\"count\":" + nv + ",\"type\":\"VEC3\",\"min\":[" + mn[0] + "," + mn[1] + "," + mn[2] + "],\"max\":[" + mx[0] + "," + mx[1] + "," + mx[2] + "]},"
            + "{\"bufferView\":1,\"componentType\":5126,\"count\":" + nv + ",\"type\":\"VEC3\"},"
            + "{\"bufferView\":2,\"componentType\":5126,\"count\":" + nv + ",\"type\":\"VEC2\"},"
            + "{\"bufferView\":3,\"componentType\":5125,\"count\":" + ni + ",\"type\":\"SCALAR\"}],"
            + "\"bufferViews\":["
            + bv(offs[0], lens[0], 34962) + "," + bv(offs[1], lens[1], 34962) + "," + bv(offs[2], lens[2], 34962) + ","
            + bv(offs[3], lens[3], 34963) + "," + bv(offs[4], lens[4], 0) + "," + bv(offs[5], lens[5], 0) + "," + bv(offs[6], lens[6], 0) + "],"
            + "\"buffers\":[{\"byteLength\":" + bin.size() + "}]}";
        byte[] jb = json.getBytes(StandardCharsets.UTF_8);
        int jpad = (4 - jb.length % 4) % 4;
        int total = 12 + 8 + jb.length + jpad + 8 + bin.size();
        ByteBuffer o = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        o.putInt(0x46546C67).putInt(2).putInt(total);
        o.putInt(jb.length + jpad).putInt(0x4E4F534A).put(jb);
        for (int i = 0; i < jpad; i++) o.put((byte) 0x20);
        o.putInt(bin.size()).putInt(0x004E4942).put(bin.toByteArray());
        Files.write(out.toPath(), o.array());
    }

    static String bv(int off, int len, int target) {
        return "{\"buffer\":0,\"byteOffset\":" + off + ",\"byteLength\":" + len + (target != 0 ? ",\"target\":" + target : "") + "}";
    }
}
