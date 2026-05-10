package com.apex.app.ui;

import com.apex.app.core.HostProfile;
import com.apex.app.core.CredentialVault;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/**
 * Okno dialogowe do edycji istniejącego profilu HostProfile.
 */
public class EditProfileDialog extends Dialog<HostProfile> {

    private TextField aliasField;
    private TextField hostField;
    private TextField portField;
    private TextField userField;
    private PasswordField passwordField;

    public EditProfileDialog(HostProfile profile, CredentialVault vault) {
        setTitle("Edycja Profilu");
        setHeaderText("Zaktualizuj dane dla: " + profile.profileName());

        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.setPadding(new Insets(24, 20, 16, 20));

        aliasField = new TextField(profile.profileName());
        aliasField.setEditable(false); // Zablokowana zmiana nazwy profilu
        aliasField.getStyleClass().add("text-field");

        hostField = new TextField(profile.host());
        hostField.getStyleClass().add("text-field");

        portField = new TextField(String.valueOf(profile.port()));
        portField.getStyleClass().add("text-field");

        userField = new TextField(profile.username());
        userField.getStyleClass().add("text-field");

        passwordField = new PasswordField();
        passwordField.getStyleClass().add("password-field");
        try {
            passwordField.setText(vault.decryptPassword(profile.encryptedPassword()));
        } catch (Exception e) {
            passwordField.setText("");
        }

        grid.add(new Label("Alias (Klucz):"), 0, 0);
        grid.add(aliasField, 1, 0);
        grid.add(new Label("Host IP:"), 0, 1);
        grid.add(hostField, 1, 1);
        grid.add(new Label("Port:"), 0, 2);
        grid.add(portField, 1, 2);
        grid.add(new Label("Użytkownik:"), 0, 3);
        grid.add(userField, 1, 3);
        grid.add(new Label("Hasło:"), 0, 4);
        grid.add(passwordField, 1, 4);

        getDialogPane().setContent(grid);
        getDialogPane().getStyleClass().add("panel-container");
        
        try {
            getDialogPane().getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        } catch (Exception ex) {
            // ignoruj
        }

        setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                int p = 22;
                try {
                    p = Integer.parseInt(portField.getText().trim());
                } catch (Exception ignored) {}

                String newPass = vault.encryptPassword(passwordField.getText());
                return new HostProfile(
                        aliasField.getText(),
                        hostField.getText().trim(),
                        p,
                        userField.getText().trim(),
                        newPass
                );
            }
            return null;
        });
    }
}
