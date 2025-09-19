package com.cottonlesergal.whisperclient.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public class Message {
    public enum Kind { TEXT, IMAGE }

    private final String id;
    private final Kind kind;
    private final String text;
    private final String base64Image;
    private final long timestamp;
    private final String from;
    private final String to;

    // Private constructor for Jackson
    @JsonCreator
    private Message(
            @JsonProperty("id") String id,
            @JsonProperty("kind") Kind kind,
            @JsonProperty("text") String text,
            @JsonProperty("base64Image") String base64Image,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("from") String from,
            @JsonProperty("to") String to) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.kind = kind;
        this.text = text;
        this.base64Image = base64Image;
        this.timestamp = timestamp > 0 ? timestamp : Instant.now().toEpochMilli();
        this.from = from;
        this.to = to;
    }

    // Builder-style factory methods
    public static Message text(String text) {
        return new Message(
                UUID.randomUUID().toString(),
                Kind.TEXT,
                text,
                null,
                Instant.now().toEpochMilli(),
                null,
                null
        );
    }

    public static Message text(String text, String from, String to) {
        return new Message(
                UUID.randomUUID().toString(),
                Kind.TEXT,
                text,
                null,
                Instant.now().toEpochMilli(),
                from,
                to
        );
    }

    public static Message image(String base64Image) {
        return new Message(
                UUID.randomUUID().toString(),
                Kind.IMAGE,
                null,
                base64Image,
                Instant.now().toEpochMilli(),
                null,
                null
        );
    }

    public static Message image(String base64Image, String from, String to) {
        return new Message(
                UUID.randomUUID().toString(),
                Kind.IMAGE,
                null,
                base64Image,
                Instant.now().toEpochMilli(),
                from,
                to
        );
    }

    // Getters for JSON serialization and general access
    @JsonProperty("id")
    public String getId() { return id; }

    @JsonProperty("kind")
    public Kind getKind() { return kind; }

    @JsonProperty("text")
    public String getText() { return text; }

    @JsonProperty("base64Image")
    public String getBase64Image() { return base64Image; }

    @JsonProperty("timestamp")
    public long getTimestamp() { return timestamp; }

    @JsonProperty("from")
    public String getFrom() { return from; }

    @JsonProperty("to")
    public String getTo() { return to; }

    // Utility methods
    public boolean isText() { return kind == Kind.TEXT; }
    public boolean isImage() { return kind == Kind.IMAGE; }

    public String getContent() {
        return isText() ? text : base64Image;
    }

    // Create new message with different sender/recipient
    public Message withSenderAndRecipient(String from, String to) {
        return new Message(this.id, this.kind, this.text, this.base64Image, this.timestamp, from, to);
    }

    // Create new message with new timestamp
    public Message withTimestamp(long timestamp) {
        return new Message(this.id, this.kind, this.text, this.base64Image, timestamp, this.from, this.to);
    }

    @Override
    public String toString() {
        return String.format("Message[id=%s, kind=%s, from=%s, to=%s, timestamp=%d]",
                id, kind, from, to, timestamp);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Message message = (Message) obj;
        return id != null ? id.equals(message.id) : message.id == null;
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}