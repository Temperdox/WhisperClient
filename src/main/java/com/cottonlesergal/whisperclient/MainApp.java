package com.cottonlesergal.whisperclient;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;

public class MainApp extends Application {
    @Override public void start(Stage stage) throws Exception {
        Scene scene = new Scene(FXMLLoader.load(
                Objects.requireNonNull(getClass().getResource("/com/cottonlesergal/whisperclient/fxml/login.fxml"))), 1080, 720);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/com/cottonlesergal/whisperclient/css/app.css")).toExternalForm());
        stage.setTitle("WhisperClient");
        stage.setScene(scene);
        stage.show();
    }
    public static void main(String[] args){ launch(args); }
}
