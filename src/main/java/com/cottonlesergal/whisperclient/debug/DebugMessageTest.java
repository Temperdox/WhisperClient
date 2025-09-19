package com.cottonlesergal.whisperclient.debug;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.services.Config;
import com.cottonlesergal.whisperclient.services.DirectoryClient;
import com.cottonlesergal.whisperclient.services.InboxWs;
import com.cottonlesergal.whisperclient.services.MessageStorageService;

/**
 * Debug utility class to test message receiving functionality
 * Add this to your project temporarily for debugging
 */
public class DebugMessageTest {

    public static void runAllTests() {
        System.out.println("========== STARTING DEBUG TESTS ==========");

        testConfiguration();
        testSession();
        testEventBus();
        testWebSocketConnection();
        testMessageStorage();

        System.out.println("========== DEBUG TESTS COMPLETED ==========");
    }

    private static void testConfiguration() {
        System.out.println("\n--- Testing Configuration ---");

        try {
            System.out.println("Config debug info: " + Config.getDebugInfo());

            if (Config.hasValidToken()) {
                System.out.println("✓ Token is present and valid");
            } else {
                System.out.println("✗ Token is missing or invalid: " + Config.getTokenInfo());
            }

            Config.validateConfiguration();
            System.out.println("✓ Configuration validation passed");

        } catch (Exception e) {
            System.err.println("✗ Configuration test failed: " + e.getMessage());
        }
    }

    private static void testSession() {
        System.out.println("\n--- Testing Session ---");

        try {
            System.out.println("Session debug info: " + Session.getDebugInfo());

            if (Session.isValid()) {
                System.out.println("✓ Session is valid");
                Session.validate();
            } else {
                System.out.println("✗ Session is invalid");
                if (Session.me == null) {
                    System.out.println("  - User is null");
                }
                if (Session.token == null || Session.token.isEmpty()) {
                    System.out.println("  - Token is null or empty");
                }
            }

        } catch (Exception e) {
            System.err.println("✗ Session test failed: " + e.getMessage());
        }
    }

    private static void testEventBus() {
        System.out.println("\n--- Testing Event Bus ---");

        try {
            // Test event bus by emitting a test event
            final boolean[] received = {false};

            var subscription = AppCtx.BUS.on("test-event", event -> {
                System.out.println("✓ Test event received: " + event.type);
                received[0] = true;
            });

            // Create and emit test event
            var testEvent = new com.cottonlesergal.whisperclient.events.Event(
                    "test-event", "test-sender", "test-recipient", System.currentTimeMillis(), null
            );

            AppCtx.BUS.emit(testEvent);

            // Give it a moment to process
            Thread.sleep(100);

            if (received[0]) {
                System.out.println("✓ Event bus is working correctly");
            } else {
                System.out.println("✗ Event bus failed to deliver test event");
            }

            subscription.close();

        } catch (Exception e) {
            System.err.println("✗ Event bus test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testWebSocketConnection() {
        System.out.println("\n--- Testing WebSocket Connection ---");

        if (!Session.isValid()) {
            System.out.println("✗ Cannot test WebSocket - session is invalid");
            return;
        }

        try {
            InboxWs inbox = new InboxWs();

            System.out.println("Attempting to connect to: " + Config.DIR_WORKER);
            System.out.println("User: " + Session.me.getUsername());
            System.out.println("Token preview: " + Config.APP_TOKEN.substring(0, Math.min(20, Config.APP_TOKEN.length())) + "...");

            // Set up event listener for incoming messages
            var messageSubscription = AppCtx.BUS.on("chat", event -> {
                System.out.println("✓ Received chat message via WebSocket!");
                System.out.println("  From: " + event.from);
                System.out.println("  To: " + event.to);
                System.out.println("  Data: " + event.data);
            });

            // Connect
            inbox.connect(Config.DIR_WORKER, Session.me.getUsername(), Config.APP_TOKEN);

            // Wait a bit for connection
            Thread.sleep(2000);

            if (inbox.isConnected()) {
                System.out.println("✓ WebSocket connection established");

                // Send a test ping
                inbox.ping();
                System.out.println("✓ Sent ping to WebSocket");

            } else {
                System.out.println("✗ WebSocket connection failed");
                System.out.println("Connection info: " + inbox.getConnectionInfo());
            }

            // Clean up
            messageSubscription.close();
            inbox.disconnect();

        } catch (Exception e) {
            System.err.println("✗ WebSocket test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testMessageStorage() {
        System.out.println("\n--- Testing Message Storage ---");

        if (!Session.isValid()) {
            System.out.println("✗ Cannot test message storage - session is invalid");
            return;
        }

        try {
            MessageStorageService storage = MessageStorageService.getInstance();

            // Create test message
            String testFriend = "test-friend";
            MessageStorageService.ChatMessage testMessage =
                    MessageStorageService.ChatMessage.fromIncoming(testFriend, "Test message content");

            // Store message
            storage.storeMessage(testFriend, testMessage);
            System.out.println("✓ Stored test message");

            // Load messages
            var messages = storage.loadMessages(testFriend, 0, 10);

            if (!messages.isEmpty()) {
                System.out.println("✓ Successfully loaded " + messages.size() + " messages");
                System.out.println("  Latest message: " + messages.get(0).getContent());
            } else {
                System.out.println("✗ No messages found in storage");
            }

            int totalCount = storage.getMessageCount(testFriend);
            System.out.println("✓ Total message count for " + testFriend + ": " + totalCount);

        } catch (Exception e) {
            System.err.println("✗ Message storage test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void testDirectMessage() {
        System.out.println("\n--- Testing Direct Message Send ---");

        if (!Session.isValid()) {
            System.out.println("✗ Cannot test direct message - session is invalid");
            return;
        }

        try {
            DirectoryClient directory = new DirectoryClient();

            // Send a test message to yourself (if you have multiple clients)
            String testMessage = "Test message from debug: " + System.currentTimeMillis();

            directory.sendChat(Session.me.getUsername(), testMessage);
            System.out.println("✓ Sent test message to self: " + testMessage);

            // Wait a bit to see if we receive it
            Thread.sleep(3000);

        } catch (Exception e) {
            System.err.println("✗ Direct message test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Method to be called from the debug button
    public static void quickDebug() {
        System.out.println("\n=== QUICK DEBUG CHECK ===");

        System.out.println("1. Session: " + (Session.isValid() ? "VALID" : "INVALID"));
        System.out.println("2. Config: " + (Config.hasValidToken() ? "VALID" : "INVALID"));
        System.out.println("3. Current user: " + (Session.me != null ? Session.me.getUsername() : "NULL"));
        System.out.println("4. Token length: " + (Config.APP_TOKEN != null ? Config.APP_TOKEN.length() : 0));

        // Test event bus quickly
        final boolean[] eventReceived = {false};
        try {
            var sub = AppCtx.BUS.on("quick-test", e -> eventReceived[0] = true);
            AppCtx.BUS.emit(new com.cottonlesergal.whisperclient.events.Event("quick-test", null, null, 0, null));
            Thread.sleep(10);
            sub.close();
            System.out.println("5. Event Bus: " + (eventReceived[0] ? "WORKING" : "FAILED"));
        } catch (Exception e) {
            System.out.println("5. Event Bus: ERROR - " + e.getMessage());
        }

        System.out.println("=== END QUICK DEBUG ===\n");
    }
}