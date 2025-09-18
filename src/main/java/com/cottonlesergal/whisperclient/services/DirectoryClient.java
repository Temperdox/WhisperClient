package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.models.UserProfile;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import com.cottonlesergal.whisperclient.core.Session;
import java.util.Optional;

public class DirectoryClient {
    private static final ObjectMapper M = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private HttpRequest.Builder base(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("authorization", "Bearer " + Config.APP_TOKEN)
                .header("content-type", "application/json");
    }

    public Optional<UserSummary> lookup(String handle) {
        try {
            var u = Config.DIR_WORKER + "/lookup?u=" + URLEncoder.encode(handle, StandardCharsets.UTF_8);
            var res = client.send(base(URI.create(u)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) return Optional.empty();
            if (res.statusCode() != 200) return Optional.empty();
            var n = M.readTree(res.body());
            return Optional.of(new UserSummary(
                    n.path("username").asText(""),
                    n.path("display").asText(""),
                    n.path("avatar").asText("")));
        } catch (Exception e) { e.printStackTrace(); return Optional.empty(); }
    }

    /** Register/update the current user in the directory */
    public void registerOrUpdate(UserProfile me) {
        registerOrUpdate(me.getUsername().toLowerCase(), me.getPubKey(), me.getAvatarUrl());
    }

    public void registerOrUpdate(String username, String pubKey, String avatarUrl) {
        try {
            var body = M.createObjectNode();
            body.put("username", username);
            body.put("pubkey", pubKey);
            body.put("avatar", avatarUrl == null ? "" : avatarUrl);
            body.put("provider", "oauth");

            HttpRequest req = base(URI.create(Config.DIR_WORKER + "/register"))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Prefix search by username; returns display + avatar for a result list */
    public List<UserSummary> search(String q) {
        try {
            String url = Config.DIR_WORKER + "/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
            HttpRequest req = base(URI.create(url)).GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return List.of();

            ArrayNode arr = (ArrayNode) M.readTree(res.body()).path("results");
            List<UserSummary> out = new ArrayList<>();
            for (JsonNode n : arr) {
                out.add(new UserSummary(
                        n.path("username").asText(""),
                        n.path("display").asText(""),
                        n.path("avatar").asText("")
                ));
            }
            return out;
        } catch (Exception ex) {
            ex.printStackTrace();
            return List.of();
        }
    }

    public boolean sendFriendRequest(String to) {
        try {
            var body = M.createObjectNode().put("to", to).toString();
            var res = client.send(
                    base(URI.create(Config.DIR_WORKER + "/friend/request"))
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return res.statusCode() == 200;
        } catch (Exception e) { e.printStackTrace(); return false; }
    }

    public boolean acceptFriend(String from) {
        try {
            var body = M.createObjectNode().put("from", from).toString();
            var res = client.send(
                    base(URI.create(Config.DIR_WORKER + "/friend/accept"))
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return res.statusCode() == 200;
        } catch (Exception e) { e.printStackTrace(); return false; }
    }

    public List<String> listPending() {
        try {
            var res = client.send(
                    base(URI.create(Config.DIR_WORKER + "/friend/pending")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return List.of();
            var arr = (ArrayNode) M.readTree(res.body()).path("requests");
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) out.add(n.path("from").asText(""));
            return out;
        } catch (Exception e) { e.printStackTrace(); return List.of(); }
    }

    public List<UserSummary> listFriends() {
        try {
            var res = client.send(
                    base(URI.create(Config.DIR_WORKER + "/friends")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return List.of();
            ArrayNode arr = (ArrayNode) M.readTree(res.body()).path("friends");
            List<UserSummary> out = new ArrayList<>();
            for (JsonNode n : arr) {
                out.add(new UserSummary(
                        n.path("username").asText(""),
                        n.path("display").asText(""),
                        n.path("avatar").asText("")));
            }
            return out;
        } catch (Exception e) { e.printStackTrace(); return List.of(); }
    }
}
