package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.Message;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class MessageStorageService {
    private static final MessageStorageService INSTANCE = new MessageStorageService();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_USERNAME = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private final Path baseDir;
    private final Map<String, SecretKey> userKeys = new ConcurrentHashMap<>();

    public static MessageStorageService getInstance() {
        return INSTANCE;
    }

    private MessageStorageService() {
        // Store in user's app data directory
        String userHome = System.getProperty("user.home");
        this.baseDir = Paths.get(userHome, ".whisperclient", "messages");
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create message storage directory", e);
        }
    }

    /**
     * Store a message for a specific user conversation
     */
    public void storeMessage(String username, ChatMessage message) {
        try {
            String safeUsername = sanitizeUsername(username);
            Path userDir = getUserDirectory(safeUsername);
            Path messagesDir = userDir.resolve("messages");
            Files.createDirectories(messagesDir);

            // Encrypt and store the message
            byte[] encrypted = encryptMessage(safeUsername, message);
            String filename = message.getTimestamp() + "_" + message.getId() + ".msg";
            Files.write(messagesDir.resolve(filename), encrypted);

            System.out.println("[MessageStorage] Stored message from " + message.getFrom() + " to " + safeUsername);

        } catch (Exception e) {
            System.err.println("Failed to store message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load messages for a user with pagination
     * @param username The user to load messages for
     * @param page Page number (0-based, 0 = most recent)
     * @param pageSize Number of messages per page
     * @return List of messages, newest first
     */
    public List<ChatMessage> loadMessages(String username, int page, int pageSize) {
        try {
            String safeUsername = sanitizeUsername(username);
            Path messagesDir = getUserDirectory(safeUsername).resolve("messages");

            if (!Files.exists(messagesDir)) {
                return new ArrayList<>();
            }

            // Get all message files sorted by timestamp (newest first)
            List<Path> messageFiles = Files.list(messagesDir)
                    .filter(path -> path.toString().endsWith(".msg"))
                    .sorted((a, b) -> {
                        // Extract timestamp from filename and sort descending
                        long timestampA = extractTimestamp(a.getFileName().toString());
                        long timestampB = extractTimestamp(b.getFileName().toString());
                        return Long.compare(timestampB, timestampA);
                    })
                    .toList();

            // Calculate pagination
            int start = page * pageSize;
            int end = Math.min(start + pageSize, messageFiles.size());

            if (start >= messageFiles.size()) {
                return new ArrayList<>();
            }

            // Load and decrypt messages for this page
            List<ChatMessage> messages = new ArrayList<>();
            for (int i = start; i < end; i++) {
                try {
                    byte[] encrypted = Files.readAllBytes(messageFiles.get(i));
                    ChatMessage message = decryptMessage(safeUsername, encrypted);
                    if (message != null) {
                        messages.add(message);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to decrypt message: " + e.getMessage());
                }
            }

            return messages;

        } catch (Exception e) {
            System.err.println("Failed to load messages: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * Get total message count for a user
     */
    public int getMessageCount(String username) {
        try {
            String safeUsername = sanitizeUsername(username);
            Path messagesDir = getUserDirectory(safeUsername).resolve("messages");

            if (!Files.exists(messagesDir)) {
                return 0;
            }

            return (int) Files.list(messagesDir)
                    .filter(path -> path.toString().endsWith(".msg"))
                    .count();

        } catch (Exception e) {
            return 0;
        }
    }

    private Path getUserDirectory(String safeUsername) throws IOException {
        Path userDir = baseDir.resolve(safeUsername);
        Files.createDirectories(userDir);
        Files.createDirectories(userDir.resolve("messages"));
        Files.createDirectories(userDir.resolve("media"));
        return userDir;
    }

    private String sanitizeUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }

        String clean = username.trim().toLowerCase();
        if (!SAFE_USERNAME.matcher(clean).matches()) {
            // Replace unsafe characters with underscores
            clean = clean.replaceAll("[^a-zA-Z0-9_-]", "_");
        }

        return clean;
    }

    private SecretKey getUserKey(String safeUsername) throws Exception {
        return userKeys.computeIfAbsent(safeUsername, username -> {
            try {
                // Simple local storage encryption - just for protecting files on disk
                // Messages over the network are already encrypted via WebRTC/HTTPS
                String keyMaterial = "WhisperClient:LocalStorage:" + Session.me.getUsername() + ":" + safeUsername;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] keyBytes = digest.digest(keyMaterial.getBytes());
                return new SecretKeySpec(keyBytes, "AES");
            } catch (Exception e) {
                throw new RuntimeException("Failed to generate user key", e);
            }
        });
    }

    private byte[] encryptMessage(String safeUsername, ChatMessage message) throws Exception {
        SecretKey key = getUserKey(safeUsername);
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));

        String json = MAPPER.writeValueAsString(message);
        byte[] encrypted = cipher.doFinal(json.getBytes());

        // Combine nonce + encrypted data
        byte[] result = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, result, 0, nonce.length);
        System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);

        return result;
    }

    private ChatMessage decryptMessage(String safeUsername, byte[] data) throws Exception {
        if (data.length < 12) return null;

        SecretKey key = getUserKey(safeUsername);
        byte[] nonce = new byte[12];
        byte[] encrypted = new byte[data.length - 12];

        System.arraycopy(data, 0, nonce, 0, 12);
        System.arraycopy(data, 12, encrypted, 0, encrypted.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));

        byte[] decrypted = cipher.doFinal(encrypted);
        String json = new String(decrypted);

        return MAPPER.readValue(json, ChatMessage.class);
    }

    private long extractTimestamp(String filename) {
        try {
            return Long.parseLong(filename.split("_")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Clear all in-memory cached messages
     */
    public void clearCache() {
        // If you have any in-memory caches, clear them here
        // This depends on your current MessageStorage implementation
        System.out.println("[MessageStorage] Cleared message cache");
    }

    /**
     * Clear a specific conversation from cache
     */
    public void clearConversationFromCache(String username) {
        // If you have conversation-specific caches, clear them here
        System.out.println("[MessageStorage] Cleared cache for conversation with: " + username);
    }

    /**
     * Get the filename used for storing a conversation
     */
    public String getConversationFileName(String username) {
        // Return the filename pattern you use for storing conversations
        // Adjust this based on your actual filename pattern
        return "messages_" + username.toLowerCase() + ".json";
    }

    /**
     * Extract username from a message file name
     */
    public String getUsernameFromFileName(String fileName) {
        // Extract username from filename
        // Adjust this based on your actual filename pattern
        if (fileName.startsWith("messages_") && fileName.endsWith(".json")) {
            return fileName.substring(9, fileName.length() - 5); // Remove "messages_" and ".json"
        }
        return fileName.replace(".json", "");
    }

    /**
     * Save messages for a specific user (used by deletion utilities)
     */
    public boolean saveMessages(String username, List<Message> messages) {
        try {
            // Use your existing save logic here
            // This should save the provided messages list for the specified user
            String fileName = getConversationFileName(username);
            String filePath = getMessagesDirectory() + File.separator + fileName;

            // Convert messages to JSON and save
            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(filePath), messages);

            System.out.println("[MessageStorage] Saved " + messages.size() + " messages for " + username);
            return true;

        } catch (Exception e) {
            System.err.println("[MessageStorage] Failed to save messages for " + username + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Clear all messages for a specific user conversation
     */
    public void clearMessages(String username) {
        try {
            String safeUsername = sanitizeUsername(username);
            Path messagesDir = getUserDirectory(safeUsername).resolve("messages");

            if (!Files.exists(messagesDir)) {
                System.out.println("[MessageStorage] No messages directory found for " + safeUsername);
                return;
            }

            // Delete all message files for this user
            List<Path> messageFiles = Files.list(messagesDir)
                    .filter(path -> path.toString().endsWith(".msg"))
                    .toList();

            int deletedCount = 0;
            for (Path messageFile : messageFiles) {
                try {
                    Files.delete(messageFile);
                    deletedCount++;
                } catch (Exception e) {
                    System.err.println("Failed to delete message file: " + messageFile + " - " + e.getMessage());
                }
            }

            System.out.println("[MessageStorage] Cleared " + deletedCount + " messages for " + safeUsername);

            // Clear from cache if you have any
            clearConversationFromCache(username);

        } catch (Exception e) {
            System.err.println("Failed to clear messages for " + username + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Delete a specific message by ID
     */
    public boolean deleteMessage(String username, String messageId) {
        try {
            String safeUsername = sanitizeUsername(username);
            Path messagesDir = getUserDirectory(safeUsername).resolve("messages");

            if (!Files.exists(messagesDir)) {
                return false;
            }

            // Find and delete the specific message file
            List<Path> messageFiles = Files.list(messagesDir)
                    .filter(path -> path.toString().endsWith(".msg"))
                    .filter(path -> path.getFileName().toString().contains(messageId))
                    .toList();

            for (Path messageFile : messageFiles) {
                Files.delete(messageFile);
                System.out.println("[MessageStorage] Deleted message " + messageId + " for " + safeUsername);
                return true;
            }

            return false;

        } catch (Exception e) {
            System.err.println("Failed to delete message " + messageId + " for " + username + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the messages directory path
     */
    private String getMessagesDirectory() {
        // Return your messages directory path
        String userHome = System.getProperty("user.home");
        return userHome + File.separator + ".whisper_client" + File.separator + "messages";
    }

    /**
     * Message data class for storage - proper Java Bean for Jackson
     */
    public static class ChatMessage {
        private String id;
        private String from;
        private String to;
        private String content;
        private String type; // "text", "image", etc.
        private long timestamp;
        private boolean isFromMe;

        // Default constructor for Jackson
        public ChatMessage() {}

        public ChatMessage(String id, String from, String to, String content, String type, boolean isFromMe) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.content = content;
            this.type = type;
            this.timestamp = Instant.now().toEpochMilli();
            this.isFromMe = isFromMe;
        }

        // Getters and setters for Jackson
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }

        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        public boolean isFromMe() { return isFromMe; }
        public void setFromMe(boolean fromMe) { this.isFromMe = fromMe; }

        // Factory methods
        public static ChatMessage fromIncoming(String from, String content) {
            return new ChatMessage(
                    UUID.randomUUID().toString(),
                    from,
                    Session.me.getUsername(),
                    content,
                    "text",
                    false
            );
        }

        public static ChatMessage fromOutgoing(String to, String content) {
            return new ChatMessage(
                    UUID.randomUUID().toString(),
                    Session.me.getUsername(),
                    to,
                    content,
                    "text",
                    true
            );
        }
    }
}