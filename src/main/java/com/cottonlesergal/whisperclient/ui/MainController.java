package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.UserProfile;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.cottonlesergal.whisperclient.services.Config;
import com.cottonlesergal.whisperclient.services.DirectoryClient;
import com.cottonlesergal.whisperclient.services.InboxWs;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class MainController {
    @FXML private Label lblTitle;
    @FXML private TextField txtSearch;
    @FXML private ListView<UserSummary> listSearch;
    @FXML private ListView<UserSummary> listFriends;
    @FXML private ListView<UserSummary> listRequests;
    @FXML private AnchorPane chatHost;
    @FXML private TextArea txtMessage;
    @FXML private HBox meCard;
    @FXML private ImageView imgMe;
    @FXML private Label lblMeName;
    @FXML private Label lblMeHandle;

    private final DirectoryClient directory = new DirectoryClient();
    private final AtomicLong searchSeq = new AtomicLong();
    private InboxWs inbox;

    private ChatController currentChat;
    private UserSummary currentPeer;

    @FXML
    private void initialize() {
        setupCellFactories();
        setupEventHandlers();
        setupUI();

        renderMe();
        refreshFriends();
        refreshPending();
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
            System.out.println("[MainController] Received friend-request event from: " + ev.from);
            refreshPending();
            showNotification("Friend Request", "New friend request from " + ev.from);
        }));

        AppCtx.BUS.on("friend-accepted", ev -> Platform.runLater(() -> {
            System.out.println("[MainController] Received friend-accepted event from: " + ev.from);
            refreshFriends();
            refreshPending();
            showNotification("Friend Added", ev.from + " accepted your friend request");
        }));

        AppCtx.BUS.on("friend-removed", ev -> Platform.runLater(() -> {
            System.out.println("[MainController] Received friend-removed event from: " + ev.from);
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

        AppCtx.BUS.on("friend-declined", ev -> Platform.runLater(() -> {
            System.out.println("[MainController] Received friend-declined event from: " + ev.from);
            showNotification("Request Declined", ev.from + " declined your friend request");
        }));

        AppCtx.BUS.on("user-blocked", ev -> Platform.runLater(() -> {
            System.out.println("[MainController] Received user-blocked event from: " + ev.from);
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
        System.out.println("[MainController] Refreshing pending requests...");
        CompletableFuture.supplyAsync(() -> directory.pending())
                .thenAccept(pending -> Platform.runLater(() -> {
                    System.out.println("[MainController] Got " + pending.size() + " pending requests");
                    listRequests.getItems().setAll(pending);
                }))
                .exceptionally(throwable -> {
                    System.err.println("[MainController] Error refreshing pending: " + throwable.getMessage());
                    throwable.printStackTrace();
                    return null;
                });
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

    @FXML private void onSend() {
        if (txtMessage == null) return;
        String t = txtMessage.getText();
        if (t == null || t.isBlank() || currentPeer == null) return;

        directory.sendChat(currentPeer.getUsername(), t);
        if (currentChat != null) currentChat.appendLocal(t);
        txtMessage.clear();
    }

    private void openChatWith(UserSummary peer) {
        try {
            lblTitle.setText("DM with @" + peer.getUsername());
            currentPeer = peer;

            FXMLLoader fx = new FXMLLoader(getClass().getResource("/com/cottonlesergal/whisperclient/fxml/chat.fxml"));
            AnchorPane pane = fx.load();
            currentChat = fx.getController();

            var f = new com.cottonlesergal.whisperclient.models.Friend(
                    peer.getUsername(),
                    peer.getDisplay(),
                    peer.getAvatar(),
                    ""
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