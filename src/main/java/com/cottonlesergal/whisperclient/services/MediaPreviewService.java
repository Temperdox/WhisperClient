package com.cottonlesergal.whisperclient.services;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MediaPreviewService {
    private static final MediaPreviewService INSTANCE = new MediaPreviewService();

    // Supported file types
    private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp");
    private static final List<String> VIDEO_EXTENSIONS = List.of(".mp4", ".webm", ".mov", ".avi", ".mkv");
    private static final List<String> AUDIO_EXTENSIONS = List.of(".mp3", ".wav", ".ogg", ".m4a", ".flac");

    public static MediaPreviewService getInstance() {
        return INSTANCE;
    }

    private MediaPreviewService() {}

    /**
     * Media preview item for pending uploads
     */
    public static class MediaPreview {
        private File file;
        private Image image;
        private String fileName;
        private long fileSize;
        private String mediaType;
        private VBox previewComponent;
        private ProgressBar progressBar;
        private Label statusLabel;
        private Runnable onRemove;
        private Runnable onSend;
        private boolean isProcessing = false;
        private String mimeType;

        public MediaPreview(File file, String mediaType) {
            this.file = file;
            this.fileName = file.getName();
            this.fileSize = file.length();
            this.mediaType = mediaType;
            this.mimeType = determineMimeType(file);
        }

        public MediaPreview(Image image, String fileName) {
            this.image = image;
            this.fileName = fileName;
            this.mediaType = "image";
            this.mimeType = "image/png"; // Default for pasted images
            this.fileSize = 0; // Will be calculated when saved
        }

        // Getters
        public File getFile() { return file; }
        public Image getImage() { return image; }
        public String getFileName() { return fileName; }
        public long getFileSize() { return fileSize; }
        public String getMediaType() { return mediaType; }
        public String getMimeType() { return mimeType; }
        public VBox getPreviewComponent() { return previewComponent; }
        public Node getPreviewNode() { return previewComponent; }
        public boolean isProcessing() { return isProcessing; }

        // Setters
        public void setOnRemove(Runnable onRemove) { this.onRemove = onRemove; }
        public void setOnSend(Runnable onSend) { this.onSend = onSend; }
        public void setFile(File file) {
            this.file = file;
            this.fileSize = file.length();
        }
        public void setProcessing(boolean processing) { this.isProcessing = processing; }

        public void updateProgress(double progress, String status) {
            Platform.runLater(() -> {
                if (progressBar != null) {
                    progressBar.setProgress(progress);
                }
                if (statusLabel != null) {
                    statusLabel.setText(status);
                }
            });
        }

        private String determineMimeType(File file) {
            String fileName = file.getName().toLowerCase();

            // Image types
            if (fileName.endsWith(".png")) return "image/png";
            if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
            if (fileName.endsWith(".gif")) return "image/gif";
            if (fileName.endsWith(".bmp")) return "image/bmp";
            if (fileName.endsWith(".webp")) return "image/webp";

            // Video types
            if (fileName.endsWith(".mp4")) return "video/mp4";
            if (fileName.endsWith(".webm")) return "video/webm";
            if (fileName.endsWith(".mov")) return "video/quicktime";
            if (fileName.endsWith(".avi")) return "video/x-msvideo";

            // Audio types
            if (fileName.endsWith(".mp3")) return "audio/mpeg";
            if (fileName.endsWith(".wav")) return "audio/wav";
            if (fileName.endsWith(".ogg")) return "audio/ogg";
            if (fileName.endsWith(".m4a")) return "audio/mp4";

            // Try to detect from file content
            try {
                String contentType = Files.probeContentType(file.toPath());
                if (contentType != null) return contentType;
            } catch (IOException e) {
                System.err.println("Failed to detect MIME type for " + fileName + ": " + e.getMessage());
            }

            return "application/octet-stream"; // Default fallback
        }
    }

    /**
     * Create a preview for any file type
     */
    public MediaPreview createPreview(File file) {
        String mediaType = determineMediaType(file);
        MediaPreview preview = new MediaPreview(file, mediaType);

        // Create appropriate preview component
        if ("image".equals(mediaType)) {
            createImagePreview(preview);
        } else {
            createFilePreview(preview);
        }

        return preview;
    }

    /**
     * Create a preview for an image from clipboard
     */
    public MediaPreview createPreview(Image image, String fileName) {
        MediaPreview preview = new MediaPreview(image, fileName);
        createImagePreview(preview);
        return preview;
    }

    /**
     * Determine media type from file extension
     */
    private String determineMediaType(File file) {
        String fileName = file.getName().toLowerCase();

        for (String ext : IMAGE_EXTENSIONS) {
            if (fileName.endsWith(ext)) return "image";
        }

        for (String ext : VIDEO_EXTENSIONS) {
            if (fileName.endsWith(ext)) return "video";
        }

        for (String ext : AUDIO_EXTENSIONS) {
            if (fileName.endsWith(ext)) return "audio";
        }

        return "file"; // Generic file type
    }

    /**
     * Create image preview component
     */
    private void createImagePreview(MediaPreview preview) {
        VBox container = new VBox(8);
        container.getStyleClass().add("media-preview");
        container.setPadding(new Insets(12));
        container.setMaxWidth(320);
        container.setStyle("-fx-background-color: #2b2d31; -fx-background-radius: 8; -fx-border-color: #40444b; -fx-border-width: 1; -fx-border-radius: 8;");

        // Image thumbnail
        ImageView thumbnail = new ImageView();
        try {
            if (preview.getImage() != null) {
                thumbnail.setImage(preview.getImage());
            } else if (preview.getFile() != null && preview.getFile().exists()) {
                Image image = new Image(preview.getFile().toURI().toString());
                thumbnail.setImage(image);
            } else {
                // Fallback for missing files
                Label missingLabel = new Label("❌ Image not found");
                missingLabel.setStyle("-fx-text-fill: #f04747; -fx-font-size: 14px;");
                container.getChildren().add(missingLabel);
                preview.previewComponent = container;
                return;
            }
        } catch (Exception e) {
            System.err.println("[MediaPreviewService] Failed to load image preview: " + e.getMessage());
            Label errorLabel = new Label("❌ Failed to load image");
            errorLabel.setStyle("-fx-text-fill: #f04747; -fx-font-size: 14px;");
            container.getChildren().add(errorLabel);
            preview.previewComponent = container;
            return;
        }

        thumbnail.setFitWidth(300);
        thumbnail.setFitHeight(200);
        thumbnail.setPreserveRatio(true);
        thumbnail.setSmooth(true);

        // Rounded corners
        Rectangle clip = new Rectangle();
        clip.setArcWidth(8);
        clip.setArcHeight(8);
        clip.widthProperty().bind(thumbnail.fitWidthProperty());
        clip.heightProperty().bind(thumbnail.fitHeightProperty());
        thumbnail.setClip(clip);

        // File info
        HBox fileInfo = new HBox(8);
        fileInfo.setAlignment(Pos.CENTER_LEFT);

        Label fileName = new Label(preview.getFileName());
        fileName.getStyleClass().add("preview-filename");
        fileName.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px; -fx-font-weight: 600;");

        Label fileSize = new Label(formatFileSize(preview.getFileSize()));
        fileSize.getStyleClass().add("preview-filesize");
        fileSize.setStyle("-fx-text-fill: #b5bac1; -fx-font-size: 11px;");

        fileInfo.getChildren().addAll(fileName, fileSize);

        // Progress bar for upload status
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        progressBar.getStyleClass().add("upload-progress");
        progressBar.setVisible(false);
        progressBar.setStyle("-fx-accent: #5865f2;");
        preview.progressBar = progressBar;

        // Status label
        Label statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px;");
        statusLabel.setVisible(false);
        preview.statusLabel = statusLabel;

        // Action buttons
        HBox actions = createActionButtons(preview);

        container.getChildren().addAll(thumbnail, fileInfo, progressBar, statusLabel, actions);
        preview.previewComponent = container;
    }

    /**
     * Create file preview component (for non-images)
     */
    private void createFilePreview(MediaPreview preview) {
        VBox container = new VBox(8);
        container.getStyleClass().add("media-preview");
        container.setPadding(new Insets(12));
        container.setMaxWidth(320);
        container.setStyle("-fx-background-color: #2b2d31; -fx-background-radius: 8; -fx-border-color: #40444b; -fx-border-width: 1; -fx-border-radius: 8;");

        // File icon and info
        HBox fileDisplay = new HBox(12);
        fileDisplay.setAlignment(Pos.CENTER_LEFT);

        Label fileIcon = new Label();
        fileIcon.setStyle("-fx-font-size: 32px;");

        switch (preview.getMediaType()) {
            case "video":
                fileIcon.setText("🎥");
                break;
            case "audio":
                fileIcon.setText("🎵");
                break;
            default:
                fileIcon.setText("📎");
                break;
        }

        VBox fileInfo = new VBox(2);

        Label fileName = new Label(preview.getFileName());
        fileName.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px; -fx-font-weight: 600;");
        fileName.setWrapText(true);
        fileName.setMaxWidth(200);

        Label fileSize = new Label(formatFileSize(preview.getFileSize()));
        fileSize.setStyle("-fx-text-fill: #b5bac1; -fx-font-size: 11px;");

        Label mimeType = new Label(preview.getMimeType());
        mimeType.setStyle("-fx-text-fill: #72767d; -fx-font-size: 10px;");

        fileInfo.getChildren().addAll(fileName, fileSize, mimeType);
        fileDisplay.getChildren().addAll(fileIcon, fileInfo);

        // Progress bar
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        progressBar.setVisible(false);
        progressBar.setStyle("-fx-accent: #5865f2;");
        preview.progressBar = progressBar;

        // Status label
        Label statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px;");
        statusLabel.setVisible(false);
        preview.statusLabel = statusLabel;

        // Action buttons
        HBox actions = createActionButtons(preview);

        container.getChildren().addAll(fileDisplay, progressBar, statusLabel, actions);
        preview.previewComponent = container;
    }

    /**
     * Create action buttons for preview
     */
    private HBox createActionButtons(MediaPreview preview) {
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);

        Button removeButton = new Button("Remove");
        removeButton.getStyleClass().add("preview-remove-button");
        removeButton.setStyle("-fx-background-color: #f04747; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 4 8; -fx-font-size: 11px;");
        removeButton.setOnMouseEntered(e -> removeButton.setStyle("-fx-background-color: #d73c3c; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 4 8; -fx-font-size: 11px;"));
        removeButton.setOnMouseExited(e -> removeButton.setStyle("-fx-background-color: #f04747; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 4 8; -fx-font-size: 11px;"));
        removeButton.setOnAction(e -> {
            if (preview.onRemove != null && !preview.isProcessing()) {
                preview.onRemove.run();
            }
        });

        // Disable the individual send button - use Enter/main Send button instead
        Button sendButton = new Button("Ready to Send");
        sendButton.getStyleClass().add("preview-send-button");
        sendButton.setStyle("-fx-background-color: #57f287; -fx-text-fill: black; -fx-background-radius: 4; -fx-padding: 4 8; -fx-font-size: 11px;");
        sendButton.setDisable(true); // Disabled - use Enter key to send
        sendButton.setTooltip(new Tooltip("Press Enter to send this file"));

        actions.getChildren().addAll(removeButton, sendButton);
        return actions;
    }

    /**
     * Show upload progress on preview
     */
    public void showProgress(MediaPreview preview, String status) {
        preview.setProcessing(true);
        Platform.runLater(() -> {
            if (preview.progressBar != null) {
                preview.progressBar.setVisible(true);
                preview.progressBar.setProgress(-1); // Indeterminate
            }
            if (preview.statusLabel != null) {
                preview.statusLabel.setVisible(true);
                preview.statusLabel.setText(status);
            }

            // Disable action buttons during processing
            disableActionButtons(preview, true);
        });
    }

    /**
     * Update upload progress with specific progress value
     */
    public void updateProgress(MediaPreview preview, String status) {
        Platform.runLater(() -> {
            if (preview.statusLabel != null) {
                preview.statusLabel.setText(status);
            }
        });
    }

    /**
     * Update upload progress with progress value and status
     */
    public void updateProgress(MediaPreview preview, double progress, String status) {
        Platform.runLater(() -> {
            if (preview.progressBar != null) {
                preview.progressBar.setProgress(Math.max(0, Math.min(1, progress)));
            }
            if (preview.statusLabel != null) {
                preview.statusLabel.setText(status);
            }
        });
    }

    /**
     * Hide progress indicators
     */
    public void hideProgress(MediaPreview preview) {
        preview.setProcessing(false);
        Platform.runLater(() -> {
            if (preview.progressBar != null) {
                preview.progressBar.setVisible(false);
            }
            if (preview.statusLabel != null) {
                preview.statusLabel.setVisible(false);
            }

            // Re-enable action buttons
            disableActionButtons(preview, false);
        });
    }

    /**
     * Disable/enable action buttons
     */
    private void disableActionButtons(MediaPreview preview, boolean disable) {
        if (preview.previewComponent != null) {
            preview.previewComponent.getChildren().stream()
                    .filter(node -> node instanceof HBox)
                    .map(node -> (HBox) node)
                    .flatMap(hbox -> hbox.getChildren().stream())
                    .filter(node -> node instanceof Button)
                    .map(node -> (Button) node)
                    .forEach(button -> {
                        button.setDisable(disable);
                        if (disable) {
                            button.setOpacity(0.5);
                        } else {
                            button.setOpacity(1.0);
                        }
                    });
        }
    }

    /**
     * Get list of supported file extensions
     */
    public List<String> getSupportedExtensions() {
        List<String> allExtensions = new ArrayList<>();
        allExtensions.addAll(IMAGE_EXTENSIONS);
        allExtensions.addAll(VIDEO_EXTENSIONS);
        allExtensions.addAll(AUDIO_EXTENSIONS);
        return allExtensions;
    }

    /**
     * Check if file type is supported
     */
    public boolean isSupportedFile(File file) {
        String fileName = file.getName().toLowerCase();
        return getSupportedExtensions().stream()
                .anyMatch(fileName::endsWith);
    }

    /**
     * Get maximum allowed file size (25MB)
     */
    public long getMaxFileSize() {
        return 25L * 1024 * 1024; // 25MB
    }

    /**
     * Check if file size is within limits
     */
    public boolean isFileSizeValid(File file) {
        return file.length() <= getMaxFileSize();
    }

    /**
     * Format file size for display
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Validate file for upload
     */
    public ValidationResult validateFile(File file) {
        if (!file.exists()) {
            return new ValidationResult(false, "File does not exist");
        }

        if (!isSupportedFile(file)) {
            return new ValidationResult(false, "File type not supported");
        }

        if (!isFileSizeValid(file)) {
            return new ValidationResult(false, "File too large (max " + formatFileSize(getMaxFileSize()) + ")");
        }

        return new ValidationResult(true, null);
    }

    /**
     * Validation result class
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        public ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public boolean isValid() { return valid; }
        public String getErrorMessage() { return errorMessage; }
    }

    /**
     * Clean up temporary files
     */
    public void cleanupTempFile(MediaPreview preview) {
        if (preview.getFile() != null &&
                preview.getFile().getName().startsWith("pasted_image_") &&
                preview.getFile().getParent().equals(System.getProperty("java.io.tmpdir"))) {
            try {
                Files.deleteIfExists(preview.getFile().toPath());
                System.out.println("[MediaPreviewService] Cleaned up temp file: " + preview.getFile().getName());
            } catch (Exception e) {
                System.err.println("[MediaPreviewService] Failed to cleanup temp file: " + e.getMessage());
            }
        }
    }
}