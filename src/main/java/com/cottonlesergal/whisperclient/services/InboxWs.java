package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.events.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

public class InboxWs implements WebSocket.Listener {
    private static final ObjectMapper M = new ObjectMapper();
    private WebSocket ws;

    public void connect(String workerBaseUrl, String username, String jwtBearer) {
        String wss = workerBaseUrl.replaceFirst("^http", "ws") + "/inbox/" + username;
        System.out.println("[InboxWs] dialing: " + wss);

        this.ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("authorization", "Bearer " + jwtBearer)
                .buildAsync(URI.create(wss), this)
                .whenComplete((ws, err) -> {
                    if (err != null) System.err.println("[InboxWs] buildAsync error: " + err);
                    else System.out.println("[InboxWs] handshake initiated OK");
                })
                .join();
    }

    @Override public void onOpen(WebSocket webSocket) {
        System.out.println("[InboxWs] connected");
        webSocket.request(1);
    }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            JsonNode n = M.readTree(data.toString());
            Event ev = new Event(
                    n.path("type").asText(""),
                    n.path("from").asText(null),
                    n.path("to").asText(null),
                    n.path("at").asLong(System.currentTimeMillis()),
                    n.path("data")
            );
            AppCtx.BUS.emit(ev);
        } catch (Exception e) {
            e.printStackTrace();
        }
        webSocket.request(1);
        return null;
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("[InboxWs] error: " + error);
    }
}
