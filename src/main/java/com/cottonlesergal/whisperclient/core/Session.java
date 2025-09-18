package com.cottonlesergal.whisperclient.core;

import com.cottonlesergal.whisperclient.models.UserProfile;

public final class Session {
    private Session() {}
    public static String token;          // JWT from the auth worker
    public static UserProfile me;        // filled after sign-in
}
