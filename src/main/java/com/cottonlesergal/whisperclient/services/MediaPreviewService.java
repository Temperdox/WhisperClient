package com.cottonlesergal.whisperclient.services;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MediaPreviewService {
    private static final MediaPreviewService INSTANCE = new MediaPreviewService();

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

        public MediaPreview(File file, String mediaType) {
            this.file = file;
            this.fileName = file.getName();
            this.fileSize = file.length();
            this.mediaType = mediaType;
        }

        public MediaPreview(Image image, String fileName) {
            this.image = image;
            this.fileName = fileName;
            this.mediaType = "image";
        }

        // Getters
        public File getFile() { return file; }
        public Image getImage() { return image; }
        public String getFileName() { return fileName; }
        public long getFileSize() { return fileSize; }
        public String getMediaType() { return mediaType; }
        public VBox getPreviewComponent() { return previewComponent; }

        public void setOnRemove(Runnable onRemove) { this.onRemove = onRemove; }
        public void setOnSend(Runnable onSend) { this.onSend = onSend; }
        public void setFile(File file) { this.file = file; }

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
    }

    /**
     * Create image preview component
     */
    public VBox createImagePreview(MediaPreview preview) {
        VBox container = new VBox(8);
        container.getStyleClass().add("media-preview");
        container.setPadding(new Insets(12));
        container.setMaxWidth(300);

        // Image thumbnail
        ImageView thumbnail = new ImageView();
        if (preview.getImage() != null) {
            thumbnail.setImage(preview.getImage());
        } else if (preview.getFile() != null) {
            // Load image from file
            try {
                Image image = new Image(preview.getFile().toURI().toString());
                thumbnail.setImage(image);
            } catch (Exception e) {
                System.err.println("Failed to load image preview: " + e.getMessage());
            }
        }

        thumbnail.setFitWidth(280);
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

        if (preview.getFile() != null) {
            Label fileSize = new Label(EnhancedMediaService.getInstance().formatFileSize(preview.getFileSize()));
            fileSize.getStyleClass().add("preview-filesize");
            fileSize.setStyle("-fx-text-fill: #b5bac1; -fx-font-size: 11px;");
            fileInfo.getChildren().addAll(fileName, fileSize);
        } else {
            fileInfo.getChildren().add(fileName);
        }

        // Progress bar for upload status
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(280);
        progressBar.getStyleClass().add("upload-progress");
        progressBar.setVisible(false);
        preview.progressBar = progressBar;

        // Status label
        Label statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px;");
        statusLabel.setVisible(false);
        preview.statusLabel = statusLabel;

        // Action buttons
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);

        Button removeButton = new Button("Remove");
        removeButton.getStyleClass().add("preview-remove-button");
        removeButton.setStyle("-fx-background-color: #f04747; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 4 8;");
        removeButton.setOnAction(e -> {
            if (preview.onRemove != null) {
                preview.onRemove.run();
            }
        });

        Button sendButton = new Button("Send");
        sendButton.getStyleClass().add("preview-send-button");
        sendButton.setStyle("-fx-background-color: #5865f2; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 4 8;");
        sendButton.setOnAction(e -> {
            if (preview.onSend != null) {
                preview.onSend.run();
            }
        });

        actions.getChildren().addAll(removeButton, sendButton);

        container.getChildren().addAll(thumbnail, fileInfo, progressBar, statusLabel, actions);
        preview.previewComponent = container;

        return container;
    }

    /**
     * Create file preview component (for non-images)
     */
    public VBox createFilePreview(MediaPreview preview) {
        VBox container = new VBox(8);
        container.getStyleClass().add("media-preview");
        container.setPadding(new Insets(12));
        container.setMaxWidth(300);

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

        Label fileSize = new Label(EnhancedMediaService.getInstance().formatFileSize(preview.getFileSize()));
        fileSize.setStyle("-fx-text-fill: #b5bac1; -fx-font-size: 11px;");

        fileInfo.getChildren().addAll(fileName, fileSize);
        fileDisplay.getChildren().addAll(fileIcon, fileInfo);

        // Progress bar
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(280);
        progressBar.setVisible(false);
        preview.progressBar = progressBar;

        // Status label
        Label statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #87898c; -fx-font-size: 11px;");
        statusLabel.setVisible(false);
        preview.statusLabel = statusLabel;

        // Action buttons
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);

        Button removeButton = new Button("Remove");
        removeButton.setStyle("-fx-background-color: #f04747; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 4 8;");
        removeButton.setOnAction(e -> {
            if (preview.onRemove != null) {
                preview.onRemove.run();
            }
        });

        Button sendButton = new Button("Send");
        sendButton.setStyle("-fx-background-color: #5865f2; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 4 8;");
        sendButton.setOnAction(e -> {
            if (preview.onSend != null) {
                preview.onSend.run();
            }
        });

        actions.getChildren().addAll(removeButton, sendButton);

        container.getChildren().addAll(fileDisplay, progressBar, statusLabel, actions);
        preview.previewComponent = container;

        return container;
    }

    /**
     * Show upload progress on preview
     */
    public void showProgress(MediaPreview preview, String status) {
        Platform.runLater(() -> {
            if (preview.progressBar != null) {
                preview.progressBar.setVisible(true);
                preview.progressBar.setProgress(-1); // Indeterminate
            }
            if (preview.statusLabel != null) {
                preview.statusLabel.setVisible(true);
                preview.statusLabel.setText(status);
            }
        });
    }

    /**
     * Update upload progress
     */
    public void updateProgress(MediaPreview preview, double progress, String status) {
        Platform.runLater(() -> {
            if (preview.progressBar != null) {
                preview.progressBar.setProgress(progress);
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
        Platform.runLater(() -> {
            if (preview.progressBar != null) {
                preview.progressBar.setVisible(false);
            }
            if (preview.statusLabel != null) {
                preview.statusLabel.setVisible(false);
            }
        });
    }
}