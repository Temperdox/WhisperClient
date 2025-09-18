package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.models.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class AuthService {
    private static final ObjectMapper M = new ObjectMapper();

    /** Launches OAuth in a WebView, captures the JWT on redirect, sets Config.APP_TOKEN, and returns a UserProfile. */
    public UserProfile signIn(Stage owner, String provider) throws Exception {
        final UserProfile[] out = { null };

        WebView wv = new WebView();
        WebEngine eng = wv.getEngine();
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setTitle("Sign in with " + provider);
        dlg.setScene(new Scene(wv, 640, 760));

        eng.locationProperty().addListener((obs, old, url) -> {
            try {
                if (url == null) return;

                // We expect the auth worker to redirect to Config.OAUTH_REDIRECT with "#token=..."
                if (url.startsWith(Config.OAUTH_REDIRECT)) {
                    String token = extractTokenFromUrl(url);
                    if (token == null || token.isBlank()) throw new IllegalStateException("No token returned");

                    // Save the JWT for subsequent API calls (Directory, WS, etc.)
                    Config.APP_TOKEN = token;
                    System.out.println("[Auth] Stored JWT. len=" + token.length());
                    System.out.println("[Auth] payload=" + peekJwtPayload(token));

                    // Parse JWT payload to build the UserProfile
                    String[] parts = token.split("\\.");
                    if (parts.length < 2) throw new IllegalStateException("Malformed JWT");
                    String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                    JsonNode c = M.readTree(payloadJson);

                    String sub       = c.path("sub").asText("");
                    String username  = c.path("username").asText("");  // what you use as handle
                    String avatar    = c.path("avatar").asText("");
                    String provClaim = c.path("provider").asText(provider);

                    // Ensure local identity exists and return profile
                    String pubKey;
                    try {
                        pubKey = CryptoService.get().ensureLocalIdentity();
                    } catch (Exception ex) {
                        throw new RuntimeException("Failed to create local identity", ex);
                    }

                    out[0] = new UserProfile(sub, username, avatar, provClaim, pubKey);
                    dlg.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
                dlg.close();
            }
        });

        // Start OAuth flow via your auth worker
        String start = Config.AUTH_WORKER + (provider.equals("google") ? "/oauth/google" : "/oauth/discord");
        System.out.println("[Auth] Opening: " + start);
        System.out.println("[Auth] Expecting redirect to: " + Config.OAUTH_REDIRECT + "#token=…");
        eng.load(start);
        dlg.showAndWait();

        if (out[0] == null) throw new IllegalStateException("OAuth did not complete.");
        return out[0];
    }

    /** Extracts "#token=..." (or "?token=...") from a URL and URL-decodes it. */
    private static String extractTokenFromUrl(String url) {
        // Prefer fragment (#token=...), but also accept query (?token=...)
        int idx = url.indexOf("#token=");
        if (idx < 0) idx = url.indexOf("?token=");
        if (idx < 0) return null;

        String raw = url.substring(idx + 7); // after "token="
        // If there are other params after token, stop at the first delimiter
        int cut = raw.indexOf('&');
        if (cut >= 0) raw = raw.substring(0, cut);

        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    /** Debug helper: peek at JWT payload without leaking full token. */
    private static String peekJwtPayload(String jwt) {
        try {
            String[] parts = jwt == null ? new String[0] : jwt.split("\\.");
            if (parts.length < 2) return "<bad jwt>";
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            return new String(json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<decode error: " + e.getMessage() + ">";
        }
    }
}
