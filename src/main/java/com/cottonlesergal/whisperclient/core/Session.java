package com.cottonlesergal.whisperclient.core;

import com.cottonlesergal.whisperclient.models.UserProfile;

public final class Session {
    private Session() {}

    public static String token;          // JWT from the auth worker
    public static UserProfile me;        // filled after sign-in

    // Helper methods for debugging and validation
    public static boolean isValid() {
        return me != null && token != null && !token.isEmpty();
    }

    public static void clear() {
        System.out.println("[Session] Clearing session data");
        token = null;
        me = null;
    }

    public static String getDebugInfo() {
        return String.format("Session[user=%s, hasToken=%s, tokenLength=%d]",
                me != null ? me.getUsername() : "null",
                token != null && !token.isEmpty(),
                token != null ? token.length() : 0
        );
    }

    public static void validate() throws IllegalStateException {
        if (me == null) {
            throw new IllegalStateException("Session user is null");
        }
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("Session token is null or empty");
        }
        System.out.println("[Session] Validation passed: " + getDebugInfo());
    }
}