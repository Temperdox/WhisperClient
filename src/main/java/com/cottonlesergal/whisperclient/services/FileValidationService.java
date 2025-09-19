package com.cottonlesergal.whisperclient.services;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class FileValidationService {
    private static final FileValidationService INSTANCE = new FileValidationService();

    // File size limits
    private static final long MAX_MEDIA_SIZE = 100 * 1024 * 1024; // 100MB for photos/videos
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;   // 50MB for other files

    // Media count limits per conversation
    private static final int MAX_MEDIA_PER_CONVERSATION = 10;

    // Track media count per conversation
    private final ConcurrentHashMap<String, AtomicInteger> mediaCounters = new ConcurrentHashMap<>();

    // Define media file types
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "svg"
    ));

    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "avi", "mov", "wmv", "flv", "webm", "mkv", "m4v", "3gp"
    ));

    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3", "wav", "ogg", "m4a", "aac", "flac", "wma"
    ));

    public static FileValidationService getInstance() {
        return INSTANCE;
    }

    private FileValidationService() {}

    /**
     * Validate file before upload
     */
    public ValidationResult validateFile(File file, String conversationId) {
        if (file == null || !file.exists()) {
            return ValidationResult.error("File does not exist");
        }

        String fileName = file.getName().toLowerCase();
        String extension = getFileExtension(fileName);
        long fileSize = file.length();

        // Check if it's a media file
        boolean isMediaFile = isMediaFile(extension);

        // Check file size limits
        if (isMediaFile && fileSize > MAX_MEDIA_SIZE) {
            return ValidationResult.error(String.format(
                    "Media files must be under %s. This file is %s.",
                    formatFileSize(MAX_MEDIA_SIZE),
                    formatFileSize(fileSize)
            ));
        }

        if (!isMediaFile && fileSize > MAX_FILE_SIZE) {
            return ValidationResult.error(String.format(
                    "Files must be under %s. This file is %s.",
                    formatFileSize(MAX_FILE_SIZE),
                    formatFileSize(fileSize)
            ));
        }

        // Check media count limit
        if (isMediaFile) {
            int currentCount = getMediaCount(conversationId);
            if (currentCount >= MAX_MEDIA_PER_CONVERSATION) {
                return ValidationResult.error(String.format(
                        "You can only send up to %d media files per conversation. " +
                                "Current count: %d. Try sending text messages or smaller files instead.",
                        MAX_MEDIA_PER_CONVERSATION,
                        currentCount
                ));
            }
        }

        // File is valid
        return ValidationResult.success();
    }

    /**
     * Validate multiple files (for drag & drop or multi-select)
     */
    public ValidationResult validateFiles(List<File> files, String conversationId) {
        if (files == null || files.isEmpty()) {
            return ValidationResult.error("No files selected");
        }

        if (files.size() > 5) {
            return ValidationResult.error("You can only upload up to 5 files at once");
        }

        // Count media files in this batch
        int mediaFilesInBatch = 0;
        long totalSize = 0;

        for (File file : files) {
            String extension = getFileExtension(file.getName().toLowerCase());
            boolean isMediaFile = isMediaFile(extension);

            if (isMediaFile) {
                mediaFilesInBatch++;
            }

            totalSize += file.length();

            // Validate each file individually
            ValidationResult result = validateFile(file, conversationId);
            if (!result.isValid()) {
                return result;
            }
        }

        // Check if this batch would exceed media limit
        int currentMediaCount = getMediaCount(conversationId);
        if (currentMediaCount + mediaFilesInBatch > MAX_MEDIA_PER_CONVERSATION) {
            return ValidationResult.error(String.format(
                    "This would exceed the media limit. Current: %d, Batch: %d, Max: %d",
                    currentMediaCount,
                    mediaFilesInBatch,
                    MAX_MEDIA_PER_CONVERSATION
            ));
        }

        // Check total batch size (reasonable limit for batch uploads)
        if (totalSize > 200 * 1024 * 1024) { // 200MB total batch limit
            return ValidationResult.error(String.format(
                    "Total batch size too large: %s. Try uploading fewer files at once.",
                    formatFileSize(totalSize)
            ));
        }

        return ValidationResult.success();
    }

    /**
     * Increment media counter for a conversation when media is successfully sent
     */
    public void incrementMediaCount(String conversationId) {
        mediaCounters.computeIfAbsent(conversationId, k -> new AtomicInteger(0)).incrementAndGet();
        System.out.println("[FileValidation] Media count for " + conversationId + ": " +
                getMediaCount(conversationId) + "/" + MAX_MEDIA_PER_CONVERSATION);
    }

    /**
     * Get current media count for a conversation
     */
    public int getMediaCount(String conversationId) {
        AtomicInteger counter = mediaCounters.get(conversationId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Reset media counter for a conversation (admin function)
     */
    public void resetMediaCount(String conversationId) {
        mediaCounters.remove(conversationId);
        System.out.println("[FileValidation] Reset media count for " + conversationId);
    }

    /**
     * Check if file extension is a media type
     */
    private boolean isMediaFile(String extension) {
        return IMAGE_EXTENSIONS.contains(extension) ||
                VIDEO_EXTENSIONS.contains(extension) ||
                AUDIO_EXTENSIONS.contains(extension);
    }

    /**
     * Get file extension from filename
     */
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1) : "";
    }

    /**
     * Format file size for display
     */
    public String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Get file type description
     */
    public String getFileTypeDescription(String fileName) {
        String extension = getFileExtension(fileName.toLowerCase());

        if (IMAGE_EXTENSIONS.contains(extension)) {
            return "Image";
        } else if (VIDEO_EXTENSIONS.contains(extension)) {
            return "Video";
        } else if (AUDIO_EXTENSIONS.contains(extension)) {
            return "Audio";
        } else {
            return "File";
        }
    }

    /**
     * Get remaining media slots for a conversation
     */
    public int getRemainingMediaSlots(String conversationId) {
        return Math.max(0, MAX_MEDIA_PER_CONVERSATION - getMediaCount(conversationId));
    }

    /**
     * Get validation statistics
     */
    public String getValidationStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("File Validation Stats:\n");
        stats.append("- Max media size: ").append(formatFileSize(MAX_MEDIA_SIZE)).append("\n");
        stats.append("- Max file size: ").append(formatFileSize(MAX_FILE_SIZE)).append("\n");
        stats.append("- Max media per conversation: ").append(MAX_MEDIA_PER_CONVERSATION).append("\n");
        stats.append("- Tracked conversations: ").append(mediaCounters.size()).append("\n");

        return stats.toString();
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