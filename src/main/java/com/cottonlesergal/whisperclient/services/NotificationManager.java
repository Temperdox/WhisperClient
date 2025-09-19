package com.cottonlesergal.whisperclient.services;

import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NotificationManager {
    private static final NotificationManager INSTANCE = new NotificationManager();

    // Track notification counts per user
    private final Map<String, Integer> notificationCounts = new ConcurrentHashMap<>();

    // Reference to the main stage for toast positioning
    private Stage mainStage;

    // Toast container
    private VBox toastContainer;

    public static NotificationManager getInstance() {
        return INSTANCE;
    }

    private NotificationManager() {}

    public void initialize(Stage stage) {
        this.mainStage = stage;
        setupToastContainer();
    }

    private void setupToastContainer() {
        if (mainStage == null) return;

        // Create toast container that overlays the main content
        toastContainer = new VBox(8);
        toastContainer.setAlignment(Pos.BOTTOM_RIGHT);
        toastContainer.setPadding(new Insets(20));
        toastContainer.setMouseTransparent(true); // Allow clicks to pass through
        toastContainer.setPrefWidth(300);
        toastContainer.setMaxWidth(300);

        // Position in bottom right
        StackPane.setAlignment(toastContainer, Pos.BOTTOM_RIGHT);

        // Add to main stage's scene root if it's a StackPane
        Platform.runLater(() -> {
            var scene = mainStage.getScene();
            if (scene != null && scene.getRoot() instanceof StackPane) {
                StackPane root = (StackPane) scene.getRoot();
                if (!root.getChildren().contains(toastContainer)) {
                    root.getChildren().add(toastContainer);
                }
            }
        });
    }

    // Notification count management
    public void incrementNotificationCount(String username) {
        notificationCounts.put(username, notificationCounts.getOrDefault(username, 0) + 1);
        System.out.println("[NotificationManager] Incremented count for " + username +
                " to " + notificationCounts.get(username));
    }

    public void clearNotificationCount(String username) {
        notificationCounts.remove(username);
        System.out.println("[NotificationManager] Cleared count for " + username);
    }

    public int getNotificationCount(String username) {
        return notificationCounts.getOrDefault(username, 0);
    }

    // Toast notifications
    public void showToast(String title, String message, ToastType type) {
        showToast(title, message, type, null);
    }

    public void showToast(String title, String message, ToastType type, String avatarUrl) {
        if (toastContainer == null) {
            setupToastContainer();
            if (toastContainer == null) {
                System.err.println("[NotificationManager] Could not create toast container");
                return;
            }
        }

        Platform.runLater(() -> createToast(title, message, type, avatarUrl));
    }

    private void createToast(String title, String message, ToastType type, String avatarUrl) {
        // Create toast container
        HBox toast = new HBox(12);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.setPadding(new Insets(12, 16, 12, 16));
        toast.setMaxWidth(300);
        toast.setPrefWidth(300);

        // Discord-style dark background
        String baseStyle = "-fx-background-radius: 8; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 12, 0, 0, 4);";
        toast.setStyle(baseStyle + "-fx-background-color: #2b2d31; -fx-border-color: #40444b; -fx-border-width: 1; -fx-border-radius: 8;");

        // App icon (WhisperClient icon)
        Label appIcon = new Label();
        appIcon.setText("💬");
        appIcon.setStyle("-fx-font-size: 16px;");
        appIcon.setPrefSize(24, 24);
        appIcon.setAlignment(Pos.CENTER);

        // Content area
        VBox content = new VBox(2);
        content.setAlignment(Pos.TOP_LEFT);
        content.setMaxWidth(240);

        // App name and username header
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        Label appName = new Label("WhisperClient");
        appName.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 12px;");

        // Add username if this is a message notification
        if (type == ToastType.MESSAGE && title.startsWith("New Message from ")) {
            String username = title.replace("New Message from ", "");
            Label usernameLabel = new Label(username);
            usernameLabel.setStyle("-fx-text-fill: #b5bac1; -fx-font-size: 12px;");
            header.getChildren().addAll(appName, new Label("•"), usernameLabel);
        } else {
            header.getChildren().add(appName);
        }

        // Message content
        Label messageLabel = new Label(message);
        messageLabel.setStyle("-fx-text-fill: #dcddde; -fx-font-size: 13px;");
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(240);

        content.getChildren().addAll(header, messageLabel);

        // Close button (like Discord)
        Label closeButton = new Label("×");
        closeButton.setStyle(
                "-fx-text-fill: #72767d; -fx-font-size: 16px; -fx-font-weight: bold; " +
                        "-fx-cursor: hand; -fx-padding: 0 4; -fx-background-radius: 3;"
        );
        closeButton.setOnMouseEntered(e ->
                closeButton.setStyle(
                        "-fx-text-fill: #ffffff; -fx-font-size: 16px; -fx-font-weight: bold; " +
                                "-fx-cursor: hand; -fx-padding: 0 4; -fx-background-color: #f04747; -fx-background-radius: 3;"
                )
        );
        closeButton.setOnMouseExited(e ->
                closeButton.setStyle(
                        "-fx-text-fill: #72767d; -fx-font-size: 16px; -fx-font-weight: bold; " +
                                "-fx-cursor: hand; -fx-padding: 0 4; -fx-background-radius: 3;"
                )
        );
        closeButton.setOnMouseClicked(e -> removeToast(toast));

        toast.getChildren().addAll(appIcon, content, closeButton);

        // Add to container
        toastContainer.getChildren().add(toast);

        // Slide in animation
        TranslateTransition slideIn = new TranslateTransition(Duration.millis(300), toast);
        slideIn.setFromX(350);
        slideIn.setToX(0);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(300), toast);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);

        slideIn.play();
        fadeIn.play();

        // Auto-remove after 5 seconds (Discord timing)
        Platform.runLater(() -> {
            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    Platform.runLater(() -> removeToast(toast));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });

        // Limit number of toasts
        while (toastContainer.getChildren().size() > 4) {
            removeToast(toastContainer.getChildren().get(0));
        }
    }

    private void removeToast(javafx.scene.Node toast) {
        if (!toastContainer.getChildren().contains(toast)) return;

        // Slide out animation
        TranslateTransition slideOut = new TranslateTransition(Duration.millis(250), toast);
        slideOut.setFromX(0);
        slideOut.setToX(300);

        FadeTransition fadeOut = new FadeTransition(Duration.millis(250), toast);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);

        slideOut.setOnFinished(e -> toastContainer.getChildren().remove(toast));

        slideOut.play();
        fadeOut.play();
    }

    // Quick methods for common notifications
    public void showMessageNotification(String from, String message) {
        incrementNotificationCount(from);
        showToast("New Message from " + from, message, ToastType.MESSAGE, null);
    }

    public void showFriendRequestNotification(String from) {
        showToast("Friend Request", from + " sent you a friend request", ToastType.MESSAGE, null);
    }

    public void showFriendAcceptedNotification(String from) {
        showToast("Friend Added", from + " accepted your friend request", ToastType.SUCCESS, null);
    }

    public void showFriendRemovedNotification(String from) {
        showToast("Friend Removed", "You are no longer friends with " + from, ToastType.WARNING, null);
    }

    public void showBlockedNotification(String user) {
        showToast("User Blocked", user + " has been blocked", ToastType.WARNING, null);
    }

    public void showErrorNotification(String title, String message) {
        showToast(title, message, ToastType.ERROR, null);
    }

    public void showSuccessNotification(String title, String message) {
        showToast(title, message, ToastType.SUCCESS, null);
    }

    public enum ToastType {
        MESSAGE, SUCCESS, WARNING, ERROR, INFO
    }
}