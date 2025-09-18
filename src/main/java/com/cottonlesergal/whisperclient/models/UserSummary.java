package com.cottonlesergal.whisperclient.models;

public class UserSummary {
    private final String username;   // unique handle (lowercase)
    private final String display;    // display name
    private final String avatar;     // URL (can be empty)

    public UserSummary(String username, String display, String avatar) {
        this.username = username; this.display = display; this.avatar = avatar;
    }
    public String getUsername() { return username; }
    public String getDisplay()  { return display; }
    public String getAvatar()   { return avatar; }
}
