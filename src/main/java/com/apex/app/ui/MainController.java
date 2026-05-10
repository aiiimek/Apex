package com.apex.app.ui;

import com.apex.app.ApexApplication;
import com.apex.app.core.CredentialVault;
import com.apex.app.core.HostProfile;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);
    private static final Preferences PREFS = Preferences.userNodeForPackage(ApexApplication.class);

    @FXML private ComboBox<String> profileComboBox;
    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField userField;
    @FXML private PasswordField passwordField;
    @FXML private Button connectButton;
    @FXML private Label statusLabel;
    @FXML private Label pathLabel;
    @FXML private TabPane mainTabPane;
    @FXML private ComboBox<String> languageComboBox;

    @FXML private ResourceBundle resources;

    private CredentialVault vault;
    private List<HostProfile> savedProfiles;

    @FXML
    public void initialize() {
        vault = new CredentialVault();
        loadProfiles();

        profileComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) applyProfileToFields(newVal);
        });

        mainTabPane.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> updateGlobalStatus());

        initLanguageSwitcher();
        updateGlobalStatus();
    }

    private void initLanguageSwitcher() {
        languageComboBox.setItems(FXCollections.observableArrayList(
                resources.getString("lang.english"),
                resources.getString("lang.polish")
        ));

        String currentLang = PREFS.get(ApexApplication.PREF_KEY_LANG, "en");
        languageComboBox.getSelectionModel().select(currentLang.equals("pl")
                ? resources.getString("lang.polish")
                : resources.getString("lang.english"));
    }

    @FXML
    private void handleLanguageChange() {
        String selected = languageComboBox.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        String newLang = selected.equals(resources.getString("lang.polish")) ? "pl" : "en";
        String currentLang = PREFS.get(ApexApplication.PREF_KEY_LANG, "en");

        if (newLang.equals(currentLang)) return;

        PREFS.put(ApexApplication.PREF_KEY_LANG, newLang);
        log.info("Language preference saved: {}", newLang);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(resources.getString("alert.restart.title"));
        alert.setHeaderText(null);
        alert.setContentText(resources.getString("alert.restart.content"));
        alert.showAndWait();
    }

    @FXML
    private void handleConnect() {
        String host = hostField.getText().trim();
        String portStr = portField.getText().trim();
        String user = userField.getText().trim();
        String password = passwordField.getText();

        if (host.isEmpty() || user.isEmpty()) {
            showAlert(Alert.AlertType.WARNING,
                    resources.getString("alert.validation.title"),
                    resources.getString("alert.validation.host_user"));
            return;
        }

        int parsedPort = 22;
        try {
            parsedPort = Integer.parseInt(portStr);
        } catch (NumberFormatException ignored) {}

        String profileName = profileComboBox.getSelectionModel().getSelectedItem();
        if (profileName == null || profileName.isBlank()) {
            profileName = user + "@" + host;
        }

        String encryptedPass = vault.encryptPassword(password);
        HostProfile profile = new HostProfile(profileName, host, parsedPort, user, encryptedPass);

        Tab sessionTab = SessionTabFactory.createSessionTab(profile, vault, resources);
        mainTabPane.getTabs().add(sessionTab);
        mainTabPane.getSelectionModel().select(sessionTab);

        log.info("New tab created: {}", profileName);

        hostField.clear();
        userField.clear();
        passwordField.clear();
        portField.setText("22");
        profileComboBox.getSelectionModel().clearSelection();
    }

    @FXML
    private void handleSaveProfile() {
        String host = hostField.getText().trim();
        String user = userField.getText().trim();
        String portStr = portField.getText().trim();
        String password = passwordField.getText();

        if (host.isEmpty() || user.isEmpty()) {
            showAlert(Alert.AlertType.WARNING,
                    resources.getString("alert.validation.title"),
                    resources.getString("alert.save.host_user"));
            return;
        }

        int parsedPort = 22;
        try {
            parsedPort = Integer.parseInt(portStr);
        } catch (NumberFormatException ignored) {}
        final int port = parsedPort;

        TextInputDialog dialog = new TextInputDialog(user + "@" + host);
        dialog.setTitle(resources.getString("dialog.save_profile.title"));
        dialog.setHeaderText(resources.getString("dialog.save_profile.header"));
        dialog.setContentText(resources.getString("dialog.save_profile.label"));
        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            String encryptedPass = vault.encryptPassword(password);
            HostProfile profile = new HostProfile(name, host, port, user, encryptedPass);
            savedProfiles.removeIf(p -> p.profileName().equals(name));
            savedProfiles.add(profile);
            vault.saveProfiles(savedProfiles);
            loadProfiles();
            profileComboBox.getSelectionModel().select(name);
            log.info("Profile saved: {}", name);
        });
    }

    private void loadProfiles() {
        savedProfiles = vault.loadProfiles();
        List<String> names = savedProfiles.stream()
                .map(HostProfile::profileName)
                .collect(Collectors.toList());
        profileComboBox.setItems(FXCollections.observableArrayList(names));
    }

    @FXML
    private void handleManageProfiles() {
        Stage manageStage = new Stage();
        manageStage.initModality(Modality.APPLICATION_MODAL);
        manageStage.setTitle(resources.getString("dialog.manage.title"));

        ListView<String> listView = new ListView<>();
        listView.setItems(FXCollections.observableArrayList(
                savedProfiles.stream().map(HostProfile::profileName).collect(Collectors.toList())
        ));
        listView.getStyleClass().add("list-view");
        VBox.setVgrow(listView, Priority.ALWAYS);

        Button editBtn = new Button(resources.getString("btn.edit"));
        editBtn.getStyleClass().add("button");

        Button deleteBtn = new Button(resources.getString("btn.delete"));
        deleteBtn.getStyleClass().addAll("button", "danger-button");

        editBtn.setOnAction(e -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                HostProfile oldProfile = savedProfiles.stream()
                        .filter(p -> p.profileName().equals(selected))
                        .findFirst().orElse(null);
                if (oldProfile != null) {
                    EditProfileDialog dlg = new EditProfileDialog(oldProfile, vault);
                    Optional<HostProfile> result = dlg.showAndWait();
                    result.ifPresent(updated -> {
                        savedProfiles.removeIf(p -> p.profileName().equals(selected));
                        savedProfiles.add(updated);
                        vault.saveProfiles(savedProfiles);
                        loadProfiles();
                        profileComboBox.getSelectionModel().select(updated.profileName());
                        log.info("Profile updated: {}", updated.profileName());
                    });
                }
            }
        });

        deleteBtn.setOnAction(e -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                savedProfiles.removeIf(p -> p.profileName().equals(selected));
                vault.saveProfiles(savedProfiles);
                loadProfiles();
                listView.getItems().remove(selected);
                profileComboBox.getSelectionModel().clearSelection();
                log.info("Profile deleted: {}", selected);
            }
        });

        HBox btnBox = new HBox(12, editBtn, deleteBtn);
        btnBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        Label headerLabel = new Label(resources.getString("dialog.manage.header"));
        headerLabel.getStyleClass().add("label-header");

        VBox layout = new VBox(14, headerLabel, listView, btnBox);
        layout.setPadding(new Insets(20));
        layout.getStyleClass().add("panel-container");

        Scene scene = new Scene(layout, 380, 440);
        try {
            scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        } catch (Exception ex) {
            log.warn("Could not load stylesheet for manage profiles window.");
        }

        manageStage.setScene(scene);
        manageStage.showAndWait();
    }

    private void applyProfileToFields(String profileName) {
        savedProfiles.stream()
                .filter(p -> p.profileName().equals(profileName))
                .findFirst()
                .ifPresent(p -> {
                    hostField.setText(p.host());
                    portField.setText(String.valueOf(p.port()));
                    userField.setText(p.username());
                    try {
                        passwordField.setText(vault.decryptPassword(p.encryptedPassword()));
                    } catch (Exception e) {
                        log.error("Failed to decrypt password", e);
                        passwordField.setText("");
                    }
                });
    }

    private void updateGlobalStatus() {
        int count = mainTabPane.getTabs().size();
        Platform.runLater(() -> {
            statusLabel.setText(count == 0
                    ? resources.getString("label.no_sessions")
                    : MessageFormat.format(resources.getString("label.sessions_count"), count));
            pathLabel.setText(count == 0
                    ? resources.getString("label.hint")
                    : "");
        });
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
