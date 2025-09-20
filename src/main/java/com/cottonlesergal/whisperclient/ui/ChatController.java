package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.Friend;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.cottonlesergal.whisperclient.services.*;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import com.cottonlesergal.whisperclient.services.MediaPreviewService.MediaPreview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatController {
    // FXML Components - Matching your existing FXML
    @FXML private ScrollPane scrollPane;
    @FXML private VBox messagesBox;
    @FXML private TextField txtMessage;
    @FXML private Button btnAttach;

    // Services - Using your existing instances
    private final DirectoryClient directory = new DirectoryClient();
    private final MessageStorageService storage = MessageStorageService.getInstance();
    private final MediaPreviewService previewService = MediaPreviewService.getInstance();
    private final NotificationManager notificationManager = NotificationManager.getInstance();
    private final HttpMediaClientService httpMediaService = HttpMediaClientService.getInstance();

    // State - Matching your existing structure
    private Friend friend;
    private VBox previewContainer;
    private final List<MediaPreview> pendingUploads = new ArrayList<>();
    private final AtomicInteger currentPage = new AtomicInteger(0);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean hasMoreMessages = new AtomicBoolean(true);

    // Enhanced state for message grouping and display
    private ChatMessage lastDisplayedMessage = null;
    private static final long MESSAGE_GROUP_TIME_MS = 5 * 60 * 1000; // 5 minutes

    @FXML
    private void initialize() {
        setupScrollPane();
        setupContextMenus();
        setupDragAndDrop();
        setupKeyboardShortcuts();
        setupClipboardPaste();
        setupPreviewContainer();
        setupAttachButton();

        // Auto-scroll to bottom when new messages arrive
        messagesBox.getChildren().addListener((javafx.collections.ListChangeListener<Node>) c -> {
            Platform.runLater(() -> scrollPane.setVvalue(1.0));
        });

        System.out.println("[ChatController] Initialized with HTTP media system");
    }

    // ============== SETUP METHODS - USING YOUR EXISTING STRUCTURE ==============

    private void setupScrollPane() {
        scrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() <= 0.01 && !isLoading.get() && hasMoreMessages.get()) {
                loadMoreMessages();
            }
        });
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        System.out.println("[ChatController] ScrollPane setup - Parent: " +
                (scrollPane.getParent() != null ? scrollPane.getParent().getClass().getSimpleName() : "null"));
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

        // Add context menu to message input for paste
        ContextMenu inputContextMenu = new ContextMenu();
        MenuItem pasteText = new MenuItem("Paste Text");
        pasteText.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            if (clipboard.hasString()) {
                txtMessage.insertText(txtMessage.getCaretPosition(), clipboard.getString());
            }
        });

        MenuItem pasteFileItem = new MenuItem("Paste Image/File");
        pasteFileItem.setOnAction(e -> handleClipboardPaste());

        inputContextMenu.getItems().addAll(pasteText, pasteFileItem);
        txtMessage.setContextMenu(inputContextMenu);
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
                // Enhanced paste with image URL detection
                if (hasImageOrFileInClipboard()) {
                    handleClipboardPaste();
                    event.consume(); // Prevent default text paste
                } else {
                    // Check for image URLs in clipboard
                    Clipboard clipboard = Clipboard.getSystemClipboard();
                    if (clipboard.hasString()) {
                        String clipboardText = clipboard.getString();
                        if (isImageUrl(clipboardText)) {
                            downloadAndSendImage(clipboardText);
                            event.consume();
                        }
                    }
                }
                // If no images/files/URLs, allow normal text paste to proceed
            }
        });
    }

    private void setupClipboardPaste() {
        // Already handled in setupKeyboardShortcuts and setupContextMenus
    }

    private void setupPreviewContainer() {
        // Create preview container if it doesn't exist
        if (previewContainer == null) {
            previewContainer = new VBox(8);
            previewContainer.setStyle("-fx-padding: 8; -fx-background-color: #36393f;");
            previewContainer.setVisible(false);
            previewContainer.setManaged(false);

            // Add to parent if possible
            if (scrollPane.getParent() instanceof VBox) {
                VBox parent = (VBox) scrollPane.getParent();
                parent.getChildren().add(parent.getChildren().size() - 1, previewContainer);
            }
        }

        System.out.println("[ChatController] Added preview container to UI");
    }

    private void setupAttachButton() {
        if (btnAttach != null) {
            btnAttach.setOnAction(this::onAttachFile);
            btnAttach.setTooltip(new Tooltip("Attach files (images, videos, audio, documents)"));
        }
    }

    // ============== INPUT HANDLING - USING YOUR EXISTING METHODS ==============

    @FXML
    private void onSend() {
        String text = txtMessage.getText().trim();
        if (text.isEmpty() && pendingUploads.isEmpty()) return;

        if (!pendingUploads.isEmpty()) {
            // Send media files - prevent double sending by clearing list immediately
            List<MediaPreview> toSend = new ArrayList<>(pendingUploads);
            pendingUploads.clear(); // Clear immediately to prevent double-sends
            updatePreviewVisibility();

            sendMediaBatch(toSend, text);
            txtMessage.clear();
        } else if (!text.isEmpty()) {
            // Send text message
            sendTextMessage(text);
            txtMessage.clear();
        }
    }

    @FXML
    private void onAttachFile(javafx.event.ActionEvent event) {
        openFileChooser();
    }

    private void openFileChooser() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Files to Send");

        // Set up file filters - matching your existing structure
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

    // ============== DRAG & DROP HANDLING - YOUR EXISTING METHODS ==============

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

    // ============== ENHANCED CLIPBOARD HANDLING WITH IMAGE URL DETECTION ==============

    private void handleClipboardPaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();

        // First check for image URLs in string content
        if (clipboard.hasString()) {
            String clipboardText = clipboard.getString();
            if (isImageUrl(clipboardText)) {
                System.out.println("[ChatController] Detected image URL in clipboard: " + clipboardText);
                downloadAndSendImage(clipboardText);
                return;
            }
        }

        // Then check for actual images and files
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
        return clipboard.hasImage() || clipboard.hasFiles() ||
                (clipboard.hasString() && isImageUrl(clipboard.getString()));
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

    // ============== IMAGE URL DETECTION AND DOWNLOAD ==============

    private boolean isImageUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;

        // Check if it's a valid URL
        try {
            new URI(url);
        } catch (Exception e) {
            return false;
        }

        // Check if URL ends with image extension or contains image hosting domains
        String lowerUrl = url.toLowerCase();
        return lowerUrl.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|svg)(\\?.*|#.*)?$") ||
                lowerUrl.contains("image") ||
                lowerUrl.contains("img") ||
                // Common image hosting domains
                lowerUrl.contains("imgur.com") ||
                lowerUrl.contains("i.redd.it") ||
                lowerUrl.contains("cdn.discordapp.com") ||
                lowerUrl.contains("pngimg.com") ||
                lowerUrl.contains("pngtree.com") ||
                lowerUrl.contains("freepik.com");
    }

    private void downloadAndSendImage(String imageUrl) {
        // Show loading notification
        notificationManager.showToast("Downloading Image", "Downloading image from URL...", NotificationManager.ToastType.INFO);

        CompletableFuture.runAsync(() -> {
            try {
                // Download the image
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(imageUrl))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .GET()
                        .build();

                HttpResponse<byte[]> response = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() == 200) {
                    byte[] imageData = response.body();

                    // Determine file extension from URL or content type
                    String fileName = generateImageFileName(imageUrl, response.headers().firstValue("content-type").orElse(""));

                    // Create temporary file
                    File tempDir = new File(System.getProperty("java.io.tmpdir"), "WhisperClient");
                    tempDir.mkdirs();
                    File tempImageFile = new File(tempDir, fileName);
                    Files.write(tempImageFile.toPath(), imageData);

                    Platform.runLater(() -> {
                        try {
                            // Create image from the downloaded data to validate
                            Image downloadedImage = new Image(new ByteArrayInputStream(imageData));

                            if (downloadedImage.isError()) {
                                notificationManager.showToast("Invalid Image", "The URL does not contain a valid image", NotificationManager.ToastType.ERROR);
                                return;
                            }

                            // Process the downloaded image file for upload
                            processFileForUpload(tempImageFile);

                            notificationManager.showToast("Image Ready", "Image downloaded and ready to send!", NotificationManager.ToastType.SUCCESS);
                            System.out.println("[ChatController] Downloaded and added image from URL: " + fileName);

                        } catch (Exception e) {
                            System.err.println("[ChatController] Failed to process downloaded image: " + e.getMessage());
                            notificationManager.showToast("Processing Error", "Failed to process downloaded image", NotificationManager.ToastType.ERROR);
                        }
                    });

                } else {
                    Platform.runLater(() -> {
                        notificationManager.showToast("Download Failed", "Failed to download image (HTTP " + response.statusCode() + ")", NotificationManager.ToastType.ERROR);
                    });
                }

            } catch (Exception e) {
                Platform.runLater(() -> {
                    System.err.println("[ChatController] Failed to download image from URL: " + e.getMessage());
                    notificationManager.showToast("Download Error", "Failed to download image: " + e.getMessage(), NotificationManager.ToastType.ERROR);
                });
            }
        });
    }

    private String generateImageFileName(String url, String contentType) {
        try {
            // Try to extract filename from URL
            URI uri = new URI(url);
            String path = uri.getPath();

            if (path != null && path.contains("/")) {
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                if (fileName.contains(".") && fileName.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|svg).*")) {
                    // Clean up the filename
                    fileName = fileName.split("\\?")[0]; // Remove query parameters
                    fileName = fileName.split("#")[0]; // Remove fragments
                    return "downloaded_" + System.currentTimeMillis() + "_" + fileName;
                }
            }

            // Fallback: determine extension from content type
            String extension = ".png"; // default
            if (contentType.contains("jpeg")) {
                extension = ".jpg";
            } else if (contentType.contains("gif")) {
                extension = ".gif";
            } else if (contentType.contains("webp")) {
                extension = ".webp";
            } else if (contentType.contains("bmp")) {
                extension = ".bmp";
            } else if (contentType.contains("svg")) {
                extension = ".svg";
            }

            return "downloaded_image_" + System.currentTimeMillis() + extension;

        } catch (Exception e) {
            return "downloaded_image_" + System.currentTimeMillis() + ".png";
        }
    }

    // ============== FILE PROCESSING - YOUR EXISTING METHOD ==============

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
                // Only remove if not currently sending
                if (!preview.isProcessing()) {
                    pendingUploads.remove(preview);
                    Platform.runLater(() -> {
                        previewContainer.getChildren().remove(preview.getPreviewComponent());
                        updatePreviewVisibility();

                        // Clean up temp file if it's a pasted image
                        if (preview.getFile() != null && preview.getFile().getName().startsWith("pasted_image_")) {
                            preview.getFile().delete();
                        }
                    });
                } else {
                    System.out.println("[ChatController] Cannot remove preview - currently processing");
                }
            });

            // No individual send callback - only use Enter key to send
            preview.setOnSend(() -> {
                System.out.println("[ChatController] Individual send disabled - use Enter key to send files");
            });

            Platform.runLater(() -> {
                previewContainer.getChildren().add(preview.getPreviewComponent());
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

    private void clearAllPreviews() {
        // Don't clean up temp files of files currently being processed
        for (MediaPreview preview : new ArrayList<>(pendingUploads)) {
            if (!preview.isProcessing()) {
                if (preview.getFile() != null && preview.getFile().getName().startsWith("pasted_image_")) {
                    preview.getFile().delete();
                }
            } else {
                System.out.println("[ChatController] Skipping cleanup of processing file: " + preview.getFileName());
            }
        }

        pendingUploads.clear();
        previewContainer.getChildren().clear();
        updatePreviewVisibility();
    }

    // ============== MESSAGE SENDING - USING YOUR EXISTING STRUCTURE ==============

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

        // Send message via WebSocket
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

    // Batch sending for Enter key (prevents duplicates)
    private void sendMediaBatch(List<MediaPreview> previewsToSend, String caption) {
        if (previewsToSend.isEmpty()) return;

        System.out.println("[ChatController] Sending batch of " + previewsToSend.size() + " media files");

        for (int i = 0; i < previewsToSend.size(); i++) {
            MediaPreview preview = previewsToSend.get(i);
            // Only add caption to first file
            String fileCaption = (i == 0 && caption != null && !caption.isEmpty()) ? caption : null;

            // Remove from UI immediately to prevent re-sending
            Platform.runLater(() -> {
                previewContainer.getChildren().remove(preview.getPreviewComponent());
            });

            sendSingleMediaFile(preview, fileCaption);
        }
    }

    private void sendSingleMediaFile(MediaPreview preview, String caption) {
        if (friend == null) return;

        // Check if already sending to prevent duplicates
        if (preview.isProcessing()) {
            System.out.println("[ChatController] Skipping duplicate send for: " + preview.getFileName());
            return;
        }

        previewService.showProgress(preview, "Sending...");

        // Use HTTP POST to send media
        httpMediaService.sendMediaAsync(preview.getFile(), friend.getUsername(), caption)
                .thenRun(() -> {
                    System.out.println("[ChatController] Media file sent successfully: " + preview.getFileName());

                    Platform.runLater(() -> {
                        try {
                            // Create inline media message for sender to see their own image
                            String base64Data = encodeFileToBase64(preview.getFile());
                            String mimeType = guessMimeType(preview.getFileName());

                            String inlineMediaMessage = String.format("[INLINE_MEDIA:%s:%s:%s:%d:%s]%s",
                                    java.util.UUID.randomUUID().toString(),
                                    preview.getFileName(),
                                    mimeType,
                                    preview.getFileSize(),
                                    base64Data,
                                    caption != null ? "\n" + caption : ""
                            );

                            ChatMessage mediaMessage = ChatMessage.fromOutgoing(friend.getUsername(), inlineMediaMessage);
                            storage.storeMessage(friend.getUsername(), mediaMessage);
                            addMessageBubble(mediaMessage);

                            previewService.hideProgress(preview);

                        } catch (Exception e) {
                            System.err.println("[ChatController] Failed to create outgoing media message: " + e.getMessage());
                            previewService.hideProgress(preview);
                        }
                    });
                })
                .exceptionally(throwable -> {
                    Platform.runLater(() -> {
                        System.err.println("[ChatController] Failed to send media: " + throwable.getMessage());
                        previewService.hideProgress(preview);
                        showError("Send Error", "Failed to send media: " + throwable.getMessage());
                    });
                    return null;
                });
    }

    // ============== ENHANCED MESSAGE DISPLAY WITH DISCORD-STYLE LAYOUT ==============

    public void addMessageBubble(ChatMessage message) {
        Node bubble = createMessageBubbleWithDeletion(message);
        messagesBox.getChildren().add(bubble);
        lastDisplayedMessage = message;
        scrollToBottom();
    }

    private Node createMessageBubbleWithDeletion(ChatMessage message) {
        boolean isFromMe = message.isFromMe();

        // Check if this message should be grouped with the previous one
        boolean shouldGroup = shouldGroupMessage(message);

        VBox messageContainer = new VBox(shouldGroup ? 2 : 8);
        messageContainer.setPadding(new Insets(shouldGroup ? 2 : 8, 12, 2, 12));
        messageContainer.setMaxWidth(700);

        // All messages align left for consistent Discord-style layout
        messageContainer.setAlignment(Pos.CENTER_LEFT);

        // Show header (avatar + name + timestamp) for first message in group
        if (!shouldGroup) {
            HBox headerBox = createMessageHeader(message, isFromMe);
            messageContainer.getChildren().add(headerBox);
        }

        // Create the actual message content
        Node messageContent;
        if (message.getContent().startsWith("[INLINE_MEDIA:")) {
            messageContent = createInlineMediaContent(message, isFromMe);
        } else {
            messageContent = createTextMessageContent(message.getContent(), isFromMe);
        }

        // Wrap content in a styled container
        HBox contentContainer = new HBox();
        contentContainer.setMaxWidth(500);
        contentContainer.setAlignment(Pos.CENTER_LEFT);

        if (shouldGroup) {
            // For grouped messages, add left margin to align with previous message content
            HBox.setMargin(messageContent, new Insets(0, 0, 0, 40)); // Account for avatar space
        }

        // Apply message bubble styling
        if (isFromMe) {
            messageContent.setStyle(messageContent.getStyle() + "; -fx-background-color: #5865f2; -fx-background-radius: 12;");
        } else {
            messageContent.setStyle(messageContent.getStyle() + "; -fx-background-color: #40444b; -fx-background-radius: 12;");
        }

        contentContainer.getChildren().add(messageContent);
        messageContainer.getChildren().add(contentContainer);

        // Add right-click context menu for deletion
        ContextMenu contextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("Delete Message");
        deleteItem.setOnAction(e -> deleteMessage(message, messageContainer));
        contextMenu.getItems().add(deleteItem);
        messageContainer.setOnContextMenuRequested(e -> contextMenu.show(messageContainer, e.getScreenX(), e.getScreenY()));

        return messageContainer;
    }

    private boolean shouldGroupMessage(ChatMessage message) {
        if (lastDisplayedMessage == null) {
            return false;
        }

        // Debug logging to see what's happening
        System.out.println("[ChatController] Checking grouping:");
        System.out.println("  Last message from: " + lastDisplayedMessage.getFrom() + " (isFromMe: " + lastDisplayedMessage.isFromMe() + ")");
        System.out.println("  Current message from: " + message.getFrom() + " (isFromMe: " + message.isFromMe() + ")");
        System.out.println("  Last timestamp: " + lastDisplayedMessage.getTimestamp());
        System.out.println("  Current timestamp: " + message.getTimestamp());
        System.out.println("  Time diff: " + Math.abs(message.getTimestamp() - lastDisplayedMessage.getTimestamp()) + "ms");

        // Group if: same sender AND within 5 minutes
        boolean sameSender;

        // For sent messages, compare using isFromMe flag
        if (message.isFromMe() && lastDisplayedMessage.isFromMe()) {
            sameSender = true; // Both are from me
        } else if (!message.isFromMe() && !lastDisplayedMessage.isFromMe()) {
            // Both are received messages - compare actual sender usernames
            sameSender = lastDisplayedMessage.getFrom().equals(message.getFrom());
        } else {
            sameSender = false; // One is sent, one is received
        }

        boolean withinTimeLimit = Math.abs(message.getTimestamp() - lastDisplayedMessage.getTimestamp()) <= MESSAGE_GROUP_TIME_MS;

        boolean shouldGroup = sameSender && withinTimeLimit;
        System.out.println("  Should group: " + shouldGroup + " (sameSender: " + sameSender + ", withinTime: " + withinTimeLimit + ")");

        return shouldGroup;
    }

    private HBox createMessageHeader(ChatMessage message, boolean isFromMe) {
        HBox headerBox = new HBox(8);
        headerBox.setAlignment(Pos.CENTER_LEFT); // Always align left for Discord-style
        headerBox.setPadding(new Insets(4, 0, 4, 0));

        // Avatar for ALL messages (both received and sent)
        ImageView avatar = new ImageView();
        avatar.setFitWidth(32);
        avatar.setFitHeight(32);

        String avatarUrl;
        String displayName;

        if (isFromMe) {
            // Use current user's avatar and name for sent messages
            avatarUrl = Session.me != null ? Session.me.getAvatarUrl() : "";
            displayName = Session.me != null ? Session.me.getDisplayName() : "You";
        } else {
            // Use friend's avatar and name for received messages
            avatarUrl = friend != null ? friend.getAvatar() : "";
            displayName = friend != null ? friend.getDisplayName() : message.getFrom();
        }

        avatar.setImage(AvatarCache.get(avatarUrl, 32));

        Circle avatarClip = new Circle(16, 16, 16);
        avatar.setClip(avatarClip);

        // Username label with different colors for sent vs received
        Label usernameLabel = new Label(displayName);
        if (isFromMe) {
            usernameLabel.setStyle("-fx-text-fill: #5865f2; -fx-font-weight: 600; -fx-font-size: 14px;"); // Blue for sent messages
        } else {
            usernameLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: 600; -fx-font-size: 14px;"); // White for received
        }

        // Timestamp
        Label timestampLabel = new Label(formatTimestamp(message.getTimestamp()));
        timestampLabel.setStyle("-fx-text-fill: #72767d; -fx-font-size: 12px;");

        headerBox.getChildren().addAll(avatar, usernameLabel, timestampLabel);

        return headerBox;
    }

    private String formatTimestamp(long timestamp) {
        LocalDateTime messageTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamp),
                java.time.ZoneId.systemDefault()
        );

        LocalDateTime now = LocalDateTime.now();

        // If today, show time only
        if (messageTime.toLocalDate().equals(now.toLocalDate())) {
            return messageTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        }

        // If yesterday, show "Yesterday at HH:mm"
        if (messageTime.toLocalDate().equals(now.toLocalDate().minusDays(1))) {
            return "Yesterday at " + messageTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        }

        // Otherwise show date and time
        return messageTime.format(DateTimeFormatter.ofPattern("MMM d, HH:mm"));
    }

    // ============== FRIEND MANAGEMENT - YOUR EXISTING METHODS ==============

    public void bindFriend(Friend friend) {
        this.friend = friend;
        if (friend != null) {
            refreshConversation();
        } else {
            clearMessages();
        }
    }

    public void setFriend(UserSummary userSummary) {
        if (userSummary != null) {
            // Convert UserSummary to Friend for compatibility
            this.friend = new Friend(
                    userSummary.getUsername(),
                    "",
                    "",
                    userSummary.getDisplay(),
                    userSummary.getAvatar()
            );
            refreshConversation();
        } else {
            this.friend = null;
            clearMessages();
        }
    }

    public void refreshConversation() {
        if (friend == null) return;

        clearMessages();
        currentPage.set(0);
        hasMoreMessages.set(true);
        lastDisplayedMessage = null; // Reset grouping

        // Load initial messages
        loadMoreMessages();
    }

    private void clearMessages() {
        messagesBox.getChildren().clear();
        lastDisplayedMessage = null; // Reset grouping
        if (friend != null) {
            addDMHeader();
        }
    }

    public void clearAllMessages() {
        clearMessages();
    }

    private void clearMessageHistory() {
        if (friend != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Are you sure you want to clear the message history with " + friend.getDisplayName() + "?",
                    ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(response -> {
                if (response == ButtonType.YES) {
                    clearMessages();
                    refreshConversation();
                }
            });
        }
    }

    private void addDMHeader() {
        messagesBox.getChildren().add(createDMHeader());
    }

    private Node createDMHeader() {
        VBox headerContainer = new VBox(12);
        headerContainer.setAlignment(Pos.CENTER);
        headerContainer.setStyle("-fx-padding: 20; -fx-background-color: transparent;");
        headerContainer.getStyleClass().add("dm-header");

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

    // ============== MESSAGE LOADING - YOUR EXISTING STRUCTURE ==============

    private void loadMoreMessages() {
        if (friend == null || isLoading.get() || !hasMoreMessages.get()) return;

        isLoading.set(true);

        CompletableFuture.supplyAsync(() -> {
            return storage.loadMessages(friend.getUsername(), currentPage.get(), 100);
        }).thenAccept(messages -> Platform.runLater(() -> {
            isLoading.set(false);
            if (!messages.isEmpty()) {
                // Reset grouping when loading older messages
                ChatMessage prevGroupMessage = lastDisplayedMessage;

                for (int i = messages.size() - 1; i >= 0; i--) {
                    Node messageBubble = createMessageBubbleWithDeletion(messages.get(i));
                    messagesBox.getChildren().add(1, messageBubble); // Add after DM header
                }

                // Restore grouping state
                lastDisplayedMessage = prevGroupMessage;

                hasMoreMessages.set(messages.size() >= 100);
                currentPage.incrementAndGet();
            } else {
                hasMoreMessages.set(false);
            }
        }));
    }

    // ============== MESSAGE CONTENT CREATION ==============

    private Node createTextMessageContent(String content, boolean isFromMe) {
        VBox contentContainer = new VBox(4);
        contentContainer.setPadding(new Insets(8, 12, 8, 12));
        contentContainer.setMaxWidth(400);

        // Check for URLs in the message
        List<String> urls = extractUrls(content);

        if (!urls.isEmpty()) {
            // Add text content first
            if (!content.trim().isEmpty()) {
                Label textLabel = createStyledTextLabel(content, isFromMe);
                contentContainer.getChildren().add(textLabel);
            }

            // Add embeds for each URL
            for (String url : urls) {
                Node embed = createLinkEmbed(url);
                if (embed != null) {
                    VBox.setMargin(embed, new Insets(8, 0, 0, 0));
                    contentContainer.getChildren().add(embed);
                }
            }
        } else {
            // Regular text message
            Label textLabel = createStyledTextLabel(content, isFromMe);
            contentContainer.getChildren().add(textLabel);
        }

        return contentContainer;
    }

    private Node createInlineMediaContent(ChatMessage message, boolean isFromMe) {
        VBox container = new VBox(4);
        container.setPadding(new Insets(8, 12, 8, 12));
        container.setMaxWidth(400);

        try {
            // Parse inline media format: [INLINE_MEDIA:id:fileName:mimeType:size:base64Data]caption
            String content = message.getContent();
            int endBracket = content.indexOf(']');
            String mediaInfo = content.substring(14, endBracket); // Remove [INLINE_MEDIA:
            String caption = endBracket + 1 < content.length() ? content.substring(endBracket + 1) : "";

            String[] parts = mediaInfo.split(":", 5);
            if (parts.length >= 5) {
                String messageId = parts[0];
                String fileName = parts[1];
                String mimeType = parts[2];
                long size = Long.parseLong(parts[3]);
                String base64Data = parts[4];

                // Display media inline and auto-save
                displayInlineMedia(container, fileName, mimeType, size, base64Data, caption);
            }

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to parse inline media: " + e.getMessage());
            Label errorLabel = new Label("Failed to load media: " + e.getMessage());
            errorLabel.setStyle("-fx-text-fill: #f04747;");
            container.getChildren().add(errorLabel);
        }

        return container;
    }

    private Label createStyledTextLabel(String text, boolean isFromMe) {
        Label textLabel = new Label(text);
        textLabel.setWrapText(true);
        textLabel.setMaxWidth(380);

        if (isFromMe) {
            textLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");
        } else {
            textLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px;");
        }

        return textLabel;
    }

    private List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<>();
        Pattern urlPattern = Pattern.compile(
                "https?://(?:[-\\w.])+(?:\\:[0-9]+)?(?:/(?:[\\w/_.])*(?:\\?(?:[\\w&=%.])*)?(?:#(?:[\\w.])*)?)?",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = urlPattern.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group());
        }

        return urls;
    }

    // ============== SIMPLIFIED LINK EMBEDS ==============

    private Node createLinkEmbed(String url) {
        try {
            if (isYouTubeUrl(url)) {
                return createYouTubeEmbed(url);
            } else if (isDirectImageUrl(url)) {
                return createImageEmbed(url);
            } else {
                return createGenericLinkEmbed(url);
            }
        } catch (Exception e) {
            System.err.println("Failed to create embed for URL: " + url + " - " + e.getMessage());
            return createFallbackLinkEmbed(url);
        }
    }

    private boolean isYouTubeUrl(String url) {
        return url.matches(".*(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/).*");
    }

    private boolean isDirectImageUrl(String url) {
        return url.toLowerCase().matches(".*\\.(jpg|jpeg|png|gif|bmp|webp)(\\?.*)?$");
    }

    private Node createYouTubeEmbed(String url) {
        HBox youtubeContainer = new HBox(12);
        youtubeContainer.setStyle("-fx-background-color: #1e2124; -fx-background-radius: 8; -fx-border-color: #ff0000; -fx-border-width: 1; -fx-border-radius: 8; -fx-padding: 12; -fx-cursor: hand;");
        youtubeContainer.setMaxWidth(380);

        Label youtubeIcon = new Label("📺");
        youtubeIcon.setStyle("-fx-font-size: 20px;");

        Label titleLabel = new Label("YouTube Video");
        titleLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px; -fx-font-weight: 600;");

        youtubeContainer.getChildren().addAll(youtubeIcon, titleLabel);
        youtubeContainer.setOnMouseClicked(e -> openUrl(url));

        return youtubeContainer;
    }

    private Node createImageEmbed(String imageUrl) {
        VBox imageContainer = new VBox(8);
        imageContainer.setStyle("-fx-background-color: #1e2124; -fx-background-radius: 8; -fx-padding: 8;");
        imageContainer.setMaxWidth(380);

        Label loadingLabel = new Label("Loading image...");
        loadingLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 12px;");
        imageContainer.getChildren().add(loadingLabel);

        // Load image asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                Image image = new Image(imageUrl, true);
                Platform.runLater(() -> {
                    imageContainer.getChildren().clear();

                    ImageView imageView = new ImageView(image);
                    imageView.setPreserveRatio(true);
                    imageView.setFitWidth(Math.min(380, image.getWidth()));
                    imageView.setFitHeight(Math.min(280, image.getHeight()));
                    imageView.setCursor(Cursor.HAND);
                    imageView.setOnMouseClicked(e -> openFullscreenImage(image, "Linked Image"));

                    imageContainer.getChildren().add(imageView);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    imageContainer.getChildren().clear();
                    Label errorLabel = new Label("Failed to load image");
                    errorLabel.setStyle("-fx-text-fill: #f04747; -fx-font-size: 12px;");
                    imageContainer.getChildren().add(errorLabel);
                });
            }
        });

        return imageContainer;
    }

    private Node createGenericLinkEmbed(String url) {
        HBox linkContainer = new HBox(12);
        linkContainer.setStyle("-fx-background-color: #1e2124; -fx-background-radius: 8; -fx-border-color: #40444b; -fx-border-width: 1; -fx-border-radius: 8; -fx-padding: 12; -fx-cursor: hand;");
        linkContainer.setMaxWidth(380);

        Label linkIcon = new Label("🔗");
        linkIcon.setStyle("-fx-font-size: 20px;");

        Label titleLabel = new Label("Web Link");
        titleLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px; -fx-font-weight: 600;");

        linkContainer.getChildren().addAll(linkIcon, titleLabel);
        linkContainer.setOnMouseClicked(e -> openUrl(url));

        return linkContainer;
    }

    private Node createFallbackLinkEmbed(String url) {
        Label linkLabel = new Label(url);
        linkLabel.setStyle("-fx-text-fill: #00aff4; -fx-font-size: 14px; -fx-underline: true; -fx-cursor: hand;");
        linkLabel.setWrapText(true);
        linkLabel.setOnMouseClicked(e -> openUrl(url));
        return linkLabel;
    }

    // ============== ENHANCED MEDIA DISPLAY WITH IN-APP PLAYERS ==============

    private void displayInlineMedia(VBox container, String fileName, String mimeType,
                                    long size, String base64Data, String caption) {
        try {
            // Auto-save the file to appropriate directory
            autoSaveMediaFile(fileName, mimeType, base64Data);

            if (mimeType.startsWith("image/")) {
                displayImageMedia(container, fileName, base64Data, size, caption);
            } else if (mimeType.startsWith("video/")) {
                displayVideoMediaWithPlayer(container, fileName, base64Data, size, caption);
            } else if (mimeType.startsWith("audio/")) {
                displayAudioMediaWithPlayer(container, fileName, base64Data, size, caption);
            } else {
                displayFileMedia(container, fileName, mimeType, size, caption);
            }

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to display inline media: " + e.getMessage());
            addErrorLabel(container, "Failed to display media: " + fileName);
        }
    }

    private void displayImageMedia(VBox container, String fileName, String base64Data, long size, String caption) {
        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);
            Image image = new Image(new ByteArrayInputStream(imageBytes));

            ImageView imageView = new ImageView(image);
            imageView.setPreserveRatio(true);
            imageView.setFitWidth(Math.min(380, image.getWidth()));
            imageView.setFitHeight(Math.min(280, image.getHeight()));
            imageView.setCursor(Cursor.HAND);
            imageView.setOnMouseClicked(e -> openFullscreenImage(image, fileName));

            container.getChildren().add(imageView);
            addMediaInfo(container, fileName, size);

            if (caption != null && !caption.trim().isEmpty()) {
                Label captionLabel = new Label(caption.trim());
                captionLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px;");
                captionLabel.setWrapText(true);
                container.getChildren().add(captionLabel);
            }

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to display image: " + e.getMessage());
            addErrorLabel(container, "Failed to display image: " + fileName);
        }
    }

    private void displayVideoMediaWithPlayer(VBox container, String fileName, String base64Data, long size, String caption) {
        try {
            // Create temporary file for video playback
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "WhisperClient");
            tempDir.mkdirs();
            File tempVideoFile = new File(tempDir, "temp_" + System.currentTimeMillis() + "_" + fileName);

            byte[] videoBytes = Base64.getDecoder().decode(base64Data);
            Files.write(tempVideoFile.toPath(), videoBytes);

            // Create in-app video player
            VBox videoContainer = new VBox(8);
            videoContainer.setStyle("-fx-background-color: #1e2124; -fx-background-radius: 8; -fx-padding: 8;");
            videoContainer.setMaxWidth(480);

            WebView videoPlayer = new WebView();
            videoPlayer.setPrefSize(460, 260);
            videoPlayer.setMaxSize(460, 260);

            String videoHtml = String.format("""
                <html>
                <head>
                    <style>
                        body { margin: 0; padding: 0; background: #000; }
                        video { width: 100%%; height: 100%%; object-fit: contain; }
                    </style>
                </head>
                <body>
                    <video controls>
                        <source src="file:///%s" type="%s">
                        Your browser does not support the video tag.
                    </video>
                </body>
                </html>
                """,
                    tempVideoFile.getAbsolutePath().replace("\\", "/"),
                    guessMimeType(fileName)
            );

            videoPlayer.getEngine().loadContent(videoHtml);
            videoContainer.getChildren().add(videoPlayer);

            // Add controls
            HBox controlsBox = new HBox(8);
            controlsBox.setAlignment(Pos.CENTER_LEFT);

            Label videoIcon = new Label("🎥");
            videoIcon.setStyle("-fx-font-size: 16px;");

            Label videoInfo = new Label(fileName + " • " + formatFileSize(size));
            videoInfo.setStyle("-fx-text-fill: #b5bac1; -fx-font-size: 12px;");

            Button fullscreenBtn = new Button("Fullscreen");
            fullscreenBtn.setStyle("-fx-background-color: #5865f2; -fx-text-fill: white; -fx-font-size: 11px;");
            fullscreenBtn.setOnAction(e -> openVideoFullscreen(tempVideoFile, fileName));

            controlsBox.getChildren().addAll(videoIcon, videoInfo, fullscreenBtn);
            videoContainer.getChildren().add(controlsBox);
            container.getChildren().add(videoContainer);

            if (caption != null && !caption.trim().isEmpty()) {
                Label captionLabel = new Label(caption.trim());
                captionLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px;");
                captionLabel.setWrapText(true);
                container.getChildren().add(captionLabel);
            }

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to display video: " + e.getMessage());
            addErrorLabel(container, "Failed to display video: " + fileName);
        }
    }

    private void displayAudioMediaWithPlayer(VBox container, String fileName, String base64Data, long size, String caption) {
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "WhisperClient");
            tempDir.mkdirs();
            File tempAudioFile = new File(tempDir, "temp_" + System.currentTimeMillis() + "_" + fileName);

            byte[] audioBytes = Base64.getDecoder().decode(base64Data);
            Files.write(tempAudioFile.toPath(), audioBytes);

            VBox audioContainer = new VBox(8);
            audioContainer.setStyle("-fx-background-color: #1e2124; -fx-background-radius: 8; -fx-padding: 12;");
            audioContainer.setMaxWidth(380);

            WebView audioPlayer = new WebView();
            audioPlayer.setPrefHeight(60);

            String audioHtml = String.format("""
                <html>
                <head>
                    <style>
                        body { margin: 0; padding: 8px; background: #1e2124; color: white; }
                        audio { width: 100%%; height: 40px; background: #40444b; }
                    </style>
                </head>
                <body>
                    <audio controls>
                        <source src="file:///%s" type="%s">
                        Your browser does not support the audio tag.
                    </audio>
                </body>
                </html>
                """,
                    tempAudioFile.getAbsolutePath().replace("\\", "/"),
                    guessMimeType(fileName)
            );

            audioPlayer.getEngine().loadContent(audioHtml);
            audioContainer.getChildren().add(audioPlayer);

            addMediaInfo(container, fileName, size);
            container.getChildren().add(audioContainer);

            if (caption != null && !caption.trim().isEmpty()) {
                Label captionLabel = new Label(caption.trim());
                captionLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px;");
                captionLabel.setWrapText(true);
                container.getChildren().add(captionLabel);
            }

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to display audio: " + e.getMessage());
            addErrorLabel(container, "Failed to display audio: " + fileName);
        }
    }

    private void displayFileMedia(VBox container, String fileName, String mimeType, long size, String caption) {
        HBox fileContainer = new HBox(12);
        fileContainer.setStyle("-fx-background-color: #1e2124; -fx-background-radius: 8; -fx-padding: 12;");
        fileContainer.setAlignment(Pos.CENTER_LEFT);
        fileContainer.setMaxWidth(380);

        Label fileIcon = new Label(getFileIcon(mimeType));
        fileIcon.setStyle("-fx-font-size: 24px;");

        VBox fileInfo = new VBox(2);
        Label fileTitle = new Label(fileName);
        fileTitle.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px; -fx-font-weight: 600;");
        Label fileMeta = new Label(mimeType + " • " + formatFileSize(size));
        fileMeta.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px;");
        fileInfo.getChildren().addAll(fileTitle, fileMeta);

        Button downloadBtn = new Button("Download");
        downloadBtn.setStyle("-fx-background-color: #5865f2; -fx-text-fill: white; -fx-font-size: 11px;");
        downloadBtn.setOnAction(e -> openFile(fileName));

        fileContainer.getChildren().addAll(fileIcon, fileInfo, downloadBtn);
        container.getChildren().add(fileContainer);

        if (caption != null && !caption.trim().isEmpty()) {
            Label captionLabel = new Label(caption.trim());
            captionLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px;");
            captionLabel.setWrapText(true);
            container.getChildren().add(captionLabel);
        }
    }

    // ============== UTILITY METHODS - YOUR EXISTING SIGNATURES ==============

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

    private void addMediaInfo(VBox container, String fileName, long size) {
        Label infoLabel = new Label(fileName + " • " + formatFileSize(size));
        infoLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px;");
        container.getChildren().add(infoLabel);
    }

    private void addErrorLabel(VBox container, String message) {
        Label errorLabel = new Label(message);
        errorLabel.setStyle("-fx-text-fill: #f04747; -fx-font-size: 12px;");
        container.getChildren().add(errorLabel);
    }

    private String getFileIcon(String mimeType) {
        if (mimeType.startsWith("image/")) return "🖼️";
        if (mimeType.startsWith("video/")) return "🎥";
        if (mimeType.startsWith("audio/")) return "🎵";
        if (mimeType.contains("pdf")) return "📄";
        if (mimeType.contains("text")) return "📝";
        return "📄";
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void openFullscreenImage(Image image, String fileName) {
        Stage imageStage = new Stage();
        imageStage.setTitle(fileName);
        imageStage.initModality(Modality.APPLICATION_MODAL);

        ImageView fullImageView = new ImageView(image);
        fullImageView.setPreserveRatio(true);
        fullImageView.setSmooth(true);

        ScrollPane scrollPaneImg = new ScrollPane(fullImageView);
        scrollPaneImg.setStyle("-fx-background-color: black;");

        Scene scene = new Scene(scrollPaneImg, 800, 600);
        imageStage.setScene(scene);
        imageStage.show();
    }

    private void openVideoFullscreen(File videoFile, String fileName) {
        Stage videoStage = new Stage();
        videoStage.setTitle("Playing: " + fileName);
        videoStage.setMaximized(true);

        WebView fullscreenPlayer = new WebView();
        String videoHtml = String.format("""
            <html>
            <head>
                <style>
                    body { margin: 0; padding: 0; background: #000; display: flex; justify-content: center; align-items: center; height: 100vh; }
                    video { max-width: 100%%; max-height: 100%%; object-fit: contain; }
                </style>
            </head>
            <body>
                <video controls autoplay>
                    <source src="file:///%s" type="%s">
                </video>
            </body>
            </html>
            """,
                videoFile.getAbsolutePath().replace("\\", "/"),
                guessMimeType(videoFile.getName())
        );

        fullscreenPlayer.getEngine().loadContent(videoHtml);
        Scene scene = new Scene(new StackPane(fullscreenPlayer));
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) videoStage.close(); });
        videoStage.setScene(scene);
        videoStage.show();
    }

    private void openUrl(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            System.err.println("Failed to open URL: " + url);
            notificationManager.showToast("Error", "Failed to open link", NotificationManager.ToastType.ERROR);
        }
    }

    private void deleteMessage(ChatMessage message, VBox container) {
        messagesBox.getChildren().remove(container);
        if (message == lastDisplayedMessage) {
            lastDisplayedMessage = null;
        }
    }

    private void autoSaveMediaFile(String fileName, String mimeType, String base64Data) {
        try {
            String userHome = System.getProperty("user.home");
            File saveDir = new File(userHome, mimeType.startsWith("image/") ? "Pictures/WhisperClient" :
                    mimeType.startsWith("video/") ? "Videos/WhisperClient" : "Downloads/WhisperClient");
            if (!saveDir.exists()) saveDir.mkdirs();

            File saveFile = new File(saveDir, fileName);
            int counter = 1;
            while (saveFile.exists()) {
                String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                String extension = fileName.substring(fileName.lastIndexOf('.'));
                saveFile = new File(saveDir, baseName + "_" + counter + extension);
                counter++;
            }

            Files.write(saveFile.toPath(), Base64.getDecoder().decode(base64Data));
            System.out.println("[ChatController] Auto-saved media to: " + saveFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[ChatController] Failed to auto-save: " + e.getMessage());
        }
    }

    private String encodeFileToBase64(File file) {
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            System.err.println("[ChatController] Failed to encode file: " + e.getMessage());
            return "";
        }
    }

    private String guessMimeType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        switch (extension) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "mov": return "video/quicktime";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            default: return "application/octet-stream";
        }
    }

    private void openFile(String fileName) {
        try {
            String userHome = System.getProperty("user.home");
            File[] searchDirs = {
                    new File(userHome, "Pictures/WhisperClient"),
                    new File(userHome, "Videos/WhisperClient"),
                    new File(userHome, "Downloads/WhisperClient")
            };

            for (File dir : searchDirs) {
                File file = new File(dir, fileName);
                if (file.exists()) {
                    Desktop.getDesktop().open(file);
                    return;
                }
            }
            notificationManager.showToast("File Not Found", "File not found in saved locations", NotificationManager.ToastType.ERROR);
        } catch (Exception e) {
            notificationManager.showToast("Open Error", "Failed to open file", NotificationManager.ToastType.ERROR);
        }
    }

    private void showError(String title, String message) {
        if (notificationManager != null) {
            notificationManager.showErrorNotification(title, message);
        }
    }

    // ============== EXISTING MESSAGE HANDLING - YOUR STRUCTURE ==============

    public void processIncomingMessage(String messageText) {
        handleCompleteMessage(messageText);
    }

    private void handleCompleteMessage(String messageContent) {
        if (friend != null) {
            ChatMessage incomingMessage = ChatMessage.fromIncoming(friend.getUsername(), messageContent);
            Platform.runLater(() -> {
                addMessageBubble(incomingMessage);
                notificationManager.clearNotificationCount(friend.getUsername());
            });
        }
    }
}