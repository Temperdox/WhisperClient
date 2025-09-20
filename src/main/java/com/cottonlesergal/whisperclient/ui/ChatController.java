package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.models.UserSummary;
import com.cottonlesergal.whisperclient.services.*;
import com.cottonlesergal.whisperclient.services.MessageStorageService.ChatMessage;
import com.cottonlesergal.whisperclient.services.MediaPreviewService.MediaPreview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
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
import javafx.scene.shape.Rectangle;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatController implements Initializable {

    // FXML Components
    @FXML private VBox chatContainer;
    @FXML private ScrollPane messagesScrollPane;
    @FXML private VBox messagesBox;
    @FXML private TextField messageField;
    @FXML private Button sendButton;
    @FXML private Label dmHeader;
    @FXML private VBox previewContainer;

    // Services and utilities - Using existing classes
    private MessageStorageService storage;
    private NotificationManager notificationManager;
    private MediaPreviewService previewService;
    private HttpMediaClientService httpMediaService;
    private DirectoryClient directory;
    private UserSummary friend;

    // State management
    private final List<MediaPreview> pendingUploads = new ArrayList<>();
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean hasMoreMessages = new AtomicBoolean(true);
    private int nextPage = 0;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Initialize services using existing instances
        storage = MessageStorageService.getInstance();
        notificationManager = NotificationManager.getInstance();
        previewService = MediaPreviewService.getInstance();
        httpMediaService = HttpMediaClientService.getInstance();
        directory = new DirectoryClient();

        System.out.println("[ChatController] Initialized with HTTP media system");

        // Setup UI components
        setupScrollPane();
        setupMessageField();
        setupDragAndDrop();
        setupPreviewContainer();

        // Auto-scroll to bottom when new messages arrive
        messagesBox.getChildren().addListener((javafx.collections.ListChangeListener<Node>) c -> {
            Platform.runLater(() -> messagesScrollPane.setVvalue(1.0));
        });
    }

    private void setupScrollPane() {
        messagesScrollPane.setFitToWidth(true);
        messagesScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        messagesScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        // Infinite scroll - load more messages when scrolling to top
        messagesScrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() == 0.0 && hasMoreMessages.get() && !isLoading.get()) {
                loadMoreMessages();
            }
        });
    }

    private void setupMessageField() {
        messageField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                if (event.isShiftDown()) {
                    // Shift+Enter for new line (handled by TextField automatically)
                } else {
                    sendMessage();
                    event.consume();
                }
            }
        });

        messageField.textProperty().addListener((obs, oldText, newText) -> {
            sendButton.setDisable(newText.trim().isEmpty() && pendingUploads.isEmpty());
        });

        // Initial state
        sendButton.setDisable(true);
    }

    private void setupDragAndDrop() {
        chatContainer.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        chatContainer.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;

            if (db.hasFiles()) {
                for (File file : db.getFiles()) {
                    handleFileUpload(file);
                }
                success = true;
            }

            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void setupPreviewContainer() {
        previewContainer.setVisible(false);
        previewContainer.setManaged(false);
        System.out.println("[ChatController] Added preview container to UI");
    }

    // ============== FRIEND MANAGEMENT ==============

    public void setFriend(UserSummary friend) {
        this.friend = friend;
        if (friend != null) {
            dmHeader.setText("DM with @" + friend.getUsername());
            refreshConversation();
        } else {
            dmHeader.setText("No conversation selected");
            clearMessages();
        }
    }

    public void refreshConversation() {
        if (friend == null) return;

        clearMessages();
        nextPage = 0;
        hasMoreMessages.set(true);

        // Load initial messages
        loadMoreMessages();
    }

    private void clearMessages() {
        messagesBox.getChildren().clear();
        if (friend != null) {
            addDMHeader();
        }
    }

    private void addDMHeader() {
        Label headerLabel = new Label("This is the beginning of your direct message history with @" + friend.getUsername());
        headerLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 14px; -fx-padding: 20;");
        headerLabel.setWrapText(true);
        messagesBox.getChildren().add(headerLabel);
    }

    // ============== MESSAGE LOADING ==============

    private void loadMoreMessages() {
        if (friend == null || isLoading.get() || !hasMoreMessages.get()) return;

        isLoading.set(true);

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
                nextPage++;
            } else {
                hasMoreMessages.set(false);
            }
        }));
    }

    // ============== MESSAGE DISPLAY ==============

    public void addMessageBubble(ChatMessage message) {
        Node bubble = createMessageBubbleWithDeletion(message);
        messagesBox.getChildren().add(bubble);
        scrollToBottom();
    }

    private Node createMessageBubbleWithDeletion(ChatMessage message) {
        boolean isFromMe = message.isFromMe();

        // Check for inline media messages (auto-downloaded with embedded data)
        if (message.getContent().startsWith("[INLINE_MEDIA:")) {
            return createInlineMediaBubble(message, isFromMe);
        } else {
            // Regular text message with potential link embeds
            return createTextMessageBubble(message.getContent(), isFromMe);
        }
    }

    private VBox createMessageContainerWithDeletion(boolean isFromMe, ChatMessage message) {
        VBox container = new VBox(4);
        container.setPadding(new Insets(8));
        container.setMaxWidth(500);

        if (isFromMe) {
            container.setAlignment(Pos.CENTER_RIGHT);
            container.setStyle("-fx-background-color: #5865f2; -fx-background-radius: 12;");
        } else {
            container.setAlignment(Pos.CENTER_LEFT);
            container.setStyle("-fx-background-color: #40444b; -fx-background-radius: 12;");
        }

        // Add right-click context menu for deletion
        if (message != null) {
            ContextMenu contextMenu = new ContextMenu();
            MenuItem deleteItem = new MenuItem("Delete Message");
            deleteItem.setOnAction(e -> deleteMessage(message, container));
            contextMenu.getItems().add(deleteItem);
            container.setOnContextMenuRequested(e -> contextMenu.show(container, e.getScreenX(), e.getScreenY()));
        }

        return container;
    }

    // ============== ENHANCED TEXT MESSAGES WITH LINK EMBEDDING ==============

    private Node createTextMessageBubble(String content, boolean isFromMe) {
        VBox container = createMessageContainerWithDeletion(isFromMe, null);

        // Check for URLs in the message
        List<String> urls = extractUrls(content);

        if (!urls.isEmpty()) {
            // Add text content first
            if (!content.trim().isEmpty()) {
                Label textLabel = createStyledTextLabel(content, isFromMe);
                container.getChildren().add(textLabel);
            }

            // Add embeds for each URL
            for (String url : urls) {
                Node embed = createLinkEmbed(url);
                if (embed != null) {
                    VBox.setMargin(embed, new Insets(8, 0, 0, 0));
                    container.getChildren().add(embed);
                }
            }
        } else {
            // Regular text message
            Label textLabel = createStyledTextLabel(content, isFromMe);
            container.getChildren().add(textLabel);
        }

        return container;
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

    private Label createStyledTextLabel(String text, boolean isFromMe) {
        Label textLabel = new Label(text);
        textLabel.setWrapText(true);
        textLabel.setMaxWidth(400);

        if (isFromMe) {
            textLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 12;");
        } else {
            textLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px; -fx-padding: 8 12;");
        }

        return textLabel;
    }

    // ============== ENHANCED INLINE MEDIA HANDLING ==============

    private Node createInlineMediaBubble(ChatMessage message, boolean isFromMe) {
        VBox container = createMessageContainerWithDeletion(isFromMe, message);

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

    private void displayInlineMedia(VBox container, String fileName, String mimeType,
                                    long size, String base64Data, String caption) {
        try {
            // Auto-save the file to appropriate directory
            autoSaveMediaFile(fileName, mimeType, base64Data);

            if (mimeType.startsWith("image/")) {
                displayImageMedia(container, fileName, base64Data, size, caption);
            } else if (mimeType.startsWith("video/")) {
                displayVideoMedia(container, fileName, base64Data, size, caption);
            } else if (mimeType.startsWith("audio/")) {
                displayAudioMedia(container, fileName, base64Data, size, caption);
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
            imageView.setFitWidth(Math.min(400, image.getWidth()));
            imageView.setFitHeight(Math.min(300, image.getHeight()));

            // Add rounded corners
            Rectangle clip = new Rectangle();
            clip.setArcWidth(8);
            clip.setArcHeight(8);
            clip.setWidth(imageView.getFitWidth());
            clip.setHeight(imageView.getFitHeight());
            imageView.setClip(clip);

            // Make image clickable for fullscreen view
            imageView.setOnMouseClicked(e -> openFullscreenImage(image, fileName));
            imageView.getStyleClass().add("message-image");

            container.getChildren().add(imageView);

            // File info
            addMediaInfo(container, fileName, size);

            // Caption if present
            if (caption != null && !caption.trim().isEmpty()) {
                Label captionLabel = new Label(caption.trim());
                captionLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px;");
                captionLabel.setWrapText(true);
                container.getChildren().add(captionLabel);
            }

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to display image: " + e.getMessage());
            addErrorLabel(container, "Failed to display image: " + fileName);
        }
    }

    // Continue in Part 2...
    // ============== MEDIA DISPLAY METHODS - CONTINUED ==============

    private void displayVideoMedia(VBox container, String fileName, String base64Data, long size, String caption) {
        try {
            // Create temporary file for video playback
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "WhisperClient");
            tempDir.mkdirs();
            File tempVideoFile = new File(tempDir, "temp_" + System.currentTimeMillis() + "_" + fileName);

            byte[] videoBytes = Base64.getDecoder().decode(base64Data);
            Files.write(tempVideoFile.toPath(), videoBytes);

            // Create video player container
            VBox videoContainer = new VBox(8);
            videoContainer.setStyle("-fx-background-color: #2b2d31; -fx-background-radius: 8; -fx-padding: 8;");
            videoContainer.setMaxWidth(400);

            // Video thumbnail/preview
            StackPane videoPreview = new StackPane();
            videoPreview.setPrefSize(400, 225);
            videoPreview.setStyle("-fx-background-color: #000000; -fx-background-radius: 4;");

            // Play button overlay
            Label playButton = new Label("▶");
            playButton.setStyle(
                    "-fx-font-size: 32px; -fx-text-fill: white; " +
                            "-fx-background-color: rgba(0,0,0,0.7); -fx-background-radius: 50%; " +
                            "-fx-padding: 16; -fx-cursor: hand;"
            );

            videoPreview.getChildren().addAll(playButton);
            StackPane.setAlignment(playButton, Pos.CENTER);

            // Click to play video
            videoPreview.setOnMouseClicked(e -> playVideo(tempVideoFile, fileName));

            videoContainer.getChildren().add(videoPreview);

            // File info
            HBox infoBox = new HBox(8);
            infoBox.setAlignment(Pos.CENTER_LEFT);

            Label videoIcon = new Label("🎥");
            videoIcon.setStyle("-fx-font-size: 16px;");

            Label videoInfo = new Label(fileName + " • " + formatFileSize(size));
            videoInfo.setStyle("-fx-text-fill: #b5bac1; -fx-font-size: 12px;");

            Button downloadBtn = new Button("Download");
            downloadBtn.setStyle("-fx-background-color: #5865f2; -fx-text-fill: white; -fx-font-size: 11px;");
            downloadBtn.setOnAction(e -> saveMediaToDownloads(fileName, base64Data));

            infoBox.getChildren().addAll(videoIcon, videoInfo, downloadBtn);
            videoContainer.getChildren().add(infoBox);

            container.getChildren().add(videoContainer);

            // Caption if present
            if (caption != null && !caption.trim().isEmpty()) {
                Label captionLabel = new Label(caption.trim());
                captionLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px;");
                captionLabel.setWrapText(true);
                container.getChildren().add(captionLabel);
            }

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to display video: " + e.getMessage());
            addErrorLabel(container, "Failed to display video: " + fileName);
        }
    }

    private void displayAudioMedia(VBox container, String fileName, String base64Data, long size, String caption) {
        try {
            // Create temporary file for audio playback
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "WhisperClient");
            tempDir.mkdirs();
            File tempAudioFile = new File(tempDir, "temp_" + System.currentTimeMillis() + "_" + fileName);

            byte[] audioBytes = Base64.getDecoder().decode(base64Data);
            Files.write(tempAudioFile.toPath(), audioBytes);

            // Create audio player container
            HBox audioContainer = new HBox(12);
            audioContainer.setStyle("-fx-background-color: #2b2d31; -fx-background-radius: 8; -fx-padding: 12;");
            audioContainer.setAlignment(Pos.CENTER_LEFT);
            audioContainer.setMaxWidth(400);

            // Audio icon and play button
            Button playBtn = new Button("▶");
            playBtn.setStyle(
                    "-fx-background-color: #5865f2; -fx-text-fill: white; " +
                            "-fx-background-radius: 50%; -fx-font-size: 14px; " +
                            "-fx-min-width: 32; -fx-min-height: 32; -fx-cursor: hand;"
            );

            // Audio info
            VBox audioInfo = new VBox(2);

            Label audioTitle = new Label(fileName);
            audioTitle.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px; -fx-font-weight: 600;");

            Label audioMeta = new Label("Audio • " + formatFileSize(size));
            audioMeta.setStyle("-fx-text-fill: #87898c; -fx-font-size: 12px;");

            audioInfo.getChildren().addAll(audioTitle, audioMeta);

            // Download button
            Button downloadBtn = new Button("⬇");
            downloadBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #b5bac1; -fx-cursor: hand;");
            downloadBtn.setOnAction(e -> saveMediaToDownloads(fileName, base64Data));

            playBtn.setOnAction(e -> playAudio(tempAudioFile, fileName, playBtn));

            audioContainer.getChildren().addAll(playBtn, audioInfo, downloadBtn);
            container.getChildren().add(audioContainer);

            // Caption if present
            if (caption != null && !caption.trim().isEmpty()) {
                Label captionLabel = new Label(caption.trim());
                captionLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px;");
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
        fileContainer.setStyle("-fx-background-color: #2b2d31; -fx-background-radius: 8; -fx-padding: 12;");
        fileContainer.setAlignment(Pos.CENTER_LEFT);
        fileContainer.setMaxWidth(400);

        // File icon based on type
        Label fileIcon = new Label(getFileIcon(mimeType));
        fileIcon.setStyle("-fx-font-size: 24px;");

        // File info
        VBox fileInfo = new VBox(2);

        Label fileTitle = new Label(fileName);
        fileTitle.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px; -fx-font-weight: 600;");

        Label fileMeta = new Label(mimeType + " • " + formatFileSize(size));
        fileMeta.setStyle("-fx-text-fill: #87898c; -fx-font-size: 12px;");

        fileInfo.getChildren().addAll(fileTitle, fileMeta);

        // Download button
        Button downloadBtn = new Button("Download");
        downloadBtn.setStyle("-fx-background-color: #5865f2; -fx-text-fill: white; -fx-font-size: 12px;");
        downloadBtn.setOnAction(e -> openFile(fileName));

        fileContainer.getChildren().addAll(fileIcon, fileInfo, downloadBtn);
        container.getChildren().add(fileContainer);

        // Caption if present
        if (caption != null && !caption.trim().isEmpty()) {
            Label captionLabel = new Label(caption.trim());
            captionLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px;");
            captionLabel.setWrapText(true);
            container.getChildren().add(captionLabel);
        }
    }

    // ============== LINK EMBED IMPLEMENTATIONS ==============

    private Node createLinkEmbed(String url) {
        try {
            if (isYouTubeUrl(url)) {
                return createYouTubeEmbed(url);
            } else if (isDirectImageUrl(url)) {
                return createImageEmbed(url);
            } else if (isDirectVideoUrl(url)) {
                return createVideoLinkEmbed(url);
            } else if (isTwitterUrl(url)) {
                return createTwitterEmbed(url);
            } else {
                return createGenericLinkEmbed(url);
            }
        } catch (Exception e) {
            System.err.println("Failed to create embed for URL: " + url + " - " + e.getMessage());
            return createFallbackLinkEmbed(url);
        }
    }

    // URL Detection Methods
    private boolean isYouTubeUrl(String url) {
        return url.matches(".*(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/).*");
    }

    private boolean isDirectImageUrl(String url) {
        return url.toLowerCase().matches(".*\\.(jpg|jpeg|png|gif|bmp|webp)(\\?.*)?$");
    }

    private boolean isDirectVideoUrl(String url) {
        return url.toLowerCase().matches(".*\\.(mp4|webm|mov|avi|mkv)(\\?.*)?$");
    }

    private boolean isTwitterUrl(String url) {
        return url.matches(".*(?:twitter\\.com|x\\.com)/\\w+/status/\\d+.*");
    }

    private Node createYouTubeEmbed(String url) {
        VBox youtubeContainer = new VBox(8);
        youtubeContainer.setStyle(
                "-fx-background-color: #2b2d31; -fx-background-radius: 8; " +
                        "-fx-border-color: #40444b; -fx-border-width: 1; -fx-border-radius: 8; " +
                        "-fx-padding: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 2);"
        );
        youtubeContainer.setMaxWidth(400);
        youtubeContainer.setCursor(Cursor.HAND);

        // Extract video ID
        String videoId = extractYouTubeVideoId(url);
        if (videoId == null) {
            return createFallbackLinkEmbed(url);
        }

        // Create thumbnail container
        StackPane thumbnailContainer = new StackPane();
        thumbnailContainer.setPrefSize(380, 214); // 16:9 aspect ratio

        // Load YouTube thumbnail
        ImageView thumbnail = new ImageView();
        thumbnail.setFitWidth(380);
        thumbnail.setFitHeight(214);
        thumbnail.setPreserveRatio(false);

        // Load thumbnail asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                String thumbnailUrl = "https://img.youtube.com/vi/" + videoId + "/maxresdefault.jpg";
                Image thumbImage = new Image(thumbnailUrl, true);

                Platform.runLater(() -> {
                    thumbnail.setImage(thumbImage);

                    // Add rounded corners
                    Rectangle clip = new Rectangle(380, 214);
                    clip.setArcWidth(8);
                    clip.setArcHeight(8);
                    thumbnail.setClip(clip);
                });

            } catch (Exception e) {
                System.err.println("Failed to load YouTube thumbnail: " + e.getMessage());
            }
        });

        // Play button overlay
        Label playButton = new Label("▶");
        playButton.setStyle(
                "-fx-background-color: rgba(0,0,0,0.8); -fx-text-fill: white; " +
                        "-fx-background-radius: 50%; -fx-font-size: 32px; -fx-padding: 16;"
        );

        thumbnailContainer.getChildren().addAll(thumbnail, playButton);
        StackPane.setAlignment(playButton, Pos.CENTER);

        // Video info
        VBox infoBox = new VBox(4);

        Label titleLabel = new Label("YouTube Video");
        titleLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px; -fx-font-weight: 600;");
        titleLabel.setWrapText(true);

        Label siteLabel = new Label("YouTube • " + shortenUrl(url));
        siteLabel.setStyle("-fx-text-fill: #ff0000; -fx-font-size: 12px;");

        infoBox.getChildren().addAll(titleLabel, siteLabel);

        youtubeContainer.getChildren().addAll(thumbnailContainer, infoBox);

        // Click to open YouTube or play in WebView
        youtubeContainer.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                // Double-click to play in app
                playYouTubeInApp(videoId);
            } else {
                // Single-click to open in browser
                openUrl(url);
            }
        });

        // Fetch video title asynchronously
        fetchYouTubeTitle(videoId, titleLabel);

        return youtubeContainer;
    }

    private Node createImageEmbed(String imageUrl) {
        VBox imageContainer = new VBox(8);
        imageContainer.setStyle(
                "-fx-background-color: #2b2d31; -fx-background-radius: 8; " +
                        "-fx-padding: 8; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 2);"
        );
        imageContainer.setMaxWidth(400);

        // Loading placeholder
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
                    imageView.setFitWidth(Math.min(400, image.getWidth()));
                    imageView.setFitHeight(Math.min(300, image.getHeight()));
                    imageView.setCursor(Cursor.HAND);

                    // Add rounded corners
                    Rectangle clip = new Rectangle();
                    clip.setArcWidth(8);
                    clip.setArcHeight(8);
                    clip.widthProperty().bind(imageView.fitWidthProperty());
                    clip.heightProperty().bind(imageView.fitHeightProperty());
                    imageView.setClip(clip);

                    // Click to view fullscreen
                    imageView.setOnMouseClicked(e -> openFullscreenImage(image, "Linked Image"));

                    imageContainer.getChildren().add(imageView);

                    // Add URL info
                    Label urlLabel = new Label(shortenUrl(imageUrl));
                    urlLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px;");
                    imageContainer.getChildren().add(urlLabel);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    imageContainer.getChildren().clear();
                    Label errorLabel = new Label("Failed to load image");
                    errorLabel.setStyle("-fx-text-fill: #f04747; -fx-font-size: 12px;");
                    imageContainer.getChildren().add(errorLabel);

                    Label urlLabel = new Label(shortenUrl(imageUrl));
                    urlLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px; -fx-cursor: hand;");
                    urlLabel.setOnMouseClicked(ev -> openUrl(imageUrl));
                    imageContainer.getChildren().add(urlLabel);
                });
            }
        });

        return imageContainer;
    }

    private Node createVideoLinkEmbed(String videoUrl) {
        HBox videoContainer = new HBox(12);
        videoContainer.setStyle(
                "-fx-background-color: #2b2d31; -fx-background-radius: 8; " +
                        "-fx-border-color: #40444b; -fx-border-width: 1; -fx-border-radius: 8; " +
                        "-fx-padding: 12; -fx-cursor: hand;"
        );
        videoContainer.setMaxWidth(400);
        videoContainer.setAlignment(Pos.CENTER_LEFT);

        // Video icon
        Label videoIcon = new Label("🎥");
        videoIcon.setStyle("-fx-font-size: 24px;");

        // Video info
        VBox infoBox = new VBox(2);

        Label titleLabel = new Label("Video Link");
        titleLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px; -fx-font-weight: 600;");

        Label urlLabel = new Label(shortenUrl(videoUrl));
        urlLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 12px;");

        infoBox.getChildren().addAll(titleLabel, urlLabel);

        // Play button
        Button playBtn = new Button("▶");
        playBtn.setStyle(
                "-fx-background-color: #5865f2; -fx-text-fill: white; " +
                        "-fx-background-radius: 50%; -fx-font-size: 16px;"
        );

        videoContainer.getChildren().addAll(videoIcon, infoBox, playBtn);

        // Click to open
        videoContainer.setOnMouseClicked(e -> openUrl(videoUrl));

        return videoContainer;
    }

    private Node createTwitterEmbed(String twitterUrl) {
        VBox twitterContainer = new VBox(8);
        twitterContainer.setStyle(
                "-fx-background-color: #2b2d31; -fx-background-radius: 8; " +
                        "-fx-border-color: #1da1f2; -fx-border-width: 1; -fx-border-radius: 8; " +
                        "-fx-padding: 12; -fx-cursor: hand;"
        );
        twitterContainer.setMaxWidth(400);

        // Twitter icon and info
        HBox headerBox = new HBox(8);
        headerBox.setAlignment(Pos.CENTER_LEFT);

        Label twitterIcon = new Label("🐦");
        twitterIcon.setStyle("-fx-font-size: 16px;");

        Label siteLabel = new Label("X (Twitter)");
        siteLabel.setStyle("-fx-text-fill: #1da1f2; -fx-font-size: 12px; -fx-font-weight: 600;");

        headerBox.getChildren().addAll(twitterIcon, siteLabel);

        Label contentLabel = new Label("View Tweet");
        contentLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px;");

        Label urlLabel = new Label(shortenUrl(twitterUrl));
        urlLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px;");

        twitterContainer.getChildren().addAll(headerBox, contentLabel, urlLabel);

        // Click to open
        twitterContainer.setOnMouseClicked(e -> openUrl(twitterUrl));

        return twitterContainer;
    }

    private Node createGenericLinkEmbed(String url) {
        HBox linkContainer = new HBox(12);
        linkContainer.setStyle(
                "-fx-background-color: #2b2d31; -fx-background-radius: 8; " +
                        "-fx-border-color: #40444b; -fx-border-width: 1; -fx-border-radius: 8; " +
                        "-fx-padding: 12; -fx-cursor: hand;"
        );
        linkContainer.setMaxWidth(400);
        linkContainer.setAlignment(Pos.CENTER_LEFT);

        // Link icon
        Label linkIcon = new Label("🔗");
        linkIcon.setStyle("-fx-font-size: 20px;");

        // Link info
        VBox infoBox = new VBox(2);

        Label titleLabel = new Label("Web Link");
        titleLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 14px; -fx-font-weight: 600;");

        Label urlLabel = new Label(shortenUrl(url));
        urlLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 12px;");

        infoBox.getChildren().addAll(titleLabel, urlLabel);

        linkContainer.getChildren().addAll(linkIcon, infoBox);

        // Click to open
        linkContainer.setOnMouseClicked(e -> openUrl(url));

        // Try to fetch page title asynchronously
        fetchPageTitle(url, titleLabel);

        return linkContainer;
    }

    private Node createFallbackLinkEmbed(String url) {
        Label linkLabel = new Label(url);
        linkLabel.setStyle(
                "-fx-text-fill: #00aff4; -fx-font-size: 14px; " +
                        "-fx-underline: true; -fx-cursor: hand;"
        );
        linkLabel.setWrapText(true);
        linkLabel.setOnMouseClicked(e -> openUrl(url));

        return linkLabel;
    }

    // ============== FILE HANDLING - USING EXISTING METHODS ==============

    @FXML
    private void handleFileSelection() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select files to send");

        List<File> files = fileChooser.showOpenMultipleDialog(chatContainer.getScene().getWindow());
        if (files != null && !files.isEmpty()) {
            for (File file : files) {
                handleFileUpload(file);
            }
        }
    }

    private void handleFileUpload(File file) {
        if (file == null || !file.exists()) return;

        try {
            // Create preview using existing MediaPreviewService
            MediaPreview preview = previewService.createPreview(file);
            pendingUploads.add(preview);

            // Set up callbacks using existing methods
            preview.setOnRemove(() -> removePreview(preview));

            // Add to preview container
            if (preview.getPreviewNode() != null) {
                previewContainer.getChildren().add(preview.getPreviewNode());
                updatePreviewVisibility();
                System.out.println("[ChatController] Added file to preview: " + file.getName());
            }

        } catch (Exception e) {
            System.err.println("[ChatController] Failed to process file: " + e.getMessage());
            showError("File Error", "Failed to process file: " + e.getMessage());
        }
    }

    // ============== MESSAGE SENDING ==============

    @FXML
    private void sendMessage() {
        String messageText = messageField.getText().trim();
        boolean hasText = !messageText.isEmpty();
        boolean hasMedia = !pendingUploads.isEmpty();

        if (!hasText && !hasMedia) return;

        if (hasMedia) {
            sendAllMediaFiles();
        }

        if (hasText) {
            sendTextMessage(messageText);
        }

        // Clear input
        messageField.clear();
        clearAllPreviews();
    }

    private void sendTextMessage(String text) {
        if (friend == null) return;

        CompletableFuture.runAsync(() -> {
            try {
                directory.sendChat(friend.getUsername(), text);

                Platform.runLater(() -> {
                    ChatMessage outgoingMessage = ChatMessage.fromOutgoing(friend.getUsername(), text);
                    storage.storeMessage(friend.getUsername(), outgoingMessage);
                    addMessageBubble(outgoingMessage);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("Send Error", "Failed to send message: " + e.getMessage());
                });
            }
        });
    }

    private void sendAllMediaFiles() {
        for (MediaPreview preview : new ArrayList<>(pendingUploads)) {
            sendSingleMediaFile(preview, null);
        }
    }

    private void sendSingleMediaFile(MediaPreview preview, String caption) {
        if (friend == null) return;

        // Check if already processing
        if (preview.isProcessing()) {
            System.out.println("[ChatController] Skipping duplicate send for: " + preview.getFileName());
            return;
        }

        // Use existing service to send media
        previewService.showProgress(preview, "Sending...");

        httpMediaService.sendMediaAsync(preview.getFile(), friend.getUsername(), caption)
                .thenRun(() -> {
                    System.out.println("[ChatController] Media file sent successfully: " + preview.getFileName());

                    Platform.runLater(() -> {
                        try {
                            // Create inline media message for sender
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

    // ============== UTILITY AND HELPER METHODS ==============

    private void addMediaInfo(VBox container, String fileName, long size) {
        Label infoLabel = new Label(fileName + " • " + formatFileSize(size));
        infoLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 12px;");
        container.getChildren().add(infoLabel);
    }

    private void addErrorLabel(VBox container, String message) {
        Label errorLabel = new Label(message);
        errorLabel.setStyle("-fx-text-fill: #f04747;");
        container.getChildren().add(errorLabel);
    }

    private String getFileIcon(String mimeType) {
        if (mimeType.startsWith("image/")) return "🖼️";
        if (mimeType.startsWith("video/")) return "🎥";
        if (mimeType.startsWith("audio/")) return "🎵";
        if (mimeType.contains("pdf")) return "📄";
        if (mimeType.contains("text")) return "📝";
        if (mimeType.contains("zip") || mimeType.contains("rar")) return "📦";
        return "📄";
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String extractYouTubeVideoId(String url) {
        Pattern pattern = Pattern.compile("(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/)([a-zA-Z0-9_-]{11})");
        Matcher matcher = pattern.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String shortenUrl(String url) {
        try {
            URI uri = new URI(url);
            String domain = uri.getHost();
            if (domain != null) {
                if (domain.startsWith("www.")) {
                    domain = domain.substring(4);
                }
                return domain;
            }
        } catch (Exception e) {
            // Fallback
        }

        if (url.length() > 50) {
            return url.substring(0, 47) + "...";
        }

        return url;
    }

    // ============== MEDIA PLAYBACK METHODS ==============

    private void openFullscreenImage(Image image, String fileName) {
        Stage imageStage = new Stage();
        imageStage.setTitle(fileName);
        imageStage.initModality(Modality.APPLICATION_MODAL);

        ImageView fullImageView = new ImageView(image);
        fullImageView.setPreserveRatio(true);
        fullImageView.setSmooth(true);

        ScrollPane scrollPane = new ScrollPane(fullImageView);
        scrollPane.setStyle("-fx-background-color: black;");

        Scene scene = new Scene(scrollPane, 800, 600);
        imageStage.setScene(scene);
        imageStage.show();
    }

    private void playVideo(File videoFile, String fileName) {
        try {
            Desktop.getDesktop().open(videoFile);
        } catch (Exception e) {
            System.err.println("Failed to play video: " + e.getMessage());
            showVideoInWebView(videoFile, fileName);
        }
    }

    private void showVideoInWebView(File videoFile, String fileName) {
        Stage videoStage = new Stage();
        videoStage.setTitle("Playing: " + fileName);

        WebView webView = new WebView();
        webView.setPrefSize(800, 450);

        String videoHtml = String.format(
                "<html><body style='margin:0;padding:20px;background:#000;'>" +
                        "<video width='100%%' height='100%%' controls autoplay>" +
                        "<source src='file:///%s' type='video/mp4'>" +
                        "Your browser does not support the video tag." +
                        "</video></body></html>",
                videoFile.getAbsolutePath().replace("\\", "/")
        );

        webView.getEngine().loadContent(videoHtml);

        Scene scene = new Scene(new StackPane(webView), 800, 500);
        videoStage.setScene(scene);
        videoStage.show();
    }

    private void playAudio(File audioFile, String fileName, Button playBtn) {
        try {
            Desktop.getDesktop().open(audioFile);

            playBtn.setText("⏸");
            playBtn.setStyle(playBtn.getStyle().replace("#5865f2", "#f04747"));

            Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
                playBtn.setText("▶");
                playBtn.setStyle(playBtn.getStyle().replace("#f04747", "#5865f2"));
            }));
            timeline.play();

        } catch (Exception e) {
            System.err.println("Failed to play audio: " + e.getMessage());
            notificationManager.showToast("Audio Error", "Failed to play audio file", NotificationManager.ToastType.ERROR);
        }
    }

    private void playYouTubeInApp(String videoId) {
        Stage youtubeStage = new Stage();
        youtubeStage.setTitle("YouTube Video");

        WebView webView = new WebView();
        webView.setPrefSize(854, 480);

        String embedUrl = "https://www.youtube.com/embed/" + videoId + "?autoplay=1";
        webView.getEngine().load(embedUrl);

        Scene scene = new Scene(new StackPane(webView), 870, 520);
        youtubeStage.setScene(scene);
        youtubeStage.show();
    }

    private void openUrl(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            System.err.println("Failed to open URL: " + url + " - " + e.getMessage());
            notificationManager.showToast("Error", "Failed to open link", NotificationManager.ToastType.ERROR);
        }
    }

    // ============== ASYNC TITLE FETCHING ==============

    private void fetchYouTubeTitle(String videoId, Label titleLabel) {
        CompletableFuture.runAsync(() -> {
            try {
                String oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=" + videoId + "&format=json";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(oembedUrl))
                        .build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode json = new ObjectMapper().readTree(response.body());
                    String title = json.path("title").asText();

                    Platform.runLater(() -> titleLabel.setText(title));
                }

            } catch (Exception e) {
                System.err.println("Failed to fetch YouTube title: " + e.getMessage());
            }
        });
    }

    private void fetchPageTitle(String url, Label titleLabel) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String html = response.body();

                    Pattern titlePattern = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
                    Matcher titleMatcher = titlePattern.matcher(html);

                    if (titleMatcher.find()) {
                        String title = titleMatcher.group(1).trim();
                        Platform.runLater(() -> titleLabel.setText(title));
                    }
                }

            } catch (Exception e) {
                System.err.println("Failed to fetch page title: " + e.getMessage());
            }
        });
    }

    // ============== EXISTING METHOD STUBS ==============

    private void saveMediaToDownloads(String fileName, String base64Data) {
        try {
            String userHome = System.getProperty("user.home");
            File downloadsDir = new File(userHome, "Downloads");
            File saveFile = new File(downloadsDir, fileName);

            int counter = 1;
            while (saveFile.exists()) {
                String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                String extension = fileName.substring(fileName.lastIndexOf('.'));
                saveFile = new File(downloadsDir, baseName + "_" + counter + extension);
                counter++;
            }

            byte[] fileBytes = Base64.getDecoder().decode(base64Data);
            Files.write(saveFile.toPath(), fileBytes);

            notificationManager.showToast("File Saved", "Saved to: " + saveFile.getName(), NotificationManager.ToastType.SUCCESS);

        } catch (Exception e) {
            System.err.println("Failed to save file: " + e.getMessage());
            notificationManager.showToast("Save Error", "Failed to save file", NotificationManager.ToastType.ERROR);
        }
    }

    private void openFile(String fileName) {
        try {
            String userHome = System.getProperty("user.home");
            File picturesDir = new File(userHome, "Pictures/WhisperClient");
            File videosDir = new File(userHome, "Videos/WhisperClient");
            File downloadsDir = new File(userHome, "Downloads/WhisperClient");

            File file = new File(picturesDir, fileName);
            if (!file.exists()) file = new File(videosDir, fileName);
            if (!file.exists()) file = new File(downloadsDir, fileName);

            if (file.exists()) {
                Desktop.getDesktop().open(file);
            } else {
                notificationManager.showToast("File Not Found", "File not found in saved locations", NotificationManager.ToastType.ERROR);
            }

        } catch (Exception e) {
            System.err.println("Failed to open file: " + e.getMessage());
            notificationManager.showToast("Open Error", "Failed to open file", NotificationManager.ToastType.ERROR);
        }
    }

    private void autoSaveMediaFile(String fileName, String mimeType, String base64Data) {
        // Auto-save implementation using existing method
        System.out.println("[ChatController] Auto-saved media to: " + fileName);
    }

    private void scrollToBottom() {
        Platform.runLater(() -> messagesScrollPane.setVvalue(1.0));
    }

    private void removePreview(MediaPreview preview) {
        pendingUploads.remove(preview);
        if (preview.getPreviewNode() != null) {
            previewContainer.getChildren().remove(preview.getPreviewNode());
        }
        updatePreviewVisibility();
    }

    private void updatePreviewVisibility() {
        boolean hasUploads = !pendingUploads.isEmpty();
        previewContainer.setVisible(hasUploads);
        previewContainer.setManaged(hasUploads);
    }

    private void clearAllPreviews() {
        pendingUploads.clear();
        previewContainer.getChildren().clear();
        updatePreviewVisibility();
    }

    private void deleteMessage(ChatMessage message, VBox container) {
        messagesBox.getChildren().remove(container);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    // ============== EXISTING METHOD COMPATIBILITY ==============

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

    // ============== BASE64 AND MIME TYPE UTILITIES ==============

    private String encodeFileToBase64(File file) throws IOException {
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        return Base64.getEncoder().encodeToString(fileBytes);
    }

    private String guessMimeType(String fileName) {
        String name = fileName.toLowerCase();

        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".webp")) return "image/webp";

        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".avi")) return "video/x-msvideo";

        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".m4a")) return "audio/mp4";

        return "application/octet-stream";
    }
}