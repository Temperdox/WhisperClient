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
import javafx.scene.control.TextArea;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class MainController {
    @FXML private Label lblTitle;
    @FXML private TextField txtSearch;
    @FXML private ListView<UserSummary> listSearch;
    @FXML private ListView<UserSummary> listFriends;
    @FXML private ListView<UserSummary> listRequests;
    @FXML private AnchorPane chatHost;

    // If your main.fxml still has a composer at the bottom:
    @FXML private TextArea txtMessage;   // keep if main owns Send button

    @FXML private HBox meCard;
    @FXML private ImageView imgMe;
    @FXML private Label lblMeName;
    @FXML private Label lblMeHandle;

    private final DirectoryClient directory = new DirectoryClient();
    private final AtomicLong searchSeq = new AtomicLong();
    private InboxWs inbox;

    // currently open chat controller (loaded into chatHost)
    private ChatController currentChat;
    // who we are chatting with currently
    private UserSummary currentPeer;

    @FXML
    private void initialize() {
        listSearch.setCellFactory(v -> new UserListCell());
        listFriends.setCellFactory(v -> new UserListCell());
        listRequests.setCellFactory(v -> new UserListCell());

        // Event subscriptions (friend flow)
        AppCtx.BUS.on("friend-request",  ev -> Platform.runLater(this::refreshPending));
        AppCtx.BUS.on("friend-accepted", ev -> Platform.runLater(this::refreshFriends));
        AppCtx.BUS.on("friend-removed",  ev -> Platform.runLater(this::refreshFriends));
        // "chat" events are rendered by ChatController; nothing to do here.

        renderMe();
        refreshFriends();
        refreshPending();

        // live search with debounce
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

        // click a search result -> send friend request
        listSearch.setOnMouseClicked(e -> {
            var sel = listSearch.getSelectionModel().getSelectedItem();
            if (sel != null) directory.sendFriendRequest(sel.getUsername());
        });

        // open chat when clicking a friend
        listFriends.setOnMouseClicked(e -> {
            var sel = listFriends.getSelectionModel().getSelectedItem();
            if (sel != null) openChatWith(sel);
        });

        // optional: open chat when clicking a pending request (after accept)
        listRequests.setOnMouseClicked(e -> {
            var sel = listRequests.getSelectionModel().getSelectedItem();
            if (sel != null) {
                // If you want, accept + open chat:
                // if (directory.acceptFriend(sel.getUsername())) { refreshPending(); refreshFriends(); }
                // openChatWith(sel);
            }
        });
    }

    public void setMe(UserProfile me) {
        Session.me = me;
        renderMe();

        // connect inbox once signed in
        if (inbox == null) inbox = new InboxWs();
        inbox.connect(Config.DIR_WORKER, me.getUsername(), Config.APP_TOKEN);
    }

    private void renderMe() {
        if (Session.me == null) { meCard.setVisible(false); meCard.setManaged(false); return; }
        meCard.setVisible(true);  meCard.setManaged(true);
        var me = Session.me;
        String display = (me.getDisplayName() == null || me.getDisplayName().isBlank())
                ? me.getUsername() : me.getDisplayName();
        lblMeName.setText(display);
        lblMeHandle.setText("@" + me.getUsername());
        imgMe.setImage(AvatarCache.get(me.getAvatarUrl(), 32));
    }

    private void refreshFriends() { listFriends.getItems().setAll(directory.friends()); }
    private void refreshPending() { listRequests.getItems().setAll(directory.pending()); }

    @FXML private void onAddFriend() {
        String q = txtSearch.getText();
        if (q != null && !q.isBlank()) directory.sendFriendRequest(q.trim());
    }

    @FXML private void onAcceptRequest() {
        var sel = listRequests.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        if (directory.acceptFriend(sel.getUsername())) {
            refreshPending();
            refreshFriends();
            // Optionally jump into chat:
            // openChatWith(sel);
        } else {
            new Alert(Alert.AlertType.INFORMATION, "Could not accept request right now.").showAndWait();
        }
    }

    @FXML private void onRemoveFriend() {
        var sel = listFriends.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        directory.removeFriend(sel.getUsername());
        // If we were chatting with this user, clear chat:
        if (currentPeer != null && currentPeer.getUsername().equalsIgnoreCase(sel.getUsername())) {
            clearChat();
        }
    }

    /** MainController owns the Send action. */
    @FXML private void onSend() {
        // Uses the text field in main.fxml (if you still have it)
        if (txtMessage == null) return; // nothing to send from main
        String t = txtMessage.getText();
        if (t == null || t.isBlank() || currentPeer == null) return;

        // send via worker
        directory.sendChat(currentPeer.getUsername(), t);

        // reflect locally in the chat panel
        if (currentChat != null) currentChat.appendLocal(t);

        txtMessage.clear();
    }

    /** Load chat.fxml into chatHost and bind to a friend/peer. */
    private void openChatWith(UserSummary peer) {
        try {
            lblTitle.setText("DM with @" + peer.getUsername());
            currentPeer = peer;

            FXMLLoader fx = new FXMLLoader(getClass().getResource("/com/cottonlesergal/whisperclient/fxml/chat.fxml"));
            AnchorPane pane = fx.load();
            currentChat = fx.getController();

            // Recreate a minimal Friend-like object for binding if needed, or just pass the summary:
            // If your ChatController requires a Friend object, you can adapt it to accept UserSummary instead.
            // For now we expose a tiny wrapper:
            var f = new com.cottonlesergal.whisperclient.models.Friend(
                    peer.getUsername(),
                    peer.getDisplay(),
                    peer.getAvatar(),
                    "" // pubkey not needed for /message path
            );
            currentChat.bindFriend(f);

            chatHost.getChildren().setAll(pane);
            AnchorPane.setTopAnchor(pane, 0.0);
            AnchorPane.setRightAnchor(pane, 0.0);
            AnchorPane.setBottomAnchor(pane, 0.0);
            AnchorPane.setLeftAnchor(pane, 0.0);
        } catch (Exception e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "Failed to open chat: " + e.getMessage()).showAndWait();
        }
    }

    private void clearChat() {
        chatHost.getChildren().clear();
        currentChat = null;
        lblTitle.setText("Whisper");
    }
}
