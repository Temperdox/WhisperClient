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
        if (toastContainer == null) {
            setupToastContainer();
            if (toastContainer == null) {
                System.err.println("[NotificationManager] Could not create toast container");
                return;
            }
        }

        Platform.runLater(() -> createToast(title, message, type));
    }

    private void createToast(String title, String message, ToastType type) {
        // Create toast container
        HBox toast = new HBox(12);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.setPadding(new Insets(12, 16, 12, 16));
        toast.setMaxWidth(280);
        toast.setPrefWidth(280);

        // Style based on type
        String baseStyle = "-fx-background-radius: 8; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 8, 0, 0, 2);";
        switch (type) {
            case MESSAGE:
                toast.setStyle(baseStyle + "-fx-background-color: #5865f2;");
                break;
            case SUCCESS:
                toast.setStyle(baseStyle + "-fx-background-color: #23a55a;");
                break;
            case WARNING:
                toast.setStyle(baseStyle + "-fx-background-color: #f0b232;");
                break;
            case ERROR:
                toast.setStyle(baseStyle + "-fx-background-color: #f23f43;");
                break;
            default:
                toast.setStyle(baseStyle + "-fx-background-color: #4f545c;");
                break;
        }

        // Icon based on type
        Label icon = new Label();
        icon.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;");
        switch (type) {
            case MESSAGE:
                icon.setText("💬");
                break;
            case SUCCESS:
                icon.setText("✓");
                break;
            case WARNING:
                icon.setText("⚠");
                break;
            case ERROR:
                icon.setText("✗");
                break;
            default:
                icon.setText("ℹ");
                break;
        }

        // Content
        VBox content = new VBox(2);
        content.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;");
        titleLabel.setWrapText(true);

        Label messageLabel = new Label(message);
        messageLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.9); -fx-font-size: 12px;");
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(220);

        content.getChildren().addAll(titleLabel, messageLabel);
        toast.getChildren().addAll(icon, content);

        // Add to container
        toastContainer.getChildren().add(toast);

        // Slide in animation
        TranslateTransition slideIn = new TranslateTransition(Duration.millis(300), toast);
        slideIn.setFromX(300);
        slideIn.setToX(0);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(300), toast);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);

        slideIn.play();
        fadeIn.play();

        // Auto-remove after 4 seconds
        Platform.runLater(() -> {
            new Thread(() -> {
                try {
                    Thread.sleep(4000);
                    Platform.runLater(() -> removeToast(toast));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });

        // Limit number of toasts
        while (toastContainer.getChildren().size() > 5) {
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
        String preview = message.length() > 50 ? message.substring(0, 50) + "..." : message;
        showToast("New Message from " + from, preview, ToastType.MESSAGE);
    }

    public void showFriendRequestNotification(String from) {
        showToast("Friend Request", from + " sent you a friend request", ToastType.MESSAGE);
    }

    public void showFriendAcceptedNotification(String from) {
        showToast("Friend Added", from + " accepted your friend request", ToastType.SUCCESS);
    }

    public void showFriendRemovedNotification(String from) {
        showToast("Friend Removed", "You are no longer friends with " + from, ToastType.WARNING);
    }

    public void showBlockedNotification(String user) {
        showToast("User Blocked", user + " has been blocked", ToastType.WARNING);
    }

    public void showErrorNotification(String title, String message) {
        showToast(title, message, ToastType.ERROR);
    }

    public void showSuccessNotification(String title, String message) {
        showToast(title, message, ToastType.SUCCESS);
    }

    public enum ToastType {
        MESSAGE, SUCCESS, WARNING, ERROR, INFO
    }
}