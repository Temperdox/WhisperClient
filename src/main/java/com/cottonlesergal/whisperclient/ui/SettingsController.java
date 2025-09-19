package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.core.Session;
import com.cottonlesergal.whisperclient.services.CredentialsStorageService;
import com.cottonlesergal.whisperclient.services.DirectoryClient;
import com.cottonlesergal.whisperclient.services.ProfileUpdateService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

public class SettingsController implements Initializable {

    // Navigation buttons
    @FXML private Button btnMyAccount;

    // Profile elements
    @FXML private ImageView profileAvatar;
    @FXML private Label lblCurrentUser;
    @FXML private Label lblCurrentTag;
    @FXML private TextField txtDisplayName;
    @FXML private TextField txtUsername;
    @FXML private Label lblProvider;
    @FXML private Label lblProviderEmail;
    @FXML private CheckBox chkAutoSignIn;
    @FXML private Button btnClose;

    private final DirectoryClient directory = new DirectoryClient();
    private final CredentialsStorageService credentialsStorage = CredentialsStorageService.getInstance();
    private final ProfileUpdateService profileService = ProfileUpdateService.getInstance();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        loadCurrentProfile();
        setupAutoSignInCheckbox();
    }

    private void loadCurrentProfile() {
        if (Session.me == null) return;

        // Set current profile info
        lblCurrentUser.setText(Session.me.getDisplayName());
        lblCurrentTag.setText("@" + Session.me.getUsername());
        txtDisplayName.setText(Session.me.getDisplayName());
        txtUsername.setText(Session.me.getUsername());
        lblProvider.setText(Session.me.getProvider());

        // Load avatar
        profileAvatar.setImage(AvatarCache.get(Session.me.getAvatarUrl(), 80));

        // Update provider status
        lblProviderEmail.setText("Connected");
    }

    private void setupAutoSignInCheckbox() {
        chkAutoSignIn.setSelected(credentialsStorage.hasCredentials());

        chkAutoSignIn.setOnAction(event -> {
            if (!chkAutoSignIn.isSelected()) {
                // Disable auto sign-in
                credentialsStorage.clearCredentials();
                showNotification("Auto sign-in disabled", "You'll need to sign in manually next time");
            } else {
                // Re-enable auto sign-in by saving current credentials
                if (Session.me != null) {
                    credentialsStorage.saveCredentials(
                            Session.me.getProvider(),
                            com.cottonlesergal.whisperclient.services.Config.APP_TOKEN,
                            Session.me.getUsername(),
                            Session.me.getDisplayName(),
                            Session.me.getAvatarUrl()
                    );
                    showNotification("Auto sign-in enabled", "You'll be signed in automatically next time");
                }
            }
        });
    }

    @FXML
    private void showMyAccount() {
        // Already showing my account - highlight the button
        resetNavigationButtons();
        btnMyAccount.getStyleClass().add("active-nav-btn");
    }

    @FXML
    private void showProfiles() {
        resetNavigationButtons();
        showNotification("Coming Soon", "Profile customization features will be available in a future update");
    }

    private void resetNavigationButtons() {
        btnMyAccount.getStyleClass().remove("active-nav-btn");
        // Add other navigation buttons as needed
    }

    @FXML
    private void changeAvatar() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Profile Picture");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp")
        );

        Stage stage = (Stage) profileAvatar.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(stage);

        if (selectedFile != null) {
            uploadNewAvatar(selectedFile);
        }
    }

    private void uploadNewAvatar(File imageFile) {
        Task<String> uploadTask = new Task<String>() {
            @Override
            protected String call() throws Exception {
                // Convert image file to base64 or upload to a service
                return profileService.uploadAvatar(imageFile);
            }
        };

        uploadTask.setOnSucceeded(event -> {
            String newAvatarUrl = uploadTask.getValue();
            if (newAvatarUrl != null && !newAvatarUrl.isEmpty()) {
                // Update local profile
                Session.me = new com.cottonlesergal.whisperclient.models.UserProfile(
                        Session.me.getSub(),
                        Session.me.getUsername(),
                        Session.me.getDisplayName(),
                        newAvatarUrl,
                        Session.me.getProvider(),
                        Session.me.getPubKey()
                );

                // Update UI
                profileAvatar.setImage(AvatarCache.get(newAvatarUrl, 80));

                // Update on server
                directory.registerOrUpdate(Session.me);

                // Update saved credentials
                if (credentialsStorage.hasCredentials()) {
                    credentialsStorage.saveCredentials(
                            Session.me.getProvider(),
                            com.cottonlesergal.whisperclient.services.Config.APP_TOKEN,
                            Session.me.getUsername(),
                            Session.me.getDisplayName(),
                            newAvatarUrl
                    );
                }

                showNotification("Avatar Updated", "Your profile picture has been updated");
            } else {
                showError("Upload Failed", "Could not upload the new avatar");
            }
        });

        uploadTask.setOnFailed(event -> {
            showError("Upload Failed", "Error uploading avatar: " + uploadTask.getException().getMessage());
        });

        Thread uploadThread = new Thread(uploadTask);
        uploadThread.setDaemon(true);
        uploadThread.start();
    }

    @FXML
    private void editProfile() {
        // Focus on the display name field for editing
        txtDisplayName.requestFocus();
        txtDisplayName.selectAll();
    }

    @FXML
    private void saveDisplayName() {
        String newDisplayName = txtDisplayName.getText().trim();

        if (newDisplayName.isEmpty()) {
            showError("Invalid Name", "Display name cannot be empty");
            return;
        }

        if (newDisplayName.equals(Session.me.getDisplayName())) {
            showNotification("No Changes", "Display name is already set to this value");
            return;
        }

        // Update display name
        CompletableFuture.supplyAsync(() -> {
            // Update local profile
            Session.me = new com.cottonlesergal.whisperclient.models.UserProfile(
                    Session.me.getSub(),
                    Session.me.getUsername(),
                    newDisplayName,
                    Session.me.getAvatarUrl(),
                    Session.me.getProvider(),
                    Session.me.getPubKey()
            );

            // Update on server
            return directory.registerOrUpdate(Session.me);
        }).thenAccept(success -> Platform.runLater(() -> {
            if (success) {
                // Update UI
                lblCurrentUser.setText(newDisplayName);

                // Update saved credentials
                if (credentialsStorage.hasCredentials()) {
                    credentialsStorage.saveCredentials(
                            Session.me.getProvider(),
                            com.cottonlesergal.whisperclient.services.Config.APP_TOKEN,
                            Session.me.getUsername(),
                            newDisplayName,
                            Session.me.getAvatarUrl()
                    );
                }

                showNotification("Display Name Updated", "Your display name has been changed to: " + newDisplayName);
            } else {
                showError("Update Failed", "Could not update display name on server");
                txtDisplayName.setText(Session.me.getDisplayName()); // Revert
            }
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                showError("Update Failed", "Error updating display name: " + throwable.getMessage());
                txtDisplayName.setText(Session.me.getDisplayName()); // Revert
            });
            return null;
        });
    }

    @FXML
    private void onLogout() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Are you sure you want to sign out?",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Sign Out");
        confirm.setHeaderText("Sign Out of WhisperClient");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                closeSettings();
                // The main controller will handle the actual logout
                // We'll need to communicate this back
                performLogout();
            }
        });
    }

    @FXML
    private void closeSettings() {
        Stage stage = (Stage) btnClose.getScene().getWindow();
        stage.close();
    }

    private void performLogout() {
        // Clear credentials and session
        credentialsStorage.clearCredentials();
        Session.me = null;
        Session.token = null;
        com.cottonlesergal.whisperclient.services.Config.APP_TOKEN = "";

        // Close settings and return to login
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                    getClass().getResource("/com/cottonlesergal/whisperclient/fxml/login.fxml")
            );

            Stage stage = (Stage) btnClose.getScene().getWindow();
            javafx.scene.Scene loginScene = new javafx.scene.Scene(loader.load(), 1080, 720);
            loginScene.getStylesheets().add(
                    getClass().getResource("/com/cottonlesergal/whisperclient/css/app.css").toExternalForm()
            );

            stage.setScene(loginScene);
            stage.setTitle("WhisperClient");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showNotification(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}