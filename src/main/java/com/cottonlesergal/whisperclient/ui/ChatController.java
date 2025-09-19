package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.Friend;
import com.cottonlesergal.whisperclient.services.*;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import com.cottonlesergal.whisperclient.ui.components.EnhancedMessageComponents;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatController {
    @FXML private ScrollPane scrollPane;
    @FXML private VBox messagesBox;
    @FXML private TextField txtMessage;
    @FXML private Button btnAttach;

    private final DirectoryClient directory = new DirectoryClient();
    private final MessageStorageService storage = MessageStorageService.getInstance();
    private final MediaMessageService mediaService = MediaMessageService.getInstance();
    private final FileValidationService fileValidator = FileValidationService.getInstance();
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
        setupKeyboardShortcuts();
        setupClipboardPaste();

        System.out.println("[ChatController] Initialized with media support and file restrictions");
    }

    private void setupScrollPane() {
        scrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() <= 0.01 && !isLoading.get() && hasMoreMessages.get()) {
                loadMoreMessages();
            }
        });

        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    }

    private void setupLoadingIndicator() {
        loadingContainer = new HBox();
        loadingContainer.setAlignment(Pos.CENTER);
        loadingContainer.setPadding(new Insets(10));
        loadingContainer.setVisible(false);
        loadingContainer.setManaged(false);

        VBox shimmerContainer = new VBox(8);
        shimmerContainer.setAlignment(Pos.CENTER);

        for (int i = 0; i < 3; i++) {
            HBox shimmerBar = createShimmerBar(i == 1 ? 200 : 150);
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

        Region shimmerOverlay = new Region();
        shimmerOverlay.setPrefSize(50, 20);
        shimmerOverlay.setStyle(
                "-fx-background-color: linear-gradient(to right, " +
                        "transparent, rgba(255,255,255,0.3), transparent); " +
                        "-fx-background-radius: 10;"
        );

        shimmerBar.getChildren().add(shimmerOverlay);

        TranslateTransition shimmer = new TranslateTransition(Duration.seconds(1.5), shimmerOverlay);
        shimmer.setFromX(-50);
        shimmer.setToX(width);
        shimmer.setCycleCount(Animation.INDEFINITE);
        shimmer.play();

        return shimmerBar;
    }

    private void setupContextMenus() {
        ContextMenu chatAreaMenu = new ContextMenu();

        MenuItem clearHistory = new MenuItem("Clear Message History");
        clearHistory.setOnAction(e -> clearMessageHistory());

        MenuItem deleteLocalData = new MenuItem("Delete All Local Messages");
        deleteLocalData.setOnAction(e -> deleteAllLocalMessages());

        MenuItem showMediaStatus = new MenuItem("Show Media Status");
        showMediaStatus.setOnAction(e -> showMediaStatus());

        MenuItem resetMediaCount = new MenuItem("Reset Media Count");
        resetMediaCount.setOnAction(e -> resetMediaCount());

        chatAreaMenu.getItems().addAll(
                clearHistory,
                new SeparatorMenuItem(),
                showMediaStatus,
                resetMediaCount,
                new SeparatorMenuItem(),
                deleteLocalData
        );

        messagesBox.setOnContextMenuRequested(event -> {
            chatAreaMenu.show(messagesBox, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    private void setupDragAndDrop() {
        messagesBox.setOnDragOver(this::handleDragOver);
        messagesBox.setOnDragDropped(this::handleDragDropped);

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

            FileValidationService.ValidationResult validation =
                    fileValidator.validateFiles(files, friend.getUsername());

            if (!validation.isValid()) {
                showError("Upload Error", validation.getErrorMessage());
                event.setDropCompleted(false);
                event.consume();
                return;
            }

            for (File file : files) {
                sendFileMessage(file);
            }
            success = true;
        }

        event.setDropCompleted(success);
        event.consume();
    }

    private void setupKeyboardShortcuts() {
        txtMessage.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case V:
                    if (event.isControlDown()) {
                        handlePaste();
                        event.consume();
                    }
                    break;
                case O:
                    if (event.isControlDown()) {
                        onAttachFile();
                        event.consume();
                    }
                    break;
                case ENTER:
                    if (event.isShiftDown()) {
                        break;
                    } else {
                        onSend();
                        event.consume();
                    }
                    break;
            }
        });

        messagesBox.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case V:
                    if (event.isControlDown()) {
                        handlePaste();
                        event.consume();
                    }
                    break;
                case O:
                    if (event.isControlDown()) {
                        onAttachFile();
                        event.consume();
                    }
                    break;
            }
        });

        messagesBox.setFocusTraversable(true);
    }

    private void setupClipboardPaste() {
        scrollPane.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.V) {
                handlePaste();
                event.consume();
            }
        });

        messagesBox.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.V) {
                showPasteIndicator();
                handlePaste();
                event.consume();
            }
        });

        scrollPane.setFocusTraversable(true);
    }

    private void showPasteIndicator() {
        Label pasteIndicator = new Label("📋 Pasting...");
        pasteIndicator.setStyle(
                "-fx-text-fill: #87898c; -fx-font-size: 12px; " +
                        "-fx-background-color: rgba(0,0,0,0.5); -fx-background-radius: 4; " +
                        "-fx-padding: 4 8;"
        );

        messagesBox.getChildren().add(pasteIndicator);

        Platform.runLater(() -> {
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    Platform.runLater(() -> messagesBox.getChildren().remove(pasteIndicator));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });
    }

    private void handlePaste() {
        final javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();

        System.out.println("[ChatController] Handling paste operation...");
        System.out.println("  Has image: " + clipboard.hasImage());
        System.out.println("  Has files: " + clipboard.hasFiles());
        System.out.println("  Has string: " + clipboard.hasString());

        if (clipboard.hasImage()) {
            System.out.println("[ChatController] Pasting image from clipboard");
            handlePastedImage(clipboard.getImage());
        } else if (clipboard.hasFiles()) {
            System.out.println("[ChatController] Pasting files from clipboard");
            List<File> files = clipboard.getFiles();

            FileValidationService.ValidationResult validation =
                    fileValidator.validateFiles(files, friend.getUsername());

            if (!validation.isValid()) {
                showError("Paste Error", validation.getErrorMessage());
                return;
            }

            for (File file : files) {
                System.out.println("  Pasting file: " + file.getName());
                sendFileMessage(file);
            }
        } else if (clipboard.hasString()) {
            String clipboardText = clipboard.getString();
            if (clipboardText != null && !clipboardText.trim().isEmpty()) {
                if (isStandaloneUrl(clipboardText.trim())) {
                    System.out.println("[ChatController] Pasting URL: " + clipboardText.trim());
                    txtMessage.setText(clipboardText.trim());
                    onSend();
                } else {
                    String currentText = txtMessage.getText();
                    int caretPosition = txtMessage.getCaretPosition();
                    String newText = currentText.substring(0, caretPosition) +
                            clipboardText +
                            currentText.substring(caretPosition);
                    txtMessage.setText(newText);
                    txtMessage.positionCaret(caretPosition + clipboardText.length());
                }
            }
        }
    }

    private boolean isStandaloneUrl(String text) {
        return text.matches("^https?://.*") && !text.contains(" ") && !text.contains("\n");
    }

    private void handlePastedImage(javafx.scene.image.Image image) {
        if (friend == null) return;

        showUploadProgress("Processing pasted image...");

        CompletableFuture.runAsync(() -> {
            try {
                File tempFile = saveImageToTempFile(image);
                if (tempFile != null) {
                    Platform.runLater(() -> {
                        hideUploadProgress();
                        sendFileMessage(tempFile);

                        new Thread(() -> {
                            try {
                                Thread.sleep(10000);
                                tempFile.delete();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    });
                } else {
                    Platform.runLater(() -> {
                        hideUploadProgress();
                        showError("Paste Error", "Failed to process pasted image");
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hideUploadProgress();
                    showError("Paste Error", "Failed to paste image: " + e.getMessage());
                });
            }
        });
    }

    private File saveImageToTempFile(javafx.scene.image.Image image) {
        try {
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "whisperclient");
            Files.createDirectories(tempDir);

            File tempFile = tempDir.resolve("pasted_image_" + System.currentTimeMillis() + ".png").toFile();

            BufferedImage bufferedImage = SwingFXUtils.fromFXImage(image, null);
            ImageIO.write(bufferedImage, "png", tempFile);

            return tempFile;
        } catch (Exception e) {
            System.err.println("Failed to save pasted image: " + e.getMessage());
            return null;
        }
    }

    @FXML
    private void onAttachFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Send");

        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All Files", "*.*"),
                new FileChooser.ExtensionFilter("Images (max 100MB)", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter("Videos (max 100MB)", "*.mp4", "*.webm", "*.mov", "*.avi", "*.mkv"),
                new FileChooser.ExtensionFilter("Audio (max 100MB)", "*.mp3", "*.wav", "*.ogg", "*.m4a"),
                new FileChooser.ExtensionFilter("Documents (max 50MB)", "*.pdf", "*.doc", "*.docx", "*.txt", "*.rtf")
        );

        Stage stage = (Stage) txtMessage.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(stage);

        if (selectedFile != null) {
            FileValidationService.ValidationResult validation =
                    fileValidator.validateFile(selectedFile, friend.getUsername());

            if (!validation.isValid()) {
                showError("Upload Error", validation.getErrorMessage());
                return;
            }

            sendFileMessage(selectedFile);
        }
    }

    private void sendFileMessage(File file) {
        if (friend == null) return;

        FileValidationService.ValidationResult validation =
                fileValidator.validateFile(file, friend.getUsername());

        if (!validation.isValid()) {
            showError("Upload Error", validation.getErrorMessage());
            return;
        }

        showUploadProgress("Uploading " + file.getName() + "...");

        CompletableFuture.runAsync(() -> {
            try {
                MediaMessageService.EnhancedMessage enhancedMessage = mediaService.processFileUpload(file);

                Platform.runLater(() -> {
                    hideUploadProgress();

                    String fileMessage = "[FILE] " + file.getName() + " (" +
                            fileValidator.formatFileSize(file.length()) + ")";

                    directory.sendChat(friend.getUsername(), fileMessage);

                    ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), fileMessage);
                    storage.storeMessage(friend.getUsername(), outgoing);

                    String extension = file.getName().substring(file.getName().lastIndexOf('.') + 1).toLowerCase();
                    if (isMediaFile(extension)) {
                        fileValidator.incrementMediaCount(friend.getUsername());
                    }

                    addEnhancedMessageBubble(enhancedMessage, true);
                    scrollToBottom();

                    updateAttachmentButtonTooltip();

                    int remainingSlots = fileValidator.getRemainingMediaSlots(friend.getUsername());
                    String successMessage = "Sent " + file.getName();
                    if (isMediaFile(extension)) {
                        successMessage += String.format(" (%d media slots remaining)", remainingSlots);
                    }
                    showNotification("File Sent", successMessage);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    hideUploadProgress();
                    showError("File Error", "Failed to send file: " + e.getMessage());
                });
            }
        });
    }

    private boolean isMediaFile(String extension) {
        Set<String> mediaExtensions = Set.of(
                "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "svg",
                "mp4", "avi", "mov", "wmv", "flv", "webm", "mkv", "m4v", "3gp",
                "mp3", "wav", "ogg", "m4a", "aac", "flac", "wma"
        );
        return mediaExtensions.contains(extension);
    }

    private void showUploadProgress(String message) {
        if (txtMessage.getParent() instanceof HBox) {
            HBox parent = (HBox) txtMessage.getParent();

            HBox progressBox = new HBox(8);
            progressBox.setAlignment(Pos.CENTER_LEFT);
            progressBox.setId("upload-progress");

            ProgressIndicator progress = new ProgressIndicator();
            progress.setPrefSize(16, 16);

            Label progressLabel = new Label(message);
            progressLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 12px;");

            progressBox.getChildren().addAll(progress, progressLabel);

            if (parent.getParent() instanceof VBox) {
                VBox grandParent = (VBox) parent.getParent();
                grandParent.getChildren().add(grandParent.getChildren().size() - 1, progressBox);
            }
        }
    }

    private void hideUploadProgress() {
        if (txtMessage.getParent() instanceof HBox) {
            HBox parent = (HBox) txtMessage.getParent();
            if (parent.getParent() instanceof VBox) {
                VBox grandParent = (VBox) parent.getParent();
                grandParent.getChildren().removeIf(node -> "upload-progress".equals(node.getId()));
            }
        }
    }

    @FXML
    public void onSend() {
        String text = txtMessage.getText();
        if (text == null || text.isBlank() || friend == null) return;

        System.out.println("[ChatController] Sending message to: " + friend.getUsername() + " - " + text);

        mediaService.processTextMessage(text).thenAccept(enhancedMessage -> {
            Platform.runLater(() -> {
                directory.sendChat(friend.getUsername(), text);

                ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), text);
                storage.storeMessage(friend.getUsername(), outgoing);

                addEnhancedMessageBubble(enhancedMessage, true);
                scrollToBottom();
            });
        });

        txtMessage.clear();
    }

    public void bindFriend(Friend f) {
        System.out.println("[ChatController] Binding friend: " + f.getUsername());
        this.friend = f;

        messagesBox.getChildren().clear();
        currentPage.set(0);
        hasMoreMessages.set(true);

        updateAttachmentButtonTooltip();

        if (subChat != null) {
            try { subChat.close(); } catch (Exception ignored) {}
        }

        subChat = AppCtx.BUS.on("profile-updated", ev -> {
            if (ev.from != null && friend.getUsername().equalsIgnoreCase(ev.from)) {
                Platform.runLater(() -> {
                    if (ev.data != null) {
                        String newDisplay = ev.data.path("display").asText("");
                        String newAvatar = ev.data.path("avatar").asText("");

                        this.friend = new Friend(
                                friend.getUsername(),
                                friend.getPubKey(),
                                friend.getInboxUrl(),
                                newDisplay,
                                newAvatar
                        );

                        refreshDMHeader();
                        System.out.println("[ChatController] Updated friend profile: " + newDisplay);
                    }
                });
            }
        });

        loadInitialMessages();
    }

    private void updateAttachmentButtonTooltip() {
        if (btnAttach != null && friend != null) {
            int remainingSlots = fileValidator.getRemainingMediaSlots(friend.getUsername());
            String tooltipText = String.format("Attach File (Ctrl+O)\nMedia files: %d/10 remaining\nMax: 100MB (media), 50MB (files)",
                    remainingSlots);

            Tooltip tooltip = new Tooltip(tooltipText);
            btnAttach.setTooltip(tooltip);
        }
    }

    private void loadInitialMessages() {
        System.out.println("[ChatController] Loading initial messages for: " + friend.getUsername());

        currentPage.set(0);
        hasMoreMessages.set(true);

        CompletableFuture.supplyAsync(() -> {
            return storage.loadMessages(friend.getUsername(), 0, 100);
        }).thenAccept(messages -> Platform.runLater(() -> {
            messagesBox.getChildren().clear();

            addDMHeader();

            if (messages.isEmpty()) {
                System.out.println("[ChatController] No messages found, showing welcome message");
                addWelcomeMessage();
            } else {
                System.out.println("[ChatController] Loaded " + messages.size() + " messages");
                for (int i = messages.size() - 1; i >= 0; i--) {
                    addMessageBubble(messages.get(i));
                }
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

            double scrollPosition = scrollPane.getVvalue();
            double contentHeight = messagesBox.getHeight();

            for (int i = messages.size() - 1; i >= 0; i--) {
                messagesBox.getChildren().add(0, createMessageBubble(messages.get(i)));
            }

            Platform.runLater(() -> {
                double newContentHeight = messagesBox.getHeight();
                double heightDifference = newContentHeight - contentHeight;
                double newScrollValue = heightDifference / (messagesBox.getHeight() - scrollPane.getViewportBounds().getHeight());
                scrollPane.setVvalue(Math.max(0, newScrollValue + scrollPosition));
            });

            hasMoreMessages.set(messages.size() >= 100);
        }));
    }

    private void showLoadingIndicator() {
        if (!messagesBox.getChildren().contains(loadingContainer)) {
            messagesBox.getChildren().add(0, loadingContainer);
        }
        loadingContainer.setVisible(true);
        loadingContainer.setManaged(true);

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

    private void addEnhancedMessageBubble(MediaMessageService.EnhancedMessage enhancedMessage, boolean isFromMe) {
        VBox messageContainer = new VBox(8);
        messageContainer.getStyleClass().add("enhanced-message-container");
        messageContainer.setPadding(new Insets(4, 16, 4, 16));

        HBox messageRow = new HBox(12);
        messageRow.getStyleClass().add("message-row");
        messageRow.setAlignment(Pos.TOP_LEFT);

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

        VBox contentArea = new VBox(8);
        contentArea.setAlignment(Pos.TOP_LEFT);
        contentArea.setMaxWidth(600);

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

        if (enhancedMessage.getText() != null && !enhancedMessage.getText().trim().isEmpty()) {
            Label textContent = new Label(enhancedMessage.getText());
            textContent.setWrapText(true);
            textContent.setMaxWidth(580);
            textContent.getStyleClass().add("message-content");
            contentArea.getChildren().add(textContent);
        }

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

        ImageView avatar = new ImageView();
        avatar.setFitWidth(32);
        avatar.setFitHeight(32);

        if (message.isFromMe() && Session.me != null) {
            avatar.setImage(AvatarCache.get(Session.me.getAvatarUrl(), 32));
        } else {
            String avatarUrl = friend != null ? friend.getAvatar() : "";
            avatar.setImage(AvatarCache.get(avatarUrl, 32));
        }

        Circle clip = new Circle(16, 16, 16);
        avatar.setClip(clip);

        VBox contentArea = new VBox(4);
        contentArea.setAlignment(Pos.TOP_LEFT);

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label();
        nameLabel.getStyleClass().add("message-author");

        if (message.isFromMe() && Session.me != null) {
            nameLabel.setText(Session.me.getDisplayName());
        } else {
            nameLabel.setText(friend != null ? friend.getDisplayName() : message.getFrom());
        }

        LocalDateTime dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(message.getTimestamp()),
                ZoneId.systemDefault()
        );
        String timeStr = dateTime.format(DateTimeFormatter.ofPattern("h:mm a"));
        Label timestampLabel = new Label(timeStr);
        timestampLabel.getStyleClass().add("message-timestamp");

        header.getChildren().addAll(nameLabel, timestampLabel);

        Label contentLabel = new Label(message.getContent());
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(600);
        contentLabel.getStyleClass().add("message-content");

        contentArea.getChildren().addAll(header, contentLabel);
        messageRow.getChildren().addAll(avatar, contentArea);

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

    private void refreshDMHeader() {
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

        ImageView largeAvatar = new ImageView();
        largeAvatar.setFitWidth(80);
        largeAvatar.setFitHeight(80);

        String avatarUrl = friend != null ? friend.getAvatar() : "";
        largeAvatar.setImage(AvatarCache.get(avatarUrl, 80));

        Circle largeClip = new Circle(40, 40, 40);
        largeAvatar.setClip(largeClip);

        Label displayName = new Label(friend != null ? friend.getDisplayName() : "Unknown");
        displayName.getStyleClass().add("dm-header-name");

        Label subtitle = new Label("This is the beginning of your direct message history with @" +
                (friend != null ? friend.getUsername() : "unknown"));
        subtitle.getStyleClass().add("dm-header-subtitle");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(400);

        headerContainer.getChildren().addAll(largeAvatar, displayName, subtitle);
        return headerContainer;
    }

    private void editMessage(ChatMessage message, Label contentLabel) {
        TextInputDialog dialog = new TextInputDialog(message.getContent());
        dialog.setTitle("Edit Message");
        dialog.setHeaderText("Edit your message");
        dialog.setContentText("Message:");

        dialog.showAndWait().ifPresent(newContent -> {
            if (!newContent.trim().isEmpty() && !newContent.equals(message.getContent())) {
                message.setContent(newContent);
                contentLabel.setText(newContent);
                storage.storeMessage(friend.getUsername(), message);
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
                messagesBox.getChildren().remove(messageNode);
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
                messagesBox.getChildren().clear();
                addDMHeader();
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
                            Files.walk(messagesDir)
                                    .sorted(java.util.Comparator.reverseOrder())
                                    .map(Path::toFile)
                                    .forEach(java.io.File::delete);
                        }
                        Platform.runLater(() -> {
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

    @FXML
    private void showMediaStatus() {
        if (friend == null) return;

        int currentCount = fileValidator.getMediaCount(friend.getUsername());
        int remaining = fileValidator.getRemainingMediaSlots(friend.getUsername());

        String statusMessage = String.format(
                "Media Status for %s:\n\n" +
                        "Media files sent: %d/10\n" +
                        "Remaining slots: %d\n\n" +
                        "Limits:\n" +
                        "• Photos/Videos: 100MB max\n" +
                        "• Other files: 50MB max\n" +
                        "• Media limit: 10 per conversation",
                friend.getDisplayName(),
                currentCount,
                remaining
        );

        Alert statusDialog = new Alert(Alert.AlertType.INFORMATION);
        statusDialog.setTitle("Media Status");
        statusDialog.setHeaderText("File Upload Status");
        statusDialog.setContentText(statusMessage);
        statusDialog.showAndWait();
    }

    @FXML
    private void resetMediaCount() {
        if (friend == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Reset media counter for " + friend.getDisplayName() + "?\n\n" +
                        "This will allow you to send 10 more media files.",
                ButtonType.YES, ButtonType.NO);

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                fileValidator.resetMediaCount(friend.getUsername());
                updateAttachmentButtonTooltip();
                showNotification("Media Count Reset", "You can now send 10 more media files");
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

    public void addMessageBubble(ChatMessage message) {
        System.out.println("[ChatController] Adding message bubble from: " + message.getFrom() +
                " (isFromMe: " + message.isFromMe() + ")");

        if (message.getContent().contains("http")) {
            mediaService.processTextMessage(message.getContent()).thenAccept(enhancedMessage -> {
                Platform.runLater(() -> {
                    addEnhancedMessageBubble(enhancedMessage, message.isFromMe());
                });
            });
        } else {
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
        Region spacer = new Region();
        spacer.setPrefHeight(20);
        messagesBox.getChildren().add(spacer);
    }

    private void addDMHeader() {
        Node header = createDMHeader();
        messagesBox.getChildren().add(header);
    }

    public void appendLocal(String text) {
        if (friend != null) {
            ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), text);
            addMessageBubble(outgoing);
            scrollToBottom();
        }
    }
}