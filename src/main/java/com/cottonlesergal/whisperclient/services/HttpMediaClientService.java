package com.cottonlesergal.whisperclient.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.io.File;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP-based media service that bypasses WebSocket chunking
 * Sends media directly via HTTP POST to avoid 1MB WebSocket limit
 */
public class HttpMediaClientService {
    private static final HttpMediaClientService INSTANCE = new HttpMediaClientService();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    // 25MB limit for HTTP uploads (much larger than 1MB WebSocket limit)
    private static final long MAX_FILE_SIZE = 25 * 1024 * 1024;

    public static HttpMediaClientService getInstance() {
        return INSTANCE;
    }

    /**
     * Send media file directly via HTTP POST (no chunking)
     */
    public CompletableFuture<Void> sendMediaAsync(File file, String to, String caption) {
        return CompletableFuture.runAsync(() -> {
            try {
                sendMedia(file, to, caption);
            } catch (Exception e) {
                throw new RuntimeException("Failed to send media: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Send media file synchronously
     */
    public void sendMedia(File file, String to, String caption) throws Exception {
        if (!file.exists()) {
            throw new RuntimeException("File does not exist");
        }

        if (file.length() > MAX_FILE_SIZE) {
            throw new RuntimeException("File too large for HTTP upload (max " + formatFileSize(MAX_FILE_SIZE) + ")");
        }

        System.out.println("[HttpMediaClientService] Sending media file: " + file.getName() +
                " (" + formatFileSize(file.length()) + ") to " + to);

        // Read file and encode
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        String base64Data = Base64.getEncoder().encodeToString(fileBytes);

        // Determine MIME type
        String mimeType = Files.probeContentType(file.toPath());
        if (mimeType == null) {
            mimeType = guessMimeType(file.getName());
        }

        // Create upload payload
        var payload = MAPPER.createObjectNode()
                .put("to", to)
                .put("fileName", file.getName())
                .put("mimeType", mimeType)
                .put("size", file.length())
                .put("data", base64Data);

        if (caption != null && !caption.trim().isEmpty()) {
            payload.put("caption", caption.trim());
        }

        // Send via HTTP POST
        HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/send-media"))
                .header("authorization", "Bearer " + Config.APP_TOKEN)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String errorBody = response.body();
            System.err.println("[HttpMediaClientService] Failed to send media. Status: " + response.statusCode() +
                    ", Body: " + errorBody);

            if (response.statusCode() == 403) {
                throw new RuntimeException("Not friends with " + to + " - cannot send media");
            } else if (response.statusCode() == 413) {
                throw new RuntimeException("File too large for server");
            } else {
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + errorBody);
            }
        }

        System.out.println("[HttpMediaClientService] Successfully sent media to " + to);
    }

    private String guessMimeType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

        switch (extension) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "webp": return "image/webp";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "mov": return "video/quicktime";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            default: return "application/octet-stream";
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}