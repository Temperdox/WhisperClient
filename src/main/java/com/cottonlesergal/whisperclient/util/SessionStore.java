package com.cottonlesergal.whisperclient.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Holds peer pubkeys / session state for decryption. */
public final class SessionStore {
    private static final SessionStore I = new SessionStore();
    public static SessionStore get(){ return I; }

    private final Map<String,String> peerKeys = new ConcurrentHashMap<>();
    public void bindPeerKey(String username, String pubKey){ peerKeys.put(username, pubKey); }
    public String peerKey(String username){ return peerKeys.get(username); }
}
