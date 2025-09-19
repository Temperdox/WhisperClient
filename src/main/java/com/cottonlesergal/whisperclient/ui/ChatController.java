package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.Friend;
import com.cottonlesergal.whisperclient.services.DirectoryClient;
import com.cottonlesergal.whisperclient.services.MediaMessageService;
import com.cottonlesergal.whisperclient.services.MessageStorageService;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import com.cottonlesergal.whisperclient.ui.components.EnhancedMessageComponents;
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
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    @FXML private Button btnAttach; // File attachment button

    private final DirectoryClient directory = new DirectoryClient();
    private final MessageStorageService storage = MessageStorageService.getInstance();
    private final MediaMessageService mediaService = MediaMessageService.getInstance();
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
        setupContextMenus();
        setupDragAndDrop();

        System.out.println("[ChatController] Initialized with media support");
    }

    private void setupContextMenus() {
        // Context menu for empty chat area
        ContextMenu chatAreaMenu = new ContextMenu();

        MenuItem clearHistory = new MenuItem("Clear Message History");
        clearHistory.setOnAction(e -> clearMessageHistory());

        MenuItem deleteLocalData = new MenuItem("Delete All Local Messages");
        deleteLocalData.setOnAction(e -> deleteAllLocalMessages());

        chatAreaMenu.getItems().addAll(clearHistory, new SeparatorMenuItem(), deleteLocalData);

        // Set context menu on messages container
        messagesBox.setOnContextMenuRequested(event -> {
            // Only show if not clicking on a message
            chatAreaMenu.show(messagesBox, event.getScreenX(), event.getScreenY());
            event.consume();
        });
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

    private void setupDragAndDrop() {
        // Enable drag and drop for the entire chat area
        messagesBox.setOnDragOver(this::handleDragOver);
        messagesBox.setOnDragDropped(this::handleDragDropped);

        // Also enable for scroll pane
        scrollPane.setOnDragOver(this::handleDragOver);
        scrollPane.setOnDragDropped(this::handleDragDropped);
    }

    private void handleDragOver(DragEvent event) {
        if (event.getGestureSource() != messagesBox && event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        boolean success = false;

        if (db.hasFiles()) {
            List<File> files = db.getFiles();
            for (File file : files) {
                sendFileMessage(file);
            }
            success = true;
        }

        event.setDropCompleted(success);
        event.consume();
    }

    @FXML
    private void onAttachFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Send");

        // Add file type filters
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All Files", "*.*"),
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.webm", "*.mov", "*.avi", "*.mkv"),
                new FileChooser.ExtensionFilter("Audio", "*.mp3", "*.wav", "*.ogg", "*.m4a"),
                new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.doc", "*.docx", "*.txt", "*.rtf")
        );

        Stage stage = (Stage) txtMessage.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(stage);

        if (selectedFile != null) {
            sendFileMessage(selectedFile);
        }
    }

    private void sendFileMessage(File file) {
        if (friend == null) return;

        try {
            MediaMessageService.EnhancedMessage enhancedMessage = mediaService.processFileUpload(file);

            // For now, we'll convert to a regular text message with file info
            // In a full implementation, you'd send the file data through your messaging protocol
            String fileMessage = "[FILE] " + file.getName() + " (" +
                    mediaService.formatFileSize(file.length()) + ")";

            directory.sendChat(friend.getUsername(), fileMessage);

            // Store and display locally
            ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), fileMessage);
            storage.storeMessage(friend.getUsername(), outgoing);

            // Create enhanced message bubble
            addEnhancedMessageBubble(enhancedMessage, true);
            scrollToBottom();

        } catch (Exception e) {
            showError("File Error", "Failed to send file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void bindFriend(Friend f) {
        System.out.println("[ChatController] Binding friend: " + f.getUsername());
        this.friend = f;

        // Clear previous messages
        messagesBox.getChildren().clear();
        currentPage.set(0);
        hasMoreMessages.set(true);

        // Remove old chat subscription
        if (subChat != null) {
            try { subChat.close(); } catch (Exception ignored) {}
        }

        // Subscribe to profile updates for this friend
        subChat = AppCtx.BUS.on("profile-updated", ev -> {
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
        System.out.println("[ChatController] Loading initial messages for: " + friend.getUsername());

        currentPage.set(0);
        hasMoreMessages.set(true);

        CompletableFuture.supplyAsync(() -> {
            return storage.loadMessages(friend.getUsername(), 0, 100);
        }).thenAccept(messages -> Platform.runLater(() -> {
            messagesBox.getChildren().clear();

            // Add DM header at the top
            addDMHeader();

            if (messages.isEmpty()) {
                System.out.println("[ChatController] No messages found, showing welcome message");
                // Show welcome message if no history
                addWelcomeMessage();
            } else {
                System.out.println("[ChatController] Loaded " + messages.size() + " messages");
                // Add messages in chronological order (oldest first)
                // Since storage returns newest first, we reverse the order
                for (int i = messages.size() - 1; i >= 0; i--) {
                    addMessageBubble(messages.get(i));
                }

                // Check if there are more messages
                hasMoreMessages.set(messages.size() >= 100);
            }

            scrollToBottom();
        })).exceptionally(throwable -> {
            System.err.println("[ChatController] Error loading initial messages: " + throwable.getMessage());
            throwable.printStackTrace();
            Platform.runLater(() -> {
                messagesBox.getChildren().clear();
                addDMHeader();
                addWelcomeMessage();
            });
            return null;
        });
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

        System.out.println("[ChatController] Sending message to: " + friend.getUsername() + " - " + text);

        // Process text for link embeds
        mediaService.processTextMessage(text).thenAccept(enhancedMessage -> {
            Platform.runLater(() -> {
                // Send via network
                directory.sendChat(friend.getUsername(), text);

                // Store outgoing message
                ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), text);
                storage.storeMessage(friend.getUsername(), outgoing);

                // Add to UI with potential embeds
                addEnhancedMessageBubble(enhancedMessage, true);
                scrollToBottom();
            });
        });

        txtMessage.clear();
    }

    /**
     * Add an enhanced message bubble with media/embed support
     */
    private void addEnhancedMessageBubble(MediaMessageService.EnhancedMessage enhancedMessage, boolean isFromMe) {
        VBox messageContainer = new VBox(8);
        messageContainer.getStyleClass().add("enhanced-message-container");
        messageContainer.setPadding(new Insets(4, 16, 4, 16));

        HBox messageRow = new HBox(12);
        messageRow.getStyleClass().add("message-row");
        messageRow.setAlignment(Pos.TOP_LEFT);

        // Create avatar (32x32 circle)
        ImageView avatar = new ImageView();
        avatar.setFitWidth(32);
        avatar.setFitHeight(32);

        if (isFromMe && Session.me != null) {
            avatar.setImage(AvatarCache.get(Session.me.getAvatarUrl(), 32));
        } else {
            String avatarUrl = friend != null ? friend.getAvatar() : "";
            avatar.setImage(AvatarCache.get(avatarUrl, 32));
        }

        Circle clip = new Circle(16, 16, 16);
        avatar.setClip(clip);

        // Message content area
        VBox contentArea = new VBox(8);
        contentArea.setAlignment(Pos.TOP_LEFT);
        contentArea.setMaxWidth(600);

        // Header with name and timestamp
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label();
        nameLabel.getStyleClass().add("message-author");

        if (isFromMe && Session.me != null) {
            nameLabel.setText(Session.me.getDisplayName());
        } else {
            nameLabel.setText(friend != null ? friend.getDisplayName() : "Unknown");
        }

        LocalDateTime dateTime = LocalDateTime.now();
        String timeStr = dateTime.format(DateTimeFormatter.ofPattern("h:mm a"));
        Label timestampLabel = new Label(timeStr);
        timestampLabel.getStyleClass().add("message-timestamp");

        header.getChildren().addAll(nameLabel, timestampLabel);
        contentArea.getChildren().add(header);

        // Text content (if any)
        if (enhancedMessage.getText() != null && !enhancedMessage.getText().trim().isEmpty()) {
            Label textContent = new Label(enhancedMessage.getText());
            textContent.setWrapText(true);
            textContent.setMaxWidth(580);
            textContent.getStyleClass().add("message-content");
            contentArea.getChildren().add(textContent);
        }

        // Media/embed content based on message type
        switch (enhancedMessage.getType()) {
            case IMAGE:
                contentArea.getChildren().add(EnhancedMessageComponents.createImageMessage(enhancedMessage));
                break;
            case VIDEO:
            case AUDIO:
            case FILE:
                contentArea.getChildren().add(EnhancedMessageComponents.createFileMessage(enhancedMessage));
                break;
            case LINK_EMBED:
                if (enhancedMessage.getLinkEmbed() != null) {
                    Node embedComponent = createEmbedComponent(enhancedMessage.getLinkEmbed());
                    if (embedComponent != null) {
                        contentArea.getChildren().add(embedComponent);
                    }
                }
                break;
        }

        messageRow.getChildren().addAll(avatar, contentArea);
        messageContainer.getChildren().add(messageRow);
        messagesBox.getChildren().add(messageContainer);
    }

    private Node createEmbedComponent(MediaMessageService.LinkEmbed embed) {
        switch (embed.getEmbedType()) {
            case YOUTUBE:
                return EnhancedMessageComponents.createYouTubeEmbed(embed);
            case TWITTER:
                return EnhancedMessageComponents.createTwitterEmbed(embed);
            case BLUESKY:
                return EnhancedMessageComponents.createBlueSkyEmbed(embed);
            case AMAZON:
                return EnhancedMessageComponents.createAmazonEmbed(embed);
            case GENERIC:
            default:
                return EnhancedMessageComponents.createGenericEmbed(embed);
        }
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

        // Add context menu for my messages only
        if (message.isFromMe()) {
            ContextMenu messageMenu = new ContextMenu();

            MenuItem copyMessage = new MenuItem("Copy Message");
            copyMessage.setOnAction(e -> {
                final javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                final javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(message.getContent());
                clipboard.setContent(content);
                showNotification("Copied", "Message copied to clipboard");
            });

            MenuItem editMessage = new MenuItem("Edit Message");
            editMessage.setOnAction(e -> editMessage(message, contentLabel));

            MenuItem deleteMessage = new MenuItem("Delete Message");
            deleteMessage.setOnAction(e -> deleteMessage(message, messageRow));

            messageMenu.getItems().addAll(copyMessage, editMessage, new SeparatorMenuItem(), deleteMessage);

            messageRow.setOnContextMenuRequested(event -> {
                messageMenu.show(messageRow, event.getScreenX(), event.getScreenY());
                event.consume();
            });
        }

        return messageRow;
    }

    private void editMessage(ChatMessage message, Label contentLabel) {
        TextInputDialog dialog = new TextInputDialog(message.getContent());
        dialog.setTitle("Edit Message");
        dialog.setHeaderText("Edit your message");
        dialog.setContentText("Message:");

        dialog.showAndWait().ifPresent(newContent -> {
            if (!newContent.trim().isEmpty() && !newContent.equals(message.getContent())) {
                // Update the message content
                message.setContent(newContent);
                contentLabel.setText(newContent);

                // Update in storage
                storage.storeMessage(friend.getUsername(), message);

                // TODO: Send edit event to other user via network
                showNotification("Message Edited", "Message has been updated");
            }
        });
    }

    private void deleteMessage(ChatMessage message, Node messageNode) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Are you sure you want to delete this message?",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Delete Message");
        confirm.setHeaderText("Delete Message");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                // Remove from UI
                messagesBox.getChildren().remove(messageNode);

                // TODO: Remove from storage (would need to track file names)
                // TODO: Send delete event to other user via network
                showNotification("Message Deleted", "Message has been removed");
            }
        });
    }

    private void clearMessageHistory() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Clear message history with " + (friend != null ? friend.getDisplayName() : "this user") + "?\n\n" +
                        "This will only clear the messages from your view. The other person will still see them.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Clear Message History");
        confirm.setHeaderText("Clear Messages");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                // Clear UI
                messagesBox.getChildren().clear();
                addDMHeader();

                // TODO: Clear from local storage for this user
                showNotification("History Cleared", "Message history has been cleared");
            }
        });
    }

    private void deleteAllLocalMessages() {
        Alert confirm = new Alert(Alert.AlertType.WARNING,
                "Delete ALL message history for ALL conversations?\n\n" +
                        "This will permanently delete all your stored messages and cannot be undone.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Delete All Messages");
        confirm.setHeaderText("WARNING: Permanent Deletion");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                CompletableFuture.runAsync(() -> {
                    try {
                        Path messagesDir = Paths.get(System.getProperty("user.home"), ".whisperclient", "messages");
                        if (Files.exists(messagesDir)) {
                            // Delete entire messages directory
                            Files.walk(messagesDir)
                                    .sorted(java.util.Comparator.reverseOrder())
                                    .map(Path::toFile)
                                    .forEach(java.io.File::delete);
                        }
                        Platform.runLater(() -> {
                            // Clear current chat UI
                            messagesBox.getChildren().clear();
                            if (friend != null) addDMHeader();
                            showNotification("All Messages Deleted", "All local message history has been permanently deleted");
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            showError("Deletion Failed", "Could not delete message history: " + e.getMessage());
                        });
                    }
                });
            }
        });
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

    // Public methods for MainController to call
    public void addMessageBubble(ChatMessage message) {
        System.out.println("[ChatController] Adding message bubble from: " + message.getFrom() +
                " (isFromMe: " + message.isFromMe() + ")");

        // Check if the message contains links and create enhanced version
        if (message.getContent().contains("http")) {
            mediaService.processTextMessage(message.getContent()).thenAccept(enhancedMessage -> {
                Platform.runLater(() -> {
                    addEnhancedMessageBubble(enhancedMessage, message.isFromMe());
                });
            });
        } else {
            // Regular message bubble
            Node bubble = createMessageBubble(message);
            messagesBox.getChildren().add(bubble);
        }
    }

    public void scrollToBottom() {
        Platform.runLater(() -> {
            scrollPane.setVvalue(1.0);
        });
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

    public void appendLocal(String text) {
        // This method is kept for compatibility but now we handle storage in onSend
        // Just add to UI since storage is handled elsewhere
        if (friend != null) {
            ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), text);
            addMessageBubble(outgoing);
            scrollToBottom();
        }
    }
}