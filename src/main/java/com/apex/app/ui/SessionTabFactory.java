package com.apex.app.ui;

import com.apex.app.core.CredentialVault;
import com.apex.app.core.HostProfile;
import com.apex.app.core.SessionConnectionManager;
import com.apex.app.core.SshTerminalService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Fabryka zakładek sesji SSH.
 *
 * Każde wywołanie createSessionTab() tworzy kompletny, izolowany stos:
 *  - SessionConnectionManager (własny SshClient)
 *  - SshTerminalService (własny kanał powłoki)
 *  - Dedykowany układ UI (terminal + nagłówek + SFTP button)
 *  - Niezależne okno SFTP powiązane z tą zakładką
 *
 * Zakładki nie współdzielą żadnych kanałów sieciowych.
 * Zamknięcie taba (krzyżyk X) automatycznie rozłącza sesję i zwalnia zasoby.
 */
public class SessionTabFactory {

    private static final Logger log = LoggerFactory.getLogger(SessionTabFactory.class);

    /**
     * Tworzy nową zakładkę z pełnym stosem sesji SSH.
     *
     * @param profile Profil połączenia (host, port, user, zaszyfrowane hasło)
     * @param vault   CredentialVault do deszyfrowania hasła
     * @return Tab gotowy do dodania do TabPane
     */
    public static Tab createSessionTab(HostProfile profile, CredentialVault vault) {
        // ═══════════════════════════════════════════════════════════════
        //  BUDOWA LAYOUTU (programowo, bez osobnego FXML)
        // ═══════════════════════════════════════════════════════════════

        // Nagłówek z tytułem, motywem i przyciskiem SFTP
        Label titleLabel = new Label("Łączenie...");
        titleLabel.getStyleClass().add("label-header");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ComboBox<String> themeCombo = new ComboBox<>(
                FXCollections.observableArrayList("Matrix", "Cyberpunk", "Classic", "Ocean"));
        themeCombo.getSelectionModel().select("Matrix");
        themeCombo.getStyleClass().addAll("combo-box", "small-combo");
        themeCombo.setPrefWidth(125);

        ToggleButton terminalToggle = new ToggleButton("Terminal SSH");
        terminalToggle.getStyleClass().addAll("button", "small-button");
        terminalToggle.setSelected(true);

        ToggleButton sftpToggle = new ToggleButton("SFTP Browser");
        sftpToggle.getStyleClass().addAll("button", "sftp-button", "small-button");
        sftpToggle.setDisable(true);

        HBox header = new HBox(12, titleLabel, spacer, themeCombo, terminalToggle, sftpToggle);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        header.getStyleClass().add("terminal-header");
        header.setPadding(new Insets(10, 20, 10, 20));

        // Terminal TextFlow w ScrollPane
        TextFlow terminalTextFlow = new TextFlow();
        terminalTextFlow.getStyleClass().addAll("terminal-text-flow", "theme-matrix");
        
        ScrollPane terminalScrollPane = new ScrollPane(terminalTextFlow);
        terminalScrollPane.getStyleClass().add("terminal-scroll-pane");
        terminalScrollPane.setFitToWidth(true);
        terminalScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        terminalScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        terminalScrollPane.setFocusTraversable(true);
        VBox.setVgrow(terminalScrollPane, Priority.ALWAYS);

        // Wymuszenie focusu po kliknięciu myszą, aby poprawnie łapać klawiaturę
        terminalScrollPane.setOnMouseClicked(e -> terminalScrollPane.requestFocus());

        // Auto-Scroll w dół gdy pojawia się nowy tekst
        terminalTextFlow.heightProperty().addListener((obs, oldVal, newVal) -> {
            terminalScrollPane.setVvalue(1.0);
        });

        // Status bar per-tab
        Label tabStatusLabel = new Label("");
        tabStatusLabel.getStyleClass().add("status-bar-label");
        HBox statusBar = new HBox(tabStatusLabel);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(5, 20, 5, 20));

        SplitPane splitPane = new SplitPane();
        splitPane.getStyleClass().add("split-pane");
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        splitPane.getItems().add(terminalScrollPane);

        // Kontener — VGrow na splitPane, aby wypełniał całą dostępną wysokość
        VBox container = new VBox(header, splitPane, statusBar);
        container.getStyleClass().add("panel-container");
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        // ═══════════════════════════════════════════════════════════════
        //  KONFIGURACJA MOTYWÓW
        // ═══════════════════════════════════════════════════════════════
        themeCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            terminalTextFlow.getStyleClass().removeAll("theme-matrix", "theme-cyberpunk", "theme-classic", "theme-ocean");
            switch (newVal) {
                case "Matrix" -> terminalTextFlow.getStyleClass().add("theme-matrix");
                case "Cyberpunk" -> terminalTextFlow.getStyleClass().add("theme-cyberpunk");
                case "Classic" -> terminalTextFlow.getStyleClass().add("theme-classic");
                case "Ocean" -> terminalTextFlow.getStyleClass().add("theme-ocean");
            }
        });

        // ═══════════════════════════════════════════════════════════════
        //  TWORZENIE TABA
        // ═══════════════════════════════════════════════════════════════
        Tab tab = new Tab(profile.profileName(), container);

        // ═══════════════════════════════════════════════════════════════
        //  IZOLOWANY STOS SIECIOWY
        // ═══════════════════════════════════════════════════════════════
        SessionConnectionManager connectionManager = new SessionConnectionManager();

        // Kontener na referencje usług (mutable, bo ustawiane asynchronicznie)
        final SshTerminalService[] terminalServiceRef = { null };
        final ClientSession[] sessionRef = { null };
        final Stage[] sftpStageRef = { null };

        // ═══════════════════════════════════════════════════════════════
        //  EVENT FILTER (przechwytywanie klawiatury terminala)
        // ═══════════════════════════════════════════════════════════════
        terminalScrollPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            SshTerminalService svc = terminalServiceRef[0];
            if (svc == null) return;

            try {
                if (event.isControlDown()) {
                    event.consume();
                    switch (event.getCode()) {
                        case C -> svc.sendBytes(new byte[]{0x03});
                        case D -> svc.sendBytes(new byte[]{0x04});
                        case Z -> svc.sendBytes(new byte[]{0x1A});
                        case L -> svc.sendBytes(new byte[]{0x0C});
                        case A -> svc.sendBytes(new byte[]{0x01});
                        case E -> svc.sendBytes(new byte[]{0x05});
                        case U -> svc.sendBytes(new byte[]{0x15});
                        case K -> svc.sendBytes(new byte[]{0x0B});
                        case W -> svc.sendBytes(new byte[]{0x17});
                        default -> { /* ignoruj inne Ctrl+X */ }
                    }
                } else {
                    switch (event.getCode()) {
                        case ENTER -> { event.consume(); svc.sendBytes(new byte[]{'\r'}); }
                        case BACK_SPACE -> { event.consume(); svc.sendBytes(new byte[]{0x7F}); }
                        case TAB -> { event.consume(); svc.sendBytes(new byte[]{'\t'}); }
                        case UP -> { event.consume(); svc.sendSequence("\033[A"); }
                        case DOWN -> { event.consume(); svc.sendSequence("\033[B"); }
                        case RIGHT -> { event.consume(); svc.sendSequence("\033[C"); }
                        case LEFT -> { event.consume(); svc.sendSequence("\033[D"); }
                        case HOME -> { event.consume(); svc.sendSequence("\033[H"); }
                        case END -> { event.consume(); svc.sendSequence("\033[F"); }
                        case PAGE_UP -> { event.consume(); svc.sendSequence("\033[5~"); }
                        case PAGE_DOWN -> { event.consume(); svc.sendSequence("\033[6~"); }
                        case DELETE -> { event.consume(); svc.sendSequence("\033[3~"); }
                        case ESCAPE -> { event.consume(); svc.sendBytes(new byte[]{0x1B}); }
                        default -> {
                            // reszta poleci z KEY_TYPED
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Błąd wysyłania klawisza [{}] do serwera", event.getCode(), e);
            }
        });

        terminalScrollPane.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            event.consume();
            SshTerminalService svc = terminalServiceRef[0];
            if (svc == null) return;
            try {
                String ch = event.getCharacter();
                // Odrzucamy puste znaki i powielone powroty karetki / backspace'y (obsłużone w KEY_PRESSED)
                if (ch != null && !ch.isEmpty() && !ch.equals("\b") && !ch.equals("\r") && !ch.equals("\n") && !ch.equals("\u007F") && !ch.equals("\t") && !event.isControlDown()) {
                    svc.sendBytes(ch.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                log.error("Błąd podczas KEY_TYPED: {}", e.getMessage(), e);
            }
        });

        // ═══════════════════════════════════════════════════════════════
        //  PRZEŁĄCZNIK WIDOKÓW (Terminal / SFTP)
        // ═══════════════════════════════════════════════════════════════
        final Parent[] sftpRootRef = { null };

        Runnable updateSplitPane = () -> {
            boolean showTerm = terminalToggle.isSelected();
            boolean showSftp = sftpToggle.isSelected();

            if (!showTerm && !showSftp) {
                terminalToggle.setSelected(true);
                showTerm = true;
            }

            splitPane.getItems().clear();
            if (showTerm) splitPane.getItems().add(terminalScrollPane);
            if (showSftp && sftpRootRef[0] != null) splitPane.getItems().add(sftpRootRef[0]);
        };

        terminalToggle.setOnAction(e -> updateSplitPane.run());

        sftpToggle.setOnAction(e -> {
            if (sftpToggle.isSelected() && sftpRootRef[0] == null) {
                ClientSession session = sessionRef[0];
                if (session != null) {
                    try {
                        URL fxmlUrl = SessionTabFactory.class.getResource("/fxml/sftp.fxml");
                        FXMLLoader loader = new FXMLLoader(fxmlUrl);
                        sftpRootRef[0] = loader.load();
                        SftpController sftpController = loader.getController();
                        sftpController.initSession(session);
                    } catch (Exception ex) {
                        log.error("Błąd ładowania SFTP", ex);
                        sftpToggle.setSelected(false);
                        showAlert("Błąd SFTP", "Nie udało się załadować widoku SFTP:\n" + ex.getMessage());
                        return;
                    }
                }
            }
            updateSplitPane.run();
        });

        // ═══════════════════════════════════════════════════════════════
        //  ZAMYKANIE TABA → pełne sprzątanie zasobów
        // ═══════════════════════════════════════════════════════════════
        tab.setOnClosed(event -> {
            log.info("Zamykanie taba: {}", profile.profileName());

            // Usuń referencję do widoku SFTP
            sftpRootRef[0] = null;

            // Zamknij terminal service
            if (terminalServiceRef[0] != null) {
                terminalServiceRef[0].disconnect();
                terminalServiceRef[0] = null;
            }

            // Zamknij sesję i klienta SSH
            if (sessionRef[0] != null) {
                connectionManager.disconnect(sessionRef[0]);
                sessionRef[0] = null;
            }

            log.info("Tab {} zamknięty, zasoby zwolnione.", profile.profileName());
        });

        // ═══════════════════════════════════════════════════════════════
        //  ASYNCHRONICZNE ŁĄCZENIE
        // ═══════════════════════════════════════════════════════════════
        String host = profile.host();
        int port = profile.port();
        String user = profile.username();
        String password;
        try {
            password = vault.decryptPassword(profile.encryptedPassword());
        } catch (Exception e) {
            password = "";
            log.warn("Nie udało się odszyfrować hasła dla profilu: {}", profile.profileName());
        }
        final String passwd = password;

        AnsiTerminalParser ansiParser = new AnsiTerminalParser(terminalTextFlow);
        
        Text initText = new Text("Łączenie z " + user + "@" + host + ":" + port + "...\n");
        initText.getStyleClass().add("terminal-text");
        terminalTextFlow.getChildren().add(initText);

        connectionManager.connect(host, port, user, passwd, null)
                .thenAccept(session -> {
                    log.info("Sesja SSH nawiązana dla taba: {}", profile.profileName());
                    sessionRef[0] = session;

                    Platform.runLater(() -> {
                        terminalTextFlow.getChildren().clear();
                        titleLabel.setText("Terminal SSH  —  " + user + "@" + host);
                        tabStatusLabel.setText(host + ":" + port);
                        sftpToggle.setDisable(false);
                    });

                    // Uruchomienie powłoki
                    try {
                        SshTerminalService svc = new SshTerminalService(session);
                        terminalServiceRef[0] = svc;
                        svc.startShell(rawBytes -> {
                            Platform.runLater(() -> {
                                ansiParser.processRawOutput(rawBytes);
                            });
                        });
                    } catch (IOException e) {
                        log.error("Błąd uruchamiania powłoki dla taba: {}", profile.profileName(), e);
                        Platform.runLater(() -> showAlert("Błąd Shell", e.getMessage()));
                    }
                })
                .exceptionally(ex -> {
                    log.error("Błąd połączenia dla taba: {}", profile.profileName(), ex);
                    Platform.runLater(() -> {
                        Text errText = new Text("BŁĄD: " + ex.getMessage() + "\n");
                        errText.getStyleClass().addAll("terminal-text", "ansi-31");
                        terminalTextFlow.getChildren().add(errText);
                        titleLabel.setText("Błąd — " + profile.profileName());
                        tabStatusLabel.setText("Rozłączono");
                    });
                    return null;
                });

        return tab;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  METODY POMOCNICZE
    // ═══════════════════════════════════════════════════════════════════



    private static void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
