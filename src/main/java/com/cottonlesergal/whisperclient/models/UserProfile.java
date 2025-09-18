package com.cottonlesergal.whisperclient.models;

public class UserProfile {
    private final String sub;          // provider subject (e.g., google:123 or discord:456)
    private final String username;     // handle (lowercase, unique)
    private final String displayName;  // friendly name; defaults to username if null/blank
    private final String avatarUrl;    // may be empty
    private final String provider;     // "google" or "discord"
    private final String pubKey;       // base64 X25519 public key (local identity)

    /** Back-compat: old 5-arg ctor — displayName defaults to username */
    public UserProfile(String sub, String username, String avatarUrl, String provider, String pubKey) {
        this(sub, username, username, avatarUrl, provider, pubKey);
    }

    /** New ctor: specify displayName separately (optional) */
    public UserProfile(String sub, String username, String displayName,
                       String avatarUrl, String provider, String pubKey) {
        this.sub = sub;
        this.username = username;
        this.displayName = (displayName == null || displayName.isBlank()) ? username : displayName;
        this.avatarUrl = avatarUrl;
        this.provider = provider;
        this.pubKey = pubKey;
    }

    public String getSub()         { return sub; }
    public String getUsername()    { return username; }
    public String getDisplayName() { return displayName; }   // <-- now available
    public String getAvatarUrl()   { return avatarUrl; }
    public String getProvider()    { return provider; }
    public String getPubKey()      { return pubKey; }
}
