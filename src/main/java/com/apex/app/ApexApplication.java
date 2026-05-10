package com.apex.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

/**
 * Główna klasa startowa interfejsu JavaFX.
 */
public class ApexApplication extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        URL fxmlLocation = getClass().getResource("/fxml/main.fxml");
        if (fxmlLocation == null) {
            throw new IllegalStateException("Nie znaleziono pliku zasobu: /fxml/main.fxml");
        }

        FXMLLoader loader = new FXMLLoader(fxmlLocation);
        Parent root = loader.load();

        primaryStage.setTitle("Apex - Remote Commander");
        Scene scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        primaryStage.setScene(scene);
        
        primaryStage.setMinWidth(1000);
        primaryStage.setMinHeight(700);
        
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
