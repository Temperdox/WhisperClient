package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.Friend;
import com.cottonlesergal.whisperclient.services.*;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import com.cottonlesergal.whisperclient.services.SimpleMediaService.SimpleMediaMessage;
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
import javafx.scene.input.*;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private final SimpleMediaService mediaService = SimpleMediaService.getInstance();
    private final MediaPreviewService previewService = MediaPreviewService.getInstance();
    private final NotificationManager notificationManager = NotificationManager.getInstance();
    private final MessageChunkingService chunkingService = MessageChunkingService.getInstance();

    private Friend friend;

    // Preview system
    private VBox previewContainer;
    private final List<MediaPreview> pendingUploads = new ArrayList<>();

    // Pagination and loading
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
        setupAttachButton();

        System.out.println("[ChatController] Initialized with enhanced media system and chunking support");
    }

    private void setupAttachButton() {
        if (btnAttach != null) {
            btnAttach.setOnAction(this::onAttachFile);
            btnAttach.setTooltip(new Tooltip("Attach files (images, videos, audio, documents)"));
        }
    }

    @FXML
    private void onAttachFile(javafx.event.ActionEvent event) {
        openFileChooser();
    }

    private void openFileChooser() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Files to Send");

        // Set up file filters
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All Supported",
                        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp",
                        "*.mp4", "*.webm", "*.mov", "*.avi", "*.mkv",
                        "*.mp3", "*.wav", "*.ogg", "*.m4a", "*.flac",
                        "*.pdf", "*.txt", "*.doc", "*.docx", "*.zip"),
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.webm", "*.mov", "*.avi", "*.mkv"),
                new FileChooser.ExtensionFilter("Audio", "*.mp3", "*.wav", "*.ogg", "*.m4a", "*.flac"),
                new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.txt", "*.doc", "*.docx"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        // Set initial directory to user's documents or pictures
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            File documentsDir = new File(userHome, "Documents");
            File picturesDir = new File(userHome, "Pictures");

            if (picturesDir.exists()) {
                fileChooser.setInitialDirectory(picturesDir);
            } else if (documentsDir.exists()) {
                fileChooser.setInitialDirectory(documentsDir);
            }
        }

        // Show file chooser
        Stage stage = (Stage) txtMessage.getScene().getWindow();
        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(stage);

        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            System.out.println("[ChatController] Selected " + selectedFiles.size() + " files");

            if (pendingUploads.size() + selectedFiles.size() > 10) {
                showError("Upload Limit", "Cannot upload more than 10 files at once. Please select fewer files.");
                return;
            }

            for (File file : selectedFiles) {
                processFileForUpload(file);
            }
        }
    }

    private void setupPreviewContainer() {
        previewContainer = new VBox(8);
        previewContainer.getStyleClass().add("preview-container");
        previewContainer.setPadding(new Insets(8));
        previewContainer.setVisible(false);
        previewContainer.setManaged(false);
        previewContainer.setStyle("-fx-background-color: #36393f; -fx-border-color: #40444b; -fx-border-width: 1 0 0 0;");

        // Add preview container to the UI structure
        Platform.runLater(() -> {
            if (scrollPane.getParent() instanceof VBox) {
                VBox parent = (VBox) scrollPane.getParent();
                // Find the input area (HBox with text field)
                Node inputArea = null;
                for (Node child : parent.getChildren()) {
                    if (child instanceof HBox) {
                        inputArea = child;
                        break;
                    }
                }

                if (inputArea != null) {
                    int inputIndex = parent.getChildren().indexOf(inputArea);
                    parent.getChildren().add(inputIndex, previewContainer);
                    System.out.println("[ChatController] Added preview container to UI");
                } else {
                    System.err.println("[ChatController] Could not find input area to add preview container");
                }
            } else {
                System.err.println("[ChatController] ScrollPane parent is not VBox: " +
                        (scrollPane.getParent() != null ? scrollPane.getParent().getClass().getSimpleName() : "null"));
            }
        });
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
        MenuItem refreshChat = new MenuItem("Refresh Conversation");
        refreshChat.setOnAction(e -> refreshConversation());
        MenuItem pasteFile = new MenuItem("Paste Image/File");
        pasteFile.setOnAction(e -> handleClipboardPaste());

        chatAreaMenu.getItems().addAll(clearHistory, new SeparatorMenuItem(), clearPreviews, refreshChat, new SeparatorMenuItem(), pasteFile);

        // Update context menu based on clipboard content
        chatAreaMenu.setOnShowing(e -> {
            pasteFile.setDisable(!hasImageOrFileInClipboard());
        });

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

    private void setupKeyboardShortcuts() {
        // Scene-level key handling for global shortcuts
        Platform.runLater(() -> {
            if (txtMessage.getScene() != null) {
                txtMessage.getScene().setOnKeyPressed(event -> {
                    // Global Ctrl+V for pasting images/files
                    if (event.isControlDown() && event.getCode() == KeyCode.V) {
                        if (!txtMessage.isFocused()) {
                            // If message field isn't focused, handle as file paste
                            handleClipboardPaste();
                            event.consume();
                        }
                    }
                });
            }
        });

        txtMessage.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                if (event.isShiftDown()) {
                    // Shift+Enter for new line
                    txtMessage.insertText(txtMessage.getCaretPosition(), "\n");
                } else {
                    // Enter to send
                    onSend();
                }
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.V) {
                // Check clipboard for images/files first, then allow normal text paste
                if (hasImageOrFileInClipboard()) {
                    handleClipboardPaste();
                    event.consume(); // Prevent default text paste
                }
                // If no images/files, allow normal text paste to proceed
            }
        });
    }

    private void setupClipboardPaste() {
        // Add context menu to message input for paste
        ContextMenu inputContextMenu = new ContextMenu();
        MenuItem pasteText = new MenuItem("Paste Text");
        pasteText.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            if (clipboard.hasString()) {
                txtMessage.insertText(txtMessage.getCaretPosition(), clipboard.getString());
            }
        });

        MenuItem pasteFile = new MenuItem("Paste Image/File");
        pasteFile.setOnAction(e -> handleClipboardPaste());

        inputContextMenu.getItems().addAll(pasteText, pasteFile);
        txtMessage.setContextMenu(inputContextMenu);

        // Update context menu visibility based on clipboard content
        inputContextMenu.setOnShowing(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            pasteText.setDisable(!clipboard.hasString());
            pasteFile.setDisable(!hasImageOrFileInClipboard());
        });
    }

    private void handleDragOver(DragEvent event) {
        if (event.getGestureSource() != messagesBox && event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasFiles()) {
            List<File> files = db.getFiles();

            if (pendingUploads.size() + files.size() > 10) {
                showError("Upload Error", "Cannot upload more than 10 files at once.");
                return;
            }

            for (File file : files) {
                processFileForUpload(file);
            }
        }
        event.setDropCompleted(true);
        event.consume();
    }

    private void handleClipboardPaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();

        if (clipboard.hasImage()) {
            Image image = clipboard.getImage();
            processImageFromClipboard(image);
        } else if (clipboard.hasFiles()) {
            List<File> files = clipboard.getFiles();
            for (File file : files) {
                if (pendingUploads.size() >= 10) {
                    showError("Upload Limit", "Cannot upload more than 10 files at once.");
                    break;
                }
                processFileForUpload(file);
            }
        } else {
            System.out.println("[ChatController] No image or files in clipboard");
        }
    }

    private boolean hasImageOrFileInClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        return clipboard.hasImage() || clipboard.hasFiles();
    }

    private void processImageFromClipboard(Image image) {
        try {
            System.out.println("[ChatController] Processing pasted image from clipboard");

            // Convert to BufferedImage
            BufferedImage bufferedImage = SwingFXUtils.fromFXImage(image, null);

            // Create temporary file
            String timestamp = String.valueOf(System.currentTimeMillis());
            String tempDir = System.getProperty("java.io.tmpdir");
            File tempFile = new File(tempDir, "pasted_image_" + timestamp + ".png");

            // Write image to file
            ImageIO.write(bufferedImage, "png", tempFile);

            System.out.println("[ChatController] Created temp file for pasted image: " + tempFile.getAbsolutePath() +
                    " (" + formatFileSize(tempFile.length()) + ")");

            processFileForUpload(tempFile);

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to process pasted image: " + e.getMessage());
            e.printStackTrace();
            showError("Paste Error", "Failed to process pasted image: " + e.getMessage());
        }
    }

    private void processFileForUpload(File file) {
        try {
            if (!file.exists() || file.length() > 25 * 1024 * 1024) { // 25MB limit
                showError("File Error", "File is too large (max 25MB) or doesn't exist.");
                return;
            }

            MediaPreview preview = previewService.createPreview(file);
            pendingUploads.add(preview);

            // Set up remove callback
            preview.setOnRemove(() -> {
                pendingUploads.remove(preview);
                Platform.runLater(() -> {
                    previewContainer.getChildren().remove(preview.getPreviewNode());
                    updatePreviewVisibility();

                    // Clean up temp file if it's a pasted image
                    previewService.cleanupTempFile(preview);
                });
            });

            // Set up send callback (individual file send)
            preview.setOnSend(() -> {
                List<MediaPreview> singlePreview = List.of(preview);
                sendPendingMedia(singlePreview, null);
            });

            Platform.runLater(() -> {
                previewContainer.getChildren().add(preview.getPreviewNode());
                updatePreviewVisibility();

                System.out.println("[ChatController] Added file to preview: " + file.getName() +
                        " (" + formatFileSize(file.length()) + ")");
            });

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to process file: " + e.getMessage());
            e.printStackTrace();
            showError("File Error", "Failed to process file: " + e.getMessage());
        }
    }

    private void updatePreviewVisibility() {
        boolean hasUploads = !pendingUploads.isEmpty();
        previewContainer.setVisible(hasUploads);
        previewContainer.setManaged(hasUploads);

        System.out.println("[ChatController] Updated preview visibility: " + hasUploads +
                " (uploads: " + pendingUploads.size() + ")");
    }

    @FXML
    private void onSend() {
        String text = txtMessage.getText().trim();
        if (text.isEmpty() && pendingUploads.isEmpty()) return;

        if (!pendingUploads.isEmpty()) {
            // Send media files
            sendPendingMedia(text);
        } else if (!text.isEmpty()) {
            // Send text message
            sendTextMessage(text);
        }

        txtMessage.clear();
    }

    private void sendTextMessage(String text) {
        if (friend == null) return;

        // Create and store outgoing message
        ChatMessage outgoingMessage = ChatMessage.fromOutgoing(friend.getUsername(), text);
        storage.storeMessage(friend.getUsername(), outgoingMessage);

        // Display in UI immediately
        Platform.runLater(() -> {
            addMessageBubble(outgoingMessage);
            scrollToBottom();
        });

        // Send message (with chunking if needed)
        new Thread(() -> {
            try {
                directory.sendChat(friend.getUsername(), text);
                System.out.println("[ChatController] Text message sent successfully");
            } catch (Exception e) {
                System.err.println("[ChatController] Failed to send text message: " + e.getMessage());
                Platform.runLater(() -> {
                    showError("Send Failed", "Could not send message: " + e.getMessage());
                });
            }
        }).start();
    }

    private void sendPendingMedia(String caption) {
        sendPendingMedia(new ArrayList<>(pendingUploads), caption);
        clearAllPreviews();
    }

    private void sendPendingMedia(List<MediaPreview> previewsToSend, String caption) {
        if (previewsToSend.isEmpty()) return;

        for (int i = 0; i < previewsToSend.size(); i++) {
            MediaPreview preview = previewsToSend.get(i);
            // Only add caption to first file
            String fileCaption = (i == 0) ? caption : null;
            sendMediaFile(preview, fileCaption);
        }

        // DON'T remove previews immediately - let sendMediaFile handle cleanup after successful send
    }

    private void sendMediaFile(MediaPreview preview, String caption) {
        if (friend == null) return;

        previewService.showProgress(preview, "Processing...");

        CompletableFuture.supplyAsync(() -> {
            try {
                // Process the media file
                SimpleMediaMessage mediaMessage = mediaService.processFile(preview.getFile());

                if (caption != null && !caption.trim().isEmpty()) {
                    mediaMessage.setCaption(caption.trim());
                }

                String mediaText = mediaService.createMediaMessageText(mediaMessage);

                // Store the message
                ChatMessage outgoingMessage = ChatMessage.fromOutgoing(friend.getUsername(), mediaText);
                storage.storeMessage(friend.getUsername(), outgoingMessage);

                return new MediaResult(mediaMessage, outgoingMessage, mediaText);

            } catch (Exception e) {
                throw new RuntimeException("Failed to process media: " + e.getMessage(), e);
            }
        }).thenAccept(result -> {
            Platform.runLater(() -> {
                previewService.updateProgress(preview, "Sending...");

                // Display in UI
                addMessageBubble(result.chatMessage);
                scrollToBottom();
            });

            // Send the media message (with chunking support)
            new Thread(() -> {
                try {
                    directory.sendChat(friend.getUsername(), result.mediaText);
                    System.out.println("[ChatController] Media message sent successfully");

                    Platform.runLater(() -> {
                        previewService.updateProgress(preview, "Sent ✓");

                        // Remove preview after successful send (with delay)
                        new Thread(() -> {
                            try {
                                Thread.sleep(2000); // Show "Sent ✓" for 2 seconds
                                Platform.runLater(() -> {
                                    pendingUploads.remove(preview);
                                    previewContainer.getChildren().remove(preview.getPreviewNode());
                                    previewService.cleanupTempFile(preview);
                                    updatePreviewVisibility();
                                });
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    });

                } catch (Exception e) {
                    System.err.println("[ChatController] Failed to send media: " + e.getMessage());
                    e.printStackTrace();
                    Platform.runLater(() -> {
                        previewService.hideProgress(preview);
                        showError("Send Failed", "Could not send " + preview.getFileName() + ": " + e.getMessage());
                        // Keep preview visible on failure so user can retry
                    });
                }
            }).start();

        }).exceptionally(throwable -> {
            Platform.runLater(() -> {
                previewService.hideProgress(preview);
                showError("Processing Failed", "Failed to process " + preview.getFileName() + ": " + throwable.getMessage());
                // Keep preview visible on failure so user can retry
            });
            return null;
        });
    }

    // Helper class for media processing results
    private static class MediaResult {
        final SimpleMediaMessage mediaMessage;
        final ChatMessage chatMessage;
        final String mediaText;

        MediaResult(SimpleMediaMessage mediaMessage, ChatMessage chatMessage, String mediaText) {
            this.mediaMessage = mediaMessage;
            this.chatMessage = chatMessage;
            this.mediaText = mediaText;
        }
    }

    /**
     * Process incoming chunked messages
     */
    public void processIncomingMessage(String messageText) {
        // This method is called by MainController when receiving messages
        // The chunking is already handled in InboxWs, so this receives complete messages
        handleCompleteMessage(messageText);
    }

    /**
     * Handle a complete message (after chunking processing)
     */
    private void handleCompleteMessage(String messageContent) {
        if (friend != null) {
            // Create ChatMessage and store it (already done in InboxWs, but kept for direct calls)
            ChatMessage incomingMessage = ChatMessage.fromIncoming(friend.getUsername(), messageContent);

            // Display in UI
            Platform.runLater(() -> {
                addMessageBubble(incomingMessage);
                scrollToBottom();

                // Clear notification since user is viewing the chat
                notificationManager.clearNotificationCount(friend.getUsername());
            });
        }
    }

    public void bindFriend(Friend f) {
        this.friend = f;
        messagesBox.getChildren().clear();
        currentPage.set(0);
        hasMoreMessages.set(true);
        clearAllPreviews();
        loadInitialMessages();

        // Clear notifications for this friend
        if (notificationManager != null) {
            notificationManager.clearNotificationCount(f.getUsername());
        }
    }

    public Friend getFriend() {
        return friend;
    }

    private void loadInitialMessages() {
        if (friend == null) return;

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

    private void loadMoreMessages() {
        if (isLoading.get() || !hasMoreMessages.get() || friend == null) return;
        isLoading.set(true);

        int nextPage = currentPage.incrementAndGet();
        CompletableFuture.supplyAsync(() -> {
            return storage.loadMessages(friend.getUsername(), nextPage, 100);
        }).thenAccept(messages -> Platform.runLater(() -> {
            isLoading.set(false);
            if (!messages.isEmpty()) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    Node messageBubble = createMessageBubbleWithDeletion(messages.get(i));
                    messagesBox.getChildren().add(1, messageBubble); // Add after DM header
                }
                hasMoreMessages.set(messages.size() >= 100);
            } else {
                hasMoreMessages.set(false);
            }
        }));
    }

    private void processStoredMessage(ChatMessage message) {
        String content = message.getContent();

        if (mediaService.isMediaMessage(content)) {
            SimpleMediaMessage mediaMessage = mediaService.extractMediaMessage(content);
            if (mediaMessage != null) {
                displayMediaMessage(mediaMessage, message.isFromMe());
            } else {
                addTextMessageBubble(content, message.isFromMe());
            }
        } else {
            addTextMessageBubble(content, message.isFromMe());
        }
    }

    private void displayMediaMessage(SimpleMediaMessage mediaMessage, boolean isFromMe) {
        VBox messageContainer = createMessageContainerWithDeletion(isFromMe, null);

        switch (mediaMessage.getType()) {
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

    private void displayImageMessage(VBox container, SimpleMediaMessage mediaMessage) {
        try {
            byte[] imageData = Base64.getDecoder().decode(mediaMessage.getData());
            Image image = new Image(new ByteArrayInputStream(imageData));

            ImageView imageView = new ImageView(image);
            imageView.setPreserveRatio(true);
            imageView.setFitWidth(Math.min(400, image.getWidth()));
            imageView.setFitHeight(Math.min(300, image.getHeight()));

            // Make image clickable for fullscreen view
            imageView.setOnMouseClicked(e -> openImageFullscreen(image));
            imageView.setStyle("-fx-cursor: hand;");

            container.getChildren().add(imageView);

            // Add caption if present
            if (mediaMessage.getCaption() != null && !mediaMessage.getCaption().trim().isEmpty()) {
                Label caption = new Label(mediaMessage.getCaption());
                caption.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px;");
                caption.setWrapText(true);
                container.getChildren().add(caption);
            }

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to display image: " + e.getMessage());
            Label errorLabel = new Label("Failed to load image: " + mediaMessage.getFileName());
            errorLabel.setStyle("-fx-text-fill: #f04747;");
            container.getChildren().add(errorLabel);
        }
    }

    private void displayVideoMessage(VBox container, SimpleMediaMessage mediaMessage) {
        // Placeholder for video display - could use MediaView in the future
        Label videoLabel = new Label("🎥 " + mediaMessage.getFileName());
        videoLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px;");

        Label sizeLabel = new Label(formatFileSize(mediaMessage.getSize()));
        sizeLabel.setStyle("-fx-text-fill: #72767d; -fx-font-size: 12px;");

        container.getChildren().addAll(videoLabel, sizeLabel);
    }

    private void displayAudioMessage(VBox container, SimpleMediaMessage mediaMessage) {
        // Placeholder for audio display
        Label audioLabel = new Label("🎵 " + mediaMessage.getFileName());
        audioLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px;");

        Label sizeLabel = new Label(formatFileSize(mediaMessage.getSize()));
        sizeLabel.setStyle("-fx-text-fill: #72767d; -fx-font-size: 12px;");

        container.getChildren().addAll(audioLabel, sizeLabel);
    }

    private void displayFileMessage(VBox container, SimpleMediaMessage mediaMessage) {
        Label fileLabel = new Label("📎 " + mediaMessage.getFileName());
        fileLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px;");

        Label sizeLabel = new Label(formatFileSize(mediaMessage.getSize()));
        sizeLabel.setStyle("-fx-text-fill: #72767d; -fx-font-size: 12px;");

        container.getChildren().addAll(fileLabel, sizeLabel);
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

        Label timeLabel = new Label(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
        timeLabel.getStyleClass().add("message-time");

        nameArea.getChildren().addAll(nameLabel, timeLabel);
        header.getChildren().addAll(avatar, nameArea);
        container.getChildren().add(header);

        return container;
    }

    /**
     * Enhanced method for creating message containers with deletion support
     */
    private VBox createMessageContainerWithDeletion(boolean isFromMe, ChatMessage message) {
        VBox container = createMessageContainer(isFromMe);

        // Add context menu for deletion if it's our message
        if (isFromMe && message != null) {
            addMessageContextMenu(container, message.getId(),
                    friend != null ? friend.getUsername() : "",
                    isFromMe);
        }

        return container;
    }

    /**
     * Add context menu to message nodes for deletion
     */
    private void addMessageContextMenu(Node messageNode, String messageId, String username, boolean isSentByMe) {
        if (!isSentByMe) {
            return; // Only allow deleting own messages
        }

        ContextMenu contextMenu = new ContextMenu();

        MenuItem deleteItem = new MenuItem("Delete Message");
        deleteItem.setOnAction(e -> {
            Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
            confirmAlert.setTitle("Delete Message");
            confirmAlert.setHeaderText("Delete this message?");
            confirmAlert.setContentText("This action cannot be undone.");

            Optional<ButtonType> result = confirmAlert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                deleteMessage(messageId, username);
            }
        });

        contextMenu.getItems().add(deleteItem);

        // Set context menu on the message node
        messageNode.setOnContextMenuRequested(e -> {
            contextMenu.show(messageNode, e.getScreenX(), e.getScreenY());
        });

        // Store message ID in node for later reference
        messageNode.setUserData(messageId);
    }

    /**
     * Enhanced createMessageBubble method with deletion support
     */
    private Node createMessageBubbleWithDeletion(ChatMessage message) {
        boolean isFromMe = message.isFromMe();

        if (mediaService.isMediaMessage(message.getContent())) {
            SimpleMediaService.SimpleMediaMessage mediaMessage = mediaService.extractMediaMessage(message.getContent());
            if (mediaMessage != null) {
                VBox container = createMessageContainerWithDeletion(isFromMe, message);

                switch (mediaMessage.getType()) {
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

        VBox container = createMessageContainerWithDeletion(isFromMe, message);

        Label textLabel = new Label(message.getContent());
        textLabel.setWrapText(true);
        textLabel.setMaxWidth(580);
        textLabel.getStyleClass().add("message-content");
        textLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 15px;");

        container.getChildren().add(textLabel);
        return container;
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

    private void addDMHeader() {
        messagesBox.getChildren().add(createDMHeader());
    }

    /**
     * Clear all messages from the chat view
     */
    public void clearAllMessages() {
        Platform.runLater(() -> {
            if (messagesBox != null) {
                messagesBox.getChildren().clear();
                addDMHeader(); // Re-add the DM header
            }
            System.out.println("[ChatController] Cleared all messages from UI");
        });
    }

    /**
     * Delete a specific message from both storage and UI
     */
    public void deleteMessage(String messageId, String username) {
        System.out.println("[ChatController] Deleting message " + messageId + " for user " + username);

        // Delete from storage
        boolean deleted = MessageStorageUtility.getInstance().deleteMessage(username, messageId);

        if (deleted) {
            // Remove from UI
            Platform.runLater(() -> {
                if (messagesBox != null) {
                    // Find and remove the message node
                    messagesBox.getChildren().removeIf(node -> {
                        // Check if the node has the message ID stored in userData
                        return messageId.equals(node.getUserData());
                    });
                }
            });

            System.out.println("[ChatController] Successfully deleted message " + messageId);
        } else {
            System.err.println("[ChatController] Failed to delete message " + messageId);
        }
    }

    /**
     * Refresh the entire conversation (useful after deletions)
     */
    public void refreshConversation() {
        if (friend == null) {
            return;
        }

        System.out.println("[ChatController] Refreshing conversation with: " + friend.getUsername());

        // Reset pagination
        currentPage.set(0);
        hasMoreMessages.set(true);

        // Reload messages
        loadInitialMessages();
    }

    private void clearMessageHistory() {
        if (friend == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Clear message history with " + friend.getDisplayName() + "?",
                ButtonType.YES, ButtonType.NO);

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                // Delete from storage
                MessageStorageUtility.getInstance().deleteConversationMessages(friend.getUsername());

                // Clear UI
                messagesBox.getChildren().clear();
                addDMHeader();
            }
        });
    }

    private void clearAllPreviews() {
        for (MediaPreview preview : new ArrayList<>(pendingUploads)) {
            if (preview.getFile() != null && preview.getFile().getName().startsWith("pasted_image_")) {
                preview.getFile().delete();
            }
        }

        pendingUploads.clear();
        previewContainer.getChildren().clear();
        updatePreviewVisibility();
    }

    public void addMessageBubble(ChatMessage message) {
        Node bubble = createMessageBubbleWithDeletion(message);
        messagesBox.getChildren().add(bubble);
    }

    public void scrollToBottom() {
        Platform.runLater(() -> scrollPane.setVvalue(1.0));
    }

    public void appendLocal(String text) {
        if (friend != null) {
            ChatMessage outgoing = ChatMessage.fromOutgoing(friend.getUsername(), text);
            addMessageBubble(outgoing);
            scrollToBottom();
        }
    }

    private void showError(String title, String message) {
        if (notificationManager != null) {
            notificationManager.showErrorNotification(title, message);
        } else {
            Alert alert = new Alert(Alert.AlertType.ERROR, message);
            alert.setTitle(title);
            alert.showAndWait();
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}