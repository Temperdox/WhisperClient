package com.cottonlesergal.whisperclient.util;

import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.Message;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;

/**
 * Utility class to convert between the old Message model and new ChatMessage storage format
 */
public final class MessageConverter {

    private MessageConverter() {} // Utility class

    /**
     * Convert a Message to ChatMessage for storage
     */
    public static ChatMessage toChatMessage(Message message, boolean isFromMe) {
        String content = message.isText() ? message.getText() : message.getBase64Image();
        String type = message.isText() ? "text" : "image";

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setId(message.getId());
        chatMessage.setFrom(message.getFrom());
        chatMessage.setTo(message.getTo());
        chatMessage.setContent(content);
        chatMessage.setType(type);
        chatMessage.setTimestamp(message.getTimestamp());
        chatMessage.setFromMe(isFromMe);

        return chatMessage;
    }

    /**
     * Convert a ChatMessage back to Message for processing
     */
    public static Message fromChatMessage(ChatMessage chatMessage) {
        if ("image".equals(chatMessage.getType())) {
            return Message.image(
                    chatMessage.getContent(),
                    chatMessage.getFrom(),
                    chatMessage.getTo()
            ).withTimestamp(chatMessage.getTimestamp());
        } else {
            return Message.text(
                    chatMessage.getContent(),
                    chatMessage.getFrom(),
                    chatMessage.getTo()
            ).withTimestamp(chatMessage.getTimestamp());
        }
    }

    /**
     * Create outgoing message for storage
     */
    public static ChatMessage createOutgoingMessage(String to, String content, String type) {
        ChatMessage message = new ChatMessage();
        message.setId(java.util.UUID.randomUUID().toString());
        message.setFrom(Session.me.getUsername());
        message.setTo(to);
        message.setContent(content);
        message.setType(type);
        message.setTimestamp(java.time.Instant.now().toEpochMilli());
        message.setFromMe(true);
        return message;
    }

    /**
     * Create incoming message for storage
     */
    public static ChatMessage createIncomingMessage(String from, String content, String type) {
        ChatMessage message = new ChatMessage();
        message.setId(java.util.UUID.randomUUID().toString());
        message.setFrom(from);
        message.setTo(Session.me.getUsername());
        message.setContent(content);
        message.setType(type);
        message.setTimestamp(java.time.Instant.now().toEpochMilli());
        message.setFromMe(false);
        return message;
    }
}