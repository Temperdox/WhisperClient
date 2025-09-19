package com.cottonlesergal.whisperclient.events;

import com.fasterxml.jackson.databind.JsonNode;

public class Event {
    public final String type;
    public final String from;
    public final String to;
    public final long at;
    public final JsonNode data;

    public Event(String type, String from, String to, long at, JsonNode data) {
        this.type = type;
        this.from = from;
        this.to = to;
        this.at = at;
        this.data = data;
    }
}