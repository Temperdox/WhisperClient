package com.cottonlesergal.whisperclient.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.concurrent.Task;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class EnhancedMediaService {
    private static final EnhancedMediaService INSTANCE = new EnhancedMediaService();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Background processing thread pool
    private final ExecutorService mediaProcessor = Executors.newFixedThreadPool(3);

    // File size limits
    private static final long MAX_MEDIA_SIZE = 100 * 1024 * 1024; // 100MB
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;   // 50MB
    private static final int MAX_BATCH_UPLOAD = 10; // 10 files per batch

    public static EnhancedMediaService getInstance() {
        return INSTANCE;
    }

    private EnhancedMediaService() {}

    /**
     * Enhanced media message structure for transmission
     */
    public static class MediaMessage {
        private String messageId;
        private String mediaType; // "image", "video", "audio", "file"
        private String mimeType;  // "image/png", "video/mp4", etc.
        private String fileName;
        private long fileSize;
        private String base64Data;
        private String checksum; // For integrity verification
        private long timestamp;

        // Constructors
        public MediaMessage() {}

        public MediaMessage(String messageId, String mediaType, String mimeType,
                            String fileName, long fileSize, String base64Data, String checksum) {
            this.messageId = messageId;
            this.mediaType = mediaType;
            this.mimeType = mimeType;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.base64Data = base64Data;
            this.checksum = checksum;
            this.timestamp = System.currentTimeMillis();
        }

        // Getters and setters
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }

        public String getMediaType() { return mediaType; }
        public void setMediaType(String mediaType) { this.mediaType = mediaType; }

        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }

        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }

        public String getBase64Data() { return base64Data; }
        public void setBase64Data(String base64Data) { this.base64Data = base64Data; }

        public String getChecksum() { return checksum; }
        public void setChecksum(String checksum) { this.checksum = checksum; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        public String toJson() {
            try {
                return MAPPER.writeValueAsString(this);
            } catch (Exception e) {
                return "{}";
            }
        }

        public static MediaMessage fromJson(String json) {
            try {
                return MAPPER.readValue(json, MediaMessage.class);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Process file upload in background thread
     */
    public CompletableFuture<MediaMessage> processFileAsync(File file, Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Platform.runLater(() -> progressCallback.accept("Reading file..."));

                // Validate file size
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

                Platform.runLater(() -> progressCallback.accept("Encoding to base64..."));

                // Read and encode file
                byte[] fileBytes = Files.readAllBytes(file.toPath());
                String base64Data = Base64.getEncoder().encodeToString(fileBytes);

                Platform.runLater(() -> progressCallback.accept("Calculating checksum..."));

                // Calculate checksum for integrity
                String checksum = calculateChecksum(fileBytes);

                Platform.runLater(() -> progressCallback.accept("Finalizing..."));

                String messageId = java.util.UUID.randomUUID().toString();

                return new MediaMessage(messageId, mediaType, mimeType, fileName, fileSize, base64Data, checksum);

            } catch (Exception e) {
                throw new RuntimeException("Failed to process file: " + e.getMessage(), e);
            }
        }, mediaProcessor);
    }

    /**
     * Reconstruct file from MediaMessage in background thread
     */
    public CompletableFuture<File> reconstructFileAsync(MediaMessage mediaMessage, Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Platform.runLater(() -> progressCallback.accept("Decoding media..."));

                // Decode base64
                byte[] fileBytes = Base64.getDecoder().decode(mediaMessage.getBase64Data());

                Platform.runLater(() -> progressCallback.accept("Verifying integrity..."));

                // Verify checksum
                String calculatedChecksum = calculateChecksum(fileBytes);
                if (!calculatedChecksum.equals(mediaMessage.getChecksum())) {
                    throw new RuntimeException("File integrity check failed");
                }

                Platform.runLater(() -> progressCallback.accept("Saving file..."));

                // Create temp file
                String tempDir = System.getProperty("java.io.tmpdir");
                String safeName = sanitizeFileName(mediaMessage.getFileName());
                File tempFile = new File(tempDir, "whisper_" + mediaMessage.getMessageId() + "_" + safeName);

                Files.write(tempFile.toPath(), fileBytes);

                Platform.runLater(() -> progressCallback.accept("Complete"));

                return tempFile;

            } catch (Exception e) {
                throw new RuntimeException("Failed to reconstruct file: " + e.getMessage(), e);
            }
        }, mediaProcessor);
    }

    /**
     * Validate batch upload
     */
    public ValidationResult validateBatchUpload(java.util.List<File> files) {
        if (files == null || files.isEmpty()) {
            return ValidationResult.error("No files selected");
        }

        if (files.size() > MAX_BATCH_UPLOAD) {
            return ValidationResult.error("You can only upload up to " + MAX_BATCH_UPLOAD + " files at once");
        }

        long totalSize = 0;
        for (File file : files) {
            if (!file.exists()) {
                return ValidationResult.error("File does not exist: " + file.getName());
            }

            long fileSize = file.length();
            String mimeType = guessMimeType(file.getName());
            String mediaType = determineMediaType(mimeType);

            // Check individual file size
            if (isMediaType(mediaType) && fileSize > MAX_MEDIA_SIZE) {
                return ValidationResult.error(String.format(
                        "%s exceeds media size limit (100MB): %s",
                        file.getName(),
                        formatFileSize(fileSize)
                ));
            }

            if (!isMediaType(mediaType) && fileSize > MAX_FILE_SIZE) {
                return ValidationResult.error(String.format(
                        "%s exceeds file size limit (50MB): %s",
                        file.getName(),
                        formatFileSize(fileSize)
                ));
            }

            totalSize += fileSize;
        }

        // Check total batch size (500MB limit for batch)
        if (totalSize > 500 * 1024 * 1024) {
            return ValidationResult.error(String.format(
                    "Total batch size too large: %s. Maximum batch size is 500MB.",
                    formatFileSize(totalSize)
            ));
        }

        return ValidationResult.success();
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

            case "pdf": return "application/pdf";
            case "txt": return "text/plain";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

            default: return "application/octet-stream";
        }
    }

    private String calculateChecksum(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return "";
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

    /**
     * Create a special message format for media transmission
     */
    public String createMediaMessageText(MediaMessage mediaMessage) {
        return "[MEDIA:" + mediaMessage.toJson() + "]";
    }

    /**
     * Check if a message contains media data
     */
    public boolean isMediaMessage(String messageText) {
        return messageText != null && messageText.startsWith("[MEDIA:") && messageText.endsWith("]");
    }

    /**
     * Extract media message from text
     */
    public MediaMessage extractMediaMessage(String messageText) {
        if (!isMediaMessage(messageText)) return null;

        try {
            String jsonData = messageText.substring(7, messageText.length() - 1); // Remove [MEDIA: and ]
            return MediaMessage.fromJson(jsonData);
        } catch (Exception e) {
            System.err.println("Failed to extract media message: " + e.getMessage());
            return null;
        }
    }

    public void shutdown() {
        mediaProcessor.shutdown();
    }

    /**
     * Validation result class
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}