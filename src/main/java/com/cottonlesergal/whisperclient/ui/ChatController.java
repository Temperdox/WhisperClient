package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.Friend;
import com.cottonlesergal.whisperclient.services.*;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import com.cottonlesergal.whisperclient.services.EnhancedMediaService.MediaMessage;
import com.cottonlesergal.whisperclient.services.MediaPreviewService.MediaPreview;
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
import java.util.ArrayList;
import java.util.Base64;
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
    private final EnhancedMediaService mediaService = EnhancedMediaService.getInstance();
    private final MediaPreviewService previewService = MediaPreviewService.getInstance();
    private final NotificationManager notificationManager = NotificationManager.getInstance();

    private Friend friend;

    // Preview system
    private VBox previewContainer;
    private final List<MediaPreview> pendingUploads = new ArrayList<>();

    private final AtomicInteger currentPage = new AtomicInteger(0);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean hasMoreMessages = new AtomicBoolean(true);

    @FXML
    private void initialize() {
        setupScrollPane();
        setupContextMenus();
        setupDragAndDrop();
        setupKeyboardShortcuts();
        setupClipboardPaste();
        setupPreviewContainer();

        System.out.println("[ChatController] Initialized with preview system");
    }

    private void setupPreviewContainer() {
        // Create preview container that will be added above the message input
        previewContainer = new VBox(8);
        previewContainer.getStyleClass().add("preview-container");
        previewContainer.setPadding(new Insets(8));
        previewContainer.setVisible(false);
        previewContainer.setManaged(false);
        previewContainer.setStyle("-fx-background-color: #36393f; -fx-border-color: #40444b; -fx-border-width: 1 0 0 0;");
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

        MenuItem clearPreviews = new MenuItem("Clear All Previews");
        clearPreviews.setOnAction(e -> clearAllPreviews());

        chatAreaMenu.getItems().addAll(clearHistory, new SeparatorMenuItem(), clearPreviews);

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

            // Check total file count including pending
            if (pendingUploads.size() + files.size() > 10) {
                showError("Upload Error", "Cannot upload more than 10 files at once. You have " +
                        pendingUploads.size() + " files pending.");
                event.setDropCompleted(false);
                event.consume();
                return;
            }

            EnhancedMediaService.ValidationResult validation = mediaService.validateBatchUpload(files);
            if (!validation.isValid()) {
                showError("Upload Error", validation.getErrorMessage());
                event.setDropCompleted(false);
                event.consume();
                return;
            }

            addFilesToPreview(files);
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
                        if (!pendingUploads.isEmpty()) {
                            sendAllPreviews();
                        } else {
                            onSend();
                        }
                        event.consume();
                    }
                    break;
                case ESCAPE:
                    clearAllPreviews();
                    event.consume();
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

            if (pendingUploads.size() + files.size() > 10) {
                showError("Paste Error", "Cannot paste more than 10 files at once. You have " +
                        pendingUploads.size() + " files pending.");
                return;
            }

            EnhancedMediaService.ValidationResult validation = mediaService.validateBatchUpload(files);
            if (!validation.isValid()) {
                showError("Paste Error", validation.getErrorMessage());
                return;
            }

            addFilesToPreview(files);
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

        // Check if we can add more files
        if (pendingUploads.size() >= 10) {
            showError("Upload Limit", "Cannot upload more than 10 files at once.");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                File tempFile = saveImageToTempFile(image);
                if (tempFile != null) {
                    Platform.runLater(() -> {
                        String fileName = "pasted_image_" + System.currentTimeMillis() + ".png";
                        MediaPreview preview = new MediaPreview(image, fileName);
                        preview.setFile(tempFile);
                        addPreviewToContainer(preview);
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
            if (pendingUploads.size() + selectedFiles.size() > 10) {
                showError("Upload Error", "Cannot upload more than 10 files at once. You have " +
                        pendingUploads.size() + " files pending.");
                return;
            }

            EnhancedMediaService.ValidationResult validation = mediaService.validateBatchUpload(selectedFiles);
            if (!validation.isValid()) {
                showError("Upload Error", validation.getErrorMessage());
                return;
            }

            addFilesToPreview(selectedFiles);
        }
    }

    private void addFilesToPreview(List<File> files) {
        for (File file : files) {
            String mediaType = determineMediaType(file);
            MediaPreview preview = new MediaPreview(file, mediaType);
            addPreviewToContainer(preview);
        }
    }

    private void addPreviewToContainer(MediaPreview preview) {
        // Create preview component
        VBox previewComponent;
        if ("image".equals(preview.getMediaType())) {
            previewComponent = previewService.createImagePreview(preview);
        } else {
            previewComponent = previewService.createFilePreview(preview);
        }

        // Set up remove action
        preview.setOnRemove(() -> {
            pendingUploads.remove(preview);
            previewContainer.getChildren().remove(previewComponent);
            updatePreviewVisibility();

            // Clean up temp file if it's a pasted image
            if (preview.getFile() != null && preview.getFile().getName().startsWith("pasted_image_")) {
                preview.getFile().delete();
            }
        });

        // Set up send action
        preview.setOnSend(() -> {
            sendSinglePreview(preview);
        });

        pendingUploads.add(preview);
        previewContainer.getChildren().add(previewComponent);
        updatePreviewVisibility();

        // Add preview container to the parent if not already added
        if (txtMessage.getParent() instanceof HBox) {
            HBox parent = (HBox) txtMessage.getParent();
            if (parent.getParent() instanceof VBox) {
                VBox grandParent = (VBox) parent.getParent();
                if (!grandParent.getChildren().contains(previewContainer)) {
                    // Add preview container above the message input
                    int inputIndex = grandParent.getChildren().indexOf(parent);
                    grandParent.getChildren().add(inputIndex, previewContainer);
                }
            }
        }
    }

    private void updatePreviewVisibility() {
        boolean hasUploads = !pendingUploads.isEmpty();
        previewContainer.setVisible(hasUploads);
        previewContainer.setManaged(hasUploads);

        // Update message input placeholder
        if (hasUploads) {
            txtMessage.setPromptText("Add a caption or press Enter to send " + pendingUploads.size() + " file(s)...");
        } else {
            txtMessage.setPromptText("Type a message or paste media (Ctrl+V)...");
        }
    }

    private String determineMediaType(File file) {
        String fileName = file.getName().toLowerCase();
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);

        if (Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(extension)) {
            return "image";
        } else if (Set.of("mp4", "avi", "mov", "wmv", "webm", "mkv").contains(extension)) {
            return "video";
        } else if (Set.of("mp3", "wav", "ogg", "m4a", "aac", "flac").contains(extension)) {
            return "audio";
        } else {
            return "file";
        }
    }

    @FXML
    public void onSend() {
        if (!pendingUploads.isEmpty()) {
            sendAllPreviews();
        } else {
            sendTextMessage();
        }
    }

    private void sendTextMessage() {
        String text = txtMessage.getText();
        if (text == null || text.isBlank() || friend == null) return;

        directory.sendChat(friend.getUsername(), text);

        ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), text);
        storage.storeMessage(friend.getUsername(), outgoing);

        addTextMessageBubble(text, true);
        scrollToBottom();
        txtMessage.clear();
    }

    private void sendAllPreviews() {
        String caption = txtMessage.getText().trim();

        // Send caption first if present
        if (!caption.isEmpty()) {
            sendTextMessage();
        }

        // Send all pending uploads
        for (MediaPreview preview : new ArrayList<>(pendingUploads)) {
            sendSinglePreview(preview);
        }
    }

    private void sendSinglePreview(MediaPreview preview) {
        if (friend == null) return;

        // Show progress
        previewService.showProgress(preview, "Encoding file...");

        mediaService.processFileAsync(preview.getFile(), status -> {
            previewService.updateProgress(preview, -1, status);
        }).thenAccept(mediaMessage -> {
            Platform.runLater(() -> {
                // Update progress
                previewService.updateProgress(preview, 0.8, "Sending...");

                // Send media message
                String mediaMessageText = mediaService.createMediaMessageText(mediaMessage);
                directory.sendChat(friend.getUsername(), mediaMessageText);

                // Store locally
                ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), mediaMessageText);
                storage.storeMessage(friend.getUsername(), outgoing);

                // Display in chat
                displayMediaMessage(mediaMessage, true);
                scrollToBottom();

                // Remove from preview
                pendingUploads.remove(preview);
                previewContainer.getChildren().remove(preview.getPreviewComponent());
                updatePreviewVisibility();

                // Clean up temp file
                if (preview.getFile().getName().startsWith("pasted_image_")) {
                    preview.getFile().delete();
                }

                previewService.updateProgress(preview, 1.0, "Sent!");
            });
        }).exceptionally(throwable -> {
            Platform.runLater(() -> {
                previewService.hideProgress(preview);
                showError("Upload Failed", "Failed to send " + preview.getFileName() + ": " + throwable.getMessage());
            });
            return null;
        });
    }

    private void clearAllPreviews() {
        for (MediaPreview preview : new ArrayList<>(pendingUploads)) {
            // Clean up temp files
            if (preview.getFile() != null && preview.getFile().getName().startsWith("pasted_image_")) {
                preview.getFile().delete();
            }
        }

        pendingUploads.clear();
        previewContainer.getChildren().clear();
        updatePreviewVisibility();
    }

    public void bindFriend(Friend f) {
        this.friend = f;
        messagesBox.getChildren().clear();
        currentPage.set(0);
        hasMoreMessages.set(true);

        // Clear any pending uploads when switching chats
        clearAllPreviews();

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
            MediaMessage mediaMessage = mediaService.extractMediaMessage(content);
            if (mediaMessage != null) {
                displayMediaMessage(mediaMessage, message.isFromMe());
            } else {
                addTextMessageBubble(content, message.isFromMe());
            }
        } else {
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

        switch (mediaMessage.getMediaType()) {
            case "image":
                displayImageMessage(messageContainer, mediaMessage);
                break;
            case "video":
                displayVideoMessage(messageContainer, mediaMessage);
                break;
            case "audio":
                displayAudioMessage(messageContainer, mediaMessage);
                break;
            default:
                displayFileMessage(messageContainer, mediaMessage);
                break;
        }

        messagesBox.getChildren().add(messageContainer);
    }

    private VBox createMessageContainer(boolean isFromMe) {
        VBox container = new VBox(8);
        container.setPadding(new Insets(4, 16, 4, 16));

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

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

    private void displayImageMessage(VBox container, MediaMessage mediaMessage) {
        // Show progress bar while decoding
        ProgressBar progressBar = new ProgressBar();
        progressBar.setPrefWidth(280);
        progressBar.setProgress(-1); // Indeterminate
        progressBar.getStyleClass().add("upload-progress");

        Label statusLabel = new Label("Loading image...");
        statusLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px;");

        container.getChildren().addAll(progressBar, statusLabel);

        // Decode image in background
        CompletableFuture.runAsync(() -> {
            try {
                byte[] imageData = Base64.getDecoder().decode(mediaMessage.getBase64Data());
                Image image = new Image(new ByteArrayInputStream(imageData));

                Platform.runLater(() -> {
                    // Remove progress indicators
                    container.getChildren().removeAll(progressBar, statusLabel);

                    // Add image
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

                    // Show filename and actual file size
                    Label filename = new Label(mediaMessage.getFileName() +
                            " (" + mediaService.formatFileSize(mediaMessage.getFileSize()) + ")");
                    filename.getStyleClass().add("media-filename");
                    filename.setStyle("-fx-text-fill: #b5bac1; -fx-font-size: 12px;");
                    imageContainer.getChildren().add(filename);

                    container.getChildren().add(imageContainer);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    container.getChildren().removeAll(progressBar, statusLabel);
                    Label errorLabel = new Label("Failed to load image: " + mediaMessage.getFileName() +
                            " (Size: " + mediaService.formatFileSize(mediaMessage.getFileSize()) + ")");
                    errorLabel.setStyle("-fx-text-fill: #f04747; -fx-font-size: 12px;");
                    container.getChildren().add(errorLabel);
                });
            }
        });
    }

    private void displayVideoMessage(VBox container, MediaMessage mediaMessage) {
        HBox videoContainer = new HBox(12);
        videoContainer.getStyleClass().add("media-container");
        videoContainer.setPadding(new Insets(12));
        videoContainer.setStyle("-fx-background-color: #2b2d31; -fx-background-radius: 8; -fx-border-color: #40444b; -fx-border-width: 1; -fx-border-radius: 8;");

        Label videoIcon = new Label("🎥");
        videoIcon.setStyle("-fx-font-size: 32px;");

        VBox videoInfo = new VBox(4);
        Label fileName = new Label(mediaMessage.getFileName());
        fileName.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px; -fx-font-weight: 600;");

        Label fileSize = new Label(mediaService.formatFileSize(mediaMessage.getFileSize()));
        fileSize.setStyle("-fx-text-fill: #b5bac1; -fx-font-size: 11px;");

        Button downloadButton = new Button("Download & Play");
        downloadButton.setStyle("-fx-background-color: #5865f2; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 6 12;");
        downloadButton.setOnAction(e -> reconstructAndOpen(mediaMessage));

        videoInfo.getChildren().addAll(fileName, fileSize, downloadButton);
        videoContainer.getChildren().addAll(videoIcon, videoInfo);
        container.getChildren().add(videoContainer);
    }

    private void displayAudioMessage(VBox container, MediaMessage mediaMessage) {
        HBox audioContainer = new HBox(12);
        audioContainer.setPadding(new Insets(12));
        audioContainer.setStyle("-fx-background-color: #2b2d31; -fx-background-radius: 8; -fx-border-color: #40444b; -fx-border-width: 1; -fx-border-radius: 8;");

        Label audioIcon = new Label("🎵");
        audioIcon.setStyle("-fx-font-size: 32px;");

        VBox audioInfo = new VBox(4);
        Label fileName = new Label(mediaMessage.getFileName());
        fileName.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px; -fx-font-weight: 600;");

        Label fileSize = new Label(mediaService.formatFileSize(mediaMessage.getFileSize()));
        fileSize.setStyle("-fx-text-fill: #b5bac1; -fx-font-size: 11px;");

        Button playButton = new Button("Download & Play");
        playButton.setStyle("-fx-background-color: #5865f2; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 6 12;");
        playButton.setOnAction(e -> reconstructAndOpen(mediaMessage));

        audioInfo.getChildren().addAll(fileName, fileSize, playButton);
        audioContainer.getChildren().addAll(audioIcon, audioInfo);
        container.getChildren().add(audioContainer);
    }

    private void displayFileMessage(VBox container, MediaMessage mediaMessage) {
        HBox fileContainer = new HBox(12);
        fileContainer.setPadding(new Insets(12));
        fileContainer.setStyle("-fx-background-color: #2b2d31; -fx-background-radius: 8; -fx-border-color: #40444b; -fx-border-width: 1; -fx-border-radius: 8;");

        Label fileIcon = new Label("📎");
        fileIcon.setStyle("-fx-font-size: 32px;");

        VBox fileInfo = new VBox(4);
        Label fileName = new Label(mediaMessage.getFileName());
        fileName.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px; -fx-font-weight: 600;");

        Label fileSize = new Label(mediaService.formatFileSize(mediaMessage.getFileSize()));
        fileSize.setStyle("-fx-text-fill: #b5bac1; -fx-font-size: 11px;");

        Button downloadButton = new Button("Download");
        downloadButton.setStyle("-fx-background-color: #5865f2; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 6 12;");
        downloadButton.setOnAction(e -> reconstructAndOpen(mediaMessage));

        fileInfo.getChildren().addAll(fileName, fileSize, downloadButton);
        fileContainer.getChildren().addAll(fileIcon, fileInfo);
        container.getChildren().add(fileContainer);
    }

    private void reconstructAndOpen(MediaMessage mediaMessage) {
        // Create progress dialog
        ProgressBar progressBar = new ProgressBar();
        progressBar.setPrefWidth(200);
        progressBar.setProgress(-1);

        Label statusLabel = new Label("Reconstructing file...");
        statusLabel.setStyle("-fx-text-fill: #dcddde;");

        VBox progressContainer = new VBox(8);
        progressContainer.setAlignment(Pos.CENTER);
        progressContainer.setPadding(new Insets(20));
        progressContainer.getChildren().addAll(statusLabel, progressBar);

        Alert progressDialog = new Alert(Alert.AlertType.INFORMATION);
        progressDialog.setTitle("Loading File");
        progressDialog.setHeaderText("Please wait...");
        progressDialog.getDialogPane().setContent(progressContainer);
        progressDialog.show();

        mediaService.reconstructFileAsync(mediaMessage, status -> {
            Platform.runLater(() -> statusLabel.setText(status));
        }).thenAccept(reconstructedFile -> {
            Platform.runLater(() -> {
                progressDialog.close();

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
                progressDialog.close();
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

        ScrollPane imageScrollPane = new ScrollPane(fullImageView);
        imageScrollPane.setFitToWidth(true);
        imageScrollPane.setFitToHeight(true);

        javafx.scene.Scene scene = new javafx.scene.Scene(imageScrollPane, 820, 620);
        imageStage.setScene(scene);
        imageStage.show();
    }

    private void addTextMessageBubble(String text, boolean isFromMe) {
        VBox container = createMessageContainer(isFromMe);

        Label textLabel = new Label(text);
        textLabel.setWrapText(true);
        textLabel.setMaxWidth(580);
        textLabel.getStyleClass().add("message-content");
        textLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 15px;");

        container.getChildren().add(textLabel);
        messagesBox.getChildren().add(container);
    }

    private Node createMessageBubble(ChatMessage message) {
        if (mediaService.isMediaMessage(message.getContent())) {
            MediaMessage mediaMessage = mediaService.extractMediaMessage(message.getContent());
            if (mediaMessage != null) {
                VBox container = createMessageContainer(message.isFromMe());

                switch (mediaMessage.getMediaType()) {
                    case "image":
                        displayImageMessage(container, mediaMessage);
                        break;
                    case "video":
                        displayVideoMessage(container, mediaMessage);
                        break;
                    case "audio":
                        displayAudioMessage(container, mediaMessage);
                        break;
                    default:
                        displayFileMessage(container, mediaMessage);
                        break;
                }

                return container;
            }
        }

        VBox container = createMessageContainer(message.isFromMe());

        Label textLabel = new Label(message.getContent());
        textLabel.setWrapText(true);
        textLabel.setMaxWidth(580);
        textLabel.getStyleClass().add("message-content");
        textLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 15px;");

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
        if (notificationManager != null) {
            notificationManager.showErrorNotification(title, message);
        } else {
            // Fallback
            Alert alert = new Alert(Alert.AlertType.ERROR, message);
            alert.setTitle(title);
            alert.showAndWait();
        }
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