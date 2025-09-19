package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.models.Friend;
import com.cottonlesergal.whisperclient.services.DirectoryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class ChatController {
    @FXML private VBox messagesBox;
    @FXML private TextField txtMessage;

    private final DirectoryClient directory = new DirectoryClient();
    private Friend friend;
    private AutoCloseable subChat;
    private static final ObjectMapper M = new ObjectMapper();

    public void bindFriend(Friend f) {
        this.friend = f;

        // re-subscribe just for this friend
        if (subChat != null) try { subChat.close(); } catch (Exception ignored) {}
        subChat = AppCtx.BUS.on("chat", ev -> {
            if (ev.from != null && friend.getUsername().equalsIgnoreCase(ev.from)) {
                String text = ev.data != null && ev.data.has("text") ? ev.data.get("text").asText("") : "";
                Platform.runLater(() -> addBubble(friend.getDisplayName() + ": " + text, false));
            }
        });
    }

    @FXML public void onSend() {
        String t = txtMessage.getText();
        if (t == null || t.isBlank() || friend == null) return;
        directory.sendChat(friend.getUsername(), t);
        addBubble("Me: " + t, true);
        txtMessage.clear();
    }

    private void addBubble(String text, boolean me) {
        Label l = new Label(text);
        l.getStyleClass().add("message-bubble");
        if (me) l.getStyleClass().add("me");
        HBox row = new HBox(l);
        row.setAlignment(me ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        messagesBox.getChildren().add(row);
    }

    public void appendLocal(String text) {
        addBubble("Me: " + text, true);
    }
}
