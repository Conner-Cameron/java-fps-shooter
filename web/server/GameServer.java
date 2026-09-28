import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dependency-free HTTP + WebSocket server for the browser PvP mode.
 *
 * There's no Node.js on this machine and no external libraries wired into
 * this project, so this hand-rolls both the static file serving and the
 * WebSocket protocol (RFC 6455 handshake + frame parsing) directly on top
 * of a raw ServerSocket -- the whole game (page + multiplayer) is served on
 * a single port, which is what most free hosting platforms expect.
 *
 * Run with: java GameServer.java   (JDK 17+, no build step needed)
 * Reads the port from the PORT env var (falls back to 8080) so it drops
 * straight into hosts like Render/Railway that inject that variable.
 */
public class GameServer {
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int KILL_LIMIT = 10;
    private static final long RESPAWN_DELAY_MS = 2000;
    private static final long MATCH_RESET_DELAY_MS = 6000;
    private static final double MIN_SPAWN_DISTANCE = 12.0; // don't spawn this close to any alive player
    private static final int SPAWN_ATTEMPTS = 20;

    // ---------------------------------------------------------- shared map
    // Loaded once at startup from web/map.json -- the very same file the
    // browser client builds its world from, so walls/building/ramps/trees/
    // rocks are defined in exactly one place. Used for bullet blocking,
    // spawn safety, and movement validation.

    /** Axis-aligned static geometry. Trees/rocks are bullet-only (not walk-solid), same as the client. */
    static final class Solid {
        final double[] min, max;
        // Slightly shrunk copy used by movement validation: a legit player
        // can stand ON a surface or press against a face (never inside), so
        // only a path that reaches into this inner box counts as walking
        // through the solid. Thin panels (0.3-thick walls, glass) shrink by
        // proportionally less so they can still be crossed-detected.
        final double[] smin, smax;
        final boolean blocksBullets, walkSolid;

        Solid(double cx, double cy, double cz, double sx, double sy, double sz, boolean blocksBullets, boolean walkSolid) {
            this.min = new double[]{cx - sx / 2, cy - sy / 2, cz - sz / 2};
            this.max = new double[]{cx + sx / 2, cy + sy / 2, cz + sz / 2};
            double m = Math.min(0.25, 0.5 * Math.min(sx, Math.min(sy, sz)) / 2);
            this.smin = new double[]{min[0] + m, min[1] + m, min[2] + m};
            this.smax = new double[]{max[0] - m, max[1] - m, max[2] - m};
            this.blocksBullets = blocksBullets;
            this.walkSolid = walkSolid;
        }
    }

    /**
     * A sloped ramp segment, i.e. a box oriented exactly like the client's
     * mesh.lookAt(start): local +Z runs from the segment's midpoint toward
     * `a`, local X = up x Z, local Y = Z x X.
     */
    static final class Ramp {
        final double[] c = new double[3];
        final double[][] axes = new double[3][]; // local X, Y, Z in world space
        final double[] half = new double[3];     // half-extents along local X, Y, Z

        Ramp(double[] a, double[] b, double width, double thickness) {
            for (int i = 0; i < 3; i++) c[i] = (a[i] + b[i]) / 2;
            double[] z = normalize(new double[]{a[0] - c[0], a[1] - c[1], a[2] - c[2]});
            double[] up = {0, 1, 0};
            double[] x = cross(up, z);
            if (length(x) < 1e-6) x = new double[]{1, 0, 0}; // ramp pointing straight up/down
            x = normalize(x);
            double[] y = cross(z, x);
            axes[0] = x; axes[1] = y; axes[2] = z;
            half[0] = width / 2;
            half[1] = thickness / 2;
            half[2] = Math.max(distance(a, b), 0.01) / 2;
        }
    }

    private static final List<Solid> SOLIDS = new ArrayList<>();
    private static final List<Ramp> RAMPS = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private static void loadMap(File file) throws IOException {
        Map<String, Object> map = Json.parseObject(Files.readString(file.toPath()));

        for (Object o : (List<Object>) map.get("boxes")) {
            Map<String, Object> b = (Map<String, Object>) o;
            double[] c = toDoubleArray((List<Object>) b.get("c"));
            double[] s = toDoubleArray((List<Object>) b.get("s"));
            boolean bullets = !Boolean.FALSE.equals(b.get("bullets"));
            SOLIDS.add(new Solid(c[0], c[1], c[2], s[0], s[1], s[2], bullets, true));
        }
        // Bounding boxes around the client's tree/rock meshes (cone + trunk, and a dodecahedron).
        for (Object o : (List<Object>) map.get("trees")) {
            double[] t = toDoubleArray((List<Object>) o);
            SOLIDS.add(new Solid(t[0], 1.8, t[1], 1.6, 3.6, 1.6, true, false));
        }
        for (Object o : (List<Object>) map.get("rocks")) {
            double[] r = toDoubleArray((List<Object>) o);
            double s = r[2];
            SOLIDS.add(new Solid(r[0], 0.3 * s, r[1], 1.2 * s, 1.2 * s, 1.2 * s, true, false));
        }
        for (Object o : (List<Object>) map.get("ramps")) {
            Map<String, Object> r = (Map<String, Object>) o;
            RAMPS.add(new Ramp(toDoubleArray((List<Object>) r.get("a")), toDoubleArray((List<Object>) r.get("b")),
                    ((Number) r.get("w")).doubleValue(), ((Number) r.get("t")).doubleValue()));
        }
        System.out.println("Loaded map: " + SOLIDS.size() + " solids, " + RAMPS.size() + " ramps");
    }

    // ---- small vector helpers (plain double[3]) ----
    private static double length(double[] v) { return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); }
    private static double[] normalize(double[] v) {
        double l = length(v);
        return l < 1e-12 ? new double[]{0, 0, 0} : new double[]{v[0] / l, v[1] / l, v[2] / l};
    }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Slab test of the ray/segment o + t*d against the box [min,max] in
     * whatever frame o/d/min/max are given in. Returns {tEnter, tExit}
     * (unbounded along the line -- callers clamp), or null for no overlap.
     * A direction component of ~0 is handled explicitly instead of
     * dividing by it, so a ray lying exactly on a face can't produce NaNs.
     */
    private static double[] slab(double[] o, double[] d, double[] min, double[] max) {
        double tmin = Double.NEGATIVE_INFINITY, tmax = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < min[i] || o[i] > max[i]) return null;
            } else {
                double t1 = (min[i] - o[i]) / d[i], t2 = (max[i] - o[i]) / d[i];
                if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
                if (t1 > tmin) tmin = t1;
                if (t2 < tmax) tmax = t2;
                if (tmin > tmax) return null;
            }
        }
        return new double[]{tmin, tmax};
    }

    /** Distance along the ray to the first hit of an axis-aligned box, or null. */
    private static Double rayAabb(double[] origin, double[] dir, double[] min, double[] max) {
        double[] t = slab(origin, dir, min, max);
        if (t == null || t[1] < 0) return null;
        return Math.max(t[0], 0);
    }

    private static Double rayRamp(double[] origin, double[] dir, Ramp r) {
        double[] rel = {origin[0] - r.c[0], origin[1] - r.c[1], origin[2] - r.c[2]};
        double[] lo = new double[3], ld = new double[3], min = new double[3], max = new double[3];
        for (int i = 0; i < 3; i++) {
            lo[i] = dot(rel, r.axes[i]);
            ld[i] = dot(dir, r.axes[i]);
            min[i] = -r.half[i];
            max[i] = r.half[i];
        }
        double[] t = slab(lo, ld, min, max);
        if (t == null || t[1] < 0) return null;
        return Math.max(t[0], 0);
    }

    /** Nearest bullet-blocking thing along the (normalized) ray: walls, building, ramps, trees, rocks, ground. */
    private static double nearestObstacleDistance(double[] origin, double[] dir) {
        double nearest = Double.MAX_VALUE;
        for (Solid s : SOLIDS) {
            if (!s.blocksBullets) continue;
            Double t = rayAabb(origin, dir, s.min, s.max);
            if (t != null && t < nearest) nearest = t;
        }
        for (Ramp r : RAMPS) {
            Double t = rayRamp(origin, dir, r);
            if (t != null && t < nearest) nearest = t;
        }
        return nearest;
    }

    private static final int MAX_HP = 100;
    // index: 0 = pistol, 1 = rifle, 2 = sniper, 3 = SMG (automatic -- client
    // fires it on a timer while held), 4 = knife (melee -- no ammo/reload,
    // range-limited instead; see WEAPON_MELEE/WEAPON_RANGE below).
    private static final int[] WEAPON_DAMAGE = {20, 34, 100, 14, 100};
    private static final long[] WEAPON_COOLDOWN_MS = {150, 300, 1000, 100, 600};
    private static final int[] WEAPON_MAG_SIZE = {8, 24, 5, 20, 1}; // knife's slot is unused -- melee bypasses ammo entirely
    private static final long[] WEAPON_RELOAD_MS = {1000, 1600, 2200, 1300, 1}; // unused for the knife
    private static final boolean[] WEAPON_MELEE = {false, false, false, false, true};
    // A modified client claiming a "shoot" hit at any distance would trivially
    // turn the knife into a hitscan one-shot at any range, so the server caps
    // it here independently of whatever the client's own reach check does.
    private static final double[] WEAPON_RANGE = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, 2.2};

    private static int clampWeapon(int weapon) {
        return Math.max(0, Math.min(WEAPON_DAMAGE.length - 1, weapon));
    }

    private static int[] fullMagazines() {
        return WEAPON_MAG_SIZE.clone();
    }

    private static final Map<Integer, Player> players = new ConcurrentHashMap<>();
    private static final AtomicInteger nextId = new AtomicInteger(1);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private static final Random random = new Random();
    private static String staticRoot;

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", args.length > 0 ? args[0] : "8080"));
        staticRoot = new File("web").isDirectory() ? "web" : ".";
        loadMap(new File(staticRoot, "map.json"));

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Serving " + new File(staticRoot).getAbsolutePath() + " on port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                Thread thread = new Thread(() -> handleConnection(socket));
                thread.setDaemon(true);
                thread.start();
            }
        }
    }

    // ---------------------------------------------------------------- I/O

    private static void handleConnection(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            Map<String, String> headers = new HashMap<>();
            String requestLine = readHeaders(in, headers);
            if (requestLine == null) {
                socket.close();
                return;
            }

            if ("websocket".equalsIgnoreCase(headers.getOrDefault("upgrade", ""))) {
                handleWebSocket(socket, headers);
            } else {
                handleHttp(socket, requestLine);
            }
        } catch (Exception e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readHeaders(InputStream in, Map<String, String> headers) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            int len = buf.size();
            if (len >= 4) {
                byte[] tail = buf.toByteArray();
                if (tail[len - 4] == '\r' && tail[len - 3] == '\n' && tail[len - 2] == '\r' && tail[len - 1] == '\n') {
                    break;
                }
            }
        }
        if (buf.size() == 0) return null;

        String[] lines = buf.toString(StandardCharsets.US_ASCII).split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) return null;
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx < 0) continue;
            headers.put(lines[i].substring(0, idx).trim().toLowerCase(), lines[i].substring(idx + 1).trim());
        }
        return lines[0];
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n == -1) throw new EOFException();
            off += n;
        }
    }

    // --------------------------------------------------------- static HTTP

    private static void handleHttp(Socket socket, String requestLine) throws IOException {
        OutputStream out = socket.getOutputStream();
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !parts[0].equals("GET")) {
            writeResponse(out, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
            socket.close();
            return;
        }

        String path = parts[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.equals("/")) path = "/index.html";

        if (path.contains("..")) {
            writeResponse(out, 400, "text/plain", "Bad Request".getBytes(StandardCharsets.UTF_8));
            socket.close();
            return;
        }

        File file = new File(staticRoot, path.substring(1));
        if (!file.isFile()) {
            writeResponse(out, 404, "text/plain", ("Not found: " + path).getBytes(StandardCharsets.UTF_8));
            socket.close();
            return;
        }

        writeResponse(out, 200, contentType(path), Files.readAllBytes(file.toPath()));
        socket.close();
    }

    private static void writeResponse(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String statusText = status == 200 ? "OK" : status == 404 ? "Not Found" : status == 400 ? "Bad Request" : "Error";
        String header = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".glb")) return "model/gltf-binary";
        if (path.endsWith(".gltf")) return "model/gltf+json";
        if (path.endsWith(".ktx2")) return "image/ktx2";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------ WebSocket

    private static void handleWebSocket(Socket socket, Map<String, String> headers) throws Exception {
        String key = headers.get("sec-websocket-key");
        if (key == null) {
            socket.close();
            return;
        }
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_MAGIC).getBytes(StandardCharsets.UTF_8)));

        OutputStream rawOut = socket.getOutputStream();
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        rawOut.write(response.getBytes(StandardCharsets.US_ASCII));
        rawOut.flush();

        int id = nextId.getAndIncrement();
        Player player = new Player(id, socket);
        players.put(id, player);
        sendWelcome(player);

        try {
            InputStream in = socket.getInputStream();
            String msg;
            while ((msg = readTextFrame(in)) != null) {
                handleMessage(player, msg);
            }
        } catch (IOException ignored) {
        } finally {
            players.remove(id);
            broadcast(Json.obj("type", "playerLeft", "id", id));
            try {
                socket.close();
            } catch (IOException ignored2) {
            }
        }
    }

    private static String readTextFrame(InputStream in) throws IOException {
        while (true) {
            int b0 = in.read();
            if (b0 == -1) return null;
            int b1 = in.read();
            if (b1 == -1) return null;

            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
            }

            byte[] mask = new byte[4];
            if (masked) readFully(in, mask, 4);

            byte[] payload = new byte[(int) len];
            readFully(in, payload, (int) len);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            }

            if (opcode == 0x8) return null;       // close
            if (opcode == 0x9 || opcode == 0xA) continue; // ping/pong -- ignored, not needed for this use case
            if (opcode == 0x1) return new String(payload, StandardCharsets.UTF_8);
            // binary/continuation frames: unused by this protocol, ignore
        }
    }

    // ------------------------------------------------------------- players

    static final class Player {
        final int id;
        final Socket socket;
        volatile String name;
        volatile double[] pos;
        volatile double yaw = 0;
        volatile double pitch = 0;
        volatile int kills = 0;
        volatile boolean alive = true;
        volatile int hp = MAX_HP;
        volatile int weapon = 1; // default: rifle
        volatile long lastShotTime = 0;
        volatile int[] ammo = fullMagazines();
        volatile boolean reloading = false;
        volatile int reloadingWeapon = -1;
        // Movement validation state (see handleState / validateMove).
        volatile boolean awaitingSync = true; // true until the client confirms it's at the server-assigned spot
        long lastStateNanos = System.nanoTime();
        double horizBudget = HORIZ_BUDGET_CAP;
        double vertBudget = VERT_BUDGET_CAP;
        volatile long lastCorrectionMs = 0;
        volatile long syncPendingSinceMs = System.currentTimeMillis(); // when awaitingSync last became true

        Player(int id, Socket socket) {
            this.id = id;
            this.socket = socket;
            this.name = "Player" + id;
            this.pos = randomSpawn();
        }

        synchronized void sendText(String text) {
            try {
                byte[] payload = text.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(0x81);
                int len = payload.length;
                if (len <= 125) {
                    frame.write(len);
                } else if (len <= 65535) {
                    frame.write(126);
                    frame.write((len >> 8) & 0xFF);
                    frame.write(len & 0xFF);
                } else {
                    frame.write(127);
                    for (int i = 7; i >= 0; i--) frame.write((int) ((len >> (8 * i)) & 0xFF));
                }
                frame.write(payload);
                OutputStream out = socket.getOutputStream();
                out.write(frame.toByteArray());
                out.flush();
            } catch (IOException e) {
                alive = false;
            }
        }
    }

    private static double[] randomSpawn() {
        return randomSpawn(null);
    }

    /**
     * Picks a random spawn point, retrying up to SPAWN_ATTEMPTS times to find
     * one at least MIN_SPAWN_DISTANCE from every other alive player (dead
     * players' stale positions don't count -- they're not an active threat).
     * {@code excludeSelf} is the respawning player themselves, whose own
     * (pre-respawn) position obviously shouldn't count against them. If no
     * attempt clears the minimum, falls back to whichever candidate ended up
     * farthest from the nearest player, rather than an infinite retry loop.
     */
    private static double[] randomSpawn(Player excludeSelf) {
        double[] best = null;
        double bestMinDist = -1;

        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            double x = (random.nextDouble() - 0.5) * 34;
            double z = -2 - random.nextDouble() * 26;
            if (spawnBlocked(x, z)) continue; // never spawn inside a wall/tree/rock

            double minDist = Double.MAX_VALUE;
            for (Player p : players.values()) {
                if (p == excludeSelf || !p.alive) continue;
                double dx = p.pos[0] - x;
                double dz = p.pos[2] - z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < minDist) minDist = dist;
            }

            if (minDist >= MIN_SPAWN_DISTANCE) {
                return new double[]{x, 1.7, z};
            }
            if (minDist > bestMinDist) {
                bestMinDist = minDist;
                best = new double[]{x, 1.7, z};
            }
        }
        return best != null ? best : new double[]{0, 1.7, 8}; // open ground
    }

    private static void broadcast(String json) {
        for (Player p : players.values()) p.sendText(json);
    }

    private static void broadcastExcept(int excludeId, String json) {
        for (Player p : players.values()) {
            if (p.id != excludeId) p.sendText(json);
        }
    }

    private static void sendWelcome(Player player) {
        List<String> others = new ArrayList<>();
        for (Player p : players.values()) {
            if (p.id == player.id) continue;
            others.add(Json.obj("id", p.id, "name", p.name, "kills", p.kills, "hp", p.hp, "pos", p.pos, "yaw", p.yaw, "pitch", p.pitch));
        }
        player.sendText(Json.obj(
                "type", "welcome",
                "id", player.id,
                "pos", player.pos, // server-assigned spawn; the client teleports here
                "killLimit", KILL_LIMIT,
                "players", Json.raw("[" + String.join(",", others) + "]")));
        broadcastExcept(player.id, Json.obj("type", "playerJoined", "id", player.id, "name", player.name));
    }

    // ------------------------------------------------------------ messages

    @SuppressWarnings("unchecked")
    private static void handleMessage(Player player, String msg) {
        Map<String, Object> obj;
        try {
            obj = Json.parseObject(msg);
        } catch (Exception e) {
            return;
        }
        Object type = obj.get("type");
        if (!(type instanceof String)) return;

        switch ((String) type) {
            case "join": {
                Object nameObj = obj.get("name");
                if (nameObj instanceof String && !((String) nameObj).isBlank()) {
                    String trimmed = ((String) nameObj).trim();
                    player.name = trimmed.substring(0, Math.min(20, trimmed.length()));
                    broadcast(Json.obj("type", "playerJoined", "id", player.id, "name", player.name));
                }
                break;
            }
            case "state": {
                handleState(player, obj);
                break;
            }
            case "shoot": {
                handleShoot(player, obj);
                break;
            }
            case "weapon": {
                Object idObj = obj.get("id");
                if (idObj instanceof Number) {
                    int newWeapon = clampWeapon(((Number) idObj).intValue());
                    // Switching holsters whatever reload was in progress -- the
                    // weapon being left keeps whatever partial ammo it had; the
                    // reload does not complete in the background.
                    player.reloading = false;
                    player.weapon = newWeapon;
                    if (!WEAPON_MELEE[newWeapon]) {
                        player.sendText(Json.obj("type", "ammo", "weapon", newWeapon, "ammo", player.ammo[newWeapon]));
                    }
                }
                break;
            }
            case "reload": {
                int weaponIdx = clampWeapon(player.weapon);
                if (!player.reloading && player.ammo[weaponIdx] < WEAPON_MAG_SIZE[weaponIdx]) {
                    startReload(player, weaponIdx);
                }
                break;
            }
            default:
                break;
        }
    }

    private static void startReload(Player p, int weaponIdx) {
        if (p.reloading) return;
        p.reloading = true;
        p.reloadingWeapon = weaponIdx;
        long duration = WEAPON_RELOAD_MS[weaponIdx];
        p.sendText(Json.obj("type", "reload", "weapon", weaponIdx, "durationMs", duration));
        scheduler.schedule(() -> {
            // If the player switched weapons (or something else already
            // cleared this) before the timer fired, don't resurrect it.
            if (p.reloading && p.reloadingWeapon == weaponIdx) {
                p.ammo[weaponIdx] = WEAPON_MAG_SIZE[weaponIdx];
                p.reloading = false;
                p.sendText(Json.obj("type", "ammo", "weapon", weaponIdx, "ammo", p.ammo[weaponIdx]));
            }
        }, duration, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static void handleShoot(Player shooter, Map<String, Object> obj) {
        if (!shooter.alive || shooter.reloading) return;

        int weaponIdx = clampWeapon(shooter.weapon);
        boolean melee = WEAPON_MELEE[weaponIdx];
        if (!melee && shooter.ammo[weaponIdx] <= 0) {
            startReload(shooter, weaponIdx); // out of ammo -- reload automatically
            return;
        }

        // Fire-rate is enforced here (not just trusted from the client) so a
        // modified client can't just spam "shoot" messages faster than the
        // equipped weapon allows. This gates the attempt itself -- a miss
        // still consumes the cooldown, same as a real gun's fire rate.
        long now = System.currentTimeMillis();
        if (now - shooter.lastShotTime < WEAPON_COOLDOWN_MS[weaponIdx]) return;
        shooter.lastShotTime = now;

        boolean emptiedMag = false;
        if (!melee) {
            shooter.ammo[weaponIdx]--;
            shooter.sendText(Json.obj("type", "ammo", "weapon", weaponIdx, "ammo", shooter.ammo[weaponIdx]));
            emptiedMag = shooter.ammo[weaponIdx] <= 0;
        }

        // The shot always starts from where the server believes the shooter
        // is -- never from a client-claimed origin -- and the direction is
        // re-normalized here: the range/obstacle/hit distances below are all
        // in units of "t along dir", so an unnormalized dir (say length 100)
        // would otherwise scale the knife's reach by 100x.
        double[] dir = normalize(parseVec3(obj.get("dir")) != null ? parseVec3(obj.get("dir")) : new double[]{0, 0, 0});
        if (length(dir) < 0.5) { // missing/invalid/zero direction: counts as a miss
            if (emptiedMag) startReload(shooter, weaponIdx);
            return;
        }
        double[] origin = shooter.pos.clone();
        // A wall/tree/rock in the way beats every player behind it, same as
        // the client's own raycast against the map's solid geometry.
        double obstacleDist = nearestObstacleDistance(origin, dir);

        Player closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Player other : players.values()) {
            if (other.id == shooter.id || !other.alive) continue;
            Double dist = intersectAABB(origin, dir, other.pos);
            if (dist != null && dist < obstacleDist && dist <= WEAPON_RANGE[weaponIdx] && dist < closestDist) {
                closestDist = dist;
                closest = other;
            }
        }

        if (closest == null) { // miss -- cooldown still applies
            if (emptiedMag) startReload(shooter, weaponIdx);
            return;
        }
        Player victim = closest;

        int damage = WEAPON_DAMAGE[weaponIdx];
        victim.hp = Math.max(0, victim.hp - damage);
        broadcast(Json.obj(
                "type", "damage", "shooterId", shooter.id, "victimId", victim.id,
                "damage", damage, "victimHp", victim.hp));

        if (victim.hp > 0) { // hit landed but didn't kill
            if (emptiedMag) startReload(shooter, weaponIdx);
            return;
        }

        victim.alive = false;
        shooter.kills++;
        broadcast(Json.obj("type", "kill", "shooterId", shooter.id, "victimId", victim.id, "shooterKills", shooter.kills));

        if (shooter.kills >= KILL_LIMIT) {
            broadcast(Json.obj("type", "matchOver", "winnerId", shooter.id, "winnerName", shooter.name, "score", shooter.kills));
            scheduler.schedule(() -> {
                for (Player p : players.values()) p.kills = 0;
                broadcast(Json.obj("type", "matchReset"));
            }, MATCH_RESET_DELAY_MS, TimeUnit.MILLISECONDS);
        }

        scheduler.schedule(() -> {
            victim.pos = randomSpawn(victim); // excluded defensively; they're also not `alive` yet at this point anyway
            victim.awaitingSync = true; // ignore movement reports until the client lands at the new spot
            victim.syncPendingSinceMs = System.currentTimeMillis();
            victim.hp = MAX_HP;
            victim.alive = true;
            victim.reloading = false;
            victim.ammo = fullMagazines();
            // Broadcast to everyone (not just the victim) as its own "respawn" message
            // rather than a regular "state" update -- other clients snap the avatar to
            // the new spot instead of smoothly interpolating it, so a respawn reads as
            // an instant teleport, not a glide across the map.
            broadcast(Json.obj("type", "respawn", "id", victim.id, "pos", victim.pos, "hp", victim.hp));
            victim.sendText(Json.obj("type", "ammo", "weapon", clampWeapon(victim.weapon), "ammo", victim.ammo[clampWeapon(victim.weapon)]));
        }, RESPAWN_DELAY_MS, TimeUnit.MILLISECONDS);

        if (emptiedMag) startReload(shooter, weaponIdx); // the killing shot itself also emptied the mag
    }

    private static double[] toDoubleArray(List<Object> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = ((Number) list.get(i)).doubleValue();
        return arr;
    }

    // Player hit box: centered on the reported eye position (camera height ~1.7).
    private static final double HALF_W = 0.4, HALF_D = 0.35, BELOW_EYE = 1.6, ABOVE_EYE = 0.2;

    private static Double intersectAABB(double[] origin, double[] dir, double[] targetPos) {
        return rayAabb(origin, dir,
                new double[]{targetPos[0] - HALF_W, targetPos[1] - BELOW_EYE, targetPos[2] - HALF_D},
                new double[]{targetPos[0] + HALF_W, targetPos[1] + ABOVE_EYE, targetPos[2] + HALF_D});
    }

    // ------------------------------------------------- movement validation
    // The client is trusted for *where it is* only within limits the real
    // client can never exceed: a speed budget (refilled at max sprint speed
    // + 50% slack for network jitter, capped so a lag spike can't bank a
    // teleport), world bounds, and no path through solid geometry. Anything
    // else gets the player snapped back and their state ignored until the
    // client confirms it landed at the corrected spot (awaitingSync).
    private static final double MAX_HORIZ_SPEED = 6.0 * 1.6 * 1.5;
    private static final double MAX_VERT_SPEED = 30.0; // a fall from the roof tops out around 22
    private static final double HORIZ_BUDGET_CAP = MAX_HORIZ_SPEED + 1.0;
    private static final double VERT_BUDGET_CAP = MAX_VERT_SPEED + 2.0;
    private static final double WORLD_HALF = 31.0; // ground is 60x60 (edge at 30)
    private static final double WORLD_MIN_Y = -3.0, WORLD_MAX_Y = 25.0; // eye height
    private static final double EYE_HEIGHT = 1.7;
    private static final double SYNC_RADIUS = 3.0;

    /** Returns null if the move is plausible, otherwise a short reason. */
    private static String validateMove(Player p, double[] to, long nowNanos) {
        for (double v : to) if (Double.isNaN(v) || Double.isInfinite(v)) return "invalid";
        if (Math.abs(to[0]) > WORLD_HALF || Math.abs(to[2]) > WORLD_HALF || to[1] < WORLD_MIN_Y || to[1] > WORLD_MAX_Y) {
            return "bounds";
        }

        double dt = Math.min(1.0, Math.max(0, (nowNanos - p.lastStateNanos) / 1e9));
        p.lastStateNanos = nowNanos;
        p.horizBudget = Math.min(HORIZ_BUDGET_CAP, p.horizBudget + dt * MAX_HORIZ_SPEED);
        p.vertBudget = Math.min(VERT_BUDGET_CAP, p.vertBudget + dt * MAX_VERT_SPEED);

        double dh = Math.hypot(to[0] - p.pos[0], to[2] - p.pos[2]);
        double dv = Math.abs(to[1] - p.pos[1]);
        if (dh > p.horizBudget) return "speed";
        if (dv > p.vertBudget) return "vertical speed";
        if (crossesSolid(p.pos, to)) return "solid";

        p.horizBudget -= dh;
        p.vertBudget -= dv;
        return null;
    }

    /** True if the straight path from -> to (at eye level and just above the feet) enters a walk-solid box. */
    private static boolean crossesSolid(double[] from, double[] to) {
        double[] d = {to[0] - from[0], to[1] - from[1], to[2] - from[2]};
        if (length(d) < 1e-9) return false;
        for (double yOff : new double[]{0, -EYE_HEIGHT + 0.05}) {
            double[] o = {from[0], from[1] + yOff, from[2]};
            for (Solid s : SOLIDS) {
                if (!s.walkSolid) continue;
                double[] t = slab(o, d, s.smin, s.smax);
                if (t != null && t[1] >= 0 && t[0] <= 1) return true;
            }
        }
        return false;
    }

    /** A spawn point must not sit inside (or hugging) anything a standing body would overlap. */
    private static boolean spawnBlocked(double x, double z) {
        for (Solid s : SOLIDS) {
            if (s.max[1] < 0.3 || s.min[1] > 1.9) continue; // doesn't overlap a standing body's height
            if (x > s.min[0] - 0.5 && x < s.max[0] + 0.5 && z > s.min[2] - 0.5 && z < s.max[2] + 0.5) return true;
        }
        return false;
    }

    private static double[] parseVec3(Object o) {
        if (!(o instanceof List) || ((List<?>) o).size() != 3) return null;
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            Object e = ((List<?>) o).get(i);
            if (!(e instanceof Number)) return null;
            v[i] = ((Number) e).doubleValue();
        }
        return v;
    }

    private static void handleState(Player p, Map<String, Object> obj) {
        double[] to = parseVec3(obj.get("pos"));
        if (to == null || !p.alive) return;
        long now = System.nanoTime();

        if (p.awaitingSync) {
            // Server placed this player (join / respawn / correction); ignore
            // whatever the client reports until it confirms it's actually there.
            if (distance(to, p.pos) > SYNC_RADIUS) {
                // The client was already told where it is (welcome / respawn / the
                // correction that started this); reports still in flight from its
                // old spot are expected for a moment, so stay quiet before re-sending.
                if (System.currentTimeMillis() - p.syncPendingSinceMs > 1500) {
                    sendCorrection(p, String.format("awaiting sync (client at %.1f,%.1f,%.1f, server at %.1f,%.1f,%.1f)",
                            to[0], to[1], to[2], p.pos[0], p.pos[1], p.pos[2]));
                }
                return;
            }
            p.awaitingSync = false;
            p.horizBudget = HORIZ_BUDGET_CAP;
            p.vertBudget = VERT_BUDGET_CAP;
            p.lastStateNanos = now;
        } else {
            String reason = validateMove(p, to, now);
            if (reason != null) {
                if (reason.equals("bounds")) p.pos = randomSpawn(p); // walked/fell off the map: back to a fresh spawn
                p.awaitingSync = true;
                p.syncPendingSinceMs = System.currentTimeMillis();
                sendCorrection(p, reason);
                return;
            }
        }

        p.pos = to;
        Object yawObj = obj.get("yaw");
        Object pitchObj = obj.get("pitch");
        if (yawObj instanceof Number) p.yaw = ((Number) yawObj).doubleValue();
        if (pitchObj instanceof Number) p.pitch = ((Number) pitchObj).doubleValue();
        broadcastExcept(p.id, Json.obj("type", "state", "id", p.id, "pos", p.pos, "yaw", p.yaw, "pitch", p.pitch));
    }

    private static void sendCorrection(Player p, String reason) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - p.lastCorrectionMs < 250) return; // don't spam while the client catches up
        p.lastCorrectionMs = nowMs;
        System.out.println("[validate] " + p.name + " (#" + p.id + ") corrected: " + reason);
        p.sendText(Json.obj("type", "correct", "pos", p.pos, "reason", reason));
    }


    // ----------------------------------------------------------------- JSON
    // Hand-written since there's no JSON library dependency in this project;
    // the message set is small and fully controlled by this file and game.js.

    static final class Json {
        static String obj(Object... kv) {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < kv.length; i += 2) {
                if (i > 0) sb.append(',');
                sb.append('"').append(kv[i]).append("\":").append(value(kv[i + 1]));
            }
            return sb.append('}').toString();
        }

        static String value(Object v) {
            if (v == null) return "null";
            if (v instanceof String) return quote((String) v);
            if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
            if (v instanceof double[]) {
                double[] a = (double[]) v;
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < a.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(a[i]);
                }
                return sb.append(']').toString();
            }
            if (v instanceof RawJson) return ((RawJson) v).json;
            return quote(String.valueOf(v));
        }

        static String quote(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            return sb.append('"').toString();
        }

        static final class RawJson {
            final String json;
            RawJson(String json) { this.json = json; }
        }

        static RawJson raw(String json) {
            return new RawJson(json);
        }

        static Map<String, Object> parseObject(String s) {
            Parser p = new Parser(s);
            Object v = p.parseValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) v;
            return map;
        }

        static final class Parser {
            final String s;
            int i = 0;

            Parser(String s) { this.s = s; }

            Object parseValue() {
                skipWs();
                char c = s.charAt(i);
                if (c == '{') return parseObj();
                if (c == '[') return parseArr();
                if (c == '"') return parseStr();
                if (c == 't') { i += 4; return Boolean.TRUE; }
                if (c == 'f') { i += 5; return Boolean.FALSE; }
                if (c == 'n') { i += 4; return null; }
                return parseNum();
            }

            Map<String, Object> parseObj() {
                Map<String, Object> map = new LinkedHashMap<>();
                i++;
                skipWs();
                if (s.charAt(i) == '}') { i++; return map; }
                while (true) {
                    skipWs();
                    String key = parseStr();
                    skipWs();
                    i++; // ':'
                    Object val = parseValue();
                    map.put(key, val);
                    skipWs();
                    if (s.charAt(i) == ',') { i++; continue; }
                    i++; // '}'
                    break;
                }
                return map;
            }

            List<Object> parseArr() {
                List<Object> list = new ArrayList<>();
                i++;
                skipWs();
                if (s.charAt(i) == ']') { i++; return list; }
                while (true) {
                    list.add(parseValue());
                    skipWs();
                    if (s.charAt(i) == ',') { i++; skipWs(); continue; }
                    i++; // ']'
                    break;
                }
                return list;
            }

            String parseStr() {
                i++; // opening quote
                StringBuilder sb = new StringBuilder();
                while (s.charAt(i) != '"') {
                    char c = s.charAt(i);
                    if (c == '\\') {
                        i++;
                        char e = s.charAt(i);
                        switch (e) {
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'u':
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                                break;
                            default: sb.append(e);
                        }
                    } else {
                        sb.append(c);
                    }
                    i++;
                }
                i++; // closing quote
                return sb.toString();
            }

            Double parseNum() {
                int start = i;
                while (i < s.length() && "-+.0123456789eE".indexOf(s.charAt(i)) >= 0) i++;
                return Double.parseDouble(s.substring(start, i));
            }

            void skipWs() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            }
        }
    }
}
