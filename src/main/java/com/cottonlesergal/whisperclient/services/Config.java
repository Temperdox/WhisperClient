package com.cottonlesergal.whisperclient.services;

public final class Config {
    private Config(){}

    // Workers
    public static final String AUTH_WORKER = envOr("WHISPER_AUTH_BASE", "https://whisperauth.cdbabmaina.workers.dev");
    public static final String DIR_WORKER  = envOr("WHISPER_DIR_BASE",  "https://whisperdir.cdbabmaina.workers.dev");
    public static final String OAUTH_REDIRECT = envOr("WHISPER_OAUTH_REDIRECT",
            "https://whisperauth.cdbabmaina.workers.dev/callback");

    // TURN / STUN
    public static final String STUN = "stun:stun.l.google.com:19302";
    public static final String TURN_HOST = "whispernet.duckdns.org";
    public static final String TURN_USER = envOr("WHISPER_TURN_USER", "webrtc");
    public static final String TURN_PASS = envOr("WHISPER_TURN_PASS", "REPLACE_ME");

    // App token (JWT) will be stashed here after OAuth
    public static String APP_TOKEN = "";

    private static String envOr(String k, String def){
        String v = System.getenv(k); if (v!=null && !v.isBlank()) return v;
        v = System.getProperty(k);    if (v!=null && !v.isBlank()) return v;
        return def;
    }
}
