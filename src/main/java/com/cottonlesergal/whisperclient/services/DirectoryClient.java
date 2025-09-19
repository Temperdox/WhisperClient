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
import java.util.concurrent.CompletableFuture;

public class DirectoryClient {
    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final MessageChunkingService chunkingService = MessageChunkingService.getInstance();

    public List<UserSummary> friends() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/friends"))
                    .header("authorization","Bearer "+Config.APP_TOKEN).GET().build();
            JsonNode j = M.readTree(client.send(req, HttpResponse.BodyHandlers.ofString()).body());
            List<UserSummary> out = new ArrayList<>();
            for (JsonNode n : j.path("friends")) {
                String u = n.asText();
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
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

            System.out.println("[DirectoryClient] Pending requests response: " + response.statusCode());
            System.out.println("[DirectoryClient] Pending requests body: " + response.body());

            JsonNode j = M.readTree(response.body());
            List<UserSummary> out = new ArrayList<>();
            JsonNode requestsNode = j.path("requests");

            System.out.println("[DirectoryClient] Found " + requestsNode.size() + " pending requests");

            for (JsonNode n : requestsNode) {
                String from = n.path("from").asText("");
                System.out.println("[DirectoryClient] Processing request from: " + from);
                UserSummary s = lookup(from);
                out.add(s != null ? s : new UserSummary(from, from, ""));
            }

            System.out.println("[DirectoryClient] Returning " + out.size() + " pending requests");
            return out;
        } catch (Exception e){
            System.err.println("[DirectoryClient] Error fetching pending requests: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
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

    public boolean declineFriend(String from) {
        try {
            String body = "{\"from\":"+M.writeValueAsString(from)+"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/friend/decline"))
                    .header("authorization","Bearer "+Config.APP_TOKEN)
                    .header("content-type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e){ return false; }
    }

    public boolean blockUser(String username) {
        try {
            String body = "{\"user\":"+M.writeValueAsString(username)+"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/user/block"))
                    .header("authorization","Bearer "+Config.APP_TOKEN)
                    .header("content-type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e){ return false; }
    }

    public boolean unblockUser(String username) {
        try {
            String body = "{\"user\":"+M.writeValueAsString(username)+"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/user/unblock"))
                    .header("authorization","Bearer "+Config.APP_TOKEN)
                    .header("content-type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e){ return false; }
    }

    public List<UserSummary> blockedUsers() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/user/blocked"))
                    .header("authorization","Bearer "+Config.APP_TOKEN).GET().build();
            JsonNode j = M.readTree(client.send(req, HttpResponse.BodyHandlers.ofString()).body());
            List<UserSummary> out = new ArrayList<>();
            for (JsonNode n : j.path("blocked")) {
                String u = n.asText();
                UserSummary s = lookup(u);
                out.add(s != null ? s : new UserSummary(u, u, ""));
            }
            return out;
        } catch (Exception e){ e.printStackTrace(); return List.of(); }
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

            if (code == 200) return true;

            System.err.println("[DirectoryClient] registerOrUpdate failed: " + code + " body=" + res.body());
            return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Enhanced sendChat method with chunking support for large media messages
     * FIXED: Uses correct /message endpoint and proper error handling
     */
    public void sendChat(String to, String text) {
        try {
            System.out.println("[DirectoryClient] Attempting to send message to: " + to);

            // First verify we can send to this user
            if (!areFriends(to)) {
                throw new RuntimeException("Cannot send message - not friends with " + to);
            }

            // Check if message needs chunking for large media
            String[] chunks = chunkingService.splitMessage(text);

            if (chunks.length > 1) {
                System.out.println("[DirectoryClient] Sending large message in " + chunks.length + " chunks to " + to);

                // Send each chunk sequentially
                for (int i = 0; i < chunks.length; i++) {
                    String chunk = chunks[i];
                    System.out.println("[DirectoryClient] Sending chunk " + (i + 1) + "/" + chunks.length +
                            " (size: " + chunk.length() + " bytes)");

                    try {
                        sendSingleMessage(to, chunk);
                        System.out.println("[DirectoryClient] Sent chunk " + (i + 1) + "/" + chunks.length);

                        // Small delay between chunks to avoid overwhelming the server
                        if (i < chunks.length - 1) {
                            Thread.sleep(50); // 50ms delay
                        }

                    } catch (Exception e) {
                        System.err.println("[DirectoryClient] Failed to send chunk " + (i + 1) + "/" + chunks.length + ": " + e.getMessage());
                        throw e; // Re-throw to stop sending remaining chunks
                    }
                }

                System.out.println("[DirectoryClient] Successfully sent all " + chunks.length + " chunks");

            } else {
                // Regular message, send normally
                sendSingleMessage(to, text);
                System.out.println("[DirectoryClient] Successfully sent regular message to " + to);
            }

        } catch (Exception e) {
            System.err.println("[DirectoryClient] Failed to send message: " + e.getMessage());
            throw new RuntimeException("Failed to send message: " + e.getMessage(), e);
        }
    }

    /**
     * Send a single message (chunk or regular message) - FIXED to use /message endpoint
     */
    private void sendSingleMessage(String to, String text) throws Exception {
        String body = "{\"to\":" + M.writeValueAsString(to) + ",\"text\":" + M.writeValueAsString(text) + "}";

        HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/message"))
                .header("authorization", "Bearer " + Config.APP_TOKEN)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String errorBody = response.body();
            System.err.println("[DirectoryClient] Failed to send message. Status: " + response.statusCode() +
                    ", Body: " + errorBody);

            // Provide more specific error messages
            if (response.statusCode() == 404) {
                throw new RuntimeException("Message endpoint not found - check server configuration");
            } else if (response.statusCode() == 403) {
                throw new RuntimeException("Not friends with " + to + " - cannot send message");
            } else if (response.statusCode() == 401) {
                throw new RuntimeException("Authentication failed - check token");
            } else {
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + errorBody);
            }
        }
    }

    /**
     * Check if we're friends with a user before sending messages
     */
    private boolean areFriends(String username) {
        try {
            // If we have authentication issues, be more lenient for existing chats
            List<UserSummary> friendsList;
            try {
                friendsList = friends();
            } catch (Exception e) {
                System.err.println("[DirectoryClient] Failed to fetch friends list: " + e.getMessage());
                // If we can't fetch friends but this is an existing chat, allow it
                System.out.println("[DirectoryClient] Allowing message to " + username + " due to auth issues");
                return true; // Be lenient when auth is broken
            }

            boolean isFriend = friendsList.stream()
                    .anyMatch(friend -> username.equalsIgnoreCase(friend.getUsername()));

            System.out.println("[DirectoryClient] Friendship check for " + username + ": " + isFriend);
            return isFriend;
        } catch (Exception e) {
            System.err.println("[DirectoryClient] Failed to check friend status: " + e.getMessage());
            return true; // Be lenient when we can't check
        }
    }

    /**
     * Debug method to test message sending capability
     */
    public void testMessageSending(String to) {
        try {
            System.out.println("[DirectoryClient] Testing message sending to: " + to);

            // Check friendship status
            boolean isFriend = areFriends(to);
            System.out.println("[DirectoryClient] Are friends with " + to + ": " + isFriend);

            if (!isFriend) {
                System.err.println("[DirectoryClient] Cannot send test message - not friends with " + to);
                return;
            }

            // Try sending a simple test message
            String testMessage = "Test message: " + System.currentTimeMillis();
            sendSingleMessage(to, testMessage);
            System.out.println("[DirectoryClient] Successfully sent test message");

        } catch (Exception e) {
            System.err.println("[DirectoryClient] Test message failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Check if friendship exists with detailed logging
     */
    public boolean checkFriendshipStatus(String username) {
        try {
            System.out.println("[DirectoryClient] Checking detailed friendship status with: " + username);

            List<UserSummary> friendsList = friends();
            System.out.println("[DirectoryClient] Current friends list:");
            for (UserSummary friend : friendsList) {
                System.out.println("  - " + friend.getUsername() + " (" + friend.getDisplay() + ")");
            }

            boolean isFriend = friendsList.stream()
                    .anyMatch(friend -> username.equalsIgnoreCase(friend.getUsername()));

            System.out.println("[DirectoryClient] Is " + username + " in friends list: " + isFriend);
            return isFriend;

        } catch (Exception e) {
            System.err.println("[DirectoryClient] Error checking friendship status: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Get connection and authentication status
     */
    public String getDebugInfo() {
        StringBuilder info = new StringBuilder();
        info.append("DirectoryClient Status:\n");
        info.append("  Worker URL: ").append(Config.DIR_WORKER).append("\n");
        info.append("  Token present: ").append(Config.APP_TOKEN != null && !Config.APP_TOKEN.isEmpty()).append("\n");
        info.append("  Token length: ").append(Config.APP_TOKEN != null ? Config.APP_TOKEN.length() : 0).append("\n");

        try {
            // Test basic connectivity
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/friends"))
                    .header("authorization","Bearer "+Config.APP_TOKEN).GET().build();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            info.append("  Friends endpoint test: ").append(response.statusCode()).append("\n");

        } catch (Exception e) {
            info.append("  Connection test failed: ").append(e.getMessage()).append("\n");
        }

        return info.toString();
    }
}