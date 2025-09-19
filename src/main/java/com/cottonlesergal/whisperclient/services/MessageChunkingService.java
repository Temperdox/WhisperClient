package com.cottonlesergal.whisperclient.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageChunkingService {
    private static final MessageChunkingService INSTANCE = new MessageChunkingService();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Maximum size for a single WebSocket message (64KB)
    private static final int MAX_MESSAGE_SIZE = 64 * 1024;

    // Buffer for reassembling chunked messages
    private final Map<String, ChunkedMessage> messageBuffer = new ConcurrentHashMap<>();

    public static MessageChunkingService getInstance() {
        return INSTANCE;
    }

    private MessageChunkingService() {}

    /**
     * Split large message into chunks if needed
     */
    public String[] splitMessage(String messageText) {
        if (messageText.length() <= MAX_MESSAGE_SIZE) {
            return new String[]{messageText};
        }

        // Create chunked message
        String messageId = java.util.UUID.randomUUID().toString();
        int totalChunks = (int) Math.ceil((double) messageText.length() / MAX_MESSAGE_SIZE);

        String[] chunks = new String[totalChunks];

        for (int i = 0; i < totalChunks; i++) {
            int start = i * MAX_MESSAGE_SIZE;
            int end = Math.min(start + MAX_MESSAGE_SIZE, messageText.length());
            String chunkData = messageText.substring(start, end);

            try {
                ChunkInfo chunkInfo = new ChunkInfo(messageId, i, totalChunks, chunkData);
                chunks[i] = "[CHUNK:" + MAPPER.writeValueAsString(chunkInfo) + "]";
            } catch (Exception e) {
                System.err.println("Failed to create chunk: " + e.getMessage());
                return new String[]{messageText}; // Fallback to original
            }
        }

        return chunks;
    }

    /**
     * Process received message - either return complete message or null if waiting for more chunks
     */
    public String processReceivedMessage(String receivedText) {
        // Check if this is a chunk
        if (!receivedText.startsWith("[CHUNK:") || !receivedText.endsWith("]")) {
            return receivedText; // Regular message
        }

        try {
            String chunkJson = receivedText.substring(7, receivedText.length() - 1);
            ChunkInfo chunkInfo = MAPPER.readValue(chunkJson, ChunkInfo.class);

            // Get or create chunked message
            ChunkedMessage chunkedMessage = messageBuffer.computeIfAbsent(
                    chunkInfo.messageId,
                    id -> new ChunkedMessage(chunkInfo.totalChunks)
            );

            // Add chunk
            chunkedMessage.addChunk(chunkInfo.chunkIndex, chunkInfo.data);

            // Check if message is complete
            if (chunkedMessage.isComplete()) {
                messageBuffer.remove(chunkInfo.messageId);
                return chunkedMessage.assembleMessage();
            }

            // Still waiting for more chunks
            return null;

        } catch (Exception e) {
            System.err.println("Failed to process chunk: " + e.getMessage());
            return receivedText; // Fallback
        }
    }

    /**
     * Clean up old incomplete messages (call periodically)
     */
    public void cleanupOldMessages() {
        long now = System.currentTimeMillis();
        messageBuffer.entrySet().removeIf(entry ->
                now - entry.getValue().createdAt > 30000 // 30 seconds timeout
        );
    }

    /**
     * Chunk information structure
     */
    private static class ChunkInfo {
        public String messageId;
        public int chunkIndex;
        public int totalChunks;
        public String data;

        public ChunkInfo() {} // For Jackson

        public ChunkInfo(String messageId, int chunkIndex, int totalChunks, String data) {
            this.messageId = messageId;
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.data = data;
        }
    }

    /**
     * Chunked message reassembly
     */
    private static class ChunkedMessage {
        private final String[] chunks;
        private final boolean[] received;
        private final long createdAt;
        private int receivedCount;

        public ChunkedMessage(int totalChunks) {
            this.chunks = new String[totalChunks];
            this.received = new boolean[totalChunks];
            this.createdAt = System.currentTimeMillis();
            this.receivedCount = 0;
        }

        public void addChunk(int index, String data) {
            if (index >= 0 && index < chunks.length && !received[index]) {
                chunks[index] = data;
                received[index] = true;
                receivedCount++;
            }
        }

        public boolean isComplete() {
            return receivedCount == chunks.length;
        }

        public String assembleMessage() {
            StringBuilder sb = new StringBuilder();
            for (String chunk : chunks) {
                if (chunk != null) {
                    sb.append(chunk);
                }
            }
            return sb.toString();
        }
    }
}