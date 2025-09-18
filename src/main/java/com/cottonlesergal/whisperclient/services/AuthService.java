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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
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

    /* ---------------------- System browser + loopback for Google ---------------------- */

    private UserProfile signInViaSystemBrowser(String provider) throws Exception {
        // 1) Start a tiny localhost HTTP server on a random free port
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String callbackUrl = "http://127.0.0.1:" + port + "/callback";

        final CountDownLatch gotJwt = new CountDownLatch(1);
        final String[] jwtHolder = { null };

        // callback serves a little HTML that reads location.hash and posts JWT back to /token
        server.createContext("/callback", exchange -> {
            String html = """
                <!doctype html><meta charset="utf-8">
                <title>WhisperClient</title>
                <body style="font:14px sans-serif;color:#ddd;background:#202225">
                <div style="margin:40px auto;max-width:520px">
                  <h2>Signing you in…</h2>
                  <p>If this page doesn't close automatically, you can close it now.</p>
                </div>
                <script>
                  const m = (location.hash||'').match(/[#?]token=([^&]+)/);
                  if (m) fetch('/token?jwt='+encodeURIComponent(m[1])).then(()=>window.close());
                </script>
                """;
            sendText(exchange, html, 200, "text/html; charset=utf-8");
        });

        // token receives ?jwt=… and signals the app
        server.createContext("/token", exchange -> {
            Map<String,String> q = splitQuery(exchange.getRequestURI().getRawQuery());
            String jwt = q.get("jwt");
            if (jwt != null && !jwt.isBlank()) {
                jwtHolder[0] = jwt;
                sendText(exchange, "OK", 200, "text/plain; charset=utf-8");
                gotJwt.countDown();
            } else {
                sendText(exchange, "Missing jwt", 400, "text/plain; charset=utf-8");
            }
        });

        server.start();

        try {
            // Open the system browser to your auth worker, passing app_redirect=callbackUrl
            String start = Config.AUTH_WORKER + "/oauth/google?app_redirect=" +
                    URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);
            System.out.println("[Auth] System browser: " + start);
            Desktop.getDesktop().browse(URI.create(start));

            // Wait (max 2 minutes) for the JWT to arrive at /token
            if (!gotJwt.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for Google OAuth.");
            }

            // Handle token like the WebView path
            return handleToken(jwtHolder[0], provider);

        } finally {
            server.stop(0);
        }
    }

    /* ---------------------- Shared helpers ---------------------- */

    private static void sendText(HttpExchange ex, String body, int code, String ctype) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ctype);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static Map<String,String> splitQuery(String raw) {
        try {
            java.util.LinkedHashMap<String,String> map = new java.util.LinkedHashMap<>();
            if (raw == null || raw.isEmpty()) return map;
            for (String part : raw.split("&")) {
                int i = part.indexOf('=');
                String k = URLDecoder.decode(i>0?part.substring(0,i):part, StandardCharsets.UTF_8);
                String v = i>0? URLDecoder.decode(part.substring(i+1), StandardCharsets.UTF_8) : "";
                map.put(k, v);
            }
            return map;
        } catch (Exception e) {
            return java.util.Collections.emptyMap();
        }
    }

    private static String extractHashParam(String url, String name) {
        int hash = url.indexOf('#');
        if (hash < 0) return null;
        String frag = url.substring(hash + 1); // token=...
        for (String p : frag.split("&")) {
            int i = p.indexOf('=');
            if (i > 0 && p.substring(0, i).equals(name)) {
                return URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Parses the JWT, saves it into Config.APP_TOKEN, builds UserProfile. */
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

    private void handleTokenAndClose(String token, String provider, Stage dlg, UserProfile[] out) throws Exception {
        out[0] = handleToken(token, provider);
        dlg.close();
    }
}
