package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.events.Event;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import com.cottonlesergal.whisperclient.ui.MainController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;

public class InboxWs implements WebSocket.Listener {
    private static final ObjectMapper M = new ObjectMapper();
    private final MessageChunkingService chunkingService = MessageChunkingService.getInstance();
    private final MessageStorageService messageStorage = MessageStorageService.getInstance();
    private final RateLimiter rateLimiter = RateLimiter.getInstance();
    private final NotificationManager notificationManager = NotificationManager.getInstance();

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

    // Cleanup timer for chunked messages
    private Timer chunkCleanupTimer;

    // Add this field for auth failure callback
    private MainController mainController;

    /**
     * Set reference to MainController for auth failure handling
     */
    public void setMainController(MainController controller) {
        this.mainController = controller;
    }

    /**
     * Handle 401 errors by notifying MainController
     */
    private void handle401Error(String context) {
        System.err.println("[InboxWs] 401 error in " + context);
        if (mainController != null) {
            Platform.runLater(() -> {
                mainController.on401Error("InboxWs:" + context);
            });
        }
    }

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
            handle401Error("missing_token");
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

                            // Check if this is a 401 authentication error
                            String errorMessage = throwable.getMessage();
                            if (errorMessage != null && (
                                    errorMessage.contains("401") ||
                                            errorMessage.contains("Unexpected HTTP response status code 401") ||
                                            errorMessage.contains("unauthorized"))) {

                                System.err.println("[InboxWs] Detected 401 authentication error");
                                handle401Error("websocket_handshake");
                            }

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

            // Check if this is a 401 error
            String errorMessage = e.getMessage();
            if (errorMessage != null && (
                    errorMessage.contains("401") ||
                            errorMessage.contains("unauthorized"))) {
                handle401Error("connect_exception");
            }

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

        // Start periodic cleanup of chunked messages
        startChunkCleanupTimer();
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            String rawMessage = data.toString();

            // Handle ping response
            if ("pong".equals(rawMessage.trim())) {
                System.out.println("[InboxWs] Received pong - connection is alive");
                webSocket.request(1);
                return null;
            }

            // SKIP chunked messages entirely - we only handle HTTP media now
            if (rawMessage.startsWith("[CHUNK:")) {
                System.out.println("[InboxWs] Skipping old chunked message - use HTTP media instead");
                webSocket.request(1);
                return null;
            }

            // Only process direct JSON messages (no chunking)
            String completeMessage = rawMessage;

            // Log complete message length
            System.out.println("[InboxWs] Processing complete message (length: " + completeMessage.length() + ")");

            // Check if this looks like valid JSON
            if (!completeMessage.trim().startsWith("{") && !completeMessage.trim().startsWith("[")) {
                System.err.println("[InboxWs] Received malformed message (not JSON): " +
                        completeMessage.substring(0, Math.min(100, completeMessage.length())) + "...");
                webSocket.request(1);
                return null;
            }

            // Handle the complete message
            handleCompleteMessage(completeMessage);

        } catch (Exception e) {
            System.err.println("[InboxWs] Error processing message: " + e.getMessage());
            // Don't print full stack trace for JSON errors to avoid spam
            if (!(e instanceof com.fasterxml.jackson.core.JsonParseException)) {
                e.printStackTrace();
            }
        }

        webSocket.request(1);
        return null;
    }

    /**
     * Handle a complete message (either regular or reassembled from chunks)
     */
    private void handleCompleteMessage(String messageText) {
        try {
            JsonNode messageNode = M.readTree(messageText);

            String type = messageNode.path("type").asText();
            String from = messageNode.path("from").asText();
            String to = messageNode.path("to").asText();
            long timestamp = messageNode.path("at").asLong(System.currentTimeMillis());

            System.out.println("[InboxWs] Parsed event - Type: " + type + ", From: " + from + ", To: " + to);

            if ("chat".equals(type)) {
                handleChatMessage(messageNode, from, to, timestamp);
            } else if ("media-direct".equals(type)) {
                handleDirectMediaMessage(messageNode, from, to, timestamp);
            } else {
                // Handle other message types (signal, etc.)
                handleOtherMessage(messageNode, type, from, to, timestamp);
            }

        } catch (Exception e) {
            System.err.println("[InboxWs] Error parsing complete message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle direct media messages sent via HTTP POST (now with auto-download)
     */
    private void handleDirectMediaMessage(JsonNode messageNode, String from, String to, long timestamp) {
        try {
            JsonNode data = messageNode.path("data");
            String fileName = data.path("fileName").asText();
            String mimeType = data.path("mimeType").asText();
            long size = data.path("size").asLong();
            String mediaId = data.path("mediaId").asText();
            String downloadUrl = data.path("downloadUrl").asText();
            String caption = data.path("caption").asText("");
            String messageId = data.path("id").asText();

            System.out.println("[InboxWs] Received media notification from " + from +
                    ": " + fileName + " (" + formatFileSize(size) + ") - Auto-downloading...");

            // Apply rate limiting ONCE at the beginning
            if (!rateLimiter.allowMessage(from)) {
                System.out.println("[InboxWs] Media message from " + from + " was rate limited");
                return;
            }

            // Auto-download the media immediately
            CompletableFuture.runAsync(() -> {
                try {
                    // Download media data
                    HttpRequest req = HttpRequest.newBuilder(URI.create(downloadUrl))
                            .header("authorization", "Bearer " + Config.APP_TOKEN)
                            .GET()
                            .build();

                    HttpClient client = HttpClient.newHttpClient();
                    HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 401) {
                        handle401Error("media_download");
                        return;
                    }

                    if (response.statusCode() == 200) {
                        // Parse response to get media data
                        ObjectMapper mapper = new ObjectMapper();
                        JsonNode mediaData = mapper.readTree(response.body());
                        String base64Data = mediaData.path("data").asText();

                        // Create inline media message with embedded data
                        String inlineMediaMessage = String.format("[INLINE_MEDIA:%s:%s:%s:%d:%s]%s",
                                messageId, fileName, mimeType, size, base64Data,
                                caption.isEmpty() ? "" : "\n" + caption);

                        // Store as a chat message with embedded media (ONLY ONCE)
                        ChatMessage mediaMessage = ChatMessage.fromIncoming(from, inlineMediaMessage);
                        messageStorage.storeMessage(from, mediaMessage);

                        System.out.println("[InboxWs] Stored media message from " + from);

                        // Update UI on JavaFX thread - but DON'T store again
                        Platform.runLater(() -> {
                            try {
                                // Update notification count
                                notificationManager.incrementNotificationCount(from);

                                // Emit media-inline event for UI handling (display only, no storage)
                                JsonNode mediaEventData = M.createObjectNode()
                                        .put("fileName", fileName)
                                        .put("mimeType", mimeType)
                                        .put("size", size)
                                        .put("data", base64Data)
                                        .put("caption", caption);

                                Event mediaEvent = new Event("media-inline", from, to, timestamp, mediaEventData);
                                AppCtx.BUS.emit(mediaEvent);

                                System.out.println("[InboxWs] Emitted media-inline event for: " + fileName);

                            } catch (Exception e) {
                                System.err.println("[InboxWs] Error updating UI for media: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });

                    } else {
                        System.err.println("[InboxWs] Failed to download media: HTTP " + response.statusCode());
                    }

                } catch (Exception e) {
                    System.err.println("[InboxWs] Error auto-downloading media: " + e.getMessage());
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            System.err.println("[InboxWs] Error handling media notification: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Handle chat messages with proper storage and UI updates
     */
    private void handleChatMessage(JsonNode messageNode, String from, String to, long timestamp) {
        try {
            JsonNode data = messageNode.path("data");
            String content = data.path("text").asText();
            String messageId = data.path("id").asText();

            System.out.println("[InboxWs] Received chat message from " + from + " (ID: " + messageId + ")");

            // Apply rate limiting
            if (!rateLimiter.allowMessage(from)) {
                System.out.println("[InboxWs] Message from " + from + " was rate limited");
                return;
            }

            // Create and store the message
            ChatMessage chatMessage = new ChatMessage(
                    messageId, from, to, content, "text", false
            );
            chatMessage.setTimestamp(timestamp);

            // Store the message
            messageStorage.storeMessage(from, chatMessage);

            // Notify UI on JavaFX thread
            Platform.runLater(() -> {
                try {
                    // Update notification count
                    notificationManager.incrementNotificationCount(from);

                    // Emit event to the event bus for any UI components that need it
                    Event chatEvent = new Event("chat", from, to, timestamp, data);
                    AppCtx.BUS.emit(chatEvent);

                } catch (Exception e) {
                    System.err.println("[InboxWs] Error updating UI: " + e.getMessage());
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            System.err.println("[InboxWs] Error handling chat message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle other message types (signals, etc.)
     */
    private void handleOtherMessage(JsonNode messageNode, String type, String from, String to, long timestamp) {
        try {
            Event ev = new Event(
                    type,
                    from,
                    to,
                    timestamp,
                    messageNode.path("data").isMissingNode() ? messageNode : messageNode.path("data")
            );

            // Additional handling for events with top-level fields (like signal)
            if (messageNode.has("kind")) {
                ev = new Event(
                        messageNode.path("type").asText("signal"),
                        from,
                        to,
                        timestamp,
                        messageNode
                );
            }

            System.out.println("[InboxWs] Emitting event to AppCtx.BUS: " + ev.type);
            AppCtx.BUS.emit(ev);

        } catch (Exception e) {
            System.err.println("[InboxWs] Error handling other message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("[InboxWs] WebSocket error: " + error.getMessage());

        // Check if this is an auth-related error
        String errorMessage = error.getMessage();
        if (errorMessage != null && (
                errorMessage.contains("401") ||
                        errorMessage.contains("unauthorized") ||
                        errorMessage.contains("authentication"))) {
            handle401Error("websocket_error");
        }

        error.printStackTrace();
        isConnected = false;

        if (shouldReconnect) {
            scheduleReconnect();
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        System.out.println("[InboxWs] WebSocket closed. Code: " + statusCode +
                ", Reason: " + (reason != null ? reason : "none"));

        // Check if close was due to authentication
        if (statusCode == 1008 || statusCode == 1002) { // Policy violation or protocol error - often auth issues
            handle401Error("websocket_close_" + statusCode);
        }

        isConnected = false;

        // Stop cleanup timer
        if (chunkCleanupTimer != null) {
            chunkCleanupTimer.cancel();
            chunkCleanupTimer = null;
        }

        if (shouldReconnect) {
            scheduleReconnect();
        }

        return null;
    }

    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            System.err.println("[InboxWs] Max reconnection attempts (" + MAX_RECONNECT_ATTEMPTS + ") reached.");
            System.err.println("[InboxWs] Token may have expired. Consider re-authenticating.");

            // Notify about authentication failure instead of just showing notification
            handle401Error("max_reconnect_attempts");

            // Continue trying with exponential backoff but notify auth system
            long delay = Math.min(32000, INITIAL_RECONNECT_DELAY * (long) Math.pow(2, Math.min(reconnectAttempts - 1, 4)));
            System.out.println("[InboxWs] Scheduling reconnect attempt " + reconnectAttempts + " in " + delay + "ms");

            scheduler.schedule(() -> {
                if (shouldReconnect && !isConnected) {
                    System.out.println("[InboxWs] Attempting reconnection...");
                    reconnectAttempts++;
                    connect(workerUrl, username, token);
                }
            }, delay, TimeUnit.MILLISECONDS);

            return;
        }

        reconnectAttempts++;
        long delay = Math.min(32000, INITIAL_RECONNECT_DELAY * (long) Math.pow(2, reconnectAttempts - 1));
        System.out.println("[InboxWs] Scheduling reconnect attempt " + reconnectAttempts + " in " + delay + "ms");

        scheduler.schedule(() -> {
            if (shouldReconnect && !isConnected) {
                System.out.println("[InboxWs] Attempting reconnection...");
                connect(workerUrl, username, token);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Start cleanup timer for chunked messages
     */
    private void startChunkCleanupTimer() {
        if (chunkCleanupTimer != null) {
            chunkCleanupTimer.cancel();
        }

        chunkCleanupTimer = new Timer("ChunkCleanup", true);
        chunkCleanupTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    chunkingService.cleanupOldMessages();
                } catch (Exception e) {
                    System.err.println("[InboxWs] Error in chunk cleanup: " + e.getMessage());
                }
            }
        }, 30000, 30000); // Clean up every 30 seconds
    }

    /**
     * Manually clean up chunked messages
     */
    public void cleanupChunkedMessages() {
        chunkingService.cleanupOldMessages();
    }

    public boolean isConnected() {
        return isConnected && ws != null && !ws.isOutputClosed();
    }

    public void disconnect() {
        System.out.println("[InboxWs] Disconnecting WebSocket...");
        shouldReconnect = false;

        // Stop cleanup timer
        if (chunkCleanupTimer != null) {
            chunkCleanupTimer.cancel();
            chunkCleanupTimer = null;
        }

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

    /**
     * Get debug information about chunking service
     */
    public void printChunkingDebugInfo() {
        chunkingService.printBufferStatus();
    }
}