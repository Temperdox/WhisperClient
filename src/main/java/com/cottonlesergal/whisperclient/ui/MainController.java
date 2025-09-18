package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.UserProfile;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.cottonlesergal.whisperclient.services.Config;
import com.cottonlesergal.whisperclient.services.DirectoryClient;
import com.cottonlesergal.whisperclient.services.InboxWs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class MainController {
    @FXML private Label lblTitle;

    @FXML private TextField txtSearch;
    @FXML private ListView<UserSummary> listSearch;

    @FXML private ListView<UserSummary> listFriends;   // Direct Messages
    @FXML private ListView<UserSummary> listRequests;  // Incoming requests (as UserSummary)

    @FXML private AnchorPane chatHost;
    @FXML private TextArea txtMessage;

    // "me" card
    @FXML private HBox meCard;
    @FXML private ImageView imgMe;
    @FXML private Label lblMeName;
    @FXML private Label lblMeHandle;

    private final DirectoryClient directory = new DirectoryClient();
    private final AtomicLong searchSeq = new AtomicLong();

    private final ObjectMapper M = new ObjectMapper();
    private InboxWs inbox;   // your WebSocket client

    // ───────────────────────── Me card ─────────────────────────
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
                ? me.getUsername()
                : me.getDisplayName();

        lblMeName.setText(display);
        lblMeHandle.setText("@" + me.getUsername());
        imgMe.setImage(AvatarCache.get(me.getAvatarUrl(), 32));
    }

    // ───────────────────────── FXML init ─────────────────────────
    @FXML
    private void initialize() {
        // cell renderers for avatar + name
        listSearch.setCellFactory(v -> new UserListCell());
        listFriends.setCellFactory(v -> new UserListCell());
        listRequests.setCellFactory(v -> new UserListCell());

        renderMe(); // in case setMe() hasn’t been called yet

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
                        if (id == searchSeq.get()) {
                            listSearch.getItems().setAll(results);
                        }
                    }));
        });

        // click a search result → send friend request
        listSearch.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                UserSummary sel = listSearch.getSelectionModel().getSelectedItem();
                if (sel != null) onAddFriendTo(sel);
            }
        });

        // double-click a request to accept it
        listRequests.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                UserSummary sel = listRequests.getSelectionModel().getSelectedItem();
                if (sel != null) acceptRequest(sel);
            }
        });
    }

    // called by LoginController after sign-in
    public void setMe(UserProfile me) {
        Session.me = Objects.requireNonNull(me);
        renderMe();

        // fill initial lists
        CompletableFuture.runAsync(() -> {
            // pending requests come as usernames; map them to summaries
            var pendingUsernames = directory.listPending();
            var summaries = pendingUsernames.stream()
                    .map(u -> directory.lookup(u).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();

            var friends = directory.listFriends();

            Platform.runLater(() -> {
                listRequests.getItems().setAll(summaries);
                listFriends.getItems().setAll(friends);
            });
        });

        // connect inbox websocket for live events
        inbox = new InboxWs(this::onInboxText);
        inbox.connect(Config.DIR_WORKER, me.getUsername(), Session.token);
    }

    // ───────────────────────── Add friend ─────────────────────────
    @FXML private void onAddFriend() {
        String q = txtSearch.getText();
        if (q == null || q.isBlank()) return;
        String handle = q.trim().toLowerCase();

        // exact lookup first: show “not found” or send invite
        CompletableFuture.supplyAsync(() -> directory.lookup(handle))
                .thenAccept(opt -> Platform.runLater(() -> {
                    if (opt.isEmpty()) {
                        alertInfo("User not found", "No user named @" + handle + " exists.");
                    } else {
                        onAddFriendTo(opt.get());
                    }
                }));
    }

    private void onAddFriendTo(UserSummary user) {
        // don’t allow sending to yourself
        if (Session.me != null && user.getUsername().equalsIgnoreCase(Session.me.getUsername())) {
            alertInfo("That’s you", "You can’t send a friend request to yourself.");
            return;
        }

        CompletableFuture.supplyAsync(() -> directory.sendFriendRequest(user.getUsername()))
                .thenAccept(ok -> Platform.runLater(() -> {
                    if (ok) {
                        alertInfo("Invite sent", "Your friend request was sent to @" + user.getUsername() + ".");
                        txtSearch.clear();
                        listSearch.getItems().clear();
                    } else {
                        alertInfo("Failed", "Could not send friend request right now.");
                    }
                }));
    }

    // ───────────────────────── Accept request ─────────────────────────
    private void acceptRequest(UserSummary from) {
        CompletableFuture.supplyAsync(() -> directory.acceptFriend(from.getUsername()))
                .thenAccept(ok -> Platform.runLater(() -> {
                    if (ok) {
                        listRequests.getItems().removeIf(u -> u.getUsername().equalsIgnoreCase(from.getUsername()));
                        // ensure they appear in Direct Messages
                        if (listFriends.getItems().stream().noneMatch(u -> u.getUsername().equalsIgnoreCase(from.getUsername()))) {
                            listFriends.getItems().add(from);
                        }
                        alertInfo("Friends", "You and @" + from.getUsername() + " are now friends.");
                    } else {
                        alertInfo("Failed", "Could not accept request right now.");
                    }
                }));
    }

    // ───────────────────────── Inbox events ─────────────────────────
    private void onInboxText(String json) {
        try {
            JsonNode evt = M.readTree(json);
            String type = evt.path("type").asText("");

            // friend-request arrives
            if ("friend-request".equals(type)) {
                String from = evt.path("from").asText("");
                if (!from.isBlank()) {
                    // look up summary so we can show avatar/name in the list
                    CompletableFuture.supplyAsync(() -> directory.lookup(from))
                            .thenAccept(opt -> opt.ifPresent(sum ->
                                    Platform.runLater(() -> {
                                        boolean exists = listRequests.getItems()
                                                .stream().anyMatch(u -> u.getUsername().equalsIgnoreCase(sum.getUsername()));
                                        if (!exists) listRequests.getItems().add(sum);
                                    })));
                }
            }

            // friend accepted signal (our outgoing request was accepted)
            else if ("signal".equals(type) && "friend-accepted".equals(evt.path("kind").asText(""))) {
                String other = evt.path("from").asText("");
                if (!other.isBlank()) {
                    CompletableFuture.supplyAsync(() -> directory.lookup(other))
                            .thenAccept(opt -> opt.ifPresent(sum ->
                                    Platform.runLater(() -> {
                                        boolean exists = listFriends.getItems()
                                                .stream().anyMatch(u -> u.getUsername().equalsIgnoreCase(sum.getUsername()));
                                        if (!exists) listFriends.getItems().add(sum);
                                    })));
                }
            }
        } catch (Exception ignored) { }
    }

    // ───────────────────────── Messaging (placeholder) ─────────────────────────
    @FXML private void onSend() {
        // TODO: send message via P2P/WebRTC once connected to a peer
        txtMessage.clear();
    }

    // ───────────────────────── Helpers ─────────────────────────
    private void alertInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(title);
        a.setHeaderText(title);
        a.setContentText(msg);
        a.showAndWait();
    }
}
