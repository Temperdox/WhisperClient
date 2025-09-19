package com.cottonlesergal.whisperclient;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Objects;

public class MainApp extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        // Remove native window decorations BEFORE creating the scene
        stage.initStyle(StageStyle.UNDECORATED);

        // Load login scene
        Scene scene = new Scene(FXMLLoader.load(
                Objects.requireNonNull(getClass().getResource("/com/cottonlesergal/whisperclient/fxml/login.fxml"))), 1080, 720);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/com/cottonlesergal/whisperclient/css/app.css")).toExternalForm());

        // Set minimum window size
        stage.setMinWidth(800);
        stage.setMinHeight(600);

        stage.setTitle("WhisperClient");
        stage.setScene(scene);

        // Center the window before showing
        stage.centerOnScreen();

        // Show the stage - style is already set
        stage.show();
    }

    public static void main(String[] args){
        launch(args);
    }
}