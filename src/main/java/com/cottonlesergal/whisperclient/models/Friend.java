package com.cottonlesergal.whisperclient.models;

public class Friend {
    private final String username, pubKey, inboxUrl, displayName, avatarUrl;

    // Updated constructor with avatar
    public Friend(String username, String pubKey, String inboxUrl, String displayName, String avatarUrl){
        this.username = username;
        this.pubKey = pubKey;
        this.inboxUrl = inboxUrl;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    // Backward compatibility constructor (without avatar)
    public Friend(String username, String pubKey, String inboxUrl, String displayName){
        this(username, pubKey, inboxUrl, displayName, "");
    }

    public String getUsername(){ return username; }
    public String getPubKey(){ return pubKey; }
    public String getInboxUrl(){ return inboxUrl; }
    public String getDisplayName(){ return displayName; }
    public String getAvatarUrl(){ return avatarUrl; }

    // Alternative method name for consistency
    public String getAvatar(){ return avatarUrl; }

    @Override
    public String toString(){
        return displayName + " (@" + username + ")";
    }
}