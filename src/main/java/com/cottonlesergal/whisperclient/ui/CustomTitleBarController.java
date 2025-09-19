package com.cottonlesergal.whisperclient.ui;

import com.cottonlesergal.whisperclient.services.WindowDecoratorService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.net.URL;
import java.util.ResourceBundle;

public class CustomTitleBarController implements Initializable {

    @FXML private HBox titleArea;
    @FXML private Label lblWindowTitle;
    @FXML private Button btnMinimize;
    @FXML private Button btnMaximize;
    @FXML private Button btnClose;

    private WindowDecoratorService windowDecorator;
    private Stage stage;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        windowDecorator = new WindowDecoratorService();

        // We'll set the stage reference after the scene is fully loaded
        Platform.runLater(() -> {
            if (titleArea.getScene() != null && titleArea.getScene().getWindow() instanceof Stage) {
                this.stage = (Stage) titleArea.getScene().getWindow();
                windowDecorator.decorateWindow(stage, titleArea);

                // Set initial button states
                updateMaximizeButton();
            }
        });
    }

    @FXML
    private void minimizeWindow() {
        if (stage != null) {
            windowDecorator.minimizeWindow(stage);
        }
    }

    @FXML
    private void toggleMaximize() {
        if (stage != null) {
            windowDecorator.toggleMaximize(stage);
            updateMaximizeButton();
        }
    }

    @FXML
    private void closeWindow() {
        if (stage != null) {
            windowDecorator.closeWindow(stage);
        }
    }

    private void updateMaximizeButton() {
        if (stage != null) {
            btnMaximize.setText(stage.isMaximized() ? "❐" : "□");
        }
    }

    public void setWindowTitle(String title) {
        lblWindowTitle.setText(title);
    }

    public void setStage(Stage stage) {
        this.stage = stage;
        if (windowDecorator != null) {
            windowDecorator.decorateWindow(stage, titleArea);
        }
    }
}