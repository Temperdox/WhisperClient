package com.cottonlesergal.whisperclient.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class SimpleMediaService {
    private static final SimpleMediaService INSTANCE = new SimpleMediaService();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ExecutorService mediaProcessor = Executors.newFixedThreadPool(3);

    // Limits
    private static final long MAX_MEDIA_SIZE = 100 * 1024 * 1024; // 100MB
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;   // 50MB
    private static final long MAX_COMPRESSED_SIZE = 10 * 1024 * 1024; // 10MB after compression

    public static SimpleMediaService getInstance() {
        return INSTANCE;
    }

    private SimpleMediaService() {}

    /**
     * Simple media message structure
     */
    public static class SimpleMediaMessage {
        private String id;
        private String type; // "image", "video", "audio", "file"
        private String mime;
        private String name;
        private long size;
        private String data; // Base64 compressed data
        private long timestamp;

        public SimpleMediaMessage() {}

        public SimpleMediaMessage(String id, String type, String mime, String name, long size, String data) {
            this.id = id;
            this.type = type;
            this.mime = mime;
            this.name = name;
            this.size = size;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getMime() { return mime; }
        public void setMime(String mime) { this.mime = mime; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }

        public String getData() { return data; }
        public void setData(String data) { this.data = data; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        public String toJson() {
            try {
                return MAPPER.writeValueAsString(this);
            } catch (Exception e) {
                return "{}";
            }
        }

        public static SimpleMediaMessage fromJson(String json) {
            try {
                return MAPPER.readValue(json, SimpleMediaMessage.class);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Process file with compression
     */
    public CompletableFuture<SimpleMediaMessage> processFileAsync(File file, Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Platform.runLater(() -> progressCallback.accept("Reading file..."));

                long fileSize = file.length();
                String fileName = file.getName();
                String mimeType = Files.probeContentType(file.toPath());
                if (mimeType == null) {
                    mimeType = guessMimeType(fileName);
                }

                String mediaType = determineMediaType(mimeType);

                // Check size limits
                if (isMediaType(mediaType) && fileSize > MAX_MEDIA_SIZE) {
                    throw new RuntimeException("Media files must be under 100MB");
                }
                if (!isMediaType(mediaType) && fileSize > MAX_FILE_SIZE) {
                    throw new RuntimeException("Files must be under 50MB");
                }

                Platform.runLater(() -> progressCallback.accept("Compressing..."));

                // Read file
                byte[] fileBytes = Files.readAllBytes(file.toPath());

                // Compress data
                byte[] compressed = compressData(fileBytes);

                // Check compressed size
                if (compressed.length > MAX_COMPRESSED_SIZE) {
                    throw new RuntimeException("File too large even after compression. Try a smaller file.");
                }

                Platform.runLater(() -> progressCallback.accept("Encoding..."));

                // Encode to base64
                String base64Data = Base64.getEncoder().encodeToString(compressed);

                String messageId = java.util.UUID.randomUUID().toString();

                SimpleMediaMessage result = new SimpleMediaMessage(messageId, mediaType, mimeType, fileName, fileSize, base64Data);

                System.out.println("[SimpleMediaService] Processed " + fileName +
                        " (" + formatFileSize(fileSize) + " -> " + formatFileSize(compressed.length) + " compressed)");

                return result;

            } catch (Exception e) {
                throw new RuntimeException("Failed to process file: " + e.getMessage(), e);
            }
        }, mediaProcessor);
    }

    /**
     * Reconstruct file from compressed data
     */
    public CompletableFuture<File> reconstructFileAsync(SimpleMediaMessage mediaMessage, Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Platform.runLater(() -> progressCallback.accept("Decompressing..."));

                // Decode base64
                byte[] compressed = Base64.getDecoder().decode(mediaMessage.getData());

                // Decompress
                byte[] fileBytes = decompressData(compressed);

                Platform.runLater(() -> progressCallback.accept("Saving file..."));

                // Create temp file
                String tempDir = System.getProperty("java.io.tmpdir");
                String safeName = sanitizeFileName(mediaMessage.getName());
                File tempFile = new File(tempDir, "whisper_" + mediaMessage.getId() + "_" + safeName);

                Files.write(tempFile.toPath(), fileBytes);

                return tempFile;

            } catch (Exception e) {
                throw new RuntimeException("Failed to reconstruct file: " + e.getMessage(), e);
            }
        }, mediaProcessor);
    }

    /**
     * Create media message text (no chunking needed due to compression)
     */
    public String createMediaMessageText(SimpleMediaMessage mediaMessage) {
        return "$MEDIA$" + mediaMessage.toJson() + "$END$";
    }

    /**
     * Check if message is media
     */
    public boolean isMediaMessage(String messageText) {
        return messageText != null && messageText.startsWith("$MEDIA$") && messageText.endsWith("$END$");
    }

    /**
     * Extract media message
     */
    public SimpleMediaMessage extractMediaMessage(String messageText) {
        if (!isMediaMessage(messageText)) return null;

        try {
            String jsonData = messageText.substring(7, messageText.length() - 5); // Remove $MEDIA$ and $END$
            return SimpleMediaMessage.fromJson(jsonData);
        } catch (Exception e) {
            System.err.println("Failed to extract media message: " + e.getMessage());
            return null;
        }
    }

    private byte[] compressData(byte[] data) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    private byte[] decompressData(byte[] compressed) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (GZIPInputStream gzis = new GZIPInputStream(bais)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = gzis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
        }

        return baos.toByteArray();
    }

    private String determineMediaType(String mimeType) {
        if (mimeType == null) return "file";

        if (mimeType.startsWith("image/")) return "image";
        if (mimeType.startsWith("video/")) return "video";
        if (mimeType.startsWith("audio/")) return "audio";
        return "file";
    }

    private boolean isMediaType(String mediaType) {
        return "image".equals(mediaType) || "video".equals(mediaType) || "audio".equals(mediaType);
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
            case "avi": return "video/x-msvideo";

            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            case "m4a": return "audio/mp4";

            default: return "application/octet-stream";
        }
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public void shutdown() {
        mediaProcessor.shutdown();
    }
}