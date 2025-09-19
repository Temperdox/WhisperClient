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

    // Limits - adjusted for better chunking compatibility
    private static final long MAX_MEDIA_SIZE = 25 * 1024 * 1024; // 25MB (reduced for chunking)
    private static final long MAX_FILE_SIZE = 25 * 1024 * 1024;   // 25MB (consistent limit)
    private static final long MAX_COMPRESSED_SIZE = 15 * 1024 * 1024; // 15MB after compression

    public static SimpleMediaService getInstance() {
        return INSTANCE;
    }

    private SimpleMediaService() {}

    /**
     * Simple media message structure - enhanced for chunking compatibility
     */
    public static class SimpleMediaMessage {
        private String id;
        private String type; // "image", "video", "audio", "file"
        private String mimeType; // Changed from 'mime' to 'mimeType' for consistency
        private String fileName; // Changed from 'name' to 'fileName' for consistency
        private long size;
        private String data; // Base64 compressed data
        private long timestamp;
        private String checksum; // Added for data integrity
        private String caption; // Added for captions

        public SimpleMediaMessage() {}

        public SimpleMediaMessage(String id, String type, String mimeType, String fileName, long size, String data) {
            this.id = id;
            this.type = type;
            this.mimeType = mimeType;
            this.fileName = fileName;
            this.size = size;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
            this.checksum = calculateChecksum(data);
        }

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }

        // Legacy getter for backward compatibility
        public String getMime() { return mimeType; }
        public void setMime(String mime) { this.mimeType = mime; }

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }

        // Legacy getter for backward compatibility
        public String getName() { return fileName; }
        public void setName(String name) { this.fileName = name; }

        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }

        public String getData() { return data; }
        public void setData(String data) {
            this.data = data;
            this.checksum = calculateChecksum(data);
        }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        public String getChecksum() { return checksum; }
        public void setChecksum(String checksum) { this.checksum = checksum; }

        public String getCaption() { return caption; }
        public void setCaption(String caption) { this.caption = caption; }

        private String calculateChecksum(String data) {
            if (data == null || data.isEmpty()) return "";
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] hash = md.digest(data.getBytes());
                return Base64.getEncoder().encodeToString(hash);
            } catch (Exception e) {
                return "";
            }
        }

        public boolean verifyChecksum() {
            if (checksum == null || checksum.isEmpty()) return true; // Skip verification if no checksum
            return checksum.equals(calculateChecksum(data));
        }

        public String toJson() {
            try {
                return MAPPER.writeValueAsString(this);
            } catch (Exception e) {
                System.err.println("[SimpleMediaMessage] Failed to serialize to JSON: " + e.getMessage());
                return "{}";
            }
        }

        public static SimpleMediaMessage fromJson(String json) {
            try {
                return MAPPER.readValue(json, SimpleMediaMessage.class);
            } catch (Exception e) {
                System.err.println("[SimpleMediaMessage] Failed to deserialize from JSON: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Synchronous file processing for immediate use
     */
    public SimpleMediaMessage processFile(File file) throws Exception {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("File does not exist");
        }

        long fileSize = file.length();
        String fileName = file.getName();
        String mimeType = Files.probeContentType(file.toPath());
        if (mimeType == null) {
            mimeType = guessMimeType(fileName);
        }

        String mediaType = determineMediaType(mimeType);

        // Check size limits
        if (fileSize > MAX_FILE_SIZE) {
            throw new RuntimeException("File must be under " + formatFileSize(MAX_FILE_SIZE));
        }

        System.out.println("[SimpleMediaService] Processing " + fileName + " (" + formatFileSize(fileSize) + ")");

        // Read file
        byte[] fileBytes = Files.readAllBytes(file.toPath());

        // Compress data
        byte[] compressed = compressData(fileBytes);

        // Check compressed size
        if (compressed.length > MAX_COMPRESSED_SIZE) {
            throw new RuntimeException("File too large even after compression (" +
                    formatFileSize(compressed.length) + " > " +
                    formatFileSize(MAX_COMPRESSED_SIZE) + "). Try a smaller file.");
        }

        // Encode to base64
        String base64Data = Base64.getEncoder().encodeToString(compressed);

        String messageId = java.util.UUID.randomUUID().toString();

        SimpleMediaMessage result = new SimpleMediaMessage(messageId, mediaType, mimeType, fileName, fileSize, base64Data);

        System.out.println("[SimpleMediaService] Processed " + fileName +
                " (" + formatFileSize(fileSize) + " -> " + formatFileSize(compressed.length) + " compressed)");

        return result;
    }

    /**
     * Process file with compression (async with progress callback)
     */
    public CompletableFuture<SimpleMediaMessage> processFileAsync(File file, Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (progressCallback != null) {
                    Platform.runLater(() -> progressCallback.accept("Reading file..."));
                }

                long fileSize = file.length();
                String fileName = file.getName();
                String mimeType = Files.probeContentType(file.toPath());
                if (mimeType == null) {
                    mimeType = guessMimeType(fileName);
                }

                String mediaType = determineMediaType(mimeType);

                // Check size limits
                if (fileSize > MAX_FILE_SIZE) {
                    throw new RuntimeException("File must be under " + formatFileSize(MAX_FILE_SIZE));
                }

                if (progressCallback != null) {
                    Platform.runLater(() -> progressCallback.accept("Compressing..."));
                }

                // Read file
                byte[] fileBytes = Files.readAllBytes(file.toPath());

                // Compress data
                byte[] compressed = compressData(fileBytes);

                // Check compressed size
                if (compressed.length > MAX_COMPRESSED_SIZE) {
                    throw new RuntimeException("File too large even after compression. Try a smaller file.");
                }

                if (progressCallback != null) {
                    Platform.runLater(() -> progressCallback.accept("Encoding..."));
                }

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
                if (progressCallback != null) {
                    Platform.runLater(() -> progressCallback.accept("Verifying data..."));
                }

                // Verify checksum if available
                if (!mediaMessage.verifyChecksum()) {
                    throw new RuntimeException("Data integrity check failed");
                }

                if (progressCallback != null) {
                    Platform.runLater(() -> progressCallback.accept("Decompressing..."));
                }

                // Decode base64
                byte[] compressed = Base64.getDecoder().decode(mediaMessage.getData());

                // Decompress
                byte[] fileBytes = decompressData(compressed);

                if (progressCallback != null) {
                    Platform.runLater(() -> progressCallback.accept("Saving file..."));
                }

                // Create temp file
                String tempDir = System.getProperty("java.io.tmpdir");
                String safeName = sanitizeFileName(mediaMessage.getFileName());
                File tempFile = new File(tempDir, "whisper_" + mediaMessage.getId() + "_" + safeName);

                Files.write(tempFile.toPath(), fileBytes);

                System.out.println("[SimpleMediaService] Reconstructed " + mediaMessage.getFileName() +
                        " to " + tempFile.getAbsolutePath());

                return tempFile;

            } catch (Exception e) {
                throw new RuntimeException("Failed to reconstruct file: " + e.getMessage(), e);
            }
        }, mediaProcessor);
    }

    /**
     * Reconstruct file synchronously
     */
    public File reconstructFile(SimpleMediaMessage mediaMessage) throws Exception {
        // Verify checksum if available
        if (!mediaMessage.verifyChecksum()) {
            throw new RuntimeException("Data integrity check failed");
        }

        // Decode base64
        byte[] compressed = Base64.getDecoder().decode(mediaMessage.getData());

        // Decompress
        byte[] fileBytes = decompressData(compressed);

        // Create temp file
        String tempDir = System.getProperty("java.io.tmpdir");
        String safeName = sanitizeFileName(mediaMessage.getFileName());
        File tempFile = new File(tempDir, "whisper_" + mediaMessage.getId() + "_" + safeName);

        Files.write(tempFile.toPath(), fileBytes);

        System.out.println("[SimpleMediaService] Reconstructed " + mediaMessage.getFileName() +
                " to " + tempFile.getAbsolutePath());

        return tempFile;
    }

    /**
     * Create media message text - optimized for chunking system
     */
    public String createMediaMessageText(SimpleMediaMessage mediaMessage) {
        // Use $MEDIA$ format but ensure it's chunking-friendly
        String jsonData = mediaMessage.toJson();
        String result = "$MEDIA$" + jsonData + "$END$";

        System.out.println("[SimpleMediaService] Created media message text (" +
                formatFileSize(result.length()) + ")");

        return result;
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
            SimpleMediaMessage message = SimpleMediaMessage.fromJson(jsonData);

            if (message != null && !message.verifyChecksum()) {
                System.err.println("[SimpleMediaService] Checksum verification failed for media message");
                // Still return the message but log the error
            }

            return message;
        } catch (Exception e) {
            System.err.println("[SimpleMediaService] Failed to extract media message: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get estimated message size after compression and encoding
     */
    public long getEstimatedMessageSize(File file) throws Exception {
        if (!file.exists()) return 0;

        // Quick estimation: compressed size is typically 10-30% of original for media files
        long fileSize = file.length();
        String mimeType = Files.probeContentType(file.toPath());

        if (mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("video/"))) {
            // Media files compress less
            return (long) (fileSize * 0.8); // Estimate 80% of original
        } else {
            // Text and other files compress more
            return (long) (fileSize * 0.3); // Estimate 30% of original
        }
    }

    /**
     * Validate file before processing
     */
    public ValidationResult validateFile(File file) {
        if (file == null || !file.exists()) {
            return new ValidationResult(false, "File does not exist");
        }

        if (file.length() == 0) {
            return new ValidationResult(false, "File is empty");
        }

        if (file.length() > MAX_FILE_SIZE) {
            return new ValidationResult(false, "File too large (max " + formatFileSize(MAX_FILE_SIZE) + ")");
        }

        try {
            long estimatedSize = getEstimatedMessageSize(file);
            if (estimatedSize > MAX_COMPRESSED_SIZE) {
                return new ValidationResult(false, "File will be too large even after compression");
            }
        } catch (Exception e) {
            return new ValidationResult(false, "Error validating file: " + e.getMessage());
        }

        return new ValidationResult(true, null);
    }

    // Private helper methods
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
        if (fileName == null || !fileName.contains(".")) {
            return "application/octet-stream";
        }

        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

        switch (extension) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "webp": return "image/webp";
            case "tiff": case "tif": return "image/tiff";

            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "mov": return "video/quicktime";
            case "avi": return "video/x-msvideo";
            case "mkv": return "video/x-matroska";

            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            case "m4a": return "audio/mp4";
            case "flac": return "audio/flac";

            case "pdf": return "application/pdf";
            case "txt": return "text/plain";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "zip": return "application/zip";
            case "json": return "application/json";

            default: return "application/octet-stream";
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "unknown";
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Get service statistics
     */
    public ServiceStats getStats() {
        return new ServiceStats(
                MAX_FILE_SIZE,
                MAX_COMPRESSED_SIZE,
                mediaProcessor.isShutdown()
        );
    }

    public void shutdown() {
        System.out.println("[SimpleMediaService] Shutting down media processor...");
        mediaProcessor.shutdown();
        try {
            if (!mediaProcessor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                mediaProcessor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mediaProcessor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Helper classes
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        public ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public boolean isValid() { return valid; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class ServiceStats {
        private final long maxFileSize;
        private final long maxCompressedSize;
        private final boolean isShutdown;

        public ServiceStats(long maxFileSize, long maxCompressedSize, boolean isShutdown) {
            this.maxFileSize = maxFileSize;
            this.maxCompressedSize = maxCompressedSize;
            this.isShutdown = isShutdown;
        }

        public long getMaxFileSize() { return maxFileSize; }
        public long getMaxCompressedSize() { return maxCompressedSize; }
        public boolean isShutdown() { return isShutdown; }
    }
}