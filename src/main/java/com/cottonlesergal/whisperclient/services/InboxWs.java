package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.events.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class InboxWs implements WebSocket.Listener {
    private static final ObjectMapper M = new ObjectMapper();
    private WebSocket ws;
    private String workerUrl;
    private String username;
    private String token;
    private boolean isConnected = false;
    private boolean shouldReconnect = true;

    // Reconnection logic
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long INITIAL_RECONNECT_DELAY = 2000; // 2 seconds

    public void connect(String workerBaseUrl, String username, String jwtBearer) {
        this.workerUrl = workerBaseUrl;
        this.username = username;
        this.token = jwtBearer;

        String wss = workerBaseUrl.replaceFirst("^http", "ws") + "/inbox/" + username;
        System.out.println("[InboxWs] Connecting to: " + wss);
        System.out.println("[InboxWs] Username: " + username);
        System.out.println("[InboxWs] Token length: " + (jwtBearer != null ? jwtBearer.length() : 0));

        if (jwtBearer == null || jwtBearer.isEmpty()) {
            System.err.println("[InboxWs] ERROR: JWT token is null or empty!");
            return;
        }

        try {
            this.ws = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build()
                    .newWebSocketBuilder()
                    .header("Authorization", "Bearer " + jwtBearer)
                    .header("User-Agent", "WhisperClient/1.0")
                    .connectTimeout(Duration.ofSeconds(30))
                    .buildAsync(URI.create(wss), this)
                    .whenComplete((webSocket, throwable) -> {
                        if (throwable != null) {
                            System.err.println("[InboxWs] Connection failed: " + throwable.getMessage());
                            throwable.printStackTrace();
                            scheduleReconnect();
                        } else {
                            System.out.println("[InboxWs] WebSocket connection initiated successfully");
                            isConnected = true;
                            reconnectAttempts = 0; // Reset attempts on successful connection
                        }
                    })
                    .join();

        } catch (Exception e) {
            System.err.println("[InboxWs] Exception during connection: " + e.getMessage());
            e.printStackTrace();
            scheduleReconnect();
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        System.out.println("[InboxWs] WebSocket opened successfully");
        isConnected = true;
        reconnectAttempts = 0;
        webSocket.request(1);

        // Send a ping to verify connection
        webSocket.sendText("ping", true);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            String message = data.toString();
            System.out.println("[InboxWs] Received message: " +
                    (message.length() > 200 ? message.substring(0, 200) + "... (truncated)" : message));

            // Handle ping response
            if ("pong".equals(message.trim())) {
                System.out.println("[InboxWs] Received pong - connection is alive");
                webSocket.request(1);
                return null;
            }

            // Check if this looks like raw base64 data (corrupted message)
            if (!message.trim().startsWith("{") && !message.trim().startsWith("[")) {
                System.err.println("[InboxWs] Received malformed message (not JSON): " +
                        message.substring(0, Math.min(100, message.length())) + "...");
                webSocket.request(1);
                return null;
            }

            // Parse and emit event
            JsonNode n = M.readTree(message);

            // Log the parsed event for debugging
            System.out.println("[InboxWs] Parsed event - Type: " + n.path("type").asText("") +
                    ", From: " + n.path("from").asText("") +
                    ", To: " + n.path("to").asText(""));

            Event ev = new Event(
                    n.path("type").asText(""),
                    n.path("from").asText(null),
                    n.path("to").asText(null),
                    n.path("at").asLong(System.currentTimeMillis()),
                    n.path("data").isMissingNode() ? n : n.path("data") // Handle both formats
            );

            // Additional handling for events with top-level fields (like signal)
            if (n.has("kind")) {
                // This is likely a signal event with kind/payload at top level
                ev = new Event(
                        n.path("type").asText("signal"),
                        n.path("from").asText(null),
                        n.path("to").asText(null),
                        n.path("at").asLong(System.currentTimeMillis()),
                        n // Pass the entire node as data
                );
            }

            System.out.println("[InboxWs] Emitting event to AppCtx.BUS: " + ev.type);
            AppCtx.BUS.emit(ev);

        } catch (Exception e) {
            System.err.println("[InboxWs] Error processing message: " + e.getMessage());
            // Don't print the full stack trace for JSON errors, just log the issue
            if (!(e instanceof com.fasterxml.jackson.core.JsonParseException)) {
                e.printStackTrace();
            }
        }

        webSocket.request(1);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("[InboxWs] WebSocket error: " + error.getMessage());
        error.printStackTrace();
        isConnected = false;

        if (shouldReconnect) {
            scheduleReconnect();
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        System.out.println("[InboxWs] WebSocket closed. Status: " + statusCode + ", Reason: " + reason);
        isConnected = false;

        if (shouldReconnect && statusCode != 1000) { // 1000 is normal closure
            scheduleReconnect();
        }

        return null;
    }

    private void scheduleReconnect() {
        if (!shouldReconnect || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                System.err.println("[InboxWs] Max reconnection attempts reached. Giving up.");
            }
            return;
        }

        reconnectAttempts++;
        long delay = INITIAL_RECONNECT_DELAY * (long) Math.pow(2, Math.min(reconnectAttempts - 1, 4)); // Exponential backoff, max 32 seconds

        System.out.println("[InboxWs] Scheduling reconnect attempt " + reconnectAttempts +
                " in " + delay + "ms");

        scheduler.schedule(() -> {
            if (shouldReconnect && workerUrl != null && username != null && token != null) {
                System.out.println("[InboxWs] Attempting reconnection...");
                connect(workerUrl, username, token);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public boolean isConnected() {
        return isConnected && ws != null && !ws.isOutputClosed();
    }

    public void disconnect() {
        System.out.println("[InboxWs] Disconnecting WebSocket...");
        shouldReconnect = false;

        if (ws != null) {
            try {
                ws.sendClose(1000, "Client disconnecting");
            } catch (Exception e) {
                System.err.println("[InboxWs] Error during disconnect: " + e.getMessage());
            }
        }

        isConnected = false;

        // Shutdown scheduler
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // Method to send a test message (for debugging)
    public void sendTestMessage(String message) {
        if (isConnected() && ws != null) {
            try {
                ws.sendText(message, true);
                System.out.println("[InboxWs] Sent test message: " + message);
            } catch (Exception e) {
                System.err.println("[InboxWs] Failed to send test message: " + e.getMessage());
            }
        } else {
            System.err.println("[InboxWs] Cannot send test message - not connected");
        }
    }

    // Health check method
    public void ping() {
        if (isConnected() && ws != null) {
            try {
                ws.sendText("ping", true);
            } catch (Exception e) {
                System.err.println("[InboxWs] Failed to send ping: " + e.getMessage());
                isConnected = false;
            }
        }
    }

    public String getConnectionInfo() {
        return String.format("InboxWs[connected=%s, attempts=%d, url=%s, user=%s]",
                isConnected(), reconnectAttempts, workerUrl, username);
    }
}