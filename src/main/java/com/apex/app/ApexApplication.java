package com.apex.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;
import java.net.URL;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

public class ApexApplication extends Application {

    public static final String PREF_KEY_LANG = "apex.language";
    private static final Preferences PREFS = Preferences.userNodeForPackage(ApexApplication.class);

    @Override
    public void start(Stage primaryStage) throws Exception {
        String langCode = PREFS.get(PREF_KEY_LANG, "en");
        Locale locale = Locale.forLanguageTag(langCode);
        ResourceBundle bundle = ResourceBundle.getBundle("bundles.messages", locale);

        URL fxmlLocation = getClass().getResource("/fxml/main.fxml");
        if (fxmlLocation == null) {
            throw new IllegalStateException("Resource not found: /fxml/main.fxml");
        }

        FXMLLoader loader = new FXMLLoader(fxmlLocation, bundle);
        Parent root = loader.load();

        primaryStage.setTitle("Apex - Remote Commander");
        Scene scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        primaryStage.setScene(scene);

        InputStream iconStream = getClass().getResourceAsStream("/images/icon.png");
        if (iconStream != null) {
            primaryStage.getIcons().add(new Image(iconStream));
        }

        primaryStage.setMinWidth(1000);
        primaryStage.setMinHeight(700);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
