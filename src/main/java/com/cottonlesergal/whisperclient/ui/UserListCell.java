package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.events.Event;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;

public class UserListCell extends ListCell<UserSummary> {
    private final HBox box = new HBox(10);
    private final ImageView avatar = new ImageView();
    private final Label name = new Label();
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
        box.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(avatar, name);
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
                emitFriendEvent("open-chat", item.getUsername());
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

        contextMenu.getItems().addAll(openChat, new SeparatorMenuItem(), removeFriend, blockUser);
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
        avatar.setImage(AvatarCache.get(item.getAvatar(), 32));
        name.setText(item.getDisplay().isBlank() ? item.getUsername() : item.getDisplay());
        setGraphic(box);
    }
}