package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.models.UserProfile;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class DirectoryClient {
    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final MessageChunkingService chunkingService = MessageChunkingService.getInstance();

    public List<UserSummary> friends() {
        System.out.println("[DEBUG] Getting friends list...");
        System.out.println("[DEBUG] DIR_WORKER: " + Config.DIR_WORKER);
        System.out.println("[DEBUG] Token present: " + (Config.APP_TOKEN != null && !Config.APP_TOKEN.isEmpty()));

        try {
            String url = Config.DIR_WORKER + "/friends";
            System.out.println("[DEBUG] Request URL: " + url);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + Config.APP_TOKEN)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            System.out.println("[DEBUG] Sending request...");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("[DEBUG] Response status: " + response.statusCode());
            System.out.println("[DEBUG] Response body: " + response.body());

            if (response.statusCode() == 200) {
                List<UserSummary> result = M.readValue(response.body(), new TypeReference<List<UserSummary>>() {});
                System.out.println("[DEBUG] Parsed " + result.size() + " friends");
                for (UserSummary friend : result) {
                    System.out.println("[DEBUG] Friend: " + friend.getUsername() + " (" + friend.getDisplay() + ")");
                }
                return result;
            } else {
                System.out.println("[DEBUG] Non-200 response: " + response.statusCode());
                return List.of();
            }

        } catch (Exception e) {
            System.out.println("[DEBUG] Exception getting friends: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    public List<UserSummary> pending() {
        System.out.println("[DEBUG] Getting pending requests...");

        try {
            String url = Config.DIR_WORKER + "/pending";
            System.out.println("[DEBUG] Request URL: " + url);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + Config.APP_TOKEN)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            System.out.println("[DEBUG] Sending request...");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("[DEBUG] Response status: " + response.statusCode());
            System.out.println("[DEBUG] Response body: " + response.body());

            if (response.statusCode() == 200) {
                List<UserSummary> result = M.readValue(response.body(), new TypeReference<List<UserSummary>>() {});
                System.out.println("[DEBUG] Parsed " + result.size() + " pending requests");
                return result;
            } else {
                System.out.println("[DEBUG] Non-200 response: " + response.statusCode());
                return List.of();
            }

        } catch (Exception e) {
            System.out.println("[DEBUG] Exception getting pending: " + e.getMessage());
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

    public boolean sendFriendRequest(String username) {
        System.out.println("[DEBUG] Sending friend request to: " + username);
        System.out.println("[DEBUG] DIR_WORKER: " + Config.DIR_WORKER);
        System.out.println("[DEBUG] Token present: " + (Config.APP_TOKEN != null && !Config.APP_TOKEN.isEmpty()));

        try {
            String url = Config.DIR_WORKER + "/friend-request";
            System.out.println("[DEBUG] Request URL: " + url);

            String bodyJson = M.writeValueAsString(Map.of("username", username));
            System.out.println("[DEBUG] Request body: " + bodyJson);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + Config.APP_TOKEN)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            System.out.println("[DEBUG] Sending request...");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("[DEBUG] Response status: " + response.statusCode());
            System.out.println("[DEBUG] Response body: " + response.body());

            if (response.statusCode() == 200) {
                System.out.println("[DEBUG] Friend request sent successfully");
                return true;
            } else {
                System.out.println("[DEBUG] Friend request failed: " + response.statusCode());
                return false;
            }

        } catch (Exception e) {
            System.out.println("[DEBUG] Exception sending friend request: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean acceptFriend(String username) {
        System.out.println("[DEBUG] Accepting friend request from: " + username);

        try {
            String url = Config.DIR_WORKER + "/accept-friend";
            System.out.println("[DEBUG] Request URL: " + url);

            String bodyJson = M.writeValueAsString(Map.of("username", username));
            System.out.println("[DEBUG] Request body: " + bodyJson);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + Config.APP_TOKEN)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            System.out.println("[DEBUG] Sending request...");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("[DEBUG] Response status: " + response.statusCode());
            System.out.println("[DEBUG] Response body: " + response.body());

            return response.statusCode() == 200;

        } catch (Exception e) {
            System.out.println("[DEBUG] Exception accepting friend: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean declineFriend(String from) {
        try {
            String body = "{\"username\":" + M.writeValueAsString(from) + "}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/decline-friend"))
                    .header("authorization","Bearer " + Config.APP_TOKEN)
                    .header("content-type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("[DirectoryClient] declineFriend -> " + res.statusCode() + " " + res.body());
            return res.statusCode() == 200;
        } catch (Exception e){ e.printStackTrace(); return false; }
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

    public boolean removeFriend(String username) {
        System.out.println("[DEBUG] Removing friend: " + username);

        try {
            String url = Config.DIR_WORKER + "/remove-friend";
            System.out.println("[DEBUG] Request URL: " + url);

            String bodyJson = M.writeValueAsString(Map.of("username", username));
            System.out.println("[DEBUG] Request body: " + bodyJson);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + Config.APP_TOKEN)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            System.out.println("[DEBUG] Sending request...");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("[DEBUG] Response status: " + response.statusCode());
            System.out.println("[DEBUG] Response body: " + response.body());

            return response.statusCode() == 200;

        } catch (Exception e) {
            System.out.println("[DEBUG] Exception removing friend: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public List<UserSummary> search(String query) {
        System.out.println("[DEBUG] Searching for: " + query);

        try {
            String url = Config.DIR_WORKER + "/search?q=" + java.net.URLEncoder.encode(query, "UTF-8");
            System.out.println("[DEBUG] Request URL: " + url);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + Config.APP_TOKEN)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            System.out.println("[DEBUG] Sending request...");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("[DEBUG] Response status: " + response.statusCode());
            System.out.println("[DEBUG] Response body: " + response.body());

            if (response.statusCode() == 200) {
                List<UserSummary> result = M.readValue(response.body(), new TypeReference<List<UserSummary>>() {});
                System.out.println("[DEBUG] Found " + result.size() + " search results");
                return result;
            } else {
                System.out.println("[DEBUG] Non-200 response: " + response.statusCode());
                return List.of();
            }

        } catch (Exception e) {
            System.out.println("[DEBUG] Exception searching: " + e.getMessage());
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