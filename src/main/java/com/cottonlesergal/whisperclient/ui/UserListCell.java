package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.events.Event;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.cottonlesergal.whisperclient.services.NotificationManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;

public class UserListCell extends ListCell<UserSummary> {
    private final HBox box = new HBox(10);
    private final ImageView avatar = new ImageView();
    private final Label name = new Label();
    private final StackPane avatarContainer = new StackPane();
    private final Label notificationBadge = new Label();
    private final ContextMenu contextMenu = new ContextMenu();
    private final ObjectMapper mapper = new ObjectMapper();

    // Context menu type to determine which actions to show
    public enum MenuType { FRIEND, REQUEST, SEARCH }
    private MenuType menuType = MenuType.FRIEND;

    public UserListCell() {
        this(MenuType.FRIEND);
    }

    public UserListCell(MenuType menuType) {
        this.menuType = menuType;
        setupUI();
        setupContextMenu();
    }

    private void setupUI() {
        avatar.setFitWidth(32);
        avatar.setFitHeight(32);
        Circle clip = new Circle(16, 16, 16);
        avatar.setClip(clip);
        name.getStyleClass().add("user-cell-name");

        // Setup avatar container with notification badge
        avatarContainer.getChildren().add(avatar);
        setupNotificationBadge();

        // Create spacer to push badge to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        box.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(avatarContainer, name, spacer);

        // Only add notification badge to friends list
        if (menuType == MenuType.FRIEND) {
            box.getChildren().add(notificationBadge);
        }
    }

    private void setupNotificationBadge() {
        notificationBadge.setVisible(false);
        notificationBadge.setManaged(false);
        notificationBadge.setPrefSize(20, 20);
        notificationBadge.setMinSize(20, 20);
        notificationBadge.setMaxSize(20, 20);
        notificationBadge.setAlignment(Pos.CENTER);

        // Style the badge
        notificationBadge.setStyle(
                "-fx-background-color: #f23f43;" +
                        "-fx-background-radius: 10;" +
                        "-fx-text-fill: white;" +
                        "-fx-font-size: 10px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 2, 0, 0, 1);"
        );
    }

    private void updateNotificationBadge(String username) {
        if (menuType != MenuType.FRIEND || username == null) {
            notificationBadge.setVisible(false);
            notificationBadge.setManaged(false);
            return;
        }

        int count = NotificationManager.getInstance().getNotificationCount(username);

        if (count > 0) {
            String displayCount = count > 99 ? "99+" : String.valueOf(count);
            notificationBadge.setText(displayCount);
            notificationBadge.setVisible(true);
            notificationBadge.setManaged(true);

            // Adjust size based on text length
            if (count > 99) {
                notificationBadge.setPrefSize(28, 20);
                notificationBadge.setMinSize(28, 20);
                notificationBadge.setMaxSize(28, 20);
            } else if (count > 9) {
                notificationBadge.setPrefSize(24, 20);
                notificationBadge.setMinSize(24, 20);
                notificationBadge.setMaxSize(24, 20);
            } else {
                notificationBadge.setPrefSize(20, 20);
                notificationBadge.setMinSize(20, 20);
                notificationBadge.setMaxSize(20, 20);
            }
        } else {
            notificationBadge.setVisible(false);
            notificationBadge.setManaged(false);
        }
    }

    private void setupContextMenu() {
        contextMenu.getItems().clear();

        switch (menuType) {
            case FRIEND:
                setupFriendMenu();
                break;
            case REQUEST:
                setupRequestMenu();
                break;
            case SEARCH:
                setupSearchMenu();
                break;
        }

        // Only show context menu on right-click for non-empty cells
        this.setOnContextMenuRequested(event -> {
            UserSummary item = getItem();
            if (item != null && !isEmpty()) {
                contextMenu.show(this, event.getScreenX(), event.getScreenY());
            }
            event.consume();
        });
    }

    private void setupFriendMenu() {
        MenuItem openChat = new MenuItem("Open Chat");
        openChat.setOnAction(e -> {
            UserSummary item = getItem();
            if (item != null) {
                // Clear notifications when opening chat
                NotificationManager.getInstance().clearNotificationCount(item.getUsername());
                updateNotificationBadge(item.getUsername());
                emitFriendEvent("open-chat", item.getUsername());
            }
        });

        MenuItem markAsRead = new MenuItem("Mark as Read");
        markAsRead.setOnAction(e -> {
            UserSummary item = getItem();
            if (item != null) {
                NotificationManager.getInstance().clearNotificationCount(item.getUsername());
                updateNotificationBadge(item.getUsername());
            }
        });

        MenuItem removeFriend = new MenuItem("Remove Friend");
        removeFriend.setOnAction(e -> {
            UserSummary item = getItem();
            if (item != null) {
                // Show confirmation dialog
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Are you sure you want to remove " + item.getDisplay() + " as a friend?",
                        ButtonType.YES, ButtonType.NO);
                confirm.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.YES) {
                        emitFriendEvent("remove-friend", item.getUsername());
                    }
                });
            }
        });

        MenuItem blockUser = new MenuItem("Block User");
        blockUser.setOnAction(e -> {
            UserSummary item = getItem();
            if (item != null) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Are you sure you want to block " + item.getDisplay() + "?",
                        ButtonType.YES, ButtonType.NO);
                confirm.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.YES) {
                        emitFriendEvent("block-user", item.getUsername());
                    }
                });
            }
        });

        contextMenu.getItems().addAll(openChat, markAsRead, new SeparatorMenuItem(), removeFriend, blockUser);
    }

    private void setupRequestMenu() {
        MenuItem acceptRequest = new MenuItem("Accept Friend Request");
        acceptRequest.setOnAction(e -> {
            UserSummary item = getItem();
            if (item != null) {
                emitFriendEvent("accept-friend", item.getUsername());
            }
        });

        MenuItem declineRequest = new MenuItem("Decline Request");
        declineRequest.setOnAction(e -> {
            UserSummary item = getItem();
            if (item != null) {
                emitFriendEvent("decline-friend", item.getUsername());
            }
        });

        MenuItem blockUser = new MenuItem("Block User");
        blockUser.setOnAction(e -> {
            UserSummary item = getItem();
            if (item != null) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Block " + item.getDisplay() + "? This will also decline their friend request.",
                        ButtonType.YES, ButtonType.NO);
                confirm.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.YES) {
                        emitFriendEvent("block-user", item.getUsername());
                    }
                });
            }
        });

        contextMenu.getItems().addAll(acceptRequest, declineRequest, new SeparatorMenuItem(), blockUser);
    }

    private void setupSearchMenu() {
        MenuItem sendRequest = new MenuItem("Send Friend Request");
        sendRequest.setOnAction(e -> {
            UserSummary item = getItem();
            if (item != null) {
                emitFriendEvent("send-friend-request", item.getUsername());
            }
        });

        contextMenu.getItems().add(sendRequest);
    }

    private void emitFriendEvent(String eventType, String targetUser) {
        try {
            var data = mapper.createObjectNode()
                    .put("targetUser", targetUser);

            Event event = new Event(eventType, null, null, System.currentTimeMillis(), data);
            AppCtx.BUS.emit(event);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    protected void updateItem(UserSummary item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
            return;
        }

        // Use updated avatar and display name
        avatar.setImage(AvatarCache.get(item.getAvatar(), 32));
        String displayText = item.getDisplay() != null && !item.getDisplay().isBlank()
                ? item.getDisplay()
                : item.getUsername();
        name.setText(displayText);

        // Update notification badge
        updateNotificationBadge(item.getUsername());

        setGraphic(box);
    }

    /**
     * Update this cell's data when a profile update event is received
     */
    public void updateProfile(String username, String newDisplay, String newAvatar) {
        UserSummary currentItem = getItem();
        if (currentItem != null && currentItem.getUsername().equalsIgnoreCase(username)) {
            // Create updated UserSummary
            UserSummary updated = new UserSummary(username, newDisplay, newAvatar);
            updateItem(updated, false);
        }
    }

    /**
     * Refresh notification badge - call this when notification counts change
     */
    public void refreshNotificationBadge() {
        UserSummary item = getItem();
        if (item != null) {
            updateNotificationBadge(item.getUsername());
        }
    }

    /**
     * Clear notification badge for this user
     */
    public void clearNotificationBadge() {
        UserSummary item = getItem();
        if (item != null) {
            NotificationManager.getInstance().clearNotificationCount(item.getUsername());
            updateNotificationBadge(item.getUsername());
        }
    }
}