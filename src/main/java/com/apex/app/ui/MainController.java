package com.apex.app.ui;

import com.apex.app.core.CredentialVault;
import com.apex.app.core.HostProfile;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Główny kontroler okna aplikacji — orkiestrator zakładek.
 *
 * Architektura Multi-Tab (Krok 14):
 * MainController zarządza wyłącznie górnym paskiem (profili, pól połączenia)
 * oraz TabPane. Cała logika terminala, klawiatury, sesji SSH i SFTP
 * została przeniesiona do SessionTabFactory, która tworzy izolowane zakładki.
 *
 * Kliknięcie "Connect" NIE nadpisuje obecnego widoku — tworzy NOWĄ zakładkę.
 * Użytkownik może mieć wiele połączeń jednocześnie.
 */
public class MainController {
    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    // Kontrolki górnego paska
    @FXML private ComboBox<String> profileComboBox;
    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField userField;
    @FXML private PasswordField passwordField;
    @FXML private Button connectButton;
    @FXML private Label statusLabel;
    @FXML private Label pathLabel;

    // TabPane — centrum aplikacji
    @FXML private TabPane mainTabPane;

    // Credential Vault
    private CredentialVault vault;
    private List<HostProfile> savedProfiles;

    /**
     * Wywoływana automatycznie przez FXMLLoader po załadowaniu main.fxml.
     * Konfiguruje: vault, profile, listener na zmiany zakładek.
     */
    @FXML
    public void initialize() {
        vault = new CredentialVault();
        loadProfiles();

        // Listener: wybranie profilu z listy autouzupełnia pola połączenia
        profileComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                applyProfileToFields(newVal);
            }
        });

        // Listener: aktualizacja statusu globalnego przy zmianie liczby zakładek
        mainTabPane.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> {
            updateGlobalStatus();
        });

        updateGlobalStatus();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONNECT — tworzenie nowej zakładki
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Tworzy nową zakładkę z połączeniem SSH.
     *
     * Jeśli pola są wypełnione ręcznie (bez profilu), tworzy tymczasowy profil.
     * Jeśli profil jest wybrany z ComboBox, używa jego danych.
     */
    @FXML
    private void handleConnect() {
        String host = hostField.getText().trim();
        String portStr = portField.getText().trim();
        String user = userField.getText().trim();
        String password = passwordField.getText();

        if (host.isEmpty() || user.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Błąd walidacji", "Host i użytkownik nie mogą być puste.");
            return;
        }

        int parsedPort = 22;
        try {
            parsedPort = Integer.parseInt(portStr);
        } catch (NumberFormatException ignored) {
        }

        // Budujemy profil (tymczasowy lub z nazwy wybranego profilu)
        String profileName = profileComboBox.getSelectionModel().getSelectedItem();
        if (profileName == null || profileName.isBlank()) {
            profileName = user + "@" + host;
        }

        String encryptedPass = vault.encryptPassword(password);
        HostProfile profile = new HostProfile(profileName, host, parsedPort, user, encryptedPass);

        // Tworzymy nową zakładkę przez fabrykę
        Tab sessionTab = SessionTabFactory.createSessionTab(profile, vault);

        // Dodajemy do TabPane i aktywujemy
        mainTabPane.getTabs().add(sessionTab);
        mainTabPane.getSelectionModel().select(sessionTab);

        log.info("Utworzono nową zakładkę: {}", profileName);

        // Czyszczenie pól po nawiązaniu połączenia
        hostField.clear();
        userField.clear();
        passwordField.clear();
        portField.setText("22");
        profileComboBox.getSelectionModel().clearSelection();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PROFILE (Credential Vault)
    // ══════════════════════════════════════════════════════════════════════════

    @FXML
    private void handleSaveProfile() {
        String host = hostField.getText().trim();
        String user = userField.getText().trim();
        String portStr = portField.getText().trim();
        String password = passwordField.getText();

        if (host.isEmpty() || user.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Brak danych", "Wypełnij Host i Użytkownik przed zapisaniem profilu.");
            return;
        }

        int parsedPort = 22;
        try {
            parsedPort = Integer.parseInt(portStr);
        } catch (NumberFormatException ignored) {
        }
        final int port = parsedPort;

        TextInputDialog dialog = new TextInputDialog(user + "@" + host);
        dialog.setTitle("Zapisz Profil");
        dialog.setHeaderText("Nadaj nazwę nowemu profilowi połączenia:");
        dialog.setContentText("Nazwa profilu:");
        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank())
                return;
            String encryptedPass = vault.encryptPassword(password);
            HostProfile profile = new HostProfile(name, host, port, user, encryptedPass);
            savedProfiles.removeIf(p -> p.profileName().equals(name));
            savedProfiles.add(profile);
            vault.saveProfiles(savedProfiles);
            loadProfiles();
            profileComboBox.getSelectionModel().select(name);
            log.info("Zapisano profil: {}", name);
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
        manageStage.setTitle("Zarządzaj Profilami");

        ListView<String> listView = new ListView<>();
        listView.setItems(FXCollections.observableArrayList(
                savedProfiles.stream().map(HostProfile::profileName).collect(Collectors.toList())
        ));
        listView.getStyleClass().add("list-view");
        VBox.setVgrow(listView, Priority.ALWAYS);

        Button editBtn = new Button("✎ Edytuj");
        editBtn.getStyleClass().add("button");

        Button deleteBtn = new Button("✕ Usuń");
        deleteBtn.getStyleClass().addAll("button", "danger-button");

        editBtn.setOnAction(e -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                HostProfile oldProfile = savedProfiles.stream()
                        .filter(p -> p.profileName().equals(selected))
                        .findFirst()
                        .orElse(null);

                if (oldProfile != null) {
                    EditProfileDialog dialog = new EditProfileDialog(oldProfile, vault);
                    Optional<HostProfile> result = dialog.showAndWait();
                    result.ifPresent(updatedProfile -> {
                        savedProfiles.removeIf(p -> p.profileName().equals(selected));
                        savedProfiles.add(updatedProfile);
                        vault.saveProfiles(savedProfiles);
                        loadProfiles();
                        profileComboBox.getSelectionModel().select(updatedProfile.profileName());
                        log.info("Zaktualizowano profil: {}", updatedProfile.profileName());
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
                log.info("Usunięto profil: {}", selected);
            }
        });

        HBox btnBox = new HBox(12, editBtn, deleteBtn);
        btnBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        Label headerLabel = new Label("Zapisane profile:");
        headerLabel.getStyleClass().add("label-header");

        VBox layout = new VBox(14, headerLabel, listView, btnBox);
        layout.setPadding(new Insets(20));
        layout.getStyleClass().add("panel-container");

        Scene scene = new Scene(layout, 380, 440);
        try {
            scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        } catch (Exception ex) {
            log.warn("Nie udało się załadować stylów dla okna zarządzania profili.");
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
                        log.error("Błąd deszyfrowania hasła", e);
                        passwordField.setText("");
                    }
                });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // POMOCNICZE METODY
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Aktualizuje globalny status (dolny pasek) z liczbą aktywnych sesji.
     */
    private void updateGlobalStatus() {
        int count = mainTabPane.getTabs().size();
        Platform.runLater(() -> {
            statusLabel.setText(count == 0 ? "Brak aktywnych sesji" : "Aktywne sesje: " + count);
            pathLabel.setText(count == 0 ? "Wybierz profil i kliknij Connect, aby otworzyć nową zakładkę." : "");
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
