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

    private static final Map<Integer, Player> players = new ConcurrentHashMap<>();
    private static final AtomicInteger nextId = new AtomicInteger(1);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private static final Random random = new Random();
    private static String staticRoot;

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", args.length > 0 ? args[0] : "8080"));
        staticRoot = new File("web").isDirectory() ? "web" : ".";

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
        double x = (random.nextDouble() - 0.5) * 34;
        double z = -2 - random.nextDouble() * 26;
        return new double[]{x, 1.7, z};
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
            others.add(Json.obj("id", p.id, "name", p.name, "kills", p.kills, "pos", p.pos, "yaw", p.yaw, "pitch", p.pitch));
        }
        player.sendText(Json.obj(
                "type", "welcome",
                "id", player.id,
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
                List<Object> posList = (List<Object>) obj.get("pos");
                if (posList != null && posList.size() == 3) {
                    player.pos = toDoubleArray(posList);
                }
                Object yawObj = obj.get("yaw");
                Object pitchObj = obj.get("pitch");
                if (yawObj instanceof Number) player.yaw = ((Number) yawObj).doubleValue();
                if (pitchObj instanceof Number) player.pitch = ((Number) pitchObj).doubleValue();
                broadcastExcept(player.id, Json.obj(
                        "type", "state", "id", player.id, "pos", player.pos, "yaw", player.yaw, "pitch", player.pitch));
                break;
            }
            case "shoot": {
                handleShoot(player, obj);
                break;
            }
            default:
                break;
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleShoot(Player shooter, Map<String, Object> obj) {
        if (!shooter.alive) return;
        List<Object> originList = (List<Object>) obj.get("origin");
        List<Object> dirList = (List<Object>) obj.get("dir");
        if (originList == null || dirList == null) return;

        double[] origin = toDoubleArray(originList);
        double[] dir = toDoubleArray(dirList);

        Player closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Player other : players.values()) {
            if (other.id == shooter.id || !other.alive) continue;
            Double dist = intersectAABB(origin, dir, other.pos);
            if (dist != null && dist < closestDist) {
                closestDist = dist;
                closest = other;
            }
        }

        if (closest == null) return;
        Player victim = closest;
        victim.alive = false;
        shooter.kills++;
        broadcast(Json.obj("type", "hit", "shooterId", shooter.id, "victimId", victim.id, "shooterKills", shooter.kills));

        if (shooter.kills >= KILL_LIMIT) {
            broadcast(Json.obj("type", "matchOver", "winnerId", shooter.id, "winnerName", shooter.name, "score", shooter.kills));
            scheduler.schedule(() -> {
                for (Player p : players.values()) p.kills = 0;
                broadcast(Json.obj("type", "matchReset"));
            }, MATCH_RESET_DELAY_MS, TimeUnit.MILLISECONDS);
        }

        scheduler.schedule(() -> {
            victim.pos = randomSpawn();
            victim.alive = true;
            // Broadcast to everyone (not just the victim) as its own "respawn" message
            // rather than a regular "state" update -- other clients snap the avatar to
            // the new spot instead of smoothly interpolating it, so a respawn reads as
            // an instant teleport, not a glide across the map.
            broadcast(Json.obj("type", "respawn", "id", victim.id, "pos", victim.pos));
        }, RESPAWN_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static double[] toDoubleArray(List<Object> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = ((Number) list.get(i)).doubleValue();
        return arr;
    }

    // Player hit box: centered on the reported eye position (camera height ~1.7).
    private static final double HALF_W = 0.4, HALF_D = 0.35, BELOW_EYE = 1.6, ABOVE_EYE = 0.2;

    private static Double intersectAABB(double[] origin, double[] dir, double[] targetPos) {
        double[] min = {targetPos[0] - HALF_W, targetPos[1] - BELOW_EYE, targetPos[2] - HALF_D};
        double[] max = {targetPos[0] + HALF_W, targetPos[1] + ABOVE_EYE, targetPos[2] + HALF_D};

        double tmin = (min[0] - origin[0]) / dir[0];
        double tmax = (max[0] - origin[0]) / dir[0];
        if (tmin > tmax) { double t = tmin; tmin = tmax; tmax = t; }

        double tymin = (min[1] - origin[1]) / dir[1];
        double tymax = (max[1] - origin[1]) / dir[1];
        if (tymin > tymax) { double t = tymin; tymin = tymax; tymax = t; }

        if (tmin > tymax || tymin > tmax) return null;
        if (tymin > tmin) tmin = tymin;
        if (tymax < tmax) tmax = tymax;

        double tzmin = (min[2] - origin[2]) / dir[2];
        double tzmax = (max[2] - origin[2]) / dir[2];
        if (tzmin > tzmax) { double t = tzmin; tzmin = tzmax; tzmax = t; }

        if (tmin > tzmax || tzmin > tmax) return null;
        if (tzmin > tmin) tmin = tzmin;

        if (tmin < 0) return null;
        return tmin;
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
