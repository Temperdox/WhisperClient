package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.UserProfile;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.cottonlesergal.whisperclient.services.*;
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
            } else {
                stage.setMaximized(true);
            }
            // Update maximize icon
            maxIcon.setText(stage.isMaximized() ? "❐" : "□");
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
        // Server-side events (from WebSocket)
        AppCtx.BUS.on("friend-request", ev -> Platform.runLater(() -> {
            refreshPending();
            showNotification("Friend Request", "New friend request from " + ev.from);
        }));

        AppCtx.BUS.on("friend-accepted", ev -> Platform.runLater(() -> {
            refreshFriends();
            refreshPending();
            showNotification("Friend Added", ev.from + " accepted your friend request");
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
            showNotification("Friend Removed", "Friend relationship with " + ev.from + " has ended");
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
            showNotification("User Blocked", "User " + ev.from + " has been blocked");
        }));

        // Client-side UI events (from context menus)
        AppCtx.BUS.on("open-chat", ev -> Platform.runLater(() -> {
            String targetUser = ev.data.path("targetUser").asText("");
            UserSummary user = findUserInFriends(targetUser);
            if (user != null) {
                openChatWith(user);
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
                if (sel != null) openChatWith(sel);
            }
        });
    }

    // Async friend management methods
    private void removeFriendAsync(String username) {
        CompletableFuture.runAsync(() -> {
            directory.removeFriend(username);
        }).thenRun(() -> Platform.runLater(() -> {
            refreshFriends();
            showNotification("Friend Removed", "Removed " + username + " from friends");
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                showError("Failed to remove friend", throwable.getMessage());
            });
            return null;
        });
    }

    // Fixed async friend management methods
    private void acceptFriendAsync(String username) {
        CompletableFuture.supplyAsync(() -> {
            return directory.acceptFriend(username);
        }).thenAccept(success -> Platform.runLater(() -> {
            if (success) {
                refreshPending();
                refreshFriends();
                showNotification("Friend Added", "You are now friends with " + username);
            } else {
                showError("Failed to accept friend request", "Could not accept request from " + username);
            }
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                showError("Failed to accept friend request", throwable.getMessage());
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
                showNotification("Request Declined", "Declined friend request from " + username);
            } else {
                showError("Failed to decline request", "Could not decline request from " + username);
            }
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                showError("Failed to decline request", throwable.getMessage());
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
                showNotification("User Blocked", "Blocked " + username);
            } else {
                showError("Failed to block user", "Could not block " + username);
            }
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                showError("Failed to block user", throwable.getMessage());
            });
            return null;
        });
    }

    private void sendFriendRequestAsync(String username) {
        CompletableFuture.supplyAsync(() -> {
            return directory.sendFriendRequest(username);
        }).thenAccept(success -> Platform.runLater(() -> {
            if (success) {
                showNotification("Request Sent", "Friend request sent to " + username);
            } else {
                showError("Failed to send request", "Could not send friend request to " + username);
            }
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                showError("Failed to send request", throwable.getMessage());
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
        renderMe();

        if (inbox == null) inbox = new InboxWs();
        inbox.connect(Config.DIR_WORKER, me.getUsername(), Config.APP_TOKEN);
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
                }));
    }

    private void refreshPending() {
        CompletableFuture.supplyAsync(() -> directory.pending())
                .thenAccept(pending -> Platform.runLater(() -> {
                    listRequests.getItems().setAll(pending);
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
            // Clear credentials to disable auto sign-in
            credentialsStorage.clearCredentials();

            // Clear session data
            Session.me = null;
            Session.token = null;
            Config.APP_TOKEN = "";

            // Disconnect inbox WebSocket
            if (inbox != null) {
                // Note: You might need to add a disconnect method to InboxWs
                inbox = null;
            }

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
                showError("Chat FXML not found", "Could not locate chat.fxml in resources");
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
            showError("Failed to open chat", e.getMessage());
        }
    }

    private void clearChat() {
        chatHost.getChildren().clear();
        currentChat = null;
        currentPeer = null;
        lblTitle.setText("Whisper");
    }
}