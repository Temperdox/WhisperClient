package com.cottonlesergal.whisperclient.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public class MessageChunkingService {
    private static final MessageChunkingService INSTANCE = new MessageChunkingService();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Reduced chunk size to account for JSON wrapper overhead
    private static final int MAX_CHUNK_DATA_SIZE = 60 * 1024; // 60KB for data, leaving room for JSON wrapper

    // Buffer for reassembling chunked messages
    private final Map<String, ChunkedMessage> messageBuffer = new ConcurrentHashMap<>();

    public static MessageChunkingService getInstance() {
        return INSTANCE;
    }

    private MessageChunkingService() {}

    /**
     * Split large message into chunks if needed
     * Now properly handles JSON escaping and size limits
     */
    public String[] splitMessage(String messageText) {
        if (messageText.length() <= MAX_CHUNK_DATA_SIZE) {
            return new String[]{messageText};
        }

        // For large messages, encode the entire content as base64 to avoid JSON escaping issues
        String encodedContent = Base64.getEncoder().encodeToString(messageText.getBytes());

        String messageId = java.util.UUID.randomUUID().toString();
        int totalChunks = (int) Math.ceil((double) encodedContent.length() / MAX_CHUNK_DATA_SIZE);

        String[] chunks = new String[totalChunks];

        System.out.println("[MessageChunkingService] Splitting message into " + totalChunks + " chunks");

        for (int i = 0; i < totalChunks; i++) {
            int start = i * MAX_CHUNK_DATA_SIZE;
            int end = Math.min(start + MAX_CHUNK_DATA_SIZE, encodedContent.length());
            String chunkData = encodedContent.substring(start, end);

            try {
                ChunkInfo chunkInfo = new ChunkInfo(messageId, i, totalChunks, chunkData, true); // true = base64 encoded
                String chunkJson = MAPPER.writeValueAsString(chunkInfo);
                chunks[i] = "[CHUNK:" + chunkJson + "]";

                System.out.println("[MessageChunkingService] Created chunk " + (i + 1) + "/" + totalChunks +
                        " (size: " + chunks[i].length() + " bytes)");

            } catch (Exception e) {
                System.err.println("Failed to create chunk " + i + ": " + e.getMessage());
                return new String[]{messageText}; // Fallback to original
            }
        }

        return chunks;
    }

    /**
     * Process received message - either return complete message or null if waiting for more chunks
     * Now properly handles base64 decoding and error recovery
     */
    public String processReceivedMessage(String receivedText) {
        // Check if this is a chunk
        if (!receivedText.startsWith("[CHUNK:") || !receivedText.endsWith("]")) {
            return receivedText; // Regular message
        }

        try {
            String chunkJson = receivedText.substring(7, receivedText.length() - 1);
            ChunkInfo chunkInfo = MAPPER.readValue(chunkJson, ChunkInfo.class);

            System.out.println("[MessageChunkingService] Received chunk " + (chunkInfo.chunkIndex + 1) +
                    "/" + chunkInfo.totalChunks + " for message " + chunkInfo.messageId);

            // Get or create chunked message
            ChunkedMessage chunkedMessage = messageBuffer.computeIfAbsent(
                    chunkInfo.messageId,
                    id -> new ChunkedMessage(chunkInfo.totalChunks, chunkInfo.isBase64Encoded)
            );

            // Add chunk
            chunkedMessage.addChunk(chunkInfo.chunkIndex, chunkInfo.data);

            // Check if message is complete
            if (chunkedMessage.isComplete()) {
                messageBuffer.remove(chunkInfo.messageId);
                String assembledMessage = chunkedMessage.assembleMessage();
                System.out.println("[MessageChunkingService] Successfully assembled message " + chunkInfo.messageId);
                return assembledMessage;
            }

            System.out.println("[MessageChunkingService] Waiting for " +
                    (chunkInfo.totalChunks - chunkedMessage.receivedCount) + " more chunks");

            // Still waiting for more chunks
            return null;

        } catch (Exception e) {
            System.err.println("[MessageChunkingService] Failed to process chunk: " + e.getMessage());
            e.printStackTrace();
            return null; // Don't fallback to original on chunk processing errors
        }
    }

    /**
     * Clean up old incomplete messages (call periodically)
     */
    public void cleanupOldMessages() {
        long now = System.currentTimeMillis();
        int removedCount = 0;

        var iterator = messageBuffer.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.getValue().createdAt > 30000) { // 30 seconds timeout
                iterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0) {
            System.out.println("[MessageChunkingService] Cleaned up " + removedCount + " incomplete messages");
        }
    }

    /**
     * Get current buffer status for debugging
     */
    public void printBufferStatus() {
        System.out.println("=== MessageChunkingService Buffer Status ===");
        System.out.println("Incomplete messages: " + messageBuffer.size());

        for (Map.Entry<String, ChunkedMessage> entry : messageBuffer.entrySet()) {
            ChunkedMessage msg = entry.getValue();
            System.out.println("  Message " + entry.getKey() + ": " +
                    msg.receivedCount + "/" + msg.chunks.length + " chunks received");
        }
        System.out.println("============================================");
    }

    /**
     * Chunk information structure - now includes encoding flag
     */
    private static class ChunkInfo {
        public String messageId;
        public int chunkIndex;
        public int totalChunks;
        public String data;
        public boolean isBase64Encoded;

        public ChunkInfo() {} // For Jackson

        public ChunkInfo(String messageId, int chunkIndex, int totalChunks, String data, boolean isBase64Encoded) {
            this.messageId = messageId;
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.data = data;
            this.isBase64Encoded = isBase64Encoded;
        }
    }

    /**
     * Chunked message reassembly - now handles base64 decoding
     */
    private static class ChunkedMessage {
        private final String[] chunks;
        private final boolean[] received;
        private final boolean isBase64Encoded;
        private final long createdAt;
        private int receivedCount;

        public ChunkedMessage(int totalChunks, boolean isBase64Encoded) {
            this.chunks = new String[totalChunks];
            this.received = new boolean[totalChunks];
            this.isBase64Encoded = isBase64Encoded;
            this.createdAt = System.currentTimeMillis();
            this.receivedCount = 0;
        }

        public synchronized void addChunk(int index, String data) {
            if (index >= 0 && index < chunks.length && !received[index]) {
                chunks[index] = data;
                received[index] = true;
                receivedCount++;
            }
        }

        public synchronized boolean isComplete() {
            return receivedCount == chunks.length;
        }

        public synchronized String assembleMessage() {
            StringBuilder sb = new StringBuilder();
            for (String chunk : chunks) {
                if (chunk != null) {
                    sb.append(chunk);
                }
            }

            String assembled = sb.toString();

            // Decode if it was base64 encoded
            if (isBase64Encoded) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(assembled);
                    return new String(decoded);
                } catch (Exception e) {
                    System.err.println("[ChunkedMessage] Failed to decode base64: " + e.getMessage());
                    return assembled; // Return as-is if decoding fails
                }
            }

            return assembled;
        }
    }
}