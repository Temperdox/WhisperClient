package com.cottonlesergal.whisperclient.ui.components;

import com.cottonlesergal.whisperclient.services.MediaMessageService;
import com.cottonlesergal.whisperclient.ui.AvatarCache;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.web.WebView;

import java.awt.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Base64;

public class EnhancedMessageComponents {

    /**
     * Create an image message component
     */
    public static VBox createImageMessage(MediaMessageService.EnhancedMessage message) {
        VBox container = new VBox(8);
        container.getStyleClass().add("image-message");

        try {
            // Decode base64 image
            byte[] imageBytes = Base64.getDecoder().decode(message.getMediaData());
            Image image = new Image(new ByteArrayInputStream(imageBytes));

            ImageView imageView = new ImageView(image);
            imageView.setFitWidth(400);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);

            // Add rounded corners
            Rectangle clip = new Rectangle();
            clip.setArcWidth(12);
            clip.setArcHeight(12);
            clip.widthProperty().bind(imageView.fitWidthProperty());
            clip.heightProperty().bind(imageView.fitHeightProperty());
            imageView.setClip(clip);

            // Click to open full size
            imageView.setOnMouseClicked(e -> openImageFullScreen(image));
            imageView.getStyleClass().add("clickable-image");

            container.getChildren().add(imageView);

            // Add filename if present
            if (message.getFileName() != null) {
                Label filename = new Label(message.getFileName());
                filename.getStyleClass().add("image-filename");
                container.getChildren().add(filename);
            }

        } catch (Exception e) {
            Label errorLabel = new Label("Failed to load image");
            errorLabel.getStyleClass().add("error-label");
            container.getChildren().add(errorLabel);
        }

        return container;
    }

    /**
     * Create a file message component
     */
    public static HBox createFileMessage(MediaMessageService.EnhancedMessage message) {
        HBox container = new HBox(12);
        container.getStyleClass().add("file-message");
        container.setAlignment(Pos.CENTER_LEFT);
        container.setPadding(new Insets(12));

        // File icon based on type
        Label fileIcon = new Label();
        fileIcon.getStyleClass().add("file-icon");

        String mimeType = message.getMediaType();
        if (mimeType.startsWith("video/")) {
            fileIcon.setText("🎥");
        } else if (mimeType.startsWith("audio/")) {
            fileIcon.setText("🎵");
        } else if (mimeType.contains("pdf")) {
            fileIcon.setText("📄");
        } else if (mimeType.contains("zip") || mimeType.contains("rar")) {
            fileIcon.setText("🗜️");
        } else {
            fileIcon.setText("📎");
        }

        // File info
        VBox fileInfo = new VBox(2);
        fileInfo.setAlignment(Pos.CENTER_LEFT);

        Label fileName = new Label(message.getFileName());
        fileName.getStyleClass().add("file-name");

        Label fileSize = new Label(MediaMessageService.getInstance().formatFileSize(message.getFileSize()));
        fileSize.getStyleClass().add("file-size");

        fileInfo.getChildren().addAll(fileName, fileSize);

        // Download/Open button
        Button openButton = new Button("Open");
        openButton.getStyleClass().add("file-open-button");
        openButton.setOnAction(e -> openFile(message.getMediaData()));

        container.getChildren().addAll(fileIcon, fileInfo, openButton);

        return container;
    }

    /**
     * Create a YouTube embed component
     */
    public static VBox createYouTubeEmbed(MediaMessageService.LinkEmbed embed) {
        VBox container = new VBox(8);
        container.getStyleClass().add("youtube-embed");
        container.setPadding(new Insets(12));

        // Thumbnail
        if (embed.getImageUrl() != null) {
            ImageView thumbnail = new ImageView();
            thumbnail.setFitWidth(320);
            thumbnail.setFitHeight(180);
            thumbnail.setPreserveRatio(true);

            // Load thumbnail asynchronously
            Platform.runLater(() -> {
                try {
                    Image thumbImage = new Image(embed.getImageUrl(), true);
                    thumbnail.setImage(thumbImage);
                } catch (Exception e) {
                    System.err.println("Failed to load YouTube thumbnail: " + e.getMessage());
                }
            });

            // Add rounded corners
            Rectangle clip = new Rectangle();
            clip.setArcWidth(8);
            clip.setArcHeight(8);
            clip.setWidth(320);
            clip.setHeight(180);
            thumbnail.setClip(clip);

            // Play button overlay
            StackPane thumbnailContainer = new StackPane();
            thumbnailContainer.getChildren().add(thumbnail);

            Label playButton = new Label("▶");
            playButton.getStyleClass().add("youtube-play-button");
            playButton.setStyle(
                    "-fx-background-color: rgba(0,0,0,0.8);" +
                            "-fx-text-fill: white;" +
                            "-fx-background-radius: 50%;" +
                            "-fx-font-size: 24px;" +
                            "-fx-padding: 12;" +
                            "-fx-cursor: hand;"
            );

            thumbnailContainer.getChildren().add(playButton);
            StackPane.setAlignment(playButton, Pos.CENTER);

            // Click to open YouTube
            thumbnailContainer.setOnMouseClicked(e -> openUrl(embed.getUrl()));
            thumbnailContainer.getStyleClass().add("clickable");

            container.getChildren().add(thumbnailContainer);
        }

        // Video info
        VBox info = new VBox(4);

        Label title = new Label(embed.getTitle());
        title.getStyleClass().add("youtube-title");
        title.setWrapText(true);
        title.setMaxWidth(320);

        Label siteName = new Label("YouTube");
        siteName.getStyleClass().add("youtube-site");

        info.getChildren().addAll(title, siteName);
        container.getChildren().add(info);

        return container;
    }

    /**
     * Create a generic link embed component
     */
    public static VBox createGenericEmbed(MediaMessageService.LinkEmbed embed) {
        VBox container = new VBox(8);
        container.getStyleClass().add("generic-embed");
        container.setPadding(new Insets(12));
        container.setMaxWidth(400);

        // Image if available
        if (embed.getImageUrl() != null) {
            ImageView image = new ImageView();
            image.setFitWidth(380);
            image.setFitHeight(200);
            image.setPreserveRatio(true);

            Platform.runLater(() -> {
                try {
                    Image embedImage = new Image(embed.getImageUrl(), true);
                    image.setImage(embedImage);
                } catch (Exception e) {
                    System.err.println("Failed to load embed image: " + e.getMessage());
                }
            });

            Rectangle clip = new Rectangle();
            clip.setArcWidth(8);
            clip.setArcHeight(8);
            clip.widthProperty().bind(image.fitWidthProperty());
            clip.heightProperty().bind(image.fitHeightProperty());
            image.setClip(clip);

            container.getChildren().add(image);
        }

        // Content
        VBox content = new VBox(4);

        if (embed.getTitle() != null) {
            Label title = new Label(embed.getTitle());
            title.getStyleClass().add("embed-title");
            title.setWrapText(true);
            title.setMaxWidth(380);
            content.getChildren().add(title);
        }

        if (embed.getDescription() != null) {
            Label description = new Label(embed.getDescription());
            description.getStyleClass().add("embed-description");
            description.setWrapText(true);
            description.setMaxWidth(380);
            content.getChildren().add(description);
        }

        if (embed.getSiteName() != null) {
            Label siteName = new Label(embed.getSiteName());
            siteName.getStyleClass().add("embed-site");
            content.getChildren().add(siteName);
        }

        container.getChildren().add(content);

        // Click to open link
        container.setOnMouseClicked(e -> openUrl(embed.getUrl()));
        container.getStyleClass().add("clickable");

        return container;
    }

    /**
     * Create a Twitter/X embed component
     */
    public static VBox createTwitterEmbed(MediaMessageService.LinkEmbed embed) {
        VBox container = new VBox(8);
        container.getStyleClass().add("twitter-embed");
        container.setPadding(new Insets(12));
        container.setMaxWidth(400);

        // Twitter icon and header
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label twitterIcon = new Label("𝕏");
        twitterIcon.getStyleClass().add("twitter-icon");
        twitterIcon.setStyle("-fx-font-size: 16px; -fx-text-fill: #1da1f2;");

        Label siteName = new Label("Post on X");
        siteName.getStyleClass().add("twitter-site");

        header.getChildren().addAll(twitterIcon, siteName);
        container.getChildren().add(header);

        // Content placeholder
        Label content = new Label("Click to view post on X");
        content.getStyleClass().add("twitter-content");
        content.setWrapText(true);

        container.getChildren().add(content);

        // Click to open
        container.setOnMouseClicked(e -> openUrl(embed.getUrl()));
        container.getStyleClass().add("clickable");

        return container;
    }

    /**
     * Create a Bluesky embed component
     */
    public static VBox createBlueSkyEmbed(MediaMessageService.LinkEmbed embed) {
        VBox container = new VBox(8);
        container.getStyleClass().add("bluesky-embed");
        container.setPadding(new Insets(12));
        container.setMaxWidth(400);

        // Bluesky icon and header
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label blueskyIcon = new Label("☁️");
        blueskyIcon.getStyleClass().add("bluesky-icon");

        Label siteName = new Label("Bluesky Post");
        siteName.getStyleClass().add("bluesky-site");

        header.getChildren().addAll(blueskyIcon, siteName);
        container.getChildren().add(header);

        // Content
        Label content = new Label("Click to view post on Bluesky");
        content.getStyleClass().add("bluesky-content");
        content.setWrapText(true);

        container.getChildren().add(content);

        // Click to open
        container.setOnMouseClicked(e -> openUrl(embed.getUrl()));
        container.getStyleClass().add("clickable");

        return container;
    }

    /**
     * Create an Amazon embed component
     */
    public static VBox createAmazonEmbed(MediaMessageService.LinkEmbed embed) {
        VBox container = new VBox(8);
        container.getStyleClass().add("amazon-embed");
        container.setPadding(new Insets(12));
        container.setMaxWidth(400);

        // Amazon header
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label amazonIcon = new Label("📦");
        amazonIcon.getStyleClass().add("amazon-icon");

        Label siteName = new Label("Amazon");
        siteName.getStyleClass().add("amazon-site");

        header.getChildren().addAll(amazonIcon, siteName);
        container.getChildren().add(header);

        // Product info
        if (embed.getTitle() != null) {
            Label title = new Label(embed.getTitle());
            title.getStyleClass().add("amazon-title");
            title.setWrapText(true);
            title.setMaxWidth(380);
            container.getChildren().add(title);
        }

        // Click to open
        container.setOnMouseClicked(e -> openUrl(embed.getUrl()));
        container.getStyleClass().add("clickable");

        return container;
    }

    // Utility methods
    private static void openImageFullScreen(Image image) {
        // Create a new stage for full-screen image viewing
        Alert imageDialog = new Alert(Alert.AlertType.INFORMATION);
        imageDialog.setTitle("Image Viewer");
        imageDialog.setHeaderText(null);

        ImageView fullImageView = new ImageView(image);
        fullImageView.setPreserveRatio(true);
        fullImageView.setFitWidth(800);

        imageDialog.getDialogPane().setContent(fullImageView);
        imageDialog.showAndWait();
    }

    private static void openFile(String filePath) {
        try {
            Desktop.getDesktop().open(new java.io.File(filePath));
        } catch (Exception e) {
            System.err.println("Failed to open file: " + e.getMessage());
        }
    }

    private static void openUrl(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            System.err.println("Failed to open URL: " + e.getMessage());
        }
    }
}