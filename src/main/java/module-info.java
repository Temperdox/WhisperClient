module com.cottonlesergal.whisperclient {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires com.almasb.fxgl.all;
    requires java.net.http;
    requires jdk.jsobject;
    requires com.fasterxml.jackson.databind;
    requires java.desktop;
    requires jdk.httpserver;

    opens com.cottonlesergal.whisperclient.ui to javafx.fxml;

    // Let WebView call methods on the JS bridge class (SignalingClient.JsBridge)
    // (WebView reflects into this package)

    // Export services package to Jackson for serialization
    exports com.cottonlesergal.whisperclient.services to com.fasterxml.jackson.databind;

    // Export your main package so MainApp is discoverable
    exports com.cottonlesergal.whisperclient;
    opens com.cottonlesergal.whisperclient.services to com.fasterxml.jackson.databind, javafx.fxml, javafx.web, jdk.jsobject;
}