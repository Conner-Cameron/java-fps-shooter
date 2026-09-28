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
    private String playerName = "";

    public boolean isConnected() {
        return connected;
    }

    public String status() {
        return status;
    }

    /** Opens the connection asynchronously; the join message is sent as soon as it's up. */
    public void connect(String url, String playerName) {
        this.playerName = playerName;
        status = "Connecting to " + url + "…";
        try {
            HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(url), this)
                    .exceptionally(e -> {
                        connected = false;
                        status = "Connection error";
                        return null;
                    });
        } catch (IllegalArgumentException e) {
            status = "Bad server address";
        }
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
        send(Json.obj("type", "join", "name", playerName));
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
        status = "Disconnected from server";
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connected = false;
        status = "Connection error";
    }
}
