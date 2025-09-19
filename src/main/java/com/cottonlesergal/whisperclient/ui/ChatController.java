package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.AppCtx;
import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.Friend;
import com.cottonlesergal.whisperclient.services.*;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import com.cottonlesergal.whisperclient.services.EnhancedMediaService.MediaMessage;
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
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
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
    private final EnhancedMediaService mediaService = EnhancedMediaService.getInstance();
    private final NotificationManager notificationManager = NotificationManager.getInstance();

    private Friend friend;
    private AutoCloseable subChat;

    private final AtomicInteger currentPage = new AtomicInteger(0);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean hasMoreMessages = new AtomicBoolean(true);

    // Track ongoing uploads
    private final AtomicInteger activeUploads = new AtomicInteger(0);

    @FXML
    private void initialize() {
        setupScrollPane();
        setupContextMenus();
        setupDragAndDrop();
        setupKeyboardShortcuts();
        setupClipboardPaste();

        System.out.println("[ChatController] Initialized with enhanced media processing");
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

    private void setupContextMenus() {
        ContextMenu chatAreaMenu = new ContextMenu();
        MenuItem clearHistory = new MenuItem("Clear Message History");
        clearHistory.setOnAction(e -> clearMessageHistory());
        chatAreaMenu.getItems().add(clearHistory);

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

            EnhancedMediaService.ValidationResult validation = mediaService.validateBatchUpload(files);
            if (!validation.isValid()) {
                showError("Upload Error", validation.getErrorMessage());
                event.setDropCompleted(false);
                event.consume();
                return;
            }

            processBatchUpload(files);
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
                    if (!event.isShiftDown()) {
                        onSend();
                        event.consume();
                    }
                    break;
            }
        });

        messagesBox.setFocusTraversable(true);
        messagesBox.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.V) {
                handlePaste();
                event.consume();
            }
        });
    }

    private void setupClipboardPaste() {
        scrollPane.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.V) {
                handlePaste();
                event.consume();
            }
        });
    }

    private void handlePaste() {
        final javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();

        if (clipboard.hasImage()) {
            handlePastedImage(clipboard.getImage());
        } else if (clipboard.hasFiles()) {
            List<File> files = clipboard.getFiles();

            EnhancedMediaService.ValidationResult validation = mediaService.validateBatchUpload(files);
            if (!validation.isValid()) {
                showError("Paste Error", validation.getErrorMessage());
                return;
            }

            processBatchUpload(files);
        } else if (clipboard.hasString()) {
            String clipboardText = clipboard.getString();
            if (clipboardText != null && !clipboardText.trim().isEmpty()) {
                if (isStandaloneUrl(clipboardText.trim())) {
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

    private void handlePastedImage(Image image) {
        if (friend == null) return;

        CompletableFuture.runAsync(() -> {
            try {
                File tempFile = saveImageToTempFile(image);
                if (tempFile != null) {
                    Platform.runLater(() -> {
                        processSingleFile(tempFile);

                        // Clean up temp file after processing
                        new Thread(() -> {
                            try {
                                Thread.sleep(30000); // 30 seconds
                                tempFile.delete();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("Paste Error", "Failed to paste image: " + e.getMessage());
                });
            }
        });
    }

    private File saveImageToTempFile(Image image) {
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
        fileChooser.setTitle("Select Files to Send (Max 10)");

        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All Files", "*.*"),
                new FileChooser.ExtensionFilter("Images (max 100MB)", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter("Videos (max 100MB)", "*.mp4", "*.webm", "*.mov", "*.avi", "*.mkv"),
                new FileChooser.ExtensionFilter("Audio (max 100MB)", "*.mp3", "*.wav", "*.ogg", "*.m4a"),
                new FileChooser.ExtensionFilter("Documents (max 50MB)", "*.pdf", "*.doc", "*.docx", "*.txt", "*.rtf")
        );

        Stage stage = (Stage) txtMessage.getScene().getWindow();
        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(stage);

        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            EnhancedMediaService.ValidationResult validation = mediaService.validateBatchUpload(selectedFiles);
            if (!validation.isValid()) {
                showError("Upload Error", validation.getErrorMessage());
                return;
            }

            processBatchUpload(selectedFiles);
        }
    }

    private void processBatchUpload(List<File> files) {
        for (File file : files) {
            processSingleFile(file);
        }
    }

    private void processSingleFile(File file) {
        if (friend == null) return;

        // Create upload progress indicator
        ProgressIndicator progressIndicator = createUploadProgress(file.getName());
        Label statusLabel = createStatusLabel("Preparing " + file.getName() + "...");

        activeUploads.incrementAndGet();
        updateUploadStatus();

        mediaService.processFileAsync(file, status -> {
            Platform.runLater(() -> statusLabel.setText(status));
        }).thenAccept(mediaMessage -> {
            Platform.runLater(() -> {
                // Remove progress indicator
                removeUploadProgress(progressIndicator, statusLabel);
                activeUploads.decrementAndGet();
                updateUploadStatus();

                // Send media message
                String mediaMessageText = mediaService.createMediaMessageText(mediaMessage);
                directory.sendChat(friend.getUsername(), mediaMessageText);

                // Store locally
                ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), mediaMessageText);
                storage.storeMessage(friend.getUsername(), outgoing);

                // Display media in chat
                displayMediaMessage(mediaMessage, true);
                scrollToBottom();

                notificationManager.showSuccessNotification("File Sent",
                        "Sent " + file.getName() + " (" + mediaService.formatFileSize(file.length()) + ")");
            });
        }).exceptionally(throwable -> {
            Platform.runLater(() -> {
                removeUploadProgress(progressIndicator, statusLabel);
                activeUploads.decrementAndGet();
                updateUploadStatus();
                showError("Upload Failed", "Failed to upload " + file.getName() + ": " + throwable.getMessage());
            });
            return null;
        });
    }

    private ProgressIndicator createUploadProgress(String fileName) {
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(20, 20);

        HBox progressContainer = new HBox(8);
        progressContainer.setAlignment(Pos.CENTER_LEFT);
        progressContainer.setPadding(new Insets(8));
        progressContainer.getStyleClass().add("upload-progress-container");
        progressContainer.setId("upload-" + fileName.hashCode());

        Label fileLabel = new Label(fileName);
        fileLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 12px;");

        progressContainer.getChildren().addAll(progress, fileLabel);
        messagesBox.getChildren().add(progressContainer);

        return progress;
    }

    private Label createStatusLabel(String status) {
        Label statusLabel = new Label(status);
        statusLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px;");
        return statusLabel;
    }

    private void removeUploadProgress(ProgressIndicator progress, Label statusLabel) {
        messagesBox.getChildren().removeIf(node -> {
            return node instanceof HBox && ((HBox) node).getChildren().contains(progress);
        });
    }

    private void updateUploadStatus() {
        int uploads = activeUploads.get();
        if (uploads > 0) {
            btnAttach.setText(uploads + "");
            btnAttach.setStyle("-fx-background-color: #f0b232; -fx-text-fill: white;");
        } else {
            btnAttach.setText("📎");
            btnAttach.setStyle(""); // Reset to default style
        }
    }

    @FXML
    public void onSend() {
        String text = txtMessage.getText();
        if (text == null || text.isBlank() || friend == null) return;

        // Send regular text message
        directory.sendChat(friend.getUsername(), text);

        ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), text);
        storage.storeMessage(friend.getUsername(), outgoing);

        addTextMessageBubble(text, true);
        scrollToBottom();
        txtMessage.clear();
    }

    public void bindFriend(Friend f) {
        this.friend = f;
        messagesBox.getChildren().clear();
        currentPage.set(0);
        hasMoreMessages.set(true);

        if (subChat != null) {
            try { subChat.close(); } catch (Exception ignored) {}
        }

        loadInitialMessages();
    }

    private void loadInitialMessages() {
        CompletableFuture.supplyAsync(() -> {
            return storage.loadMessages(friend.getUsername(), 0, 100);
        }).thenAccept(messages -> Platform.runLater(() -> {
            messagesBox.getChildren().clear();
            addDMHeader();

            if (!messages.isEmpty()) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    processStoredMessage(messages.get(i));
                }
            }
            scrollToBottom();
        }));
    }

    private void processStoredMessage(ChatMessage message) {
        String content = message.getContent();

        if (mediaService.isMediaMessage(content)) {
            // This is a media message - reconstruct it
            MediaMessage mediaMessage = mediaService.extractMediaMessage(content);
            if (mediaMessage != null) {
                displayMediaMessage(mediaMessage, message.isFromMe());
            } else {
                // Fallback to text if media parsing fails
                addTextMessageBubble(content, message.isFromMe());
            }
        } else {
            // Regular text message
            addTextMessageBubble(content, message.isFromMe());
        }
    }

    private void loadMoreMessages() {
        if (isLoading.get() || !hasMoreMessages.get()) return;
        isLoading.set(true);

        int nextPage = currentPage.incrementAndGet();
        CompletableFuture.supplyAsync(() -> {
            return storage.loadMessages(friend.getUsername(), nextPage, 100);
        }).thenAccept(messages -> Platform.runLater(() -> {
            isLoading.set(false);
            if (!messages.isEmpty()) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    Node messageBubble = createMessageBubble(messages.get(i));
                    messagesBox.getChildren().add(0, messageBubble);
                }
                hasMoreMessages.set(messages.size() >= 100);
            } else {
                hasMoreMessages.set(false);
            }
        }));
    }

    private void displayMediaMessage(MediaMessage mediaMessage, boolean isFromMe) {
        VBox messageContainer = createMessageContainer(isFromMe);

        // Add media content based on type
        switch (mediaMessage.getMediaType()) {
            case "image":
                displayImageMessage(messageContainer, mediaMessage, isFromMe);
                break;
            case "video":
                displayVideoMessage(messageContainer, mediaMessage, isFromMe);
                break;
            case "audio":
                displayAudioMessage(messageContainer, mediaMessage, isFromMe);
                break;
            default:
                displayFileMessage(messageContainer, mediaMessage, isFromMe);
                break;
        }

        messagesBox.getChildren().add(messageContainer);
    }

    private VBox createMessageContainer(boolean isFromMe) {
        VBox container = new VBox(8);
        container.setPadding(new Insets(4, 16, 4, 16));

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        // Avatar
        ImageView avatar = new ImageView();
        avatar.setFitWidth(32);
        avatar.setFitHeight(32);
        Circle clip = new Circle(16, 16, 16);
        avatar.setClip(clip);

        if (isFromMe && Session.me != null) {
            avatar.setImage(AvatarCache.get(Session.me.getAvatarUrl(), 32));
        } else {
            String avatarUrl = friend != null ? friend.getAvatar() : "";
            avatar.setImage(AvatarCache.get(avatarUrl, 32));
        }

        // Name and timestamp
        VBox nameArea = new VBox(2);
        Label nameLabel = new Label();
        nameLabel.getStyleClass().add("message-author");

        if (isFromMe && Session.me != null) {
            nameLabel.setText(Session.me.getDisplayName());
        } else {
            nameLabel.setText(friend != null ? friend.getDisplayName() : "Unknown");
        }

        Label timestamp = new Label(LocalDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a")));
        timestamp.getStyleClass().add("message-timestamp");

        nameArea.getChildren().addAll(nameLabel, timestamp);
        header.getChildren().addAll(avatar, nameArea);
        container.getChildren().add(header);

        return container;
    }

    private void displayImageMessage(VBox container, MediaMessage mediaMessage, boolean isFromMe) {
        try {
            byte[] imageData = Base64.getDecoder().decode(mediaMessage.getBase64Data());
            Image image = new Image(new ByteArrayInputStream(imageData));

            ImageView imageView = new ImageView(image);
            imageView.setFitWidth(400);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);

            Rectangle clip = new Rectangle();
            clip.setArcWidth(12);
            clip.setArcHeight(12);
            clip.widthProperty().bind(imageView.fitWidthProperty());
            clip.heightProperty().bind(imageView.fitHeightProperty());
            imageView.setClip(clip);

            imageView.getStyleClass().add("message-image");
            imageView.setOnMouseClicked(e -> openImageFullscreen(image));

            VBox imageContainer = new VBox(4);
            imageContainer.getChildren().add(imageView);

            Label filename = new Label(mediaMessage.getFileName());
            filename.getStyleClass().add("media-filename");
            imageContainer.getChildren().add(filename);

            container.getChildren().add(imageContainer);

        } catch (Exception e) {
            Label errorLabel = new Label("Failed to load image: " + mediaMessage.getFileName());
            errorLabel.getStyleClass().add("error-message");
            container.getChildren().add(errorLabel);
        }
    }

    private void displayVideoMessage(VBox container, MediaMessage mediaMessage, boolean isFromMe) {
        HBox videoContainer = new HBox(12);
        videoContainer.getStyleClass().add("media-container");
        videoContainer.setPadding(new Insets(12));

        Label videoIcon = new Label("🎥");
        videoIcon.setStyle("-fx-font-size: 32px;");

        VBox videoInfo = new VBox(4);
        Label fileName = new Label(mediaMessage.getFileName());
        fileName.getStyleClass().add("media-filename");

        Label fileSize = new Label(mediaService.formatFileSize(mediaMessage.getFileSize()));
        fileSize.getStyleClass().add("media-filesize");

        Button playButton = new Button("Download & Play");
        playButton.getStyleClass().add("media-action-button");
        playButton.setOnAction(e -> reconstructAndOpen(mediaMessage));

        videoInfo.getChildren().addAll(fileName, fileSize, playButton);
        videoContainer.getChildren().addAll(videoIcon, videoInfo);
        container.getChildren().add(videoContainer);
    }

    private void displayAudioMessage(VBox container, MediaMessage mediaMessage, boolean isFromMe) {
        HBox audioContainer = new HBox(12);
        audioContainer.getStyleClass().add("media-container");
        audioContainer.setPadding(new Insets(12));

        Label audioIcon = new Label("🎵");
        audioIcon.setStyle("-fx-font-size: 32px;");

        VBox audioInfo = new VBox(4);
        Label fileName = new Label(mediaMessage.getFileName());
        fileName.getStyleClass().add("media-filename");

        Label fileSize = new Label(mediaService.formatFileSize(mediaMessage.getFileSize()));
        fileSize.getStyleClass().add("media-filesize");

        Button playButton = new Button("Download & Play");
        playButton.getStyleClass().add("media-action-button");
        playButton.setOnAction(e -> reconstructAndOpen(mediaMessage));

        audioInfo.getChildren().addAll(fileName, fileSize, playButton);
        audioContainer.getChildren().addAll(audioIcon, audioInfo);
        container.getChildren().add(audioContainer);
    }

    private void displayFileMessage(VBox container, MediaMessage mediaMessage, boolean isFromMe) {
        HBox fileContainer = new HBox(12);
        fileContainer.getStyleClass().add("media-container");
        fileContainer.setPadding(new Insets(12));

        Label fileIcon = new Label("📎");
        fileIcon.setStyle("-fx-font-size: 32px;");

        VBox fileInfo = new VBox(4);
        Label fileName = new Label(mediaMessage.getFileName());
        fileName.getStyleClass().add("media-filename");

        Label fileSize = new Label(mediaService.formatFileSize(mediaMessage.getFileSize()));
        fileSize.getStyleClass().add("media-filesize");

        Button downloadButton = new Button("Download");
        downloadButton.getStyleClass().add("media-action-button");
        downloadButton.setOnAction(e -> reconstructAndOpen(mediaMessage));

        fileInfo.getChildren().addAll(fileName, fileSize, downloadButton);
        fileContainer.getChildren().addAll(fileIcon, fileInfo);
        container.getChildren().add(fileContainer);
    }

    private void reconstructAndOpen(MediaMessage mediaMessage) {
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(16, 16);

        Label statusLabel = new Label("Reconstructing file...");
        statusLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px;");

        HBox progressContainer = new HBox(8);
        progressContainer.setAlignment(Pos.CENTER_LEFT);
        progressContainer.getChildren().addAll(progress, statusLabel);
        messagesBox.getChildren().add(progressContainer);

        mediaService.reconstructFileAsync(mediaMessage, status -> {
            Platform.runLater(() -> statusLabel.setText(status));
        }).thenAccept(reconstructedFile -> {
            Platform.runLater(() -> {
                messagesBox.getChildren().remove(progressContainer);

                try {
                    java.awt.Desktop.getDesktop().open(reconstructedFile);
                    notificationManager.showSuccessNotification("File Opened",
                            "Opened " + mediaMessage.getFileName());
                } catch (Exception e) {
                    showError("Open Failed", "Could not open file: " + e.getMessage());
                }
            });
        }).exceptionally(throwable -> {
            Platform.runLater(() -> {
                messagesBox.getChildren().remove(progressContainer);
                showError("Reconstruction Failed", throwable.getMessage());
            });
            return null;
        });
    }

    private void openImageFullscreen(Image image) {
        Stage imageStage = new Stage();
        imageStage.setTitle("Image Viewer");

        ImageView fullImageView = new ImageView(image);
        fullImageView.setPreserveRatio(true);
        fullImageView.setFitWidth(800);
        fullImageView.setFitHeight(600);

        ScrollPane scrollPane = new ScrollPane(fullImageView);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);

        javafx.scene.Scene scene = new javafx.scene.Scene(scrollPane, 820, 620);
        imageStage.setScene(scene);
        imageStage.show();
    }

    private void addTextMessageBubble(String text, boolean isFromMe) {
        VBox container = createMessageContainer(isFromMe);

        Label textLabel = new Label(text);
        textLabel.setWrapText(true);
        textLabel.setMaxWidth(580);
        textLabel.getStyleClass().add("message-content");

        container.getChildren().add(textLabel);
        messagesBox.getChildren().add(container);
    }

    private Node createMessageBubble(ChatMessage message) {
        // Check if it's a media message
        if (mediaService.isMediaMessage(message.getContent())) {
            MediaMessage mediaMessage = mediaService.extractMediaMessage(message.getContent());
            if (mediaMessage != null) {
                VBox container = createMessageContainer(message.isFromMe());

                switch (mediaMessage.getMediaType()) {
                    case "image":
                        displayImageMessage(container, mediaMessage, message.isFromMe());
                        break;
                    case "video":
                        displayVideoMessage(container, mediaMessage, message.isFromMe());
                        break;
                    case "audio":
                        displayAudioMessage(container, mediaMessage, message.isFromMe());
                        break;
                    default:
                        displayFileMessage(container, mediaMessage, message.isFromMe());
                        break;
                }

                return container;
            }
        }

        // Regular text message
        VBox container = createMessageContainer(message.isFromMe());

        Label textLabel = new Label(message.getContent());
        textLabel.setWrapText(true);
        textLabel.setMaxWidth(580);
        textLabel.getStyleClass().add("message-content");

        container.getChildren().add(textLabel);
        return container;
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

    private void clearMessageHistory() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Clear message history with " + (friend != null ? friend.getDisplayName() : "this user") + "?",
                ButtonType.YES, ButtonType.NO);

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                messagesBox.getChildren().clear();
                addDMHeader();
            }
        });
    }

    private void showError(String title, String message) {
        notificationManager.showErrorNotification(title, message);
    }

    public void addMessageBubble(ChatMessage message) {
        Node bubble = createMessageBubble(message);
        messagesBox.getChildren().add(bubble);
    }

    public void scrollToBottom() {
        Platform.runLater(() -> scrollPane.setVvalue(1.0));
    }

    private void addDMHeader() {
        messagesBox.getChildren().add(createDMHeader());
    }

    public void appendLocal(String text) {
        if (friend != null) {
            ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), text);
            addMessageBubble(outgoing);
            scrollToBottom();
        }
    }
}