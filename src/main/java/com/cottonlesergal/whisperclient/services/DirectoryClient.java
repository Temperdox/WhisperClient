package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.models.UserProfile;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class DirectoryClient {
    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    public List<UserSummary> friends() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/friends"))
                    .header("authorization","Bearer "+Config.APP_TOKEN).GET().build();
            JsonNode j = M.readTree(client.send(req, HttpResponse.BodyHandlers.ofString()).body());
            List<UserSummary> out = new ArrayList<>();
            for (JsonNode n : j.path("friends")) {
                String u = n.asText();
                // fetch display/avatar for friend
                UserSummary s = lookup(u);
                out.add(s != null ? s : new UserSummary(u,u,""));
            }
            return out;
        } catch (Exception e){ e.printStackTrace(); return List.of(); }
    }

    public List<UserSummary> pending() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/friend/pending"))
                    .header("authorization","Bearer "+Config.APP_TOKEN).GET().build();
            JsonNode j = M.readTree(client.send(req, HttpResponse.BodyHandlers.ofString()).body());
            List<UserSummary> out = new ArrayList<>();
            for (JsonNode n : j.path("requests")) {
                String from = n.path("from").asText("");
                UserSummary s = lookup(from);
                out.add(s != null ? s : new UserSummary(from, from, ""));
            }
            return out;
        } catch (Exception e){ e.printStackTrace(); return List.of(); }
    }

    public UserSummary lookup(String u) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            Config.DIR_WORKER + "/lookup?u=" + URLEncoder.encode(u, StandardCharsets.UTF_8)))
                    .GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            JsonNode n = M.readTree(res.body());
            return new UserSummary(
                    n.path("username").asText(""),
                    n.path("display").asText(""),
                    n.path("avatar").asText("")
            );
        } catch (Exception e){ return null; }
    }

    public boolean sendFriendRequest(String to) {
        try {
            String body = "{\"to\":" + M.writeValueAsString(to) + "}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/friend/request"))
                    .header("authorization","Bearer "+Config.APP_TOKEN)
                    .header("content-type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e){ return false; }
    }

    public boolean acceptFriend(String from) {
        try {
            String body = "{\"from\":"+M.writeValueAsString(from)+"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/friend/accept"))
                    .header("authorization","Bearer "+Config.APP_TOKEN)
                    .header("content-type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e){ return false; }
    }

    public void removeFriend(String user) {
        try {
            String body = "{\"user\":"+M.writeValueAsString(user)+"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/friend/remove"))
                    .header("authorization","Bearer "+Config.APP_TOKEN)
                    .header("content-type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    public void sendChat(String to, String text) {
        try {
            String body = "{\"to\":"+M.writeValueAsString(to)+",\"text\":"+M.writeValueAsString(text)+"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/message"))
                    .header("authorization","Bearer "+Config.APP_TOKEN)
                    .header("content-type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e){ e.printStackTrace(); }
    }

    public List<UserSummary> search(String q) {
        try {
            String url = Config.DIR_WORKER + "/search?q=" +
                    URLEncoder.encode(q, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET().build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return List.of();

            JsonNode arr = M.readTree(res.body()).path("results");
            List<UserSummary> out = new ArrayList<>();
            for (JsonNode n : arr) {
                out.add(new UserSummary(
                        n.path("username").asText(""),
                        n.path("display").asText(""),
                        n.path("avatar").asText("")
                ));
            }
            return out;
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    public boolean registerOrUpdate(UserProfile me) {
        try {
            // build request JSON the worker expects
            var payload = M.createObjectNode()
                    .put("username", me.getUsername())
                    .put("pubkey",   me.getPubKey())
                    .put("display",  (me.getDisplayName()==null || me.getDisplayName().isBlank())
                            ? me.getUsername() : me.getDisplayName())
                    .put("provider", me.getProvider() == null ? "oauth" : me.getProvider())
                    .put("avatar",   me.getAvatarUrl() == null ? "" : me.getAvatarUrl());

            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/register"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();

            // 200 => ok, 409 => username taken with different pubkey (surface as failure)
            if (code == 200) return true;

            System.err.println("[DirectoryClient] registerOrUpdate failed: " + code + " body=" + res.body());
            return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
