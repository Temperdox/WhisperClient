package com.cottonlesergal.whisperclient.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class UserSummary {
    private final String username;   // unique handle (lowercase)
    private final String display;    // display name
    private final String avatar;     // URL (can be empty)
    private final String provider;   // Add this field since your worker returns it

    // Default constructor for Jackson
    public UserSummary() {
        this.username = "";
        this.display = "";
        this.avatar = "";
        this.provider = "";
    }

    // Constructor with Jackson annotations
    @JsonCreator
    public UserSummary(
            @JsonProperty("username") String username,
            @JsonProperty("display") String display,
            @JsonProperty("avatar") String avatar,
            @JsonProperty("provider") String provider) {
        this.username = username;
        this.display = display;
        this.avatar = avatar;
        this.provider = provider != null ? provider : "";
    }

    // Legacy constructor (3 params) for backwards compatibility
    public UserSummary(String username, String display, String avatar) {
        this(username, display, avatar, "");
    }

    public String getUsername() { return username; }
    public String getDisplay()  { return display; }
    public String getAvatar()   { return avatar; }
    public String getProvider() { return provider; }
}