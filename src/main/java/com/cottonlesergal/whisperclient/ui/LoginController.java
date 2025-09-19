package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.models.UserProfile;
import com.cottonlesergal.whisperclient.services.AuthService;
import com.cottonlesergal.whisperclient.services.CredentialsStorageService;
import com.cottonlesergal.whisperclient.services.DirectoryClient;
import com.cottonlesergal.whisperclient.services.WindowDecoratorService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URL;
import java.util.ResourceBundle;

public class LoginController implements Initializable {

    @FXML private Label lblStatus;
    @FXML private CheckBox chkRememberMe;
    @FXML private VBox autoSignInContainer;
    @FXML private VBox loginButtonsContainer;

    // Custom title bar controls
    @FXML private HBox customTitleBar;
    @FXML private HBox titleArea;
    @FXML private Label lblWindowTitle;
    @FXML private Label maxIcon;

    private final AuthService auth = new AuthService();
    private final DirectoryClient directory = new DirectoryClient();
    private final CredentialsStorageService credentialsStorage = CredentialsStorageService.getInstance();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupCustomTitleBar();

        // Try auto sign-in if credentials exist
        if (credentialsStorage.hasCredentials()) {
            attemptAutoSignIn();
        }
    }

    private void setupCustomTitleBar() {
        Platform.runLater(() -> {
            if (customTitleBar != null && customTitleBar.getScene() != null &&
                    customTitleBar.getScene().getWindow() instanceof Stage) {
                Stage stage = (Stage) customTitleBar.getScene().getWindow();
                WindowDecoratorService windowDecorator = new WindowDecoratorService();
                windowDecorator.decorateWindow(stage, titleArea);
            }
        });
    }

    // Window control methods
    @FXML
    private void minimizeWindow() {
        Stage stage = (Stage) customTitleBar.getScene().getWindow();
        if (stage != null) {
            stage.setIconified(true);
        }
    }

    @FXML
    private void toggleMaximize() {
        Stage stage = (Stage) customTitleBar.getScene().getWindow();
        if (stage != null) {
            if (stage.isMaximized()) {
                stage.setMaximized(false);
            } else {
                stage.setMaximized(true);
            }
            // Update maximize icon
            maxIcon.setText(stage.isMaximized() ? "❐" : "□");
        }
    }

    @FXML
    private void closeWindow() {
        Platform.exit();
    }

    @FXML
    public void onGoogle() {
        signin("google");
    }

    @FXML
    public void onDiscord() {
        signin("discord");
    }

    private void attemptAutoSignIn() {
        showAutoSignInProgress(true);
        lblStatus.setText("Attempting automatic sign-in...");

        Task<UserProfile> autoSignInTask = new Task<UserProfile>() {
            @Override
            protected UserProfile call() throws Exception {
                CredentialsStorageService.SavedCredentials savedCredentials = credentialsStorage.loadCredentials();

                if (savedCredentials == null) {
                    throw new Exception("No saved credentials found");
                }

                // Validate the saved JWT token by trying to use it
                // We'll simulate this by creating a UserProfile from saved data
                // In a real implementation, you might want to validate the token with your server

                // For now, we'll trust the saved credentials and create a UserProfile
                return createUserProfileFromCredentials(savedCredentials);
            }
        };

        autoSignInTask.setOnSucceeded(event -> {
            UserProfile userProfile = autoSignInTask.getValue();
            if (userProfile != null) {
                lblStatus.setText("Signed in automatically as " + userProfile.getDisplayName());

                // Register/update in the directory
                directory.registerOrUpdate(userProfile);

                // Navigate to main UI
                navigateToMainUI(userProfile);
            } else {
                handleAutoSignInFailure("Invalid saved credentials");
            }
        });

        autoSignInTask.setOnFailed(event -> {
            Throwable exception = autoSignInTask.getException();
            handleAutoSignInFailure(exception != null ? exception.getMessage() : "Auto sign-in failed");
        });

        // Run the task in a background thread
        Thread autoSignInThread = new Thread(autoSignInTask);
        autoSignInThread.setDaemon(true);
        autoSignInThread.start();
    }

    private void handleAutoSignInFailure(String message) {
        Platform.runLater(() -> {
            showAutoSignInProgress(false);
            lblStatus.setText("Please sign in manually");
            System.out.println("[LoginController] Auto sign-in failed: " + message);
            // Clear corrupted/invalid credentials
            credentialsStorage.clearCredentials();
        });
    }

    private void showAutoSignInProgress(boolean show) {
        autoSignInContainer.setVisible(show);
        autoSignInContainer.setManaged(show);
        loginButtonsContainer.setVisible(!show);
        loginButtonsContainer.setManaged(!show);
    }

    private UserProfile createUserProfileFromCredentials(CredentialsStorageService.SavedCredentials savedCredentials) {
        // Set the JWT token in config for API calls
        com.cottonlesergal.whisperclient.services.Config.APP_TOKEN = savedCredentials.getEncryptedToken(); // This contains the decrypted token

        // Create UserProfile from saved data - you may need to adapt this to match your UserProfile constructor
        return new UserProfile(
                savedCredentials.getProvider() + ":" + savedCredentials.getUsername(), // sub
                savedCredentials.getUsername(),
                savedCredentials.getDisplayName(),
                savedCredentials.getAvatarUrl(),
                savedCredentials.getProvider(),
                "" // pubKey - you might need to regenerate or save this too
        );
    }

    private void signin(String provider) {
        try {
            lblStatus.setText("Opening " + provider + "…");
            Stage stage = (Stage) lblStatus.getScene().getWindow();

            UserProfile me = auth.signIn(stage, provider);
            if (me == null) {
                lblStatus.setText("Sign-in cancelled.");
                return;
            }

            // Save credentials if "Remember Me" is checked
            if (chkRememberMe.isSelected()) {
                credentialsStorage.saveCredentials(
                        provider,
                        com.cottonlesergal.whisperclient.services.Config.APP_TOKEN, // JWT token
                        me.getUsername(),
                        me.getDisplayName(),
                        me.getAvatarUrl()
                );
            }

            // Register/update in the directory
            directory.registerOrUpdate(me);

            // Navigate to main UI
            navigateToMainUI(me);

        } catch (Exception e) {
            e.printStackTrace();
            lblStatus.setText("Sign-in failed: " + e.getMessage());
        }
    }

    private void navigateToMainUI(UserProfile userProfile) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/cottonlesergal/whisperclient/fxml/main.fxml")
            );
            Parent root = loader.load();
            MainController mc = loader.getController();
            mc.setMe(userProfile);

            Stage stage = (Stage) lblStatus.getScene().getWindow();
            Scene scene = new Scene(root, 1200, 800);

            // Set the new scene (stage style was already set in MainApp)
            stage.setScene(scene);
            stage.setTitle("WhisperClient");
            stage.show();

        } catch (Exception e) {
            e.printStackTrace();
            lblStatus.setText("Failed to load main interface: " + e.getMessage());
        }
    }
}