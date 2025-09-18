package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.models.UserProfile;
import com.cottonlesergal.whisperclient.services.AuthService;
import com.cottonlesergal.whisperclient.services.DirectoryClient;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;

public class LoginController {

    @FXML private Label lblStatus;

    private final AuthService auth = new AuthService();
    private final DirectoryClient directory = new DirectoryClient();

    @FXML public void onGoogle()  { signin("google"); }
    @FXML public void onDiscord() { signin("discord"); }

    private void signin(String provider) {
        try {
            lblStatus.setText("Opening " + provider + "…");
            Stage stage = (Stage) lblStatus.getScene().getWindow();

            // Must return the signed-in profile (username/display/avatar/pubkey…)
            UserProfile me = auth.signIn(stage, provider);
            if (me == null) {
                lblStatus.setText("Sign-in cancelled.");
                return;
            }

            // Register/update in the directory so others can discover you
            directory.registerOrUpdate(me);

            // Load main UI and pass the profile to display in the sidebar
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/cottonlesergal/whisperclient/fxml/main.fxml")
            );
            Parent root = loader.load();
            MainController mc = loader.getController();
            mc.setMe(me);

            Scene scene = new Scene(root, 1200, 800);
            stage.setScene(scene);
            stage.setTitle("WhisperClient");
            stage.show();

        } catch (Exception e) {
            e.printStackTrace();
            lblStatus.setText("Sign-in failed: " + e.getMessage());
        }
    }
}
