package com.cottonlesergal.whisperclient.services;

import com.cottonlesergal.whisperclient.models.UserProfile;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.cottonlesergal.whisperclient.ui.MainController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class DirectoryClient {
    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final MessageChunkingService chunkingService = MessageChunkingService.getInstance();

    // Add this field for auth failure callback
    private MainController mainController;

    /**
     * Set reference to MainController for auth failure handling
     */
    public void setMainController(MainController controller) {
        this.mainController = controller;
    }

    /**
     * Handle 401 errors by notifying MainController
     */
    private void handle401Error(String context) {
        System.err.println("[DirectoryClient] 401 error in " + context);
        if (mainController != null) {
            Platform.runLater(() -> {
                mainController.on401Error("DirectoryClient:" + context);
            });
        }
    }

    public boolean registerOrUpdate(UserProfile me) {
        try {
            var payload = M.createObjectNode()
                    .put("username", me.getUsername())
                    .put("pubkey", me.getPubKey()) // Use correct method name
                    .put("display", (me.getDisplayName() == null || me.getDisplayName().isBlank())
                            ? me.getUsername() : me.getDisplayName())
                    .put("provider", me.getProvider() == null ? "oauth" : me.getProvider())
                    .put("avatar", me.getAvatarUrl() == null ? "" : me.getAvatarUrl());

            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/register"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();

            if (code == 200) {
                System.out.println("[DirectoryClient] Successfully registered/updated user: " + me.getUsername());
                return true;
            }

            System.err.println("[DirectoryClient] registerOrUpdate failed: " + code + " body=" + res.body());

            // Handle 401 errors
            if (code == 401) {
                handle401Error("registerOrUpdate");
            }

            return false;
        } catch (Exception e) {
            System.err.println("[DirectoryClient] Exception in registerOrUpdate: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public List<UserSummary> friends() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/friends"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN).GET().build();

            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                handle401Error("friends");
                return new ArrayList<>();
            }

            if (response.statusCode() != 200) {
                System.err.println("[DirectoryClient] Friends request failed: " + response.statusCode());
                return new ArrayList<>();
            }

            JsonNode j = M.readTree(response.body());
            List<UserSummary> out = new ArrayList<>();

            // FIXED: Server returns array of user objects directly, not nested
            for (JsonNode n : j) {
                String username = n.path("username").asText("");
                String display = n.path("display").asText(username);
                String avatar = n.path("avatar").asText("");
                out.add(new UserSummary(username, display, avatar));
            }
            return out;
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    public List<UserSummary> pending() {
        try {
            // FIXED: Endpoint /friend/pending -> /pending
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/pending"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN).GET().build();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

            System.out.println("[DirectoryClient] Pending requests response: " + response.statusCode());
            System.out.println("[DirectoryClient] Pending requests body: " + response.body());

            if (response.statusCode() == 401) {
                handle401Error("pending");
                return new ArrayList<>();
            }

            if (response.statusCode() != 200) {
                System.err.println("[DirectoryClient] Pending request failed: " + response.statusCode());
                return new ArrayList<>();
            }

            JsonNode j = M.readTree(response.body());
            List<UserSummary> out = new ArrayList<>();

            // FIXED: Server returns array of user objects directly, not nested in "requests"
            for (JsonNode n : j) {
                String username = n.path("username").asText("");
                String display = n.path("display").asText(username);
                String avatar = n.path("avatar").asText("");
                out.add(new UserSummary(username, display, avatar));
            }

            System.out.println("[DirectoryClient] Returning " + out.size() + " pending requests");
            return out;
        } catch (Exception e) {
            System.err.println("[DirectoryClient] Error fetching pending requests: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    public UserSummary lookup(String u) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            Config.DIR_WORKER + "/lookup?u=" + URLEncoder.encode(u, StandardCharsets.UTF_8)))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 401) {
                handle401Error("lookup");
                return null;
            }

            if (res.statusCode() != 200) return null;
            JsonNode n = M.readTree(res.body());
            return new UserSummary(
                    n.path("username").asText(""),
                    n.path("display").asText(""),
                    n.path("avatar").asText("")
            );
        } catch (Exception e) {
            return null;
        }
    }

    public List<UserSummary> search(String q) {
        try {
            String url = Config.DIR_WORKER + "/search?q=" +
                    URLEncoder.encode(q, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .GET().build();

            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 401) {
                handle401Error("search");
                return new ArrayList<>();
            }

            if (res.statusCode() != 200) return List.of();

            // FIXED: Server returns array directly, not nested in "results"
            JsonNode arr = M.readTree(res.body());
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

    // FIXED: All friend management methods with correct endpoints and field names
    public boolean sendFriendRequest(String to) {
        try {
            // FIXED: Field name "to" -> "username"
            String body = "{\"username\":" + M.writeValueAsString(to) + "}";
            // FIXED: Endpoint /friend/request -> /friend-request
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/friend-request"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                handle401Error("sendFriendRequest");
                return false;
            }

            System.out.println("[DirectoryClient] Friend request response: " + response.statusCode() + " - " + response.body());
            return response.statusCode() == 200;
        } catch (Exception e) {
            System.err.println("[DirectoryClient] Exception in sendFriendRequest: " + e.getMessage());
            return false;
        }
    }

    public boolean acceptFriend(String from) {
        try {
            // FIXED: Field name "from" -> "username"
            String body = "{\"username\":" + M.writeValueAsString(from) + "}";
            // FIXED: Endpoint /friend/accept -> /accept-friend
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/accept-friend"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                handle401Error("acceptFriend");
                return false;
            }

            System.out.println("[DirectoryClient] Accept friend response: " + response.statusCode() + " - " + response.body());
            return response.statusCode() == 200;
        } catch (Exception e) {
            System.err.println("[DirectoryClient] Exception in acceptFriend: " + e.getMessage());
            return false;
        }
    }

    public boolean declineFriend(String from) {
        try {
            // FIXED: Field name "from" -> "username"
            String body = "{\"username\":" + M.writeValueAsString(from) + "}";
            // FIXED: Endpoint /friend/decline -> /decline-friend
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/decline-friend"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                handle401Error("declineFriend");
                return false;
            }

            System.out.println("[DirectoryClient] Decline friend response: " + response.statusCode() + " - " + response.body());
            return response.statusCode() == 200;
        } catch (Exception e) {
            System.err.println("[DirectoryClient] Exception in declineFriend: " + e.getMessage());
            return false;
        }
    }

    // FIXED: Return type void -> boolean, field name "user" -> "username", endpoint /friend/remove -> /remove-friend
    public boolean removeFriend(String user) {
        try {
            String body = "{\"username\":" + M.writeValueAsString(user) + "}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/remove-friend"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                handle401Error("removeFriend");
                return false;
            }

            System.out.println("[DirectoryClient] Remove friend response: " + response.statusCode() + " - " + response.body());
            return response.statusCode() == 200;
        } catch (Exception e) {
            System.err.println("[DirectoryClient] Exception in removeFriend: " + e.getMessage());
            return false;
        }
    }

    public boolean blockUser(String username) {
        try {
            // FIXED: Field name "user" -> "username"
            String body = "{\"username\":" + M.writeValueAsString(username) + "}";
            // FIXED: Endpoint /user/block -> /block-user
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/block-user"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                handle401Error("blockUser");
                return false;
            }

            System.out.println("[DirectoryClient] Block user response: " + response.statusCode() + " - " + response.body());
            return response.statusCode() == 200;
        } catch (Exception e) {
            System.err.println("[DirectoryClient] Exception in blockUser: " + e.getMessage());
            return false;
        }
    }

    public boolean unblockUser(String username) {
        try {
            // FIXED: Field name "user" -> "username"
            String body = "{\"username\":" + M.writeValueAsString(username) + "}";
            // FIXED: Endpoint /user/unblock -> /unblock-user (assuming server has this endpoint)
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/unblock-user"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                handle401Error("unblockUser");
                return false;
            }

            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public List<UserSummary> blockedUsers() {
        try {
            // FIXED: Endpoint /user/blocked -> /blocked (assuming server has this endpoint)
            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/blocked"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN).GET().build();

            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                handle401Error("blockedUsers");
                return new ArrayList<>();
            }

            if (response.statusCode() != 200) {
                return new ArrayList<>();
            }

            JsonNode j = M.readTree(response.body());
            List<UserSummary> out = new ArrayList<>();
            for (JsonNode n : j.path("blocked")) {
                String u = n.asText();
                UserSummary s = lookup(u);
                out.add(s != null ? s : new UserSummary(u, u, ""));
            }
            return out;
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Enhanced sendChat method with chunking support for large media messages
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
     * Send a single message (chunk or regular message) with 401 error handling
     */
    private void sendSingleMessage(String to, String text) throws Exception {
        String body = "{\"to\":" + M.writeValueAsString(to) + ",\"text\":" + M.writeValueAsString(text) + "}";

        // FIXED: Endpoint /message -> /send (based on common patterns, adjust if needed)
        HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/send"))
                .header("authorization", "Bearer " + Config.APP_TOKEN)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            handle401Error("sendMessage");
            throw new RuntimeException("Authentication failed - check token");
        }

        if (response.statusCode() != 200) {
            String errorBody = response.body();
            System.err.println("[DirectoryClient] Failed to send message. Status: " + response.statusCode() +
                    ", Body: " + errorBody);

            // Provide more specific error messages
            if (response.statusCode() == 404) {
                throw new RuntimeException("Message endpoint not found - check server configuration");
            } else if (response.statusCode() == 403) {
                throw new RuntimeException("Not friends with " + to + " - cannot send message");
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
                    .anyMatch(friend -> friend.getUsername().equalsIgnoreCase(username));

            if (!isFriend) {
                System.err.println("[DirectoryClient] User " + username + " is not in friends list");
                System.out.println("[DirectoryClient] Current friends: " +
                        friendsList.stream().map(UserSummary::getUsername).toList());
            }

            return isFriend;

        } catch (Exception e) {
            System.err.println("[DirectoryClient] Exception checking friendship: " + e.getMessage());
            return true; // Be lenient on errors
        }
    }
}