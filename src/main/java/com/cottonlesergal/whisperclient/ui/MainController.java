package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.UserProfile;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.cottonlesergal.whisperclient.services.*;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import java.util.Optional;
import java.util.UUID;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class MainController {
    @FXML private Label lblTitle;
    @FXML private TextField txtSearch;
    @FXML private ListView<UserSummary> listSearch;
    @FXML private ListView<UserSummary> listFriends;
    @FXML private ListView<UserSummary> listRequests;
    @FXML private AnchorPane chatHost;
    @FXML private HBox meCard;
    @FXML private ImageView imgMe;
    @FXML private Label lblMeName;
    @FXML private Label lblMeHandle;

    // Custom title bar controls
    @FXML private HBox customTitleBar;
    @FXML private HBox titleArea;
    @FXML private Label lblWindowTitle;
    @FXML private Label maxIcon;

    private final DirectoryClient directory = new DirectoryClient();
    private final CredentialsStorageService credentialsStorage = CredentialsStorageService.getInstance();
    private final MessageStorageService messageStorage = MessageStorageService.getInstance();
    private final NotificationManager notificationManager = NotificationManager.getInstance();
    private final RateLimiter rateLimiter = RateLimiter.getInstance();
    private final AtomicLong searchSeq = new AtomicLong();
    private InboxWs inbox;

    private ChatController currentChat;
    private UserSummary currentPeer;

    @FXML
    private void initialize() {
        setupCellFactories();
        setupEventHandlers();
        setupUI();
        setupCustomTitleBar();

        // Initialize notification manager
        Platform.runLater(() -> {
            if (customTitleBar.getScene() != null && customTitleBar.getScene().getWindow() instanceof Stage) {
                Stage stage = (Stage) customTitleBar.getScene().getWindow();
                notificationManager.initialize(stage);
            }
        });

        renderMe();
        refreshFriends();
        refreshPending();
    }

    private void setupCustomTitleBar() {
        Platform.runLater(() -> {
            if (customTitleBar.getScene() != null && customTitleBar.getScene().getWindow() instanceof Stage) {
                Stage stage = (Stage) customTitleBar.getScene().getWindow();
                WindowDecoratorService windowDecorator = new WindowDecoratorService();
                windowDecorator.decorateWindow(stage, titleArea);
            }
        });
    }

    // Window control methods
    @FXML
    private void minimizeWindow() {
        Stage stage = (Stage) customTitleBar.getScene().getWindow();
        if (stage != null) {
            stage.setIconified(true);
        }
    }

    @FXML
    private void toggleMaximize() {
        Stage stage = (Stage) customTitleBar.getScene().getWindow();
        if (stage != null) {
            if (stage.isMaximized()) {
                stage.setMaximized(false);
                maxIcon.setText("□"); // Maximize icon
            } else {
                stage.setMaximized(true);
                maxIcon.setText("❐"); // Restore icon - two overlapping squares
            }
        }
    }

    @FXML
    private void closeWindow() {
        Platform.exit();
    }

    private void setupCellFactories() {
        // Set up different cell factories for different list types
        listSearch.setCellFactory(v -> new UserListCell(UserListCell.MenuType.SEARCH));
        listFriends.setCellFactory(v -> new UserListCell(UserListCell.MenuType.FRIEND));
        listRequests.setCellFactory(v -> new UserListCell(UserListCell.MenuType.REQUEST));
    }

    private void setupEventHandlers() {
        // === MEDIA HANDLER - FOR HTTP MEDIA MESSAGES ===
        AppCtx.BUS.on("media-direct", ev -> Platform.runLater(() -> {
            System.out.println("[MainController] Received media notification from: " + ev.from);

            if (ev.data != null) {
                String fileName = ev.data.path("fileName").asText("");
                String mediaId = ev.data.path("mediaId").asText("");
                long size = ev.data.path("size").asLong(0);

                System.out.println("[MainController] Media details: " + fileName +
                        " (" + formatFileSize(size) + "), ID: " + mediaId);

                // Create media URL message format for storage and display
                String downloadUrl = ev.data.path("downloadUrl").asText("");
                String caption = ev.data.path("caption").asText("");
                String messageId = ev.data.path("id").asText("");

                String mediaUrlMessage = String.format("[MEDIA_URL:%s:%s:%s:%d:%s]%s",
                        messageId, fileName, ev.data.path("mimeType").asText(), size, downloadUrl,
                        caption.isEmpty() ? "" : "\n" + caption);

                // Store the incoming message
                ChatMessage incomingMessage = ChatMessage.fromIncoming(ev.from, mediaUrlMessage);
                messageStorage.storeMessage(ev.from, incomingMessage);

                // Show toast notification
                notificationManager.showMessageNotification(ev.from, "📎 " + fileName);

                // Refresh friends list to show notification badges
                refreshFriendsUI();

                // If we're currently chatting with this person, add to UI immediately and clear badge
                if (currentChat != null && currentPeer != null &&
                        currentPeer.getUsername().equalsIgnoreCase(ev.from)) {
                    System.out.println("[MainController] Adding media message to current chat UI");
                    currentChat.addMessageBubble(incomingMessage);
                    currentChat.scrollToBottom();

                    // Clear notification count since user is viewing the chat
                    notificationManager.clearNotificationCount(ev.from);
                    refreshFriendsUI();
                }
            }
        }));
        // === INCOMING MESSAGE HANDLER WITH RATE LIMITING ===
        AppCtx.BUS.on("chat", ev -> {
            // Check rate limiting first - don't even queue to UI thread if rate limited
            if (!rateLimiter.allowMessage(ev.from)) {
                // Message is rate limited - show a notification but don't process
                if (rateLimiter.getRemainingCooldown(ev.from) > 4000) { // Only show notification at start of cooldown
                    Platform.runLater(() -> {
                        notificationManager.showToast("Rate Limited",
                                ev.from + " is sending messages too quickly. Rate limited for 5 seconds.",
                                NotificationManager.ToastType.WARNING);
                    });
                }
                return; // Drop the message
            }

            // Process message on UI thread only if not rate limited
            Platform.runLater(() -> {
                System.out.println("[MainController] Received chat message from: " + ev.from);

                if (ev.data != null) {
                    String messageText = ev.data.path("text").asText("");
                    String messageId = ev.data.path("id").asText("");

                    System.out.println("[MainController] Message content length: " + messageText.length());

                    // Check if this is a media message
                    if (EnhancedMediaService.getInstance().isMediaMessage(messageText)) {
                        System.out.println("[MainController] Processing received media message");

                        // Extract media message
                        EnhancedMediaService.MediaMessage mediaMessage =
                                EnhancedMediaService.getInstance().extractMediaMessage(messageText);

                        if (mediaMessage != null) {
                            System.out.println("[MainController] Extracted media: " + mediaMessage.getFileName() +
                                    " (" + EnhancedMediaService.getInstance().formatFileSize(mediaMessage.getFileSize()) + ")");
                        }
                    }

                    // Store the incoming message
                    ChatMessage incomingMessage = ChatMessage.fromIncoming(ev.from, messageText);
                    messageStorage.storeMessage(ev.from, incomingMessage);

                    // Show toast notification and increment badge count
                    String displayText = messageText;
                    if (EnhancedMediaService.getInstance().isMediaMessage(messageText)) {
                        EnhancedMediaService.MediaMessage media =
                                EnhancedMediaService.getInstance().extractMediaMessage(messageText);
                        if (media != null) {
                            displayText = "📎 " + media.getFileName();
                        }
                    }

                    notificationManager.showMessageNotification(ev.from, displayText);

                    // Refresh friends list to show notification badges
                    refreshFriendsUI();

                    // If we're currently chatting with this person, add to UI immediately and clear badge
                    if (currentChat != null && currentPeer != null &&
                            currentPeer.getUsername().equalsIgnoreCase(ev.from)) {
                        System.out.println("[MainController] Adding message to current chat UI");
                        currentChat.addMessageBubble(incomingMessage);
                        currentChat.scrollToBottom();

                        // Clear notification count since user is viewing the chat
                        notificationManager.clearNotificationCount(ev.from);
                        refreshFriendsUI();
                    }
                }
            });
        });

        // === SIGNALING HANDLER - FOR WEBRTC ===
        AppCtx.BUS.on("signal", ev -> Platform.runLater(() -> {
            System.out.println("[MainController] Received signal from: " + ev.from +
                    ", kind: " + (ev.data != null ? ev.data.path("kind").asText("") : "unknown"));

            // Handle WebRTC signaling if needed
            // For now, just log it
        }));

        // Server-side events (from WebSocket)
        AppCtx.BUS.on("friend-request", ev -> Platform.runLater(() -> {
            System.out.println("[MainController] Received friend request from: " + ev.from);
            refreshPending();
            notificationManager.showFriendRequestNotification(ev.from);
        }));

        AppCtx.BUS.on("friend-accepted", ev -> Platform.runLater(() -> {
            System.out.println("[MainController] Friend request accepted by: " + ev.from);
            refreshFriends();
            refreshPending();
            notificationManager.showFriendAcceptedNotification(ev.from);
        }));

        AppCtx.BUS.on("friend-removed", ev -> Platform.runLater(() -> {
            refreshFriends();
            // If we were chatting with this user, clear the chat
            if (currentPeer != null && ev.data != null && ev.data.has("user")) {
                String removedUser = ev.data.path("user").asText("");
                if (currentPeer.getUsername().equalsIgnoreCase(removedUser)) {
                    clearChat();
                }
            }
            notificationManager.showFriendRemovedNotification(ev.from);
        }));

        AppCtx.BUS.on("user-blocked", ev -> Platform.runLater(() -> {
            refreshFriends();
            refreshPending();
            if (currentPeer != null && ev.data != null && ev.data.has("user")) {
                String blockedUser = ev.data.path("user").asText("");
                if (currentPeer.getUsername().equalsIgnoreCase(blockedUser)) {
                    clearChat();
                }
            }
            notificationManager.showBlockedNotification(ev.from);
        }));

        // Profile updates
        AppCtx.BUS.on("profile-updated", ev -> Platform.runLater(() -> {
            System.out.println("[MainController] Profile updated for: " + ev.from);
            // Refresh friend list to show updated profiles
            refreshFriends();
        }));

        // Client-side UI events (from context menus)
        AppCtx.BUS.on("open-chat", ev -> Platform.runLater(() -> {
            String targetUser = ev.data.path("targetUser").asText("");
            UserSummary user = findUserInFriends(targetUser);
            if (user != null) {
                // Clear notifications when opening chat
                notificationManager.clearNotificationCount(targetUser);
                openChatWith(user);
                refreshFriendsUI(); // Refresh to clear badges
            }
        }));

        AppCtx.BUS.on("remove-friend", ev -> Platform.runLater(() -> {
            String targetUser = ev.data.path("targetUser").asText("");
            removeFriendAsync(targetUser);
        }));

        AppCtx.BUS.on("accept-friend", ev -> Platform.runLater(() -> {
            String targetUser = ev.data.path("targetUser").asText("");
            acceptFriendAsync(targetUser);
        }));

        AppCtx.BUS.on("decline-friend", ev -> Platform.runLater(() -> {
            String targetUser = ev.data.path("targetUser").asText("");
            declineFriendAsync(targetUser);
        }));

        AppCtx.BUS.on("block-user", ev -> Platform.runLater(() -> {
            String targetUser = ev.data.path("targetUser").asText("");
            blockUserAsync(targetUser);
        }));

        AppCtx.BUS.on("send-friend-request", ev -> Platform.runLater(() -> {
            String targetUser = ev.data.path("targetUser").asText("");
            sendFriendRequestAsync(targetUser);
        }));
    }

    private void setupUI() {
        // Live search with debounce
        txtSearch.textProperty().addListener((obs, old, q) -> {
            final long id = searchSeq.incrementAndGet();
            if (q == null || q.trim().length() < 2) {
                listSearch.getItems().clear();
                return;
            }
            CompletableFuture
                    .supplyAsync(() -> directory.search(q.trim()))
                    .thenAccept(results -> Platform.runLater(() -> {
                        if (id == searchSeq.get()) listSearch.getItems().setAll(results);
                    }));
        });

        // Double-click to open chat for friends
        listFriends.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                var sel = listFriends.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    // Clear notifications when opening chat
                    notificationManager.clearNotificationCount(sel.getUsername());
                    openChatWith(sel);
                    refreshFriendsUI(); // Refresh to clear badges
                }
            }
        });
    }

    // Async friend management methods
    private void removeFriendAsync(String username) {
        CompletableFuture.runAsync(() -> {
            directory.removeFriend(username);
        }).thenRun(() -> Platform.runLater(() -> {
            refreshFriends();
            notificationManager.showSuccessNotification("Friend Removed", "Removed " + username + " from friends");
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                notificationManager.showErrorNotification("Failed to remove friend", throwable.getMessage());
            });
            return null;
        });
    }

    private void acceptFriendAsync(String username) {
        CompletableFuture.supplyAsync(() -> {
            return directory.acceptFriend(username);
        }).thenAccept(success -> Platform.runLater(() -> {
            if (success) {
                refreshPending();
                refreshFriends();
                notificationManager.showSuccessNotification("Friend Added", "You are now friends with " + username);
            } else {
                notificationManager.showErrorNotification("Failed to accept friend request", "Could not accept request from " + username);
            }
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                notificationManager.showErrorNotification("Failed to accept friend request", throwable.getMessage());
            });
            return null;
        });
    }

    private void declineFriendAsync(String username) {
        CompletableFuture.supplyAsync(() -> {
            return directory.declineFriend(username);
        }).thenAccept(success -> Platform.runLater(() -> {
            if (success) {
                refreshPending();
                notificationManager.showSuccessNotification("Request Declined", "Declined friend request from " + username);
            } else {
                notificationManager.showErrorNotification("Failed to decline request", "Could not decline request from " + username);
            }
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                notificationManager.showErrorNotification("Failed to decline request", throwable.getMessage());
            });
            return null;
        });
    }

    private void blockUserAsync(String username) {
        CompletableFuture.supplyAsync(() -> {
            return directory.blockUser(username);
        }).thenAccept(success -> Platform.runLater(() -> {
            if (success) {
                refreshFriends();
                refreshPending();
                notificationManager.showSuccessNotification("User Blocked", "Blocked " + username);
            } else {
                notificationManager.showErrorNotification("Failed to block user", "Could not block " + username);
            }
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                notificationManager.showErrorNotification("Failed to block user", throwable.getMessage());
            });
            return null;
        });
    }

    private void sendFriendRequestAsync(String username) {
        CompletableFuture.supplyAsync(() -> {
            return directory.sendFriendRequest(username);
        }).thenAccept(success -> Platform.runLater(() -> {
            if (success) {
                notificationManager.showSuccessNotification("Request Sent", "Friend request sent to " + username);
            } else {
                notificationManager.showErrorNotification("Failed to send request", "Could not send friend request to " + username);
            }
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                notificationManager.showErrorNotification("Failed to send request", throwable.getMessage());
            });
            return null;
        });
    }

    // Utility methods
    private UserSummary findUserInFriends(String username) {
        return listFriends.getItems().stream()
                .filter(user -> user.getUsername().equalsIgnoreCase(username))
                .findFirst()
                .orElse(null);
    }

    private void showNotification(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    public void setMe(UserProfile me) {
        Session.me = me;
        Session.token = Config.APP_TOKEN; // Ensure session token is set
        renderMe();

        // Initialize WebSocket connection
        connectToInbox();
    }

    private void connectToInbox() {
        if (Session.me == null || Config.APP_TOKEN == null || Config.APP_TOKEN.isEmpty()) {
            System.err.println("[MainController] Cannot connect to inbox: missing user or token");
            return;
        }

        if (inbox == null) inbox = new InboxWs();

        System.out.println("[MainController] Connecting to inbox for user: " + Session.me.getUsername());
        System.out.println("[MainController] Using token: " +
                Config.APP_TOKEN.substring(0, Math.min(20, Config.APP_TOKEN.length())) + "...");

        inbox.connect(Config.DIR_WORKER, Session.me.getUsername(), Config.APP_TOKEN);
    }

    // Debug method for testing connection
    @FXML
    private void debugConnection() {
        System.out.println("=== DEBUG CONNECTION STATUS ===");

        // Show rate limiter stats
        System.out.println("\n" + rateLimiter.getStats());

        // Import the debug class
        try {
            // Use reflection to avoid compile issues if debug class is missing
            Class<?> debugClass = Class.forName("com.cottonlesergal.whisperclient.debug.DebugMessageTest");
            var quickDebugMethod = debugClass.getMethod("quickDebug");
            quickDebugMethod.invoke(null);

            var testDirectMessageMethod = debugClass.getMethod("testDirectMessage");
            testDirectMessageMethod.invoke(null);

        } catch (Exception e) {
            // Fallback to basic debug if the debug class doesn't exist
            System.out.println("Session.me: " + (Session.me != null ? Session.me.getUsername() : "null"));
            System.out.println("APP_TOKEN: " + (Config.APP_TOKEN != null && !Config.APP_TOKEN.isEmpty() ?
                    "exists (" + Config.APP_TOKEN.length() + " chars)" : "missing"));
            System.out.println("Inbox: " + (inbox != null ? "created" : "null"));

            if (inbox != null) {
                System.out.println("WebSocket connected: " + inbox.isConnected());
                System.out.println("Connection info: " + inbox.getConnectionInfo());
            }

            // Test the connection by trying to reconnect
            if (Session.me != null && Config.APP_TOKEN != null && !Config.APP_TOKEN.isEmpty()) {
                System.out.println("Attempting to reconnect...");
                connectToInbox();

                // Test sending a message to self
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(2000); // Wait for connection
                        directory.sendChat(Session.me.getUsername(), "Debug test message: " + System.currentTimeMillis());
                        System.out.println("Sent debug test message to self");
                    } catch (Exception ex) {
                        System.err.println("Failed to send debug message: " + ex.getMessage());
                    }
                });
            }
        }
    }

    /**
     * DEBUG: Delete all locally stored messages
     */
    @FXML
    private void debugDeleteAllMessages() {
        System.out.println("[DEBUG] Delete all messages requested");

        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Delete All Messages");
        confirmAlert.setHeaderText("Are you sure?");
        confirmAlert.setContentText("This will permanently delete ALL locally stored messages for ALL conversations.\n\nA backup will be created automatically.");

        Optional<ButtonType> result = confirmAlert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            // Run in background thread
            new Thread(() -> {
                boolean success = MessageStorageUtility.getInstance().deleteAllMessages();

                Platform.runLater(() -> {
                    if (success) {
                        showInfoAlert("Success", "All messages deleted successfully",
                                "All local message storage has been cleared. A backup was created.");

                        // Clear UI if chat view is open
                        if (currentChat != null) {
                            currentChat.clearAllMessages();
                        }

                    } else {
                        showErrorAlert("Error", "Failed to delete messages",
                                "Some messages could not be deleted. Check the console for details.");
                    }
                });
            }).start();
        }
    }

    /**
     * DEBUG: Delete messages for current conversation
     */
    @FXML
    private void debugDeleteCurrentConversation() {
        UserSummary selectedFriend = listFriends.getSelectionModel().getSelectedItem();

        if (selectedFriend == null) {
            showErrorAlert("No Conversation", "No conversation selected",
                    "Please select a friend/conversation first.");
            return;
        }

        String username = selectedFriend.getUsername();
        System.out.println("[DEBUG] Delete conversation with " + username + " requested");

        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Delete Conversation");
        confirmAlert.setHeaderText("Delete conversation with " + username + "?");
        confirmAlert.setContentText("This will permanently delete all messages in this conversation.\n\nA backup will be created automatically.");

        Optional<ButtonType> result = confirmAlert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            // Run in background thread
            new Thread(() -> {
                boolean success = MessageStorageUtility.getInstance().deleteConversationMessages(username);

                Platform.runLater(() -> {
                    if (success) {
                        showInfoAlert("Success", "Conversation deleted",
                                "All messages with " + username + " have been deleted.");

                        // Clear UI
                        if (currentChat != null) {
                            currentChat.clearAllMessages();
                        }

                    } else {
                        showErrorAlert("Error", "Failed to delete conversation",
                                "Could not delete conversation messages. Check the console for details.");
                    }
                });
            }).start();
        }
    }

    /**
     * DEBUG: Show storage information
     */
    @FXML
    private void debugShowStorageInfo() {
        System.out.println("[DEBUG] Storage info requested");
        MessageStorageUtility.getInstance().printStorageDebugInfo();

        MessageStorageUtility.StorageStats stats = MessageStorageUtility.getInstance().getStorageStats();

        String info = String.format(
                "Storage Information:\n\n" +
                        "Conversations: %d\n" +
                        "Total Messages: %d\n" +
                        "Storage Size: %s\n" +
                        "Backups: %d\n\n" +
                        "See console for detailed file listing.",
                stats.conversationCount,
                stats.totalMessages,
                formatBytes(stats.totalSizeBytes),
                stats.backupCount
        );

        showInfoAlert("Storage Information", "Current storage status", info);
    }

    /**
     * DEBUG: Show chunking service status
     */
    @FXML
    private void debugShowChunkingStatus() {
        System.out.println("[DEBUG] Chunking service status requested");
        MessageChunkingService.getInstance().printBufferStatus();
        MessageChunkingService.getInstance().cleanupOldMessages();

        showInfoAlert("Chunking Service", "Buffer status printed to console",
                "Check the console for detailed chunking service information.");
    }

    /**
     * DEBUG: Test message chunking
     */
    @FXML
    private void debugTestMessageChunking() {
        System.out.println("[DEBUG] Testing message chunking...");

        // Create a large test message
        StringBuilder largeMessage = new StringBuilder();
        largeMessage.append("$MEDIA${\"id\":\"test-media\",\"type\":\"image\",\"mime\":\"image/png\",\"name\":\"test.png\",\"size\":1000000,\"data\":\"");

        // Add lots of base64-like data to make it large
        String testData = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==";
        for (int i = 0; i < 1000; i++) { // Make it big enough to require chunking
            largeMessage.append(testData);
        }
        largeMessage.append("\"}");

        String originalMessage = largeMessage.toString();
        System.out.println("[DEBUG] Original message size: " + originalMessage.length() + " bytes");

        // Test chunking
        String[] chunks = MessageChunkingService.getInstance().splitMessage(originalMessage);
        System.out.println("[DEBUG] Message split into " + chunks.length + " chunks");

        // Test reassembly
        for (String chunk : chunks) {
            String result = MessageChunkingService.getInstance().processReceivedMessage(chunk);
            if (result != null) {
                System.out.println("[DEBUG] Reassembled message size: " + result.length() + " bytes");
                System.out.println("[DEBUG] Messages match: " + originalMessage.equals(result));
                break;
            }
        }

        showInfoAlert("Chunking Test", "Test completed",
                "Chunking test completed. Check console for results.");
    }

    /**
     * DEBUG: Create backup
     */
    @FXML
    private void debugCreateBackup() {
        System.out.println("[DEBUG] Creating manual backup...");

        new Thread(() -> {
            String backupPath = MessageStorageUtility.getInstance().createBackup("manual_backup");

            Platform.runLater(() -> {
                if (backupPath != null) {
                    showInfoAlert("Backup Created", "Manual backup completed",
                            "Backup created at:\n" + backupPath);
                } else {
                    showErrorAlert("Backup Failed", "Could not create backup",
                            "Failed to create backup. Check console for details.");
                }
            });
        }).start();
    }

    /**
     * DEBUG: Send test chunked message to self
     */
    @FXML
    private void debugSendTestChunkedMessage() {
        if (Session.me == null) {
            showErrorAlert("Not Logged In", "No session", "You must be logged in to send test messages.");
            return;
        }

        System.out.println("[DEBUG] Sending test chunked message to self...");

        // Create a large image message using SimpleMediaService
        SimpleMediaService mediaService = SimpleMediaService.getInstance();

        // Create test media message
        SimpleMediaService.SimpleMediaMessage testMedia = new SimpleMediaService.SimpleMediaMessage();
        testMedia.setId(UUID.randomUUID().toString());
        testMedia.setType("image");
        testMedia.setMimeType("image/png");
        testMedia.setFileName("test_large_image.png");
        testMedia.setSize(2 * 1024 * 1024); // 2MB

        // Create large base64 data (simulate large image)
        StringBuilder largeData = new StringBuilder();
        String sampleData = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==";
        for (int i = 0; i < 5000; i++) {
            largeData.append(sampleData);
        }
        testMedia.setData(largeData.toString());
        testMedia.setChecksum("test-checksum-" + System.currentTimeMillis());
        testMedia.setTimestamp(System.currentTimeMillis());

        String mediaMessageText = mediaService.createMediaMessageText(testMedia);
        System.out.println("[DEBUG] Created test media message (" + mediaMessageText.length() + " bytes)");

        // Send to self
        new Thread(() -> {
            try {
                directory.sendChat(Session.me.getUsername(), mediaMessageText);
                System.out.println("[DEBUG] Test chunked message sent successfully");
            } catch (Exception e) {
                System.err.println("[DEBUG] Failed to send test message: " + e.getMessage());
            }
        }).start();

        showInfoAlert("Test Message", "Sending test chunked message",
                "A large test message is being sent to yourself. Check the console and chat for results.");
    }

    /**
     * DEBUG: Clear chunking service buffer
     */
    @FXML
    private void debugClearChunkingBuffer() {
        System.out.println("[DEBUG] Clearing chunking service buffer...");

        MessageChunkingService chunkingService = MessageChunkingService.getInstance();
        chunkingService.cleanupOldMessages();

        // Force clear all incomplete messages by setting timeout to 0
        chunkingService.printBufferStatus();

        showInfoAlert("Buffer Cleared", "Chunking buffer cleared",
                "All incomplete chunked messages have been cleared from the buffer.");
    }

    // Helper methods
    private void showInfoAlert(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void showErrorAlert(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void renderMe() {
        if (Session.me == null) {
            meCard.setVisible(false);
            meCard.setManaged(false);
            return;
        }
        meCard.setVisible(true);
        meCard.setManaged(true);
        var me = Session.me;
        String display = (me.getDisplayName() == null || me.getDisplayName().isBlank())
                ? me.getUsername() : me.getDisplayName();
        lblMeName.setText(display);
        lblMeHandle.setText("@" + me.getUsername());
        imgMe.setImage(AvatarCache.get(me.getAvatarUrl(), 32));
    }

    private void refreshFriends() {
        CompletableFuture.supplyAsync(() -> directory.friends())
                .thenAccept(friends -> Platform.runLater(() -> {
                    listFriends.getItems().setAll(friends);
                    System.out.println("[MainController] Refreshed friends list: " + friends.size() + " friends");
                }));
    }

    private void refreshFriendsUI() {
        // Refresh the UI to update notification badges
        Platform.runLater(() -> {
            // Force refresh of list cells to update badges
            listFriends.refresh();
        });
    }

    private void refreshPending() {
        CompletableFuture.supplyAsync(() -> directory.pending())
                .thenAccept(pending -> Platform.runLater(() -> {
                    listRequests.getItems().setAll(pending);
                    System.out.println("[MainController] Refreshed pending requests: " + pending.size() + " requests");
                }));
    }

    @FXML private void onAddFriend() {
        String q = txtSearch.getText();
        if (q != null && !q.isBlank()) {
            sendFriendRequestAsync(q.trim());
        }
    }

    @FXML private void onAcceptRequest() {
        var sel = listRequests.getSelectionModel().getSelectedItem();
        if (sel != null) {
            acceptFriendAsync(sel.getUsername());
        }
    }

    @FXML private void onRemoveFriend() {
        var sel = listFriends.getSelectionModel().getSelectedItem();
        if (sel != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Are you sure you want to remove " + sel.getDisplay() + " as a friend?",
                    ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(response -> {
                if (response == ButtonType.YES) {
                    removeFriendAsync(sel.getUsername());
                }
            });
        }
    }

    @FXML
    private void onSettings() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/cottonlesergal/whisperclient/fxml/settings.fxml"));

            Stage settingsStage = new Stage();
            settingsStage.setTitle("Settings");
            settingsStage.initModality(Modality.APPLICATION_MODAL);
            settingsStage.initOwner(lblTitle.getScene().getWindow());

            javafx.scene.Scene scene = new javafx.scene.Scene(loader.load(), 900, 600);
            scene.getStylesheets().add(getClass().getResource("/com/cottonlesergal/whisperclient/css/app.css").toExternalForm());

            settingsStage.setScene(scene);
            settingsStage.setResizable(false);
            settingsStage.show();

        } catch (Exception e) {
            e.printStackTrace();
            showSimpleSettingsDialog();
        }
    }

    @FXML
    private void onLogout() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Are you sure you want to sign out?",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Sign Out");
        confirm.setHeaderText("Sign Out of WhisperClient");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                performLogout();
            }
        });
    }

    private void showSimpleSettingsDialog() {
        Alert settingsDialog = new Alert(Alert.AlertType.INFORMATION);
        settingsDialog.setTitle("Settings");
        settingsDialog.setHeaderText("WhisperClient Settings");

        // Create content with current user info and basic options
        StringBuilder content = new StringBuilder();
        if (Session.me != null) {
            content.append("Signed in as: ").append(Session.me.getDisplayName()).append("\n");
            content.append("Username: @").append(Session.me.getUsername()).append("\n");
            content.append("Provider: ").append(Session.me.getProvider()).append("\n\n");
        }

        content.append("Auto sign-in: ").append(credentialsStorage.hasCredentials() ? "Enabled" : "Disabled").append("\n");
        content.append("Message storage: Encrypted locally\n");
        content.append("Connection: Secure WebSocket");

        settingsDialog.setContentText(content.toString());

        // Add disable auto sign-in option if enabled
        if (credentialsStorage.hasCredentials()) {
            ButtonType disableAutoSignIn = new ButtonType("Disable Auto Sign-In");
            settingsDialog.getButtonTypes().add(disableAutoSignIn);

            settingsDialog.showAndWait().ifPresent(response -> {
                if (response == disableAutoSignIn) {
                    credentialsStorage.clearCredentials();
                    showNotification("Auto Sign-In Disabled", "You will need to sign in manually next time");
                }
            });
        } else {
            settingsDialog.showAndWait();
        }
    }

    private void performLogout() {
        try {
            // Disconnect WebSocket
            if (inbox != null) {
                inbox.disconnect();
                inbox = null;
            }

            // Clear credentials to disable auto sign-in
            credentialsStorage.clearCredentials();

            // Clear session data
            Session.me = null;
            Session.token = null;
            Config.APP_TOKEN = "";

            // Clear UI state
            clearChat();
            listFriends.getItems().clear();
            listRequests.getItems().clear();
            listSearch.getItems().clear();

            // Navigate back to login screen
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/cottonlesergal/whisperclient/fxml/login.fxml")
            );

            Stage stage = (Stage) lblTitle.getScene().getWindow();
            Scene loginScene = new Scene(loader.load(), 1080, 720);
            loginScene.getStylesheets().add(
                    getClass().getResource("/com/cottonlesergal/whisperclient/css/app.css").toExternalForm()
            );

            stage.setScene(loginScene);
            stage.setTitle("WhisperClient");

            System.out.println("[MainController] Successfully logged out");

        } catch (Exception e) {
            e.printStackTrace();
            showError("Logout Error", "Failed to return to login screen: " + e.getMessage());
        }
    }

    private void openChatWith(UserSummary peer) {
        try {
            lblTitle.setText("DM with @" + peer.getUsername());
            currentPeer = peer;

            // Clear notification count when opening chat
            notificationManager.clearNotificationCount(peer.getUsername());
            refreshFriendsUI();

            // Try different possible FXML locations
            String[] possiblePaths = {
                    "/com/cottonlesergal/whisperclient/fxml/chat.fxml",
                    "/com/cottonlesergal/whisperclient/fxml/chat_view.fxml",
                    "chat.fxml",
                    "chat_view.fxml"
            };

            FXMLLoader fx = null;
            for (String path : possiblePaths) {
                var resource = getClass().getResource(path);
                if (resource != null) {
                    fx = new FXMLLoader(resource);
                    System.out.println("[MainController] Found FXML at: " + path);
                    break;
                }
            }

            if (fx == null) {
                notificationManager.showErrorNotification("Chat FXML not found", "Could not locate chat.fxml in resources");
                return;
            }

            AnchorPane pane = fx.load();
            currentChat = fx.getController();

            // Create Friend object with avatar information
            var f = new com.cottonlesergal.whisperclient.models.Friend(
                    peer.getUsername(),
                    "", // pubkey - not needed for display
                    "", // inboxUrl - not needed for display
                    peer.getDisplay(),
                    peer.getAvatar() // avatar URL
            );
            currentChat.bindFriend(f);

            chatHost.getChildren().setAll(pane);
            AnchorPane.setTopAnchor(pane, 0.0);
            AnchorPane.setRightAnchor(pane, 0.0);
            AnchorPane.setBottomAnchor(pane, 0.0);
            AnchorPane.setLeftAnchor(pane, 0.0);
        } catch (Exception e) {
            e.printStackTrace();
            notificationManager.showErrorNotification("Failed to open chat", e.getMessage());
        }
    }

    private void clearChat() {
        chatHost.getChildren().clear();
        currentChat = null;
        currentPeer = null;
        lblTitle.setText("Whisper");
    }

    // === TEST METHODS FOR NOTIFICATION SYSTEM ===
    @FXML
    private void testNotificationBadges() {
        System.out.println("[MainController] Testing notification badges...");

        // Test different badge counts
        if (!listFriends.getItems().isEmpty()) {
            UserSummary firstFriend = listFriends.getItems().get(0);
            String username = firstFriend.getUsername();

            // Test single digit
            notificationManager.incrementNotificationCount(username);
            refreshFriendsUI();

            // Wait and test double digit after 2 seconds
            Platform.runLater(() -> {
                new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                        Platform.runLater(() -> {
                            // Add 14 more to make it 15
                            for (int i = 0; i < 14; i++) {
                                notificationManager.incrementNotificationCount(username);
                            }
                            refreshFriendsUI();
                            System.out.println("Badge should now show: 15");
                        });

                        Thread.sleep(2000);
                        Platform.runLater(() -> {
                            // Add 85 more to make it 100 (which shows as 99+)
                            for (int i = 0; i < 85; i++) {
                                notificationManager.incrementNotificationCount(username);
                            }
                            refreshFriendsUI();
                            System.out.println("Badge should now show: 99+");
                        });

                        Thread.sleep(3000);
                        Platform.runLater(() -> {
                            // Clear the badge
                            notificationManager.clearNotificationCount(username);
                            refreshFriendsUI();
                            System.out.println("Badge should now be hidden");
                        });

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            });
        } else {
            System.out.println("No friends to test badges with");
        }
    }

    @FXML
    private void testToastNotifications() {
        System.out.println("[MainController] Testing Discord-style toast notifications...");

        // Test message notification (Discord style)
        notificationManager.showMessageNotification("Jam3s", "tghf");

        // Delay between toasts
        Platform.runLater(() -> {
            new Thread(() -> {
                try {
                    Thread.sleep(1500);
                    Platform.runLater(() -> notificationManager.showMessageNotification("TestUser",
                            "This is a longer message to test how the notification handles wrapping text content"));

                    Thread.sleep(1500);
                    Platform.runLater(() -> notificationManager.showFriendRequestNotification("NewFriend"));

                    Thread.sleep(1500);
                    Platform.runLater(() -> notificationManager.showSuccessNotification("Success",
                            "Friend request accepted!"));

                    Thread.sleep(1500);
                    Platform.runLater(() -> notificationManager.showErrorNotification("Connection Error",
                            "Unable to connect to server"));

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });
    }

    // === RATE LIMITING TEST METHODS ===
    @FXML
    private void testRateLimiting() {
        System.out.println("[MainController] Testing rate limiting...");

        if (!listFriends.getItems().isEmpty()) {
            UserSummary firstFriend = listFriends.getItems().get(0);
            String username = firstFriend.getUsername();

            // Simulate rapid messages from this user
            Platform.runLater(() -> {
                new Thread(() -> {
                    System.out.println("Simulating 10 rapid messages from " + username);
                    for (int i = 1; i <= 10; i++) {
                        try {
                            // Create mock event
                            var mockData = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                                    .put("text", "Spam message " + i)
                                    .put("id", "test-" + i);

                            var mockEvent = new com.cottonlesergal.whisperclient.events.Event(
                                    "chat", username, Session.me.getUsername(), System.currentTimeMillis(), mockData
                            );

                            // Emit the event (will be rate limited)
                            AppCtx.BUS.emit(mockEvent);

                            Thread.sleep(100); // 10 messages per second - way over the limit
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    System.out.println("Rate limiting test completed. Check console for rate limit messages.");
                }).start();
            });
        } else {
            System.out.println("No friends to test rate limiting with");
        }
    }

    @FXML
    private void testMediaMessage() {
        System.out.println("[MainController] Testing media message format...");

        // Create a test media message
        try {
            EnhancedMediaService.MediaMessage testMedia = new EnhancedMediaService.MediaMessage();
            testMedia.setMessageId("test-123");
            testMedia.setMediaType("image");
            testMedia.setMimeType("image/png");
            testMedia.setFileName("test_image.png");
            testMedia.setFileSize(1024 * 1024); // 1MB
            testMedia.setBase64Data("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg=="); // 1x1 pixel PNG
            testMedia.setChecksum("test-checksum");
            testMedia.setTimestamp(System.currentTimeMillis());

            String mediaMessageText = EnhancedMediaService.getInstance().createMediaMessageText(testMedia);
            System.out.println("Created media message: " + mediaMessageText.substring(0, Math.min(200, mediaMessageText.length())) + "...");

            // Test extraction
            EnhancedMediaService.MediaMessage extracted = EnhancedMediaService.getInstance().extractMediaMessage(mediaMessageText);
            if (extracted != null) {
                System.out.println("✓ Successfully extracted media message:");
                System.out.println("  File: " + extracted.getFileName());
                System.out.println("  Size: " + EnhancedMediaService.getInstance().formatFileSize(extracted.getFileSize()));
                System.out.println("  Type: " + extracted.getMediaType());
            } else {
                System.out.println("✗ Failed to extract media message");
            }

            // Test sending to self
            if (Session.me != null) {
                directory.sendChat(Session.me.getUsername(), mediaMessageText);
                System.out.println("Sent test media message to self");
            }

        } catch (Exception e) {
            System.err.println("Media test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @FXML
    private void checkWebSocketHealth() {
        System.out.println("[MainController] Checking WebSocket health...");

        if (inbox != null) {
            System.out.println("WebSocket status: " + inbox.getConnectionInfo());

            // Send a simple ping
            inbox.ping();

            // Send a test JSON message
            if (Session.me != null) {
                directory.sendChat(Session.me.getUsername(), "WebSocket health check: " + System.currentTimeMillis());
            }
        } else {
            System.out.println("WebSocket is null - not connected");
        }
    }

    @FXML
    private void clearRateLimits() {
        rateLimiter.clearAllRateLimits();
        notificationManager.showSuccessNotification("Rate Limits Cleared", "All rate limits have been reset");
        System.out.println("[MainController] Cleared all rate limits");
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}