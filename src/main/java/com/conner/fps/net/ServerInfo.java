package com.conner.fps.net;

import com.conner.fps.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Plain-HTTP side of the game server: the same host that speaks WebSocket on
 * {@code /ws} also answers {@code /health} (used to wake a sleeping free-tier
 * host before connecting) and {@code /stats} (the lifetime leaderboard).
 */
public final class ServerInfo {
    private ServerInfo() {
    }

    /** One leaderboard line. */
    public static final class Leader {
        public final String name;
        public final int kills, deaths, wins;

        Leader(String name, int kills, int deaths, int wins) {
            this.name = name;
            this.kills = kills;
            this.deaths = deaths;
            this.wins = wins;
        }
    }

    /** {@code wss://host/ws} -> {@code https://host}; null if it isn't a ws(s) URL. */
    public static String httpBase(String wsUrl) {
        try {
            URI u = URI.create(wsUrl.trim());
            String scheme = "wss".equalsIgnoreCase(u.getScheme()) ? "https" : "ws".equalsIgnoreCase(u.getScheme()) ? "http" : null;
            if (scheme == null || u.getHost() == null) return null;
            return scheme + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static HttpResponse<String> get(String url, int timeoutSec) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSec)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSec)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Hosted servers can be asleep; a local or LAN one is either up or not, so there is nothing to wait for. */
    public static boolean mayBeAsleep(String wsUrl) {
        String base = httpBase(wsUrl);
        if (base == null) return false;
        String host = URI.create(base).getHost().toLowerCase();
        return !(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.endsWith(".local")
                || host.startsWith("192.168.") || host.startsWith("10.") || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*"));
    }

    /**
     * True once the server answers HTTP at all. A sleeping host either refuses,
     * times out, or replies 5xx from its proxy while it boots; any answer below
     * 500 (even a 404 from an older server without /health) means it's up.
     */
    public static boolean isAwake(String wsUrl) {
        String base = httpBase(wsUrl);
        if (base == null) return true; // not something we can probe: let the WebSocket connect decide
        try {
            return get(base + "/health", 5).statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /** Top players by kills; empty if the server has none or can't be reached. Never throws. */
    public static CompletableFuture<List<Leader>> leaderboard(String wsUrl) {
        return CompletableFuture.supplyAsync(() -> {
            List<Leader> out = new ArrayList<>();
            String base = httpBase(wsUrl);
            if (base == null) return out;
            try {
                HttpResponse<String> res = get(base + "/stats", 5);
                if (res.statusCode() != 200) return out;
                for (Object o : Json.list(Json.parseObject(res.body()).get("leaderboard"))) {
                    Map<String, Object> row = Json.obj(o);
                    out.add(new Leader(String.valueOf(row.get("name")), (int) Json.num(row.get("kills"), 0),
                            (int) Json.num(row.get("deaths"), 0), (int) Json.num(row.get("wins"), 0)));
                    if (out.size() >= 5) break;
                }
            } catch (Exception ignored) {
                out.clear();
            }
            return out;
        });
    }
}
