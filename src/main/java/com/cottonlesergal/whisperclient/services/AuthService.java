package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.models.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AuthService {
    private static final ObjectMapper M = new ObjectMapper();

    /** Public API: sign in with provider ("google" or "discord"). */
    public UserProfile signIn(Stage owner, String provider) throws Exception {
        if ("google".equalsIgnoreCase(provider)) {
            // Google blocks embedded webviews -> use system browser + localhost capture
            return signInViaSystemBrowser(provider);
        } else {
            // Discord is fine in WebView
            return signInViaWebView(owner, provider);
        }
    }

    // ============== TOKEN REFRESH METHODS (NEW) ==============

    /**
     * Check if the current token is expired by parsing the JWT
     */
    public static boolean isTokenExpired() {
        if (Config.APP_TOKEN == null || Config.APP_TOKEN.isEmpty()) {
            return true;
        }

        try {
            String[] parts = Config.APP_TOKEN.split("\\.");
            if (parts.length < 2) return true;

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode payload = M.readTree(payloadJson);

            long exp = payload.path("exp").asLong(0);
            if (exp == 0) {
                // No expiration claim, assume it doesn't expire
                return false;
            }

            long currentTime = System.currentTimeMillis() / 1000; // Convert to seconds
            long timeUntilExpiry = exp - currentTime;

            System.out.println("[AuthService] Token expires in " + timeUntilExpiry + " seconds");

            // Consider expired if less than 5 minutes remaining
            return timeUntilExpiry < 300;

        } catch (Exception e) {
            System.err.println("[AuthService] Failed to parse token expiration: " + e.getMessage());
            return true; // Assume expired if we can't parse
        }
    }

    /**
     * Get time until token expiration in seconds
     */
    public static long getTimeUntilExpiration() {
        if (Config.APP_TOKEN == null || Config.APP_TOKEN.isEmpty()) {
            return 0;
        }

        try {
            String[] parts = Config.APP_TOKEN.split("\\.");
            if (parts.length < 2) return 0;

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode payload = M.readTree(payloadJson);

            long exp = payload.path("exp").asLong(0);
            if (exp == 0) return Long.MAX_VALUE; // No expiration

            long currentTime = System.currentTimeMillis() / 1000;
            return Math.max(0, exp - currentTime);

        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Attempt to refresh the token by re-authenticating silently
     */
    public CompletableFuture<Boolean> refreshTokenSilently(String provider) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("[AuthService] Attempting silent token refresh for provider: " + provider);

                if ("google".equalsIgnoreCase(provider)) {
                    return refreshGoogleTokenSilently();
                } else if ("discord".equalsIgnoreCase(provider)) {
                    return refreshDiscordTokenSilently();
                }

                return false;

            } catch (Exception e) {
                System.err.println("[AuthService] Silent token refresh failed: " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * Try to refresh Google token silently
     */
    private boolean refreshGoogleTokenSilently() {
        try {
            // Try to get a new token by hitting the silent refresh endpoint
            String refreshUrl = Config.AUTH_WORKER + "/oauth/google?silent=true";

            HttpRequest req = HttpRequest.newBuilder(URI.create(refreshUrl))
                    .header("User-Agent", "WhisperClient/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());

            System.out.println("[AuthService] Google silent refresh response: " + response.statusCode());

            if (response.statusCode() == 200) {
                // Check if response contains success and new token
                JsonNode result = M.readTree(response.body());
                boolean success = result.path("success").asBoolean(false);
                boolean requiresUserAction = result.path("requiresUserAction").asBoolean(true);
                String newToken = result.path("token").asText("");

                if (success && !requiresUserAction && !newToken.isEmpty()) {
                    Config.APP_TOKEN = newToken;
                    System.out.println("[AuthService] Successfully refreshed Google token silently");
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            System.err.println("[AuthService] Google silent refresh failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Try to refresh Discord token silently
     */
    private boolean refreshDiscordTokenSilently() {
        try {
            // Similar approach for Discord
            String refreshUrl = Config.AUTH_WORKER + "/oauth/discord?silent=true";

            HttpRequest req = HttpRequest.newBuilder(URI.create(refreshUrl))
                    .header("User-Agent", "WhisperClient/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());

            System.out.println("[AuthService] Discord silent refresh response: " + response.statusCode());

            if (response.statusCode() == 200) {
                JsonNode result = M.readTree(response.body());
                boolean success = result.path("success").asBoolean(false);
                boolean requiresUserAction = result.path("requiresUserAction").asBoolean(true);
                String newToken = result.path("token").asText("");

                if (success && !requiresUserAction && !newToken.isEmpty()) {
                    Config.APP_TOKEN = newToken;
                    System.out.println("[AuthService] Successfully refreshed Discord token silently");
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            System.err.println("[AuthService] Discord silent refresh failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validate current token with the server
     */
    public CompletableFuture<Boolean> validateToken() {
        return CompletableFuture.supplyAsync(() -> {
            if (Config.APP_TOKEN == null || Config.APP_TOKEN.isEmpty()) {
                return false;
            }

            try {
                String validateUrl = Config.AUTH_WORKER + "/validate-token";

                HttpRequest req = HttpRequest.newBuilder(URI.create(validateUrl))
                        .header("authorization", "Bearer " + Config.APP_TOKEN)
                        .GET()
                        .build();

                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(req, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode result = M.readTree(response.body());
                    boolean valid = result.path("valid").asBoolean(false);
                    boolean needsRefresh = result.path("needsRefresh").asBoolean(false);
                    long expiresIn = result.path("expiresIn").asLong(0);

                    System.out.println("[AuthService] Token validation - valid: " + valid +
                            ", needsRefresh: " + needsRefresh +
                            ", expiresIn: " + expiresIn + "s");

                    return valid;
                }

                return false;

            } catch (Exception e) {
                System.err.println("[AuthService] Token validation failed: " + e.getMessage());
                return false;
            }
        });
    }

    // ============== EXISTING AUTHENTICATION METHODS (unchanged from your original code) ==============

    /* ---------------------- WebView flow ---------------------- */

    private UserProfile signInViaWebView(Stage owner, String provider) throws Exception {
        final UserProfile[] out = { null };

        WebView wv = new WebView();
        WebEngine eng = wv.getEngine();
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setTitle("Sign in with " + provider);
        dlg.setScene(new Scene(wv, 640, 760));

        System.out.println("[Auth] Opening: " + Config.AUTH_WORKER + "/oauth/" + provider);
        System.out.println("[Auth] Expecting redirect to: " + Config.OAUTH_REDIRECT + "#token=…");

        eng.locationProperty().addListener((obs, old, url) -> {
            try {
                if (url != null && url.startsWith(Config.OAUTH_REDIRECT)) {
                    String token = extractHashParam(url, "token");
                    if (token == null || token.isBlank()) throw new IllegalStateException("No token returned");
                    handleTokenAndClose(token, provider, dlg, out);
                }
            } catch (Exception e) {
                e.printStackTrace();
                dlg.close();
            }
        });

        eng.load(Config.AUTH_WORKER + (provider.equals("google") ? "/oauth/google" : "/oauth/discord"));
        dlg.showAndWait();

        if (out[0] == null) throw new IllegalStateException("OAuth did not complete.");
        return out[0];
    }

    /* ---------------------- System browser flow ---------------------- */

    private UserProfile signInViaSystemBrowser(String provider) throws Exception {
        System.out.println("[Auth] Using system browser for " + provider + " authentication");

        // Start local server to capture the redirect
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = server.getAddress().getPort();
        String redirectUri = "http://localhost:" + port + "/callback";

        System.out.println("[Auth] Local server started on port: " + port);

        final UserProfile[] result = { null };
        final CountDownLatch latch = new CountDownLatch(1);

        // Set up the callback handler
        server.createContext("/callback", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQuery(query);

                String fragment = exchange.getRequestURI().getFragment();
                if (fragment != null && !fragment.isBlank()) {
                    Map<String, String> fragmentParams = parseQuery(fragment);
                    params.putAll(fragmentParams);
                }

                // Look for token in various places
                String token = params.get("token");
                if (token == null || token.isBlank()) {
                    // Check the full URI for hash params
                    String fullUri = exchange.getRequestURI().toString();
                    if (fullUri.contains("#token=")) {
                        int start = fullUri.indexOf("#token=") + 7;
                        int end = fullUri.indexOf("&", start);
                        if (end == -1) end = fullUri.length();
                        token = URLDecoder.decode(fullUri.substring(start, end), StandardCharsets.UTF_8);
                    }
                }

                if (token != null && !token.isBlank()) {
                    result[0] = handleToken(token, provider);
                    String response = "Authentication successful! You can close this window.";
                    sendResponse(exchange, 200, response);
                } else {
                    String error = params.getOrDefault("error", "Unknown error");
                    String response = "Authentication failed: " + error;
                    sendResponse(exchange, 400, response);
                }
            } catch (Exception e) {
                e.printStackTrace();
                String response = "Authentication error: " + e.getMessage();
                sendResponse(exchange, 500, response);
            } finally {
                latch.countDown();
            }
        });

        server.start();

        try {
            // Build the OAuth URL with the dynamic redirect URI
            String authUrl = Config.AUTH_WORKER + "/oauth/" + provider +
                    "?app_redirect=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);

            System.out.println("[Auth] Opening browser to: " + authUrl);

            // Open the system browser
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(authUrl));
            } else {
                System.err.println("[Auth] Desktop not supported. Please open: " + authUrl);
                throw new UnsupportedOperationException("Cannot open browser");
            }

            // Wait for callback with timeout
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            if (!completed) {
                throw new RuntimeException("Authentication timed out after 60 seconds");
            }

            if (result[0] == null) {
                throw new RuntimeException("Authentication failed - no user profile received");
            }

            return result[0];

        } finally {
            server.stop(0);
        }
    }

    /* ---------------------- Helper methods (unchanged from your original code) ---------------------- */

    private void handleTokenAndClose(String token, String provider, Stage dlg, UserProfile[] out) throws Exception {
        out[0] = handleToken(token, provider);
        dlg.close();
    }

    private UserProfile handleToken(String token, String provider) throws Exception {
        System.out.println("[Auth] Stored JWT. len=" + token.length());
        Config.APP_TOKEN = token;

        String[] parts = token.split("\\.");
        if (parts.length < 2) throw new IllegalStateException("Malformed JWT");
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        System.out.println("[Auth] payload=" + payloadJson);

        JsonNode c = M.readTree(payloadJson);
        String sub       = c.path("sub").asText("");
        String username  = c.path("username").asText("");
        String avatar    = c.path("avatar").asText("");
        String provClaim = c.path("provider").asText(provider);

        // Ensure local identity
        String pubKey = CryptoService.get().ensureLocalIdentity();
        return new UserProfile(sub, username, avatar, provClaim, pubKey);
    }

    private String extractHashParam(String url, String param) {
        if (url == null || !url.contains("#")) return null;

        String hash = url.substring(url.indexOf("#") + 1);
        for (String kv : hash.split("&")) {
            if (kv.startsWith(param + "=")) {
                try {
                    return URLDecoder.decode(kv.substring((param + "=").length()), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return kv.substring((param + "=").length());
                }
            }
        }
        return null;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new java.util.HashMap<>();
        if (query == null) return params;

        for (String pair : query.split("&")) {
            int eq = pair.indexOf("=");
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private void sendResponse(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}