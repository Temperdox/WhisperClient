package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.Friend;
import com.cottonlesergal.whisperclient.services.DirectoryClient;
import com.cottonlesergal.whisperclient.services.MessageStorageService;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatController {
    @FXML private ScrollPane scrollPane;
    @FXML private VBox messagesBox;
    @FXML private TextField txtMessage;

    private final DirectoryClient directory = new DirectoryClient();
    private final MessageStorageService storage = MessageStorageService.getInstance();
    private final ObjectMapper mapper = new ObjectMapper();

    private Friend friend;
    private AutoCloseable subChat;

    private final AtomicInteger currentPage = new AtomicInteger(0);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean hasMoreMessages = new AtomicBoolean(true);

    private ProgressIndicator loadingIndicator;
    private HBox loadingContainer;

    @FXML
    private void initialize() {
        setupScrollPane();
        setupLoadingIndicator();
    }

    private void setupScrollPane() {
        // Listen for scroll events to load more messages
        scrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            // If scrolled to top and not currently loading, load more messages
            if (newVal.doubleValue() <= 0.01 && !isLoading.get() && hasMoreMessages.get()) {
                loadMoreMessages();
            }
        });

        // Ensure scroll pane fits to width
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    }

    private void setupLoadingIndicator() {
        // Create animated loading indicator
        loadingContainer = new HBox();
        loadingContainer.setAlignment(Pos.CENTER);
        loadingContainer.setPadding(new Insets(10));
        loadingContainer.setVisible(false);
        loadingContainer.setManaged(false);

        // Create shimmer/skeleton loading bars
        VBox shimmerContainer = new VBox(8);
        shimmerContainer.setAlignment(Pos.CENTER);

        for (int i = 0; i < 3; i++) {
            HBox shimmerBar = createShimmerBar(i == 1 ? 200 : 150); // Vary widths
            shimmerContainer.getChildren().add(shimmerBar);
        }

        loadingContainer.getChildren().add(shimmerContainer);
    }

    private HBox createShimmerBar(double width) {
        HBox shimmerBar = new HBox();
        shimmerBar.setPrefWidth(width);
        shimmerBar.setPrefHeight(20);
        shimmerBar.setMaxWidth(width);
        shimmerBar.setStyle("-fx-background-color: #3b3f45; -fx-background-radius: 10;");

        // Create shimmer effect with gradient
        Region shimmerOverlay = new Region();
        shimmerOverlay.setPrefSize(50, 20);
        shimmerOverlay.setStyle(
                "-fx-background-color: linear-gradient(to right, " +
                        "transparent, rgba(255,255,255,0.3), transparent); " +
                        "-fx-background-radius: 10;"
        );

        shimmerBar.getChildren().add(shimmerOverlay);

        // Animate the shimmer overlay from left to right
        TranslateTransition shimmer = new TranslateTransition(Duration.seconds(1.5), shimmerOverlay);
        shimmer.setFromX(-50);
        shimmer.setToX(width);
        shimmer.setCycleCount(Animation.INDEFINITE);
        shimmer.play();

        return shimmerBar;
    }

    public void bindFriend(Friend f) {
        this.friend = f;

        // Clear previous messages
        messagesBox.getChildren().clear();
        currentPage.set(0);
        hasMoreMessages.set(true);

        // Re-subscribe for this friend's chat events
        if (subChat != null) {
            try { subChat.close(); } catch (Exception ignored) {}
        }

        subChat = AppCtx.BUS.on("chat", ev -> {
            if (ev.from != null && friend.getUsername().equalsIgnoreCase(ev.from)) {
                String text = ev.data != null && ev.data.has("text") ? ev.data.get("text").asText("") : "";
                Platform.runLater(() -> {
                    // Store incoming message
                    ChatMessage incoming = ChatMessage.fromIncoming(ev.from, text);
                    storage.storeMessage(friend.getUsername(), incoming);

                    // Only add to UI if we're currently viewing this chat
                    if (friend != null && friend.getUsername().equalsIgnoreCase(ev.from)) {
                        addMessageBubble(incoming);
                        scrollToBottom();
                    }
                });
            }
        });

        // Subscribe to profile updates for this friend
        AutoCloseable profileSub = AppCtx.BUS.on("profile-updated", ev -> {
            if (ev.from != null && friend.getUsername().equalsIgnoreCase(ev.from)) {
                Platform.runLater(() -> {
                    if (ev.data != null) {
                        String newDisplay = ev.data.path("display").asText("");
                        String newAvatar = ev.data.path("avatar").asText("");

                        // Update friend object
                        this.friend = new Friend(
                                friend.getUsername(),
                                friend.getPubKey(),
                                friend.getInboxUrl(),
                                newDisplay,
                                newAvatar
                        );

                        // Refresh the DM header
                        refreshDMHeader();

                        System.out.println("[ChatController] Updated friend profile: " + newDisplay);
                    }
                });
            }
        });

        // Load initial messages
        loadInitialMessages();
    }

    private void refreshDMHeader() {
        // Find and update the DM header if it exists
        for (int i = 0; i < messagesBox.getChildren().size(); i++) {
            var node = messagesBox.getChildren().get(i);
            if (node.getStyleClass().contains("dm-header")) {
                messagesBox.getChildren().remove(i);
                messagesBox.getChildren().add(i, createDMHeader());
                break;
            }
        }
    }

    private Node createDMHeader() {
        VBox headerContainer = new VBox(8);
        headerContainer.getStyleClass().add("dm-header");
        headerContainer.setPadding(new Insets(20, 24, 16, 24));

        // Large avatar
        ImageView largeAvatar = new ImageView();
        largeAvatar.setFitWidth(80);
        largeAvatar.setFitHeight(80);

        String avatarUrl = friend != null ? friend.getAvatar() : "";
        largeAvatar.setImage(AvatarCache.get(avatarUrl, 80));

        // Circular clip for large avatar
        Circle largeClip = new Circle(40, 40, 40);
        largeAvatar.setClip(largeClip);

        // Display name
        Label displayName = new Label(friend != null ? friend.getDisplayName() : "Unknown");
        displayName.getStyleClass().add("dm-header-name");

        // Subtitle
        Label subtitle = new Label("This is the beginning of your direct message history with @" +
                (friend != null ? friend.getUsername() : "unknown"));
        subtitle.getStyleClass().add("dm-header-subtitle");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(400);

        headerContainer.getChildren().addAll(largeAvatar, displayName, subtitle);
        return headerContainer;
    }

    private void loadInitialMessages() {
        currentPage.set(0);
        hasMoreMessages.set(true);

        CompletableFuture.supplyAsync(() -> {
            return storage.loadMessages(friend.getUsername(), 0, 100);
        }).thenAccept(messages -> Platform.runLater(() -> {
            messagesBox.getChildren().clear();

            // Add DM header at the top
            addDMHeader();

            if (messages.isEmpty()) {
                // Show welcome message if no history
                addWelcomeMessage();
            } else {
                // Add messages in chronological order (oldest first)
                // Since storage returns newest first, we reverse the order
                for (int i = messages.size() - 1; i >= 0; i--) {
                    addMessageBubble(messages.get(i));
                }

                // Check if there are more messages
                hasMoreMessages.set(messages.size() >= 100);
            }

            scrollToBottom();
        }));
    }

    private void loadMoreMessages() {
        if (isLoading.get() || !hasMoreMessages.get()) return;

        isLoading.set(true);
        showLoadingIndicator();

        int nextPage = currentPage.incrementAndGet();

        CompletableFuture.supplyAsync(() -> {
            return storage.loadMessages(friend.getUsername(), nextPage, 100);
        }).thenAccept(messages -> Platform.runLater(() -> {
            hideLoadingIndicator();
            isLoading.set(false);

            if (messages.isEmpty()) {
                hasMoreMessages.set(false);
                return;
            }

            // Remember scroll position
            double scrollPosition = scrollPane.getVvalue();
            double contentHeight = messagesBox.getHeight();

            // Add older messages at the top (reverse order since they come newest-first)
            for (int i = messages.size() - 1; i >= 0; i--) {
                messagesBox.getChildren().add(0, createMessageBubble(messages.get(i)));
            }

            // Restore scroll position relative to new content
            Platform.runLater(() -> {
                double newContentHeight = messagesBox.getHeight();
                double heightDifference = newContentHeight - contentHeight;
                double newScrollValue = heightDifference / (messagesBox.getHeight() - scrollPane.getViewportBounds().getHeight());
                scrollPane.setVvalue(Math.max(0, newScrollValue + scrollPosition));
            });

            // Check if there are more messages
            hasMoreMessages.set(messages.size() >= 100);
        }));
    }

    private void showLoadingIndicator() {
        if (!messagesBox.getChildren().contains(loadingContainer)) {
            messagesBox.getChildren().add(0, loadingContainer);
        }
        loadingContainer.setVisible(true);
        loadingContainer.setManaged(true);

        // Fade in animation
        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), loadingContainer);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();
    }

    private void hideLoadingIndicator() {
        FadeTransition fadeOut = new FadeTransition(Duration.millis(200), loadingContainer);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> {
            loadingContainer.setVisible(false);
            loadingContainer.setManaged(false);
            messagesBox.getChildren().remove(loadingContainer);
        });
        fadeOut.play();
    }

    @FXML
    public void onSend() {
        String text = txtMessage.getText();
        if (text == null || text.isBlank() || friend == null) return;

        // Send via network
        directory.sendChat(friend.getUsername(), text);

        // Store outgoing message
        ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), text);
        storage.storeMessage(friend.getUsername(), outgoing);

        // Add to UI
        addMessageBubble(outgoing);
        scrollToBottom();

        txtMessage.clear();
    }

    private void addMessageBubble(ChatMessage message) {
        Node bubble = createMessageBubble(message);
        messagesBox.getChildren().add(bubble);
    }

    private Node createMessageBubble(ChatMessage message) {
        HBox messageRow = new HBox(12);
        messageRow.getStyleClass().add("message-row");
        messageRow.setPadding(new Insets(4, 16, 4, 16));

        // Create avatar (32x32 circle)
        ImageView avatar = new ImageView();
        avatar.setFitWidth(32);
        avatar.setFitHeight(32);

        // Use actual avatar if available, otherwise create placeholder
        if (message.isFromMe() && Session.me != null) {
            avatar.setImage(AvatarCache.get(Session.me.getAvatarUrl(), 32));
        } else {
            // For friend messages, try to get their avatar
            String avatarUrl = friend != null ? friend.getAvatar() : "";
            avatar.setImage(AvatarCache.get(avatarUrl, 32));
        }

        // Create circular clip for avatar
        Circle clip = new Circle(16, 16, 16);
        avatar.setClip(clip);

        // Message content area
        VBox contentArea = new VBox(4);
        contentArea.setAlignment(Pos.TOP_LEFT);

        // Header with name and timestamp
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label();
        nameLabel.getStyleClass().add("message-author");

        if (message.isFromMe() && Session.me != null) {
            nameLabel.setText(Session.me.getDisplayName());
        } else {
            nameLabel.setText(friend != null ? friend.getDisplayName() : message.getFrom());
        }

        // Timestamp
        LocalDateTime dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(message.getTimestamp()),
                ZoneId.systemDefault()
        );
        String timeStr = dateTime.format(DateTimeFormatter.ofPattern("h:mm a"));
        Label timestampLabel = new Label(timeStr);
        timestampLabel.getStyleClass().add("message-timestamp");

        header.getChildren().addAll(nameLabel, timestampLabel);

        // Message content
        Label contentLabel = new Label(message.getContent());
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(600);
        contentLabel.getStyleClass().add("message-content");

        contentArea.getChildren().addAll(header, contentLabel);

        messageRow.getChildren().addAll(avatar, contentArea);

        return messageRow;
    }

    private void addWelcomeMessage() {
        // Welcome message is now handled by the DM header
        // Just add some spacing if needed
        Region spacer = new Region();
        spacer.setPrefHeight(20);
        messagesBox.getChildren().add(spacer);
    }

    private void addDMHeader() {
        Node header = createDMHeader();
        messagesBox.getChildren().add(header);
    }

    private void scrollToBottom() {
        Platform.runLater(() -> {
            scrollPane.setVvalue(1.0);
        });
    }

    public void appendLocal(String text) {
        // This method is kept for compatibility but now we handle storage in onSend
        // Just add to UI since storage is handled elsewhere
        ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), text);
        addMessageBubble(outgoing);
        scrollToBottom();
    }
}