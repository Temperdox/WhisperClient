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

    // Debug and validation methods
    public static boolean hasValidToken() {
        return APP_TOKEN != null && !APP_TOKEN.isEmpty() && APP_TOKEN.length() > 10;
    }

    public static String getTokenInfo() {
        if (APP_TOKEN == null) return "null";
        if (APP_TOKEN.isEmpty()) return "empty";
        return "length=" + APP_TOKEN.length() + ", starts=" +
                APP_TOKEN.substring(0, Math.min(10, APP_TOKEN.length()));
    }

    public static void validateConfiguration() throws IllegalStateException {
        if (!hasValidToken()) {
            throw new IllegalStateException("APP_TOKEN is not set or invalid: " + getTokenInfo());
        }

        if (AUTH_WORKER == null || AUTH_WORKER.isEmpty()) {
            throw new IllegalStateException("AUTH_WORKER is not configured");
        }

        if (DIR_WORKER == null || DIR_WORKER.isEmpty()) {
            throw new IllegalStateException("DIR_WORKER is not configured");
        }

        System.out.println("[Config] Configuration validated successfully");
    }

    public static String getDebugInfo() {
        return String.format("Config[token=%s, authWorker=%s, dirWorker=%s]",
                getTokenInfo(), AUTH_WORKER, DIR_WORKER);
    }
}