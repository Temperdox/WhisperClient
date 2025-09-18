package com.cottonlesergal.whisperclient.services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public class InboxWs implements WebSocket.Listener {
    private final Consumer<String> onText;
    private WebSocket ws;

    public InboxWs(Consumer<String> onText){ this.onText = onText; }

    /**
     * Connect to Worker inbox, e.g. base "https://whisperauth....workers.dev"
     * We convert http(s) -> ws(s) and append "/inbox/{username}".
     */
    public void connect(String workerBaseUrl, String username, String jwtBearer) {
        // Fallback to global token if caller passed null/blank
        String token = (jwtBearer != null && !jwtBearer.isBlank()) ? jwtBearer : Config.APP_TOKEN;

        final String wss = workerBaseUrl.replaceFirst("^http", "ws") + "/inbox/" + username;

        // ---- DEBUG ----
        System.out.println("[InboxWs] dialing: " + wss);
        System.out.println("[InboxWs] token.from=" + (jwtBearer != null && !jwtBearer.isBlank() ? "arg" : "Config.APP_TOKEN"));
        System.out.println("[InboxWs] jwt.len=" + (token == null ? 0 : token.length())
                + " preview=" + preview(token));
        System.out.println("[InboxWs] jwt.payload=" + peekJwtPayload(token));
        // --------------

        try {
            this.ws = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .header("authorization", "Bearer " + token)
                    .buildAsync(URI.create(wss), this)
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            System.err.println("[InboxWs] buildAsync error: " + err);
                            Throwable c = err; int i = 0;
                            while (c != null && i++ < 6) {
                                System.err.println("  cause[" + i + "]: " + c.getClass().getName() + ": " + c.getMessage());
                                c = c.getCause();
                            }
                        } else {
                            System.out.println("[InboxWs] handshake initiated OK");
                        }
                    })
                    .join();
            System.out.println("[InboxWs] connected (onOpen will fire next)");
        } catch (java.util.concurrent.CompletionException ce) {
            System.err.println("[InboxWs] join() failed: " + ce);
            Throwable c = ce.getCause(); int i = 0;
            while (c != null && i++ < 6) {
                System.err.println("  cause[" + i + "]: " + c.getClass().getName() + ": " + c.getMessage());
                c = c.getCause();
            }
            throw ce; // preserve behavior
        }
    }

    // helpers
    private static String preview(String s) {
        if (s == null || s.isEmpty()) return "<empty>";
        return s.length() <= 16 ? s : s.substring(0, 8) + "…" + s.substring(s.length() - 8);
    }
    private static String peekJwtPayload(String jwt) {
        try {
            String[] parts = jwt == null ? new String[0] : jwt.split("\\.");
            if (parts.length < 2) return "<bad jwt>";
            byte[] json = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return new String(json, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<decode error: " + e.getMessage() + ">";
        }
    }

    @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        onText.accept(data.toString());
        webSocket.request(1);
        return null;
    }

    @Override public void onError(WebSocket webSocket, Throwable error) { error.printStackTrace(); }
}
