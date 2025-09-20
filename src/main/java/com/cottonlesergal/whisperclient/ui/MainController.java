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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    // Authentication refresh fields
    private Timer authRefreshTimer;
    private boolean isRefreshingAuth = false;
    private AuthService authService = new AuthService();
    private Stage primaryStage;

    private ChatController currentChat;
    private UserSummary currentPeer;

    // Add deduplication for event processing
    private boolean eventHandlersSetup = false;
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    @FXML
    private void initialize() {
        setupCellFactories();

        // Only setup event handlers once to prevent duplicates
        if (!eventHandlersSetup) {
            setupEventHandlers();
            eventHandlersSetup = true;
        }

        setupUI();
        setupCustomTitleBar();
        startAutomaticAuthRefresh();

        // Initialize notification manager
        Platform.runLater(() -> {
            if (customTitleBar.getScene() != null && customTitleBar.getScene().getWindow() instanceof Stage) {
                Stage stage = (Stage) customTitleBar.getScene().getWindow();
                notificationManager.initialize(stage);
                primaryStage = stage;
            }
        });

        renderMe();
        refreshFriends();
        refreshPending();
    }

    // ============== AUTHENTICATION REFRESH METHODS ==============

    private void startAutomaticAuthRefresh() {
        if (authRefreshTimer != null) {
            authRefreshTimer.cancel();
        }

        authRefreshTimer = new Timer("AuthRefreshTimer", true);

        authRefreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (Session.me != null && Config.APP_TOKEN != null && !isRefreshingAuth) {
                    checkAndRefreshAuth();
                }
            }
        }, 30000, 30000);

        System.out.println("[MainController] Started automatic auth refresh monitoring");
    }

    private void checkAndRefreshAuth() {
        try {
            if (AuthService.isTokenExpired()) {
                System.out.println("[MainController] Token expired or expiring soon, attempting automatic refresh...");
                performAutomaticTokenRefresh();
                return;
            }

            HttpRequest req = HttpRequest.newBuilder(URI.create(Config.DIR_WORKER + "/friends"))
                    .header("authorization", "Bearer " + Config.APP_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                System.out.println("[MainController] Token expired, attempting automatic refresh...");
                performAutomaticTokenRefresh();
            } else if (response.statusCode() == 200) {
                resetAuthErrorState();
            }

        } catch (Exception e) {
            System.err.println("[MainController] Auth check failed: " + e.getMessage());
        }
    }

    private void performAutomaticTokenRefresh() {
        if (isRefreshingAuth) {
            System.out.println("[MainController] Auth refresh already in progress, skipping");
            return;
        }

        isRefreshingAuth = true;
        String provider = Session.me != null ? Session.me.getProvider() : "google";

        authService.refreshTokenSilently(provider).thenAccept(success -> {
            Platform.runLater(() -> {
                if (success) {
                    System.out.println("[MainController] Token automatically refreshed successfully");

                    new Thread(() -> {
                        try {
                            boolean registered = directory.registerOrUpdate(Session.me);
                            if (registered) {
                                Platform.runLater(() -> {
                                    reconnectWebSocket();
                                    refreshFriends();

                                    if (notificationManager != null) {
                                        notificationManager.showToast("Connection", "Reconnected successfully",
                                                NotificationManager.ToastType.SUCCESS);
                                    }
                                });
                            }
                        } catch (Exception e) {
                            System.err.println("[MainController] Re-registration after token refresh failed: " + e.getMessage());
                        } finally {
                            isRefreshingAuth = false;
                        }
                    }).start();

                } else {
                    System.err.println("[MainController] Silent token refresh failed - attempting re-sign in");
                    attemptAutomaticReSignIn();
                }
            });
        }).exceptionally(throwable -> {
            Platform.runLater(() -> {
                System.err.println("[MainController] Token refresh failed: " + throwable.getMessage());
                performLegacyAuthRefresh();
            });
            return null;
        });
    }

    private void attemptAutomaticReSignIn() {
        if (Session.me == null) return;

        String provider = Session.me.getProvider();

        new Thread(() -> {
            try {
                System.out.println("[MainController] Attempting automatic re-sign in with " + provider);

                UserProfile refreshedUser = authService.signIn(primaryStage, provider);

                if (refreshedUser != null) {
                    Platform.runLater(() -> {
                        System.out.println("[MainController] Automatic re-sign in successful");
                        Session.me = refreshedUser;

                        new Thread(() -> {
                            try {
                                boolean registered = directory.registerOrUpdate(refreshedUser);
                                if (registered) {
                                    Platform.runLater(() -> {
                                        reconnectWebSocket();
                                        refreshFriends();
                                        refreshPending();

                                        if (notificationManager != null) {
                                            notificationManager.showToast("Authentication", "Automatically signed in again",
                                                    NotificationManager.ToastType.SUCCESS);
                                        }
                                    });
                                }
                            } catch (Exception e) {
                                System.err.println("[MainController] Re-registration after re-sign in failed: " + e.getMessage());
                            } finally {
                                isRefreshingAuth = false;
                            }
                        }).start();
                    });
                } else {
                    Platform.runLater(() -> {
                        isRefreshingAuth = false;
                        System.err.println("[MainController] Automatic re-sign in failed");

                        if (notificationManager != null) {
                            notificationManager.showErrorNotification("Authentication Required",
                                    "Please restart the app to refresh your authentication.");
                        }
                    });
                }

            } catch (Exception e) {
                Platform.runLater(() -> {
                    isRefreshingAuth = false;
                    System.err.println("[MainController] Exception during automatic re-sign in: " + e.getMessage());
                    performLegacyAuthRefresh();
                });
            }
        }).start();
    }

    private void performLegacyAuthRefresh() {
        new Thread(() -> {
            try {
                System.out.println("[MainController] Attempting legacy auth refresh...");

                boolean success = directory.registerOrUpdate(Session.me);

                if (success) {
                    System.out.println("[MainController] Authentication automatically refreshed successfully");

                    Platform.runLater(() -> {
                        reconnectWebSocket();
                        refreshFriends();

                        if (notificationManager != null) {
                            notificationManager.showToast("Connection", "Reconnected successfully",
                                    NotificationManager.ToastType.SUCCESS);
                        }
                    });

                } else {
                    System.err.println("[MainController] Automatic auth refresh failed");

                    Platform.runLater(() -> {
                        if (notificationManager != null) {
                            notificationManager.showErrorNotification("Connection Issue",
                                    "Authentication refresh failed. Some features may not work properly.");
                        }
                    });
                }

            } catch (Exception e) {
                System.err.println("[MainController] Exception during auth refresh: " + e.getMessage());
                e.printStackTrace();

            } finally {
                isRefreshingAuth = false;
            }
        }).start();
    }

    public void on401Error(String context) {
        System.out.println("[MainController] Detected 401 error in: " + context);

        if (!isRefreshingAuth) {
            Platform.runLater(() -> {
                handleAuthFailure(context);
            });
        }
    }

    public void handleAuthFailure(String context) {
        System.out.println("[MainController] Handling auth failure in context: " + context);

        if (!isRefreshingAuth) {
            performAutomaticTokenRefresh();
        } else {
            System.out.println("[MainController] Auth refresh already in progress");
        }
    }

    private void reconnectWebSocket() {
        if (inbox != null) {
            System.out.println("[MainController] Reconnecting WebSocket after auth refresh...");
            inbox.disconnect();

            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    Platform.runLater(() -> {
                        inbox = null;
                        connectToInbox();
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    private void resetAuthErrorState() {
        // Reset any error flags or UI states here if needed
    }

    // ============== CONNECTION AND UI SETUP ==============

    private void connectToInbox() {
        if (Session.me == null || Config.APP_TOKEN == null || Config.APP_TOKEN.isEmpty()) {
            System.err.println("[MainController] Cannot connect to inbox: missing user or token");

            if (Session.me != null) {
                handleAuthFailure("missing_token");
            }
            return;
        }

        if (inbox == null) inbox = new InboxWs();

        // CRITICAL: Wire up 401 error handling
        inbox.setMainController(this);

        System.out.println("[MainController] Connecting to inbox for user: " + Session.me.getUsername());
        System.out.println("[MainController] Using token: " +
                Config.APP_TOKEN.substring(0, Math.min(20, Config.APP_TOKEN.length())) + "...");

        inbox.connect(Config.DIR_WORKER, Session.me.getUsername(), Config.APP_TOKEN);
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
                maxIcon.setText("□");
            } else {
                stage.setMaximized(true);
                maxIcon.setText("❐");
            }
        }
    }

    @FXML
    private void closeWindow() {
        if (authRefreshTimer != null) {
            authRefreshTimer.cancel();
            authRefreshTimer = null;
        }

        Platform.exit();
    }

    private void setupCellFactories() {
        listSearch.setCellFactory(v -> new UserListCell(UserListCell.MenuType.SEARCH));
        listFriends.setCellFactory(v -> new UserListCell(UserListCell.MenuType.FRIEND));
        listRequests.setCellFactory(v -> new UserListCell(UserListCell.MenuType.REQUEST));
    }

    @SuppressWarnings("resource") // Event listeners are permanent for app lifetime
    private void setupEventHandlers() {
        // === INLINE MEDIA HANDLER - FIXED TO PREVENT DUPLICATES ===
        AppCtx.BUS.on("media-inline", ev -> {
            if (ev == null || ev.from == null) {
                System.err.println("[MainController] Received null media-inline event or sender");
                return;
            }

            // Add unique processing check
            String eventId = "media-" + ev.from + "-" + System.currentTimeMillis();
            if (processedMessageIds.contains(eventId)) {
                System.out.println("[MainController] Skipping duplicate media-inline event");
                return;
            }
            processedMessageIds.add(eventId);

            Platform.runLater(() -> {
                try {
                    System.out.println("[MainController] Received inline media from: " + ev.from);
                    if (ev.data != null) {
                        String fileName = ev.data.path("fileName").asText("");
                        long size = ev.data.path("size").asLong(0);
                        System.out.println("[MainController] Processing inline media: " + fileName +
                                " (" + formatFileSize(size) + ")");

                        synchronized (this) {
                            if (currentChat != null && currentPeer != null &&
                                    currentPeer.getUsername().equalsIgnoreCase(ev.from)) {
                                System.out.println("[MainController] Refreshing chat to show new media");
                                currentChat.refreshConversation();
                                notificationManager.clearNotificationCount(ev.from);
                                refreshFriendsUI();
                            }
                        }
                    } else {
                        System.err.println("[MainController] Received inline media event with null data from: " + ev.from);
                    }
                } catch (Exception e) {
                    System.err.println("[MainController] Error processing inline media: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });

        // === INCOMING MESSAGE HANDLER - FIXED TO PREVENT DUPLICATES ===
        AppCtx.BUS.on("chat", ev -> {
            if (ev == null || ev.from == null) {
                System.err.println("[MainController] Received null chat event or sender");
                return;
            }

            // Add message deduplication
            String messageId = ev.data != null ? ev.data.path("id").asText("") : "";
            if (!messageId.isEmpty() && processedMessageIds.contains(messageId)) {
                System.out.println("[MainController] Skipping duplicate chat message: " + messageId);
                return;
            }
            if (!messageId.isEmpty()) {
                processedMessageIds.add(messageId);
            }

            try {
                if (!rateLimiter.allowMessage(ev.from)) {
                    if (rateLimiter.getRemainingCooldown(ev.from) > 4000) {
                        Platform.runLater(() -> {
                            try {
                                notificationManager.showToast("Rate Limited",
                                        ev.from + " is sending messages too quickly. Rate limited for 5 seconds.",
                                        NotificationManager.ToastType.WARNING);
                            } catch (Exception e) {
                                System.err.println("[MainController] Error showing rate limit toast: " + e.getMessage());
                            }
                        });
                    }
                    return;
                }
            } catch (Exception e) {
                System.err.println("[MainController] Error in rate limiting: " + e.getMessage());
            }

            Platform.runLater(() -> {
                try {
                    System.out.println("[MainController] Received chat message from: " + ev.from);
                    if (ev.data == null) {
                        System.err.println("[MainController] Received chat event with null data from: " + ev.from);
                        return;
                    }

                    String messageText = ev.data.path("text").asText("");
                    System.out.println("[MainController] Message content length: " + messageText.length());

                    // Check if this is a media message
                    EnhancedMediaService mediaService = EnhancedMediaService.getInstance();
                    boolean isMediaMsg = false;
                    EnhancedMediaService.MediaMessage mediaMessage = null;

                    try {
                        isMediaMsg = mediaService.isMediaMessage(messageText);
                        if (isMediaMsg) {
                            System.out.println("[MainController] Processing received media message");
                            mediaMessage = mediaService.extractMediaMessage(messageText);
                            if (mediaMessage != null) {
                                System.out.println("[MainController] Extracted media: " + mediaMessage.getFileName() +
                                        " (" + mediaService.formatFileSize(mediaMessage.getFileSize()) + ")");
                            } else {
                                System.err.println("[MainController] Failed to extract media message content");
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("[MainController] Error processing media message: " + e.getMessage());
                    }

                    // Store the incoming message
                    ChatMessage incomingMessage = ChatMessage.fromIncoming(ev.from, messageText);
                    messageStorage.storeMessage(ev.from, incomingMessage);

                    // Show toast notification and increment badge count
                    String displayText = messageText;
                    if (isMediaMsg && mediaMessage != null) {
                        displayText = "📎 " + mediaMessage.getFileName();
                    }
                    notificationManager.showMessageNotification(ev.from, displayText);

                    // Refresh friends list to show notification badges
                    refreshFriendsUI();

                    // If we're currently chatting with this person, add to UI immediately and clear badge
                    synchronized (this) {
                        if (currentChat != null && currentPeer != null &&
                                currentPeer.getUsername().equalsIgnoreCase(ev.from)) {
                            System.out.println("[MainController] Adding message to current chat UI");
                            currentChat.addMessageBubble(incomingMessage);
                            currentChat.scrollToBottom();
                            notificationManager.clearNotificationCount(ev.from);
                            refreshFriendsUI();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[MainController] Error processing chat message: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        });

        // === OTHER EVENT HANDLERS ===
        AppCtx.BUS.on("signal", ev -> {
            if (ev == null || ev.from == null) return;
            Platform.runLater(() -> {
                try {
                    System.out.println("[MainController] Received signal from: " + ev.from +
                            ", kind: " + (ev.data != null ? ev.data.path("kind").asText("") : "unknown"));
                } catch (Exception e) {
                    System.err.println("[MainController] Error processing signal: " + e.getMessage());
                }
            });
        });

        AppCtx.BUS.on("friend-request", ev -> {
            if (ev == null || ev.from == null) return;
            Platform.runLater(() -> {
                try {
                    System.out.println("[MainController] Received friend request from: " + ev.from);
                    refreshPending();
                    notificationManager.showFriendRequestNotification(ev.from);
                } catch (Exception e) {
                    System.err.println("[MainController] Error processing friend request: " + e.getMessage());
                }
            });
        });

        AppCtx.BUS.on("friend-accepted", ev -> {
            if (ev == null || ev.from == null) return;
            Platform.runLater(() -> {
                try {
                    System.out.println("[MainController] Friend request accepted by: " + ev.from);
                    refreshFriends();
                    refreshPending();
                    notificationManager.showFriendAcceptedNotification(ev.from);
                } catch (Exception e) {
                    System.err.println("[MainController] Error processing friend accepted: " + e.getMessage());
                }
            });
        });

        AppCtx.BUS.on("friend-removed", ev -> {
            if (ev == null || ev.from == null) return;
            Platform.runLater(() -> {
                try {
                    refreshFriends();
                    synchronized (this) {
                        if (currentPeer != null && ev.data != null && ev.data.has("user")) {
                            String removedUser = ev.data.path("user").asText("");
                            if (currentPeer.getUsername().equalsIgnoreCase(removedUser)) {
                                clearChat();
                            }
                        }
                    }
                    notificationManager.showFriendRemovedNotification(ev.from);
                } catch (Exception e) {
                    System.err.println("[MainController] Error processing friend removed: " + e.getMessage());
                }
            });
        });

        AppCtx.BUS.on("user-blocked", ev -> {
            if (ev == null || ev.from == null) return;
            Platform.runLater(() -> {
                try {
                    refreshFriends();
                    refreshPending();
                    synchronized (this) {
                        if (currentPeer != null && ev.data != null && ev.data.has("user")) {
                            String blockedUser = ev.data.path("user").asText("");
                            if (currentPeer.getUsername().equalsIgnoreCase(blockedUser)) {
                                clearChat();
                            }
                        }
                    }
                    notificationManager.showBlockedNotification(ev.from);
                } catch (Exception e) {
                    System.err.println("[MainController] Error processing user blocked: " + e.getMessage());
                }
            });
        });

        AppCtx.BUS.on("profile-updated", ev -> {
            if (ev == null || ev.from == null) return;
            Platform.runLater(() -> {
                try {
                    System.out.println("[MainController] Profile updated for: " + ev.from);
                    refreshFriends();
                } catch (Exception e) {
                    System.err.println("[MainController] Error processing profile update: " + e.getMessage());
                }
            });
        });

        // UI event handlers
        AppCtx.BUS.on("open-chat", ev -> Platform.runLater(() -> {
            try {
                if (ev == null || ev.data == null) return;
                String targetUser = ev.data.path("targetUser").asText("");
                if (targetUser.isEmpty()) return;

                UserSummary user = findUserInFriends(targetUser);
                if (user != null) {
                    notificationManager.clearNotificationCount(targetUser);
                    openChatWith(user);
                    refreshFriendsUI();
                }
            } catch (Exception e) {
                System.err.println("[MainController] Error opening chat: " + e.getMessage());
            }
        }));

        AppCtx.BUS.on("remove-friend", ev -> Platform.runLater(() -> {
            try {
                if (ev == null || ev.data == null) return;
                String targetUser = ev.data.path("targetUser").asText("");
                if (!targetUser.isEmpty()) {
                    removeFriendAsync(targetUser);
                }
            } catch (Exception e) {
                System.err.println("[MainController] Error removing friend: " + e.getMessage());
            }
        }));

        AppCtx.BUS.on("accept-friend", ev -> Platform.runLater(() -> {
            try {
                if (ev == null || ev.data == null) return;
                String targetUser = ev.data.path("targetUser").asText("");
                if (!targetUser.isEmpty()) {
                    acceptFriendAsync(targetUser);
                }
            } catch (Exception e) {
                System.err.println("[MainController] Error accepting friend: " + e.getMessage());
            }
        }));

        AppCtx.BUS.on("decline-friend", ev -> Platform.runLater(() -> {
            try {
                if (ev == null || ev.data == null) return;
                String targetUser = ev.data.path("targetUser").asText("");
                if (!targetUser.isEmpty()) {
                    declineFriendAsync(targetUser);
                }
            } catch (Exception e) {
                System.err.println("[MainController] Error declining friend: " + e.getMessage());
            }
        }));

        AppCtx.BUS.on("block-user", ev -> Platform.runLater(() -> {
            try {
                if (ev == null || ev.data == null) return;
                String targetUser = ev.data.path("targetUser").asText("");
                if (!targetUser.isEmpty()) {
                    blockUserAsync(targetUser);
                }
            } catch (Exception e) {
                System.err.println("[MainController] Error blocking user: " + e.getMessage());
            }
        }));

        AppCtx.BUS.on("send-friend-request", ev -> Platform.runLater(() -> {
            try {
                if (ev == null || ev.data == null) return;
                String targetUser = ev.data.path("targetUser").asText("");
                if (!targetUser.isEmpty()) {
                    sendFriendRequestAsync(targetUser);
                }
            } catch (Exception e) {
                System.err.println("[MainController] Error sending friend request: " + e.getMessage());
            }
        }));

        // Clean up old processed message IDs periodically
        Timer cleanupTimer = new Timer("MessageIdCleanup", true);
        cleanupTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (processedMessageIds.size() > 1000) {
                    processedMessageIds.clear();
                    System.out.println("[MainController] Cleared processed message IDs cache");
                }
            }
        }, 300000, 300000); // Every 5 minutes
    }

    private void setupUI() {
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

        listFriends.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                var sel = listFriends.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    notificationManager.clearNotificationCount(sel.getUsername());
                    openChatWith(sel);
                    refreshFriendsUI();
                }
            }
        });
    }

    // ============== FRIEND MANAGEMENT ==============

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

    // ============== UI MANAGEMENT ==============

    public void setMe(UserProfile me) {
        Session.me = me;
        Session.token = Config.APP_TOKEN;
        renderMe();

        // CRITICAL: Wire up 401 error handling
        directory.setMainController(this);

        connectToInbox();
    }

    private void openChatWith(UserSummary peer) {
        try {
            lblTitle.setText("DM with @" + peer.getUsername());
            currentPeer = peer;

            notificationManager.clearNotificationCount(peer.getUsername());
            refreshFriendsUI();

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

            var f = new com.cottonlesergal.whisperclient.models.Friend(
                    peer.getUsername(),
                    "",
                    "",
                    peer.getDisplay(),
                    peer.getAvatar()
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
        Platform.runLater(() -> {
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
            if (authRefreshTimer != null) {
                authRefreshTimer.cancel();
                authRefreshTimer = null;
            }

            if (inbox != null) {
                inbox.disconnect();
                inbox = null;
            }

            credentialsStorage.clearCredentials();

            Session.me = null;
            Session.token = null;
            Config.APP_TOKEN = "";

            clearChat();
            listFriends.getItems().clear();
            listRequests.getItems().clear();
            listSearch.getItems().clear();

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

    // ============== DEBUG METHODS ==============

    @FXML
    private void debugConnection() {
        System.out.println("=== DEBUG CONNECTION STATUS ===");

        System.out.println("\n" + rateLimiter.getStats());

        try {
            Class<?> debugClass = Class.forName("com.cottonlesergal.whisperclient.debug.DebugMessageTest");
            var quickDebugMethod = debugClass.getMethod("quickDebug");
            quickDebugMethod.invoke(null);

            var testDirectMessageMethod = debugClass.getMethod("testDirectMessage");
            testDirectMessageMethod.invoke(null);

        } catch (Exception e) {
            System.out.println("Session.me: " + (Session.me != null ? Session.me.getUsername() : "null"));
            System.out.println("APP_TOKEN: " + (Config.APP_TOKEN != null && !Config.APP_TOKEN.isEmpty() ?
                    "exists (" + Config.APP_TOKEN.length() + " chars)" : "missing"));
            System.out.println("Inbox: " + (inbox != null ? "created" : "null"));

            if (inbox != null) {
                System.out.println("WebSocket connected: " + inbox.isConnected());
                System.out.println("Connection info: " + inbox.getConnectionInfo());
            }

            if (Session.me != null && Config.APP_TOKEN != null && !Config.APP_TOKEN.isEmpty()) {
                System.out.println("Attempting to reconnect...");
                connectToInbox();

                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(2000);
                        directory.sendChat(Session.me.getUsername(), "Debug test message: " + System.currentTimeMillis());
                        System.out.println("Sent debug test message to self");
                    } catch (Exception ex) {
                        System.err.println("Failed to send debug message: " + ex.getMessage());
                    }
                });
            }
        }
    }

    @FXML
    private void debugDeleteAllMessages() {
        System.out.println("[DEBUG] Delete all messages requested");

        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Delete All Messages");
        confirmAlert.setHeaderText("Are you sure?");
        confirmAlert.setContentText("This will permanently delete ALL locally stored messages for ALL conversations.\n\nA backup will be created automatically.");

        Optional<ButtonType> result = confirmAlert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            new Thread(() -> {
                boolean success = MessageStorageUtility.getInstance().deleteAllMessages();

                Platform.runLater(() -> {
                    if (success) {
                        showInfoAlert("Success", "All messages deleted successfully",
                                "All local message storage has been cleared. A backup was created.");

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
            new Thread(() -> {
                boolean success = MessageStorageUtility.getInstance().deleteConversationMessages(username);

                Platform.runLater(() -> {
                    if (success) {
                        showInfoAlert("Success", "Conversation deleted",
                                "All messages with " + username + " have been deleted.");

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

    @FXML
    private void debugShowChunkingStatus() {
        System.out.println("[DEBUG] Chunking service status requested");
        MessageChunkingService.getInstance().printBufferStatus();
        MessageChunkingService.getInstance().cleanupOldMessages();

        showInfoAlert("Chunking Service", "Buffer status printed to console",
                "Check the console for detailed chunking service information.");
    }

    @FXML
    private void debugTestMessageChunking() {
        System.out.println("[DEBUG] Testing message chunking...");

        StringBuilder largeMessage = new StringBuilder();
        largeMessage.append("$MEDIA${\"id\":\"test-media\",\"type\":\"image\",\"mime\":\"image/png\",\"name\":\"test.png\",\"size\":1000000,\"data\":\"");

        String testData = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==";
        for (int i = 0; i < 1000; i++) {
            largeMessage.append(testData);
        }
        largeMessage.append("\"}");

        String originalMessage = largeMessage.toString();
        System.out.println("[DEBUG] Original message size: " + originalMessage.length() + " bytes");

        String[] chunks = MessageChunkingService.getInstance().splitMessage(originalMessage);
        System.out.println("[DEBUG] Message split into " + chunks.length + " chunks");

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

    @FXML
    private void debugSendTestChunkedMessage() {
        if (Session.me == null) {
            showErrorAlert("Not Logged In", "No session", "You must be logged in to send test messages.");
            return;
        }

        System.out.println("[DEBUG] Sending test chunked message to self...");

        SimpleMediaService mediaService = SimpleMediaService.getInstance();

        SimpleMediaService.SimpleMediaMessage testMedia = new SimpleMediaService.SimpleMediaMessage();
        testMedia.setId(UUID.randomUUID().toString());
        testMedia.setType("image");
        testMedia.setMimeType("image/png");
        testMedia.setFileName("test_large_image.png");
        testMedia.setSize(2 * 1024 * 1024);

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

    @FXML
    private void debugClearChunkingBuffer() {
        System.out.println("[DEBUG] Clearing chunking service buffer...");

        MessageChunkingService chunkingService = MessageChunkingService.getInstance();
        chunkingService.cleanupOldMessages();
        chunkingService.printBufferStatus();

        showInfoAlert("Buffer Cleared", "Chunking buffer cleared",
                "All incomplete chunked messages have been cleared from the buffer.");
    }

    // === TEST METHODS FOR NOTIFICATION SYSTEM ===
    @FXML
    private void testNotificationBadges() {
        System.out.println("[MainController] Testing notification badges...");

        if (!listFriends.getItems().isEmpty()) {
            UserSummary firstFriend = listFriends.getItems().get(0);
            String username = firstFriend.getUsername();

            notificationManager.incrementNotificationCount(username);
            refreshFriendsUI();

            Platform.runLater(() -> {
                new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                        Platform.runLater(() -> {
                            for (int i = 0; i < 14; i++) {
                                notificationManager.incrementNotificationCount(username);
                            }
                            refreshFriendsUI();
                            System.out.println("Badge should now show: 15");
                        });

                        Thread.sleep(2000);
                        Platform.runLater(() -> {
                            for (int i = 0; i < 85; i++) {
                                notificationManager.incrementNotificationCount(username);
                            }
                            refreshFriendsUI();
                            System.out.println("Badge should now show: 99+");
                        });

                        Thread.sleep(3000);
                        Platform.runLater(() -> {
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

        notificationManager.showMessageNotification("Jam3s", "tghf");

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

    @FXML
    private void testRateLimiting() {
        System.out.println("[MainController] Testing rate limiting...");

        if (!listFriends.getItems().isEmpty()) {
            UserSummary firstFriend = listFriends.getItems().get(0);
            String username = firstFriend.getUsername();

            Platform.runLater(() -> {
                new Thread(() -> {
                    System.out.println("Simulating 10 rapid messages from " + username);
                    for (int i = 1; i <= 10; i++) {
                        try {
                            var mockData = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                                    .put("text", "Spam message " + i)
                                    .put("id", "test-" + i);

                            var mockEvent = new com.cottonlesergal.whisperclient.events.Event(
                                    "chat", username, Session.me.getUsername(), System.currentTimeMillis(), mockData
                            );

                            AppCtx.BUS.emit(mockEvent);

                            Thread.sleep(100);
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

        try {
            EnhancedMediaService.MediaMessage testMedia = new EnhancedMediaService.MediaMessage();
            testMedia.setMessageId("test-123");
            testMedia.setMediaType("image");
            testMedia.setMimeType("image/png");
            testMedia.setFileName("test_image.png");
            testMedia.setFileSize(1024 * 1024);
            testMedia.setBase64Data("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==");
            testMedia.setChecksum("test-checksum");
            testMedia.setTimestamp(System.currentTimeMillis());

            String mediaMessageText = EnhancedMediaService.getInstance().createMediaMessageText(testMedia);
            System.out.println("Created media message: " + mediaMessageText.substring(0, Math.min(200, mediaMessageText.length())) + "...");

            EnhancedMediaService.MediaMessage extracted = EnhancedMediaService.getInstance().extractMediaMessage(mediaMessageText);
            if (extracted != null) {
                System.out.println("✓ Successfully extracted media message:");
                System.out.println("  File: " + extracted.getFileName());
                System.out.println("  Size: " + EnhancedMediaService.getInstance().formatFileSize(extracted.getFileSize()));
                System.out.println("  Type: " + extracted.getMediaType());
            } else {
                System.out.println("✗ Failed to extract media message");
            }

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
            inbox.ping();

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

    // ============== UTILITY METHODS ==============

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

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}