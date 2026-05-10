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
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ResourceBundle;

public class SessionTabFactory {

    private static final Logger log = LoggerFactory.getLogger(SessionTabFactory.class);

    public static Tab createSessionTab(HostProfile profile, CredentialVault vault, ResourceBundle bundle) {

        Label titleLabel = new Label(bundle.getString("tab.connecting"));
        titleLabel.getStyleClass().add("label-header");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label themeLabel = new Label(bundle.getString("label.theme"));
        themeLabel.getStyleClass().add("status-label");

        ComboBox<String> themeCombo = new ComboBox<>(
                FXCollections.observableArrayList("Matrix", "Cyberpunk", "Classic", "Ocean"));
        themeCombo.getSelectionModel().select("Matrix");
        themeCombo.getStyleClass().addAll("combo-box", "small-combo");
        themeCombo.setPrefWidth(125);

        ToggleButton terminalToggle = new ToggleButton(bundle.getString("btn.terminal"));
        terminalToggle.getStyleClass().addAll("button", "small-button");
        terminalToggle.setSelected(true);

        ToggleButton sftpToggle = new ToggleButton(bundle.getString("btn.sftp_browser"));
        sftpToggle.getStyleClass().addAll("button", "sftp-button", "small-button");
        sftpToggle.setDisable(true);

        HBox header = new HBox(12, titleLabel, spacer, themeLabel, themeCombo, terminalToggle, sftpToggle);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        header.getStyleClass().add("terminal-header");
        header.setPadding(new Insets(10, 20, 10, 20));

        TextFlow terminalTextFlow = new TextFlow();
        terminalTextFlow.getStyleClass().addAll("terminal-text-flow", "theme-matrix");

        ScrollPane terminalScrollPane = new ScrollPane(terminalTextFlow);
        terminalScrollPane.getStyleClass().add("terminal-scroll-pane");
        terminalScrollPane.setFitToWidth(true);
        terminalScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        terminalScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        terminalScrollPane.setFocusTraversable(true);
        VBox.setVgrow(terminalScrollPane, Priority.ALWAYS);

        terminalScrollPane.setOnMouseClicked(e -> terminalScrollPane.requestFocus());
        terminalTextFlow.heightProperty().addListener((obs, oldVal, newVal) -> terminalScrollPane.setVvalue(1.0));

        Label tabStatusLabel = new Label("");
        tabStatusLabel.getStyleClass().add("status-bar-label");
        HBox statusBar = new HBox(tabStatusLabel);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(5, 20, 5, 20));

        SplitPane splitPane = new SplitPane();
        splitPane.getStyleClass().add("split-pane");
        splitPane.getItems().add(terminalScrollPane);

        VBox container = new VBox(header, splitPane, statusBar);
        container.getStyleClass().add("panel-container");
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        themeCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            terminalTextFlow.getStyleClass().removeAll("theme-matrix", "theme-cyberpunk", "theme-classic", "theme-ocean");
            switch (newVal) {
                case "Matrix"   -> terminalTextFlow.getStyleClass().add("theme-matrix");
                case "Cyberpunk" -> terminalTextFlow.getStyleClass().add("theme-cyberpunk");
                case "Classic"  -> terminalTextFlow.getStyleClass().add("theme-classic");
                case "Ocean"    -> terminalTextFlow.getStyleClass().add("theme-ocean");
            }
        });

        Tab tab = new Tab(profile.profileName(), container);

        SessionConnectionManager connectionManager = new SessionConnectionManager();
        final SshTerminalService[] terminalServiceRef = { null };
        final ClientSession[] sessionRef = { null };

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
                        default -> {}
                    }
                } else {
                    switch (event.getCode()) {
                        case ENTER      -> { event.consume(); svc.sendBytes(new byte[]{'\r'}); }
                        case BACK_SPACE -> { event.consume(); svc.sendBytes(new byte[]{0x7F}); }
                        case TAB        -> { event.consume(); svc.sendBytes(new byte[]{'\t'}); }
                        case UP         -> { event.consume(); svc.sendSequence("\033[A"); }
                        case DOWN       -> { event.consume(); svc.sendSequence("\033[B"); }
                        case RIGHT      -> { event.consume(); svc.sendSequence("\033[C"); }
                        case LEFT       -> { event.consume(); svc.sendSequence("\033[D"); }
                        case HOME       -> { event.consume(); svc.sendSequence("\033[H"); }
                        case END        -> { event.consume(); svc.sendSequence("\033[F"); }
                        case PAGE_UP    -> { event.consume(); svc.sendSequence("\033[5~"); }
                        case PAGE_DOWN  -> { event.consume(); svc.sendSequence("\033[6~"); }
                        case DELETE     -> { event.consume(); svc.sendSequence("\033[3~"); }
                        case ESCAPE     -> { event.consume(); svc.sendBytes(new byte[]{0x1B}); }
                        default -> {}
                    }
                }
            } catch (IOException e) {
                log.error("Key send error [{}]", event.getCode(), e);
            }
        });

        terminalScrollPane.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            event.consume();
            SshTerminalService svc = terminalServiceRef[0];
            if (svc == null) return;
            try {
                String ch = event.getCharacter();
                if (ch != null && !ch.isEmpty() && !ch.equals("\b") && !ch.equals("\r")
                        && !ch.equals("\n") && !ch.equals("\u007F") && !ch.equals("\t")
                        && !event.isControlDown()) {
                    svc.sendBytes(ch.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                log.error("KEY_TYPED error: {}", e.getMessage(), e);
            }
        });

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
                        FXMLLoader loader = new FXMLLoader(fxmlUrl, bundle);
                        sftpRootRef[0] = loader.load();
                        SftpController sftpController = loader.getController();
                        sftpController.initSession(session);
                    } catch (Exception ex) {
                        log.error("SFTP load error", ex);
                        sftpToggle.setSelected(false);
                        showAlert("SFTP Error", "Failed to load SFTP view:\n" + ex.getMessage());
                        return;
                    }
                }
            }
            updateSplitPane.run();
        });

        tab.setOnClosed(event -> {
            log.info("Closing tab: {}", profile.profileName());
            sftpRootRef[0] = null;
            if (terminalServiceRef[0] != null) {
                terminalServiceRef[0].disconnect();
                terminalServiceRef[0] = null;
            }
            if (sessionRef[0] != null) {
                connectionManager.disconnect(sessionRef[0]);
                sessionRef[0] = null;
            }
            log.info("Tab {} closed, resources released.", profile.profileName());
        });

        String host = profile.host();
        int port = profile.port();
        String user = profile.username();
        String password;
        try {
            password = vault.decryptPassword(profile.encryptedPassword());
        } catch (Exception e) {
            password = "";
            log.warn("Failed to decrypt password for profile: {}", profile.profileName());
        }
        final String passwd = password;

        AnsiTerminalParser ansiParser = new AnsiTerminalParser(terminalTextFlow);

        Text initText = new Text(MessageFormat.format(bundle.getString("tab.connecting") + " {0}@{1}:{2}\n", user, host, port));
        initText.getStyleClass().add("terminal-text");
        terminalTextFlow.getChildren().add(initText);

        connectionManager.connect(host, port, user, passwd, null)
                .thenAccept(session -> {
                    log.info("SSH session established for tab: {}", profile.profileName());
                    sessionRef[0] = session;
                    Platform.runLater(() -> {
                        terminalTextFlow.getChildren().clear();
                        titleLabel.setText(MessageFormat.format(bundle.getString("tab.terminal_title"), user, host));
                        tabStatusLabel.setText(host + ":" + port);
                        sftpToggle.setDisable(false);
                    });
                    try {
                        SshTerminalService svc = new SshTerminalService(session);
                        terminalServiceRef[0] = svc;
                        svc.startShell(rawBytes -> Platform.runLater(() -> ansiParser.processRawOutput(rawBytes)));
                    } catch (IOException e) {
                        log.error("Shell start error for tab: {}", profile.profileName(), e);
                        Platform.runLater(() -> showAlert("Shell Error", e.getMessage()));
                    }
                })
                .exceptionally(ex -> {
                    log.error("Connection error for tab: {}", profile.profileName(), ex);
                    Platform.runLater(() -> {
                        Text errText = new Text("ERROR: " + ex.getMessage() + "\n");
                        errText.getStyleClass().addAll("terminal-text", "ansi-31");
                        terminalTextFlow.getChildren().add(errText);
                        titleLabel.setText(MessageFormat.format(bundle.getString("tab.error_title"), profile.profileName()));
                        tabStatusLabel.setText(bundle.getString("tab.disconnected"));
                    });
                    return null;
                });

        return tab;
    }

    private static void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
