package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.Friend;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.cottonlesergal.whisperclient.services.*;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import com.cottonlesergal.whisperclient.services.MediaPreviewService.MediaPreview;
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
    // FXML Components
    @FXML private ScrollPane scrollPane;
    @FXML private VBox messagesBox;
    @FXML private TextField txtMessage;
    @FXML private Button btnAttach;

    // Services
    private final DirectoryClient directory = new DirectoryClient();
    private final MessageStorageService storage = MessageStorageService.getInstance();
    private final MediaPreviewService previewService = MediaPreviewService.getInstance();
    private final NotificationManager notificationManager = NotificationManager.getInstance();
    private final HttpMediaClientService httpMediaService = HttpMediaClientService.getInstance();

    // State
    private Friend friend;
    private VBox previewContainer;
    private final List<MediaPreview> pendingUploads = new ArrayList<>();
    private final AtomicInteger currentPage = new AtomicInteger(0);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean hasMoreMessages = new AtomicBoolean(true);

    // Message consolidation state
    private ChatMessage lastDisplayedMessage = null;
    private VBox lastMessageContainer = null;
    private final List<ChatMessage> messagesInLastGroup = new ArrayList<>();
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
        System.out.println("[ChatController] Initialized with HTTP media system");
    }

    // ============== SETUP METHODS ==============

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

        chatAreaMenu.setOnShowing(e -> {
            pasteFile.setDisable(!hasImageOrFileInClipboard());
        });

        messagesBox.setOnContextMenuRequested(event -> {
            // Calculate dynamic positioning - place menu below deletion buttons
            double baseY = event.getScreenY();
            double offsetY = calculateContextMenuOffset();

            chatAreaMenu.show(messagesBox, event.getScreenX(), baseY + offsetY);
            event.consume();
        });

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

    private double calculateContextMenuOffset() {
        // Calculate offset based on how many deletion options would be shown
        int deletionButtonCount = 0;

        if (lastMessageContainer != null) {
            deletionButtonCount = 1; // "Delete Message Group"
            if (messagesInLastGroup.size() > 1) {
                deletionButtonCount = 2; // + "Delete This Message"
            }
        }

        return deletionButtonCount * 30;
    }

    private void setupDragAndDrop() {
        messagesBox.setOnDragOver(this::handleDragOver);
        messagesBox.setOnDragDropped(this::handleDragDropped);
        scrollPane.setOnDragOver(this::handleDragOver);
        scrollPane.setOnDragDropped(this::handleDragDropped);
    }

    private void setupKeyboardShortcuts() {
        Platform.runLater(() -> {
            if (txtMessage.getScene() != null) {
                txtMessage.getScene().setOnKeyPressed(event -> {
                    if (event.isControlDown() && event.getCode() == KeyCode.V) {
                        if (!txtMessage.isFocused()) {
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
                    txtMessage.insertText(txtMessage.getCaretPosition(), "\n");
                } else {
                    onSend();
                }
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.V) {
                if (hasImageOrFileInClipboard()) {
                    handleClipboardPaste();
                    event.consume();
                } else {
                    Clipboard clipboard = Clipboard.getSystemClipboard();
                    if (clipboard.hasString()) {
                        String clipboardText = clipboard.getString();
                        if (isImageUrl(clipboardText)) {
                            downloadAndSendImage(clipboardText);
                            event.consume();
                        }
                    }
                }
            }
        });
    }

    private void setupClipboardPaste() {
        // Handled in other setup methods
    }

    private void setupPreviewContainer() {
        if (previewContainer == null) {
            previewContainer = new VBox(8);
            previewContainer.setStyle("-fx-padding: 8; -fx-background-color: #36393f;");
            previewContainer.setVisible(false);
            previewContainer.setManaged(false);

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

    // ============== INPUT HANDLING ==============

    @FXML
    private void onSend() {
        String text = txtMessage.getText().trim();
        if (text.isEmpty() && pendingUploads.isEmpty()) return;

        if (!pendingUploads.isEmpty()) {
            List<MediaPreview> toSend = new ArrayList<>(pendingUploads);
            pendingUploads.clear();
            updatePreviewVisibility();

            sendMediaBatch(toSend, text);
            txtMessage.clear();
        } else if (!text.isEmpty()) {
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

        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            File picturesDir = new File(userHome, "Pictures");
            if (picturesDir.exists()) {
                fileChooser.setInitialDirectory(picturesDir);
            }
        }

        Stage stage = (Stage) txtMessage.getScene().getWindow();
        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(stage);

        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            for (File file : selectedFiles) {
                processFileForUpload(file);
            }
        }
    }

    // ============== MESSAGE CONSOLIDATION LOGIC ==============

    public void addMessageBubble(ChatMessage message) {
        if (shouldConsolidateMessage(message)) {
            consolidateWithLastMessage(message);
        } else {
            VBox bubble = createNewMessageBubble(message);
            messagesBox.getChildren().add(bubble);
            lastMessageContainer = bubble;
            messagesInLastGroup.clear();
            messagesInLastGroup.add(message);
        }

        lastDisplayedMessage = message;
        scrollToBottom();
    }

    private boolean shouldConsolidateMessage(ChatMessage message) {
        if (lastDisplayedMessage == null || lastMessageContainer == null) {
            return false;
        }

        boolean sameSender;
        if (message.isFromMe() && lastDisplayedMessage.isFromMe()) {
            sameSender = true;
        } else if (!message.isFromMe() && !lastDisplayedMessage.isFromMe()) {
            sameSender = lastDisplayedMessage.getFrom().equals(message.getFrom());
        } else {
            sameSender = false;
        }

        boolean withinTimeLimit = Math.abs(message.getTimestamp() - lastDisplayedMessage.getTimestamp()) <= MESSAGE_GROUP_TIME_MS;

        return sameSender && withinTimeLimit;
    }

    private void consolidateWithLastMessage(ChatMessage message) {
        if (lastMessageContainer == null) return;

        VBox contentContainer = findContentContainer(lastMessageContainer);
        if (contentContainer == null) return;

        messagesInLastGroup.add(message);

        Region spacer = new Region();
        spacer.setPrefHeight(6);
        contentContainer.getChildren().add(spacer);

        if (message.getContent().startsWith("[INLINE_MEDIA:")) {
            Node mediaContent = createInlineMediaContent(message, message.isFromMe());
            mediaContent.setStyle("-fx-padding: 2 0;");
            createIndividualMessageContextMenu(mediaContent, message, lastMessageContainer);
            contentContainer.getChildren().add(mediaContent);
        } else {
            Label textLabel = createConsolidatedTextLabel(message.getContent(), message.isFromMe(), message);
            contentContainer.getChildren().add(textLabel);
        }
    }

    private VBox createNewMessageBubble(ChatMessage message) {
        boolean isFromMe = message.isFromMe();

        VBox messageContainer = new VBox(4);
        messageContainer.setPadding(new Insets(8, 12, 2, 12));
        messageContainer.setMaxWidth(700);
        messageContainer.setAlignment(Pos.CENTER_LEFT);

        HBox headerBox = createMessageHeader(message, isFromMe);
        messageContainer.getChildren().add(headerBox);

        VBox contentContainer = new VBox(4);
        contentContainer.setPadding(new Insets(8, 12, 8, 12));
        contentContainer.setMaxWidth(400);

        if (isFromMe) {
            contentContainer.setStyle("-fx-background-color: #5865f2; -fx-background-radius: 12;");
        } else {
            contentContainer.setStyle("-fx-background-color: #40444b; -fx-background-radius: 12;");
        }

        if (message.getContent().startsWith("[INLINE_MEDIA:")) {
            Node mediaContent = createInlineMediaContent(message, isFromMe);
            mediaContent.setStyle("-fx-padding: 2 0;");
            createIndividualMessageContextMenu(mediaContent, message, messageContainer);
            contentContainer.getChildren().add(mediaContent);
        } else {
            Label textLabel = createConsolidatedTextLabel(message.getContent(), isFromMe, message);
            contentContainer.getChildren().add(textLabel);
        }

        HBox contentWrapper = new HBox();
        contentWrapper.setMaxWidth(500);
        contentWrapper.setAlignment(Pos.CENTER_LEFT);
        contentWrapper.getChildren().add(contentContainer);

        messageContainer.getChildren().add(contentWrapper);

        createMessageGroupContextMenu(messageContainer, message);

        return messageContainer;
    }

    private VBox findContentContainer(VBox messageContainer) {
        for (Node child : messageContainer.getChildren()) {
            if (child instanceof HBox) {
                HBox hbox = (HBox) child;
                for (Node hboxChild : hbox.getChildren()) {
                    if (hboxChild instanceof VBox) {
                        return (VBox) hboxChild;
                    }
                }
            }
        }
        return null;
    }

    // ============== DYNAMIC CONTEXT MENU POSITIONING ==============

    private void createMessageGroupContextMenu(VBox messageContainer, ChatMessage firstMessage) {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem deleteGroup = new MenuItem("Delete Message Group");
        deleteGroup.setOnAction(e -> deleteMessageGroup(messageContainer));

        contextMenu.getItems().add(deleteGroup);

        messageContainer.setOnContextMenuRequested(e -> {
            double baseY = e.getScreenY();
            double offsetY = 30; // Base offset for "Delete Message Group" button

            contextMenu.show(messageContainer, e.getScreenX(), baseY + offsetY);
            e.consume();
        });
    }

    private void createIndividualMessageContextMenu(Node messageNode, ChatMessage message, VBox parentContainer) {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem deleteThis = new MenuItem("Delete This Message");
        deleteThis.setOnAction(e -> deleteIndividualMessage(messageNode, message, parentContainer));

        MenuItem deleteGroup = new MenuItem("Delete Message Group");
        deleteGroup.setOnAction(e -> deleteMessageGroup(parentContainer));

        contextMenu.getItems().addAll(deleteThis, deleteGroup);

        messageNode.setOnContextMenuRequested(e -> {
            double baseY = e.getScreenY();
            double offsetY = 60; // Offset for both deletion buttons

            contextMenu.show(messageNode, e.getScreenX(), baseY + offsetY);
            e.consume();
        });
    }

    private Label createConsolidatedTextLabel(String text, boolean isFromMe, ChatMessage message) {
        Label textLabel = new Label(text);
        textLabel.setWrapText(true);
        textLabel.setMaxWidth(380);

        if (isFromMe) {
            textLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 2 0;");
        } else {
            textLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px; -fx-padding: 2 0;");
        }

        createIndividualMessageContextMenu(textLabel, message, lastMessageContainer);

        return textLabel;
    }

    private void deleteIndividualMessage(Node messageNode, ChatMessage message, VBox parentContainer) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Message");
        confirm.setHeaderText("Delete this message?");
        confirm.setContentText("This will remove only this specific message from the group.");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                VBox contentContainer = findContentContainer(parentContainer);
                if (contentContainer != null) {
                    int nodeIndex = contentContainer.getChildren().indexOf(messageNode);
                    contentContainer.getChildren().remove(messageNode);

                    if (nodeIndex > 0 && contentContainer.getChildren().size() > nodeIndex - 1) {
                        Node prevNode = contentContainer.getChildren().get(nodeIndex - 1);
                        if (prevNode instanceof Region) {
                            contentContainer.getChildren().remove(prevNode);
                        }
                    }
                }

                messagesInLastGroup.remove(message);

                if (messagesInLastGroup.isEmpty()) {
                    messagesBox.getChildren().remove(parentContainer);
                    lastMessageContainer = null;
                    lastDisplayedMessage = null;
                }
            }
        });
    }

    private void deleteMessageGroup(VBox messageContainer) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Message Group");
        confirm.setHeaderText("Delete entire message group?");
        confirm.setContentText("This will remove all messages in this group.");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                messagesBox.getChildren().remove(messageContainer);

                if (messageContainer == lastMessageContainer) {
                    lastMessageContainer = null;
                    lastDisplayedMessage = null;
                    messagesInLastGroup.clear();
                }
            }
        });
    }

    private HBox createMessageHeader(ChatMessage message, boolean isFromMe) {
        HBox headerBox = new HBox(8);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        headerBox.setPadding(new Insets(4, 0, 4, 0));

        ImageView avatar = new ImageView();
        avatar.setFitWidth(32);
        avatar.setFitHeight(32);

        String avatarUrl;
        String displayName;

        if (isFromMe) {
            avatarUrl = Session.me != null ? Session.me.getAvatarUrl() : "";
            displayName = Session.me != null ? Session.me.getDisplayName() : "You";
        } else {
            avatarUrl = friend != null ? friend.getAvatar() : "";
            displayName = friend != null ? friend.getDisplayName() : message.getFrom();
        }

        avatar.setImage(AvatarCache.get(avatarUrl, 32));

        Circle avatarClip = new Circle(16, 16, 16);
        avatar.setClip(avatarClip);

        Label usernameLabel = new Label(displayName);
        if (isFromMe) {
            usernameLabel.setStyle("-fx-text-fill: #5865f2; -fx-font-weight: 600; -fx-font-size: 14px;");
        } else {
            usernameLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: 600; -fx-font-size: 14px;");
        }

        Label timestampLabel = new Label(formatTimestamp(message.getTimestamp()));
        timestampLabel.setStyle("-fx-text-fill: #72767d; -fx-font-size: 12px;");

        headerBox.getChildren().addAll(avatar, usernameLabel, timestampLabel);

        return headerBox;
    }

    // ============== MESSAGE SENDING ==============

    private void sendTextMessage(String text) {
        if (friend == null) return;

        ChatMessage outgoingMessage = ChatMessage.fromOutgoing(friend.getUsername(), text);
        storage.storeMessage(friend.getUsername(), outgoingMessage);

        Platform.runLater(() -> {
            addMessageBubble(outgoingMessage);
            scrollToBottom();
        });

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

    private void sendMediaBatch(List<MediaPreview> previewsToSend, String caption) {
        if (previewsToSend.isEmpty()) return;

        for (int i = 0; i < previewsToSend.size(); i++) {
            MediaPreview preview = previewsToSend.get(i);
            String fileCaption = (i == 0 && caption != null && !caption.isEmpty()) ? caption : null;

            Platform.runLater(() -> {
                previewContainer.getChildren().remove(preview.getPreviewComponent());
            });

            sendSingleMediaFile(preview, fileCaption);
        }
    }

    private void sendSingleMediaFile(MediaPreview preview, String caption) {
        if (friend == null) return;

        if (preview.isProcessing()) {
            System.out.println("[ChatController] Skipping duplicate send for: " + preview.getFileName());
            return;
        }

        previewService.showProgress(preview, "Sending...");

        httpMediaService.sendMediaAsync(preview.getFile(), friend.getUsername(), caption)
                .thenRun(() -> {
                    Platform.runLater(() -> {
                        try {
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
                });
    }

    // ============== FILE PROCESSING ==============

    private void processFileForUpload(File file) {
        try {
            if (!file.exists() || file.length() > 25 * 1024 * 1024) {
                showError("File Error", "File is too large (max 25MB) or doesn't exist.");
                return;
            }

            MediaPreview preview = previewService.createPreview(file);
            pendingUploads.add(preview);

            preview.setOnRemove(() -> {
                if (!preview.isProcessing()) {
                    pendingUploads.remove(preview);
                    Platform.runLater(() -> {
                        previewContainer.getChildren().remove(preview.getPreviewComponent());
                        updatePreviewVisibility();

                        if (preview.getFile() != null && preview.getFile().getName().startsWith("pasted_image_")) {
                            preview.getFile().delete();
                        }
                    });
                }
            });

            preview.setOnSend(() -> {
                System.out.println("[ChatController] Individual send disabled - use Enter key to send files");
            });

            Platform.runLater(() -> {
                previewContainer.getChildren().add(preview.getPreviewComponent());
                updatePreviewVisibility();
            });

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to process file: " + e.getMessage());
            showError("File Error", "Failed to process file: " + e.getMessage());
        }
    }

    // ============== CLIPBOARD HANDLING WITH IMAGE URL DETECTION ==============

    private void handleClipboardPaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();

        if (clipboard.hasString()) {
            String clipboardText = clipboard.getString();
            if (isImageUrl(clipboardText)) {
                downloadAndSendImage(clipboardText);
                return;
            }
        }

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
        }
    }

    private boolean hasImageOrFileInClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        return clipboard.hasImage() || clipboard.hasFiles() ||
                (clipboard.hasString() && isImageUrl(clipboard.getString()));
    }

    private void processImageFromClipboard(Image image) {
        try {
            BufferedImage bufferedImage = SwingFXUtils.fromFXImage(image, null);
            String timestamp = String.valueOf(System.currentTimeMillis());
            String tempDir = System.getProperty("java.io.tmpdir");
            File tempFile = new File(tempDir, "pasted_image_" + timestamp + ".png");

            ImageIO.write(bufferedImage, "png", tempFile);
            processFileForUpload(tempFile);

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to process pasted image: " + e.getMessage());
            showError("Paste Error", "Failed to process pasted image: " + e.getMessage());
        }
    }

    private boolean isImageUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;

        try {
            new URI(url);
        } catch (Exception e) {
            return false;
        }

        String lowerUrl = url.toLowerCase();
        return lowerUrl.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|svg)(\\?.*|#.*)?$") ||
                lowerUrl.contains("image") || lowerUrl.contains("img") ||
                lowerUrl.contains("imgur.com") || lowerUrl.contains("pngtree.com") || lowerUrl.contains("freepik.com");
    }

    private void downloadAndSendImage(String imageUrl) {
        notificationManager.showToast("Downloading Image", "Downloading image from URL...", NotificationManager.ToastType.INFO);

        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(imageUrl))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .GET()
                        .build();

                HttpResponse<byte[]> response = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() == 200) {
                    byte[] imageData = response.body();
                    String fileName = "downloaded_image_" + System.currentTimeMillis() + ".png";

                    File tempDir = new File(System.getProperty("java.io.tmpdir"), "WhisperClient");
                    tempDir.mkdirs();
                    File tempImageFile = new File(tempDir, fileName);
                    Files.write(tempImageFile.toPath(), imageData);

                    Platform.runLater(() -> {
                        processFileForUpload(tempImageFile);
                        notificationManager.showToast("Image Ready", "Image downloaded and ready to send!", NotificationManager.ToastType.SUCCESS);
                    });
                }

            } catch (Exception e) {
                Platform.runLater(() -> {
                    notificationManager.showToast("Download Error", "Failed to download image", NotificationManager.ToastType.ERROR);
                });
            }
        });
    }

    // ============== DRAG & DROP ==============

    private void handleDragOver(DragEvent event) {
        if (event.getGestureSource() != messagesBox && event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasFiles()) {
            for (File file : db.getFiles()) {
                processFileForUpload(file);
            }
        }
        event.setDropCompleted(true);
        event.consume();
    }

    // ============== MEDIA DISPLAY ==============

    private Node createInlineMediaContent(ChatMessage message, boolean isFromMe) {
        VBox container = new VBox(4);

        try {
            String content = message.getContent();
            int endBracket = content.indexOf(']');
            String mediaInfo = content.substring(14, endBracket);
            String caption = endBracket + 1 < content.length() ? content.substring(endBracket + 1) : "";

            String[] parts = mediaInfo.split(":", 5);
            if (parts.length >= 5) {
                String fileName = parts[1];
                String mimeType = parts[2];
                long size = Long.parseLong(parts[3]);
                String base64Data = parts[4];

                if (mimeType.startsWith("image/")) {
                    byte[] imageBytes = Base64.getDecoder().decode(base64Data);
                    Image image = new Image(new ByteArrayInputStream(imageBytes));

                    ImageView imageView = new ImageView(image);
                    imageView.setPreserveRatio(true);
                    imageView.setFitWidth(Math.min(360, image.getWidth()));
                    imageView.setFitHeight(Math.min(260, image.getHeight()));
                    imageView.setCursor(Cursor.HAND);
                    imageView.setOnMouseClicked(e -> openFullscreenImage(image, fileName));

                    container.getChildren().add(imageView);
                } else if (mimeType.startsWith("video/")) {
                    File tempDir = new File(System.getProperty("java.io.tmpdir"), "WhisperClient");
                    tempDir.mkdirs();
                    File tempVideoFile = new File(tempDir, "temp_" + System.currentTimeMillis() + "_" + fileName);

                    byte[] videoBytes = Base64.getDecoder().decode(base64Data);
                    Files.write(tempVideoFile.toPath(), videoBytes);

                    WebView videoPlayer = new WebView();
                    videoPlayer.setPrefSize(360, 200);

                    String videoHtml = String.format("""
                    <html>
                    <head><style>body{margin:0;padding:0;background:#000;}video{width:100%%;height:100%%;object-fit:contain;}</style></head>
                    <body><video controls><source src="file:///%s" type="%s"></video></body>
                    </html>
                    """,
                            tempVideoFile.getAbsolutePath().replace("\\", "/"), mimeType
                    );

                    videoPlayer.getEngine().loadContent(videoHtml);
                    container.getChildren().add(videoPlayer);
                }

                if (caption != null && !caption.trim().isEmpty()) {
                    Label captionLabel = new Label(caption.trim());
                    captionLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px;");
                    captionLabel.setWrapText(true);
                    container.getChildren().add(captionLabel);
                }
            }

        } catch (Exception e) {
            Label errorLabel = new Label("Failed to load media");
            errorLabel.setStyle("-fx-text-fill: #f04747;");
            container.getChildren().add(errorLabel);
        }

        return container;
    }

    // ============== FRIEND MANAGEMENT ==============

    public void bindFriend(Friend f) {
        this.friend = f;
        messagesBox.getChildren().clear();
        currentPage.set(0);
        hasMoreMessages.set(true);
        clearAllPreviews();

        lastDisplayedMessage = null;
        lastMessageContainer = null;
        messagesInLastGroup.clear();

        loadInitialMessages();

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
                    VBox messageBubble = createNewMessageBubble(messages.get(i));
                    messagesBox.getChildren().add(1, messageBubble);
                }
                hasMoreMessages.set(messages.size() >= 100);
            } else {
                hasMoreMessages.set(false);
            }
        }));
    }

    private void processStoredMessage(ChatMessage message) {
        addMessageBubble(message);
    }

    private void addDMHeader() {
        VBox headerContainer = new VBox(12);
        headerContainer.setAlignment(Pos.CENTER);
        headerContainer.setStyle("-fx-padding: 20; -fx-background-color: transparent;");

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
        messagesBox.getChildren().add(headerContainer);
    }

    // ============== UTILITY METHODS ==============

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

    public void clearAllMessages() {
        Platform.runLater(() -> {
            if (messagesBox != null) {
                messagesBox.getChildren().clear();
                addDMHeader();
            }

            lastDisplayedMessage = null;
            lastMessageContainer = null;
            messagesInLastGroup.clear();
        });
    }

    public void refreshConversation() {
        if (friend == null) return;

        currentPage.set(0);
        hasMoreMessages.set(true);

        lastDisplayedMessage = null;
        lastMessageContainer = null;
        messagesInLastGroup.clear();

        loadInitialMessages();
    }

    private void clearMessageHistory() {
        if (friend == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Clear message history with " + friend.getDisplayName() + "?",
                ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                storage.clearMessages(friend.getUsername());
                refreshConversation();
            }
        });
    }

    private void updatePreviewVisibility() {
        boolean hasUploads = !pendingUploads.isEmpty();
        previewContainer.setVisible(hasUploads);
        previewContainer.setManaged(hasUploads);
    }

    private void clearAllPreviews() {
        for (MediaPreview preview : new ArrayList<>(pendingUploads)) {
            if (!preview.isProcessing()) {
                if (preview.getFile() != null && preview.getFile().getName().startsWith("pasted_image_")) {
                    preview.getFile().delete();
                }
            }
        }

        pendingUploads.clear();
        previewContainer.getChildren().clear();
        updatePreviewVisibility();
    }

    private String formatTimestamp(long timestamp) {
        LocalDateTime messageTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamp),
                java.time.ZoneId.systemDefault()
        );

        LocalDateTime now = LocalDateTime.now();

        if (messageTime.toLocalDate().equals(now.toLocalDate())) {
            return messageTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        }

        if (messageTime.toLocalDate().equals(now.toLocalDate().minusDays(1))) {
            return "Yesterday at " + messageTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        }

        return messageTime.format(DateTimeFormatter.ofPattern("MMM d, HH:mm"));
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String encodeFileToBase64(File file) {
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
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
            default: return "application/octet-stream";
        }
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

    private void showError(String title, String message) {
        if (notificationManager != null) {
            notificationManager.showErrorNotification(title, message);
        }
    }

    // ============== EXISTING MESSAGE HANDLING ==============

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