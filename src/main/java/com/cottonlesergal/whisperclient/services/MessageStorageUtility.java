package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for managing message storage operations including cleanup and deletion
 */
public class MessageStorageUtility {
    private static final MessageStorageUtility INSTANCE = new MessageStorageUtility();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Storage paths - with null safety
    private static final String USER_HOME = getUserHomeDirectory();
    private static final String APP_DATA_DIR = USER_HOME + File.separator + ".whisperclient";
    private static final String MESSAGES_DIR = APP_DATA_DIR + File.separator + "messages";
    private static final String BACKUP_DIR = APP_DATA_DIR + File.separator + "backups";

    private static String getUserHomeDirectory() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.trim().isEmpty()) {
            // Fallback to current directory if user.home is not available
            userHome = System.getProperty("user.dir");
            if (userHome == null || userHome.trim().isEmpty()) {
                userHome = "."; // Last resort fallback
            }
        }
        return userHome;
    }

    public static MessageStorageUtility getInstance() {
        return INSTANCE;
    }

    private MessageStorageUtility() {
        // Ensure directories exist
        ensureDirectoriesExist();
    }

    private void ensureDirectoriesExist() {
        try {
            // Validate paths before attempting to create directories
            if (MESSAGES_DIR == null || MESSAGES_DIR.trim().isEmpty()) {
                System.err.println("[MessageStorageUtility] Messages directory path is null or empty");
                return;
            }

            if (BACKUP_DIR == null || BACKUP_DIR.trim().isEmpty()) {
                System.err.println("[MessageStorageUtility] Backup directory path is null or empty");
                return;
            }

            Path messagesPath = Paths.get(MESSAGES_DIR);
            Path backupPath = Paths.get(BACKUP_DIR);

            Files.createDirectories(messagesPath);
            Files.createDirectories(backupPath);

            System.out.println("[MessageStorageUtility] Created directories:");
            System.out.println("  Messages: " + messagesPath.toAbsolutePath());
            System.out.println("  Backups: " + backupPath.toAbsolutePath());

        } catch (Exception e) {
            System.err.println("[MessageStorageUtility] Failed to create directories: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Delete all locally stored messages for all users
     */
    public boolean deleteAllMessages() {
        try {
            System.out.println("[MessageStorageUtility] Starting deletion of all local messages...");

            // Create backup first
            String backupPath = createBackup("full_backup_before_delete");
            if (backupPath != null) {
                System.out.println("[MessageStorageUtility] Created backup at: " + backupPath);
            }

            // Delete all message directories and files
            File messagesDir = new File(MESSAGES_DIR);
            if (messagesDir.exists() && messagesDir.isDirectory()) {
                File[] userDirs = messagesDir.listFiles(File::isDirectory);
                if (userDirs != null) {
                    int deletedUsers = 0;
                    for (File userDir : userDirs) {
                        if (deleteDirectory(userDir)) {
                            deletedUsers++;
                            System.out.println("[MessageStorageUtility] Deleted user directory: " + userDir.getName());
                        } else {
                            System.err.println("[MessageStorageUtility] Failed to delete user directory: " + userDir.getName());
                        }
                    }

                    System.out.println("[MessageStorageUtility] Successfully deleted " + deletedUsers + " user directories");
                    return true;
                }
            }

            System.out.println("[MessageStorageUtility] No message directories found to delete");
            return true;

        } catch (Exception e) {
            System.err.println("[MessageStorageUtility] Error deleting all messages: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Delete all messages for a specific conversation
     */
    public boolean deleteConversationMessages(String username) {
        try {
            System.out.println("[MessageStorageUtility] Deleting messages for conversation with: " + username);

            // Create backup first
            String backupPath = createConversationBackup(username);
            if (backupPath != null) {
                System.out.println("[MessageStorageUtility] Created conversation backup at: " + backupPath);
            }

            // Delete user directory
            String sanitizedUsername = sanitizeUsername(username);
            Path userDir = Paths.get(MESSAGES_DIR, sanitizedUsername);

            if (Files.exists(userDir)) {
                if (deleteDirectory(userDir.toFile())) {
                    System.out.println("[MessageStorageUtility] Successfully deleted conversation directory: " + userDir);
                    return true;
                } else {
                    System.err.println("[MessageStorageUtility] Failed to delete conversation directory: " + userDir);
                    return false;
                }
            } else {
                System.out.println("[MessageStorageUtility] No conversation directory found for: " + username);
                return true; // Consider success if directory doesn't exist
            }

        } catch (Exception e) {
            System.err.println("[MessageStorageUtility] Error deleting conversation messages: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Delete a specific message by ID
     */
    public boolean deleteMessage(String username, String messageId) {
        try {
            System.out.println("[MessageStorageUtility] Deleting message " + messageId + " from conversation with " + username);

            String sanitizedUsername = sanitizeUsername(username);
            Path messagesDir = Paths.get(MESSAGES_DIR, sanitizedUsername, "messages");

            if (!Files.exists(messagesDir)) {
                System.out.println("[MessageStorageUtility] No messages directory found for: " + username);
                return false;
            }

            // Find and delete the specific message file
            boolean deleted = Files.list(messagesDir)
                    .filter(path -> path.getFileName().toString().endsWith("_" + messageId + ".msg"))
                    .findFirst()
                    .map(messagePath -> {
                        try {
                            Files.delete(messagePath);
                            System.out.println("[MessageStorageUtility] Deleted message file: " + messagePath.getFileName());
                            return true;
                        } catch (IOException e) {
                            System.err.println("[MessageStorageUtility] Failed to delete message file: " + e.getMessage());
                            return false;
                        }
                    })
                    .orElse(false);

            if (!deleted) {
                System.out.println("[MessageStorageUtility] Message file not found: " + messageId);
            }

            return deleted;

        } catch (Exception e) {
            System.err.println("[MessageStorageUtility] Error deleting message: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Delete multiple messages by ID
     */
    public boolean deleteMessages(String username, List<String> messageIds) {
        try {
            System.out.println("[MessageStorageUtility] Deleting " + messageIds.size() +
                    " messages from conversation with " + username);

            int deletedCount = 0;
            for (String messageId : messageIds) {
                if (deleteMessage(username, messageId)) {
                    deletedCount++;
                }
            }

            System.out.println("[MessageStorageUtility] Successfully deleted " + deletedCount + "/" + messageIds.size() + " messages");
            return deletedCount > 0;

        } catch (Exception e) {
            System.err.println("[MessageStorageUtility] Error deleting messages: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Create a backup of all messages
     */
    public String createBackup(String backupName) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String jsonBackupPath = BACKUP_DIR + File.separator +
                    (backupName != null ? backupName + "_" : "backup_") + timestamp + ".json";

            Map<String, Object> backup = new HashMap<>();
            backup.put("timestamp", Instant.now().toEpochMilli());
            backup.put("version", "1.0");
            backup.put("conversations", new HashMap<String, List<ChatMessage>>());

            // Load all conversations
            File messagesDir = new File(MESSAGES_DIR);
            if (messagesDir.exists() && messagesDir.isDirectory()) {
                File[] userDirs = messagesDir.listFiles(File::isDirectory);
                if (userDirs != null) {
                    Map<String, List<ChatMessage>> conversations = (Map<String, List<ChatMessage>>) backup.get("conversations");

                    for (File userDir : userDirs) {
                        String username = userDir.getName();
                        List<ChatMessage> messages = MessageStorageService.getInstance().loadMessages(username, 0, 10000);
                        conversations.put(username, messages);
                    }
                }
            }

            // Write backup file
            try (FileWriter writer = new FileWriter(jsonBackupPath)) {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(writer, backup);
            }

            System.out.println("[MessageStorageUtility] Created backup: " + jsonBackupPath);
            return jsonBackupPath;

        } catch (Exception e) {
            System.err.println("[MessageStorageUtility] Failed to create backup: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Create a backup of a specific conversation
     */
    public String createConversationBackup(String username) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String backupPath = BACKUP_DIR + File.separator + "conversation_" + username + "_" + timestamp + ".json";

            Map<String, Object> backup = new HashMap<>();
            backup.put("timestamp", Instant.now().toEpochMilli());
            backup.put("username", username);
            backup.put("messages", MessageStorageService.getInstance().loadMessages(username, 0, 10000));

            try (FileWriter writer = new FileWriter(backupPath)) {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(writer, backup);
            }

            System.out.println("[MessageStorageUtility] Created conversation backup: " + backupPath);
            return backupPath;

        } catch (Exception e) {
            System.err.println("[MessageStorageUtility] Failed to create conversation backup: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Get storage statistics
     */
    public StorageStats getStorageStats() {
        StorageStats stats = new StorageStats();

        try {
            File messagesDir = new File(MESSAGES_DIR);
            if (messagesDir.exists() && messagesDir.isDirectory()) {
                File[] userDirs = messagesDir.listFiles(File::isDirectory);
                if (userDirs != null) {
                    stats.conversationCount = userDirs.length;

                    long totalSize = 0;
                    int totalMessages = 0;

                    for (File userDir : userDirs) {
                        totalSize += calculateDirectorySize(userDir);
                        String username = userDir.getName();
                        totalMessages += MessageStorageService.getInstance().getMessageCount(username);
                    }

                    stats.totalSizeBytes = totalSize;
                    stats.totalMessages = totalMessages;
                }
            }

            // Check backups
            File backupDir = new File(BACKUP_DIR);
            if (backupDir.exists() && backupDir.isDirectory()) {
                File[] backupFiles = backupDir.listFiles((dir, name) -> name.endsWith(".json"));
                if (backupFiles != null) {
                    stats.backupCount = backupFiles.length;
                }
            }

        } catch (Exception e) {
            System.err.println("[MessageStorageUtility] Error getting storage stats: " + e.getMessage());
        }

        return stats;
    }

    /**
     * Print debug information about message storage
     */
    public void printStorageDebugInfo() {
        System.out.println("=== Message Storage Debug Info ===");

        StorageStats stats = getStorageStats();
        System.out.println("Conversations: " + stats.conversationCount);
        System.out.println("Total messages: " + stats.totalMessages);
        System.out.println("Storage size: " + formatBytes(stats.totalSizeBytes));
        System.out.println("Backups: " + stats.backupCount);

        System.out.println("\nStorage paths:");
        System.out.println("Messages: " + MESSAGES_DIR);
        System.out.println("Backups: " + BACKUP_DIR);

        // List conversation directories
        System.out.println("\nConversation directories:");
        File messagesDir = new File(MESSAGES_DIR);
        if (messagesDir.exists() && messagesDir.isDirectory()) {
            File[] userDirs = messagesDir.listFiles(File::isDirectory);
            if (userDirs != null) {
                for (File userDir : userDirs) {
                    String username = userDir.getName();
                    int messageCount = MessageStorageService.getInstance().getMessageCount(username);
                    System.out.println("  " + username + " - " + formatBytes(calculateDirectorySize(userDir)) +
                            " (" + messageCount + " messages)");
                }
            }
        }

        System.out.println("===================================");
    }

    // Helper methods
    private boolean deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            return directory.delete();
        }
        return false;
    }

    private long calculateDirectorySize(File directory) {
        long size = 0;
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        size += calculateDirectorySize(file);
                    } else {
                        size += file.length();
                    }
                }
            }
        }
        return size;
    }

    private String sanitizeUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }
        return username.trim().toLowerCase().replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Storage statistics container
     */
    public static class StorageStats {
        public int conversationCount = 0;
        public int totalMessages = 0;
        public long totalSizeBytes = 0;
        public int backupCount = 0;
    }
}