package com.conner.fps.net;

import com.conner.fps.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebSocket transport to GameServer (JDK's java.net.http client, so no
 * dependency): connection lifecycle and JSON framing only. Parsed server
 * messages are queued for the game thread to drain with {@link #poll()};
 * outgoing messages go through one sender thread because a WebSocket only
 * allows one send in flight at a time.
 */
public final class GameClient implements WebSocket.Listener {
    private static final long WAKE_TIMEOUT_MS = 120_000;
    private final ConcurrentLinkedQueue<Map<String, Object>> inbox = new ConcurrentLinkedQueue<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ws-sender");
        t.setDaemon(true);
        return t;
    });
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket socket;
    private volatile boolean connected = false;
    private volatile String status = "Not connected";
    private volatile boolean failed = false;
    private volatile boolean closedByUs = false;
    private volatile long wakeStartMs = 0; // non-zero while waiting for a sleeping host
    private String playerName = "";
    private String roomMode = "quick";
    private String roomCode = "";

    public boolean isConnected() {
        return connected;
    }

    public String status() {
        if (wakeStartMs != 0) {
            long secs = (System.currentTimeMillis() - wakeStartMs) / 1000;
            return "Waking the server2026 (" + secs + "s) - free hosting sleeps when idle";
        }
        return status;
    }

    /** True once the connection attempt (or the link itself) has irrecoverably failed before/instead of a match. */
    public boolean failed() {
        return failed;
    }

    /**
     * Connects and joins a room. {@code mode} is "quick" (auto-matchmake), "create" (new private room) or
     * "code" (join {@code code}). Free hosts sleep when idle, so this first polls /health until the server
     * answers -- {@link #status()} reports the wait -- and only then opens the WebSocket.
     */
    public void connect(String url, String playerName, String mode, String code) {
        this.playerName = playerName;
        this.roomMode = mode;
        this.roomCode = code == null ? "" : code;
        failed = false;
        status = "Connecting to " + url + "â¦";
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            wakeStartMs = start;
            boolean awake = !ServerInfo.mayBeAsleep(url);
            while (!awake && !closedByUs && System.currentTimeMillis() - start < WAKE_TIMEOUT_MS) {
                if (ServerInfo.isAwake(url)) { awake = true; break; }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            wakeStartMs = 0;
            if (closedByUs) return;
            if (!awake) {
                fail("Server didn't wake up - try again in a minute");
                return;
            }
            openSocket(url);
        }, "ws-connect");
        t.setDaemon(true);
        t.start();
    }

    private void openSocket(String url) {
        status = "Connecting to " + url + "â¦";
        try {
            HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(url), this)
                    .exceptionally(e -> {
                        fail("Connection error");
                        return null;
                    });
        } catch (IllegalArgumentException e) {
            fail("Bad server address");
        }
    }

    private void fail(String why) {
        connected = false;
        failed = true;
        status = why;
    }

    public Map<String, Object> poll() {
        return inbox.poll();
    }

    /** Sends a pre-built JSON object; silently dropped while not connected. */
    public void send(String json) {
        WebSocket ws = socket;
        if (!connected || ws == null) return;
        sender.submit(() -> {
            try {
                ws.sendText(json, true).join();
            } catch (Exception e) {
                connected = false;
                status = "Disconnected from server";
            }
        });
    }

    public void close() {
        closedByUs = true;
        connected = false;
        WebSocket ws = socket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        sender.shutdownNow();
    }

    // ------------------------------------------------------------------ WebSocket.Listener

    @Override
    public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        connected = true;
        status = "Connected";
        send(Json.obj("type", "join", "name", playerName, "mode", roomMode, "code", roomCode));
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            try {
                inbox.add(Json.parseObject(text));
            } catch (RuntimeException e) {
                // ignore malformed frames
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        connected = false;
        if (!closedByUs) failed = true;
        status = "Disconnected from server";
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connected = false;
        if (!closedByUs) failed = true;
        status = "Connection error";
    }
}
