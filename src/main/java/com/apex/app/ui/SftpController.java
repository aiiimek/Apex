package com.apex.app.ui;

import com.apex.app.core.SftpBrowserService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * Kontroler dedykowanego okna przeglądarki SFTP.
 *
 * Architektura transferów:
 * Każda operacja (upload/download) tworzy wewnętrzną klasę FileTransferTask
 * dziedziczącą po javafx.concurrent.Task<Void>. Task uruchamiany jest w osobnym
 * wątku, dzięki czemu JavaFX UI pozostaje responsywne podczas długich
 * transferów.
 * Postęp (0.0–1.0) jest propagowany do ProgressBar przez updateProgress().
 */
public class SftpController {
    private static final Logger log = LoggerFactory.getLogger(SftpController.class);

    // ─── Widoki FXML ──────────────────────────────────────────────────────
    @FXML
    private ListView<String> fileListView;
    @FXML
    private Label currentPathLabel;
    @FXML
    private Label statusLabel;
    @FXML
    private Label transferStatusLabel;
    @FXML
    private ProgressBar transferProgressBar;

    /** Serwis SFTP – inicjalizowany metodą initSession() z MainController. */
    private SftpBrowserService sftpService;

    // ══════════════════════════════════════════════════════════════════════
    // INICJALIZACJA
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Inicjalizuje kontroler z aktywną sesją SSH.
     * Musi być wywołana raz po FXMLLoader.load(), przed show() okna.
     *
     * @param session Aktywna sesja SSH przekazana z MainController.
     */
    public void initSession(ClientSession session) {
        this.sftpService = new SftpBrowserService(session);
        setupInteractions();
        refreshList();
    }

    // ══════════════════════════════════════════════════════════════════════
    // KONFIGURACJA ZDARZEŃ UI
    // ══════════════════════════════════════════════════════════════════════

    private void setupInteractions() {
        // ── Kliknięcia na liście plików ───────────────────────────────────
        fileListView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                String selected = fileListView.getSelectionModel().getSelectedItem();
                if (selected == null)
                    return;

                if (selected.startsWith("[DIR] ")) {
                    // Podwójne kliknięcie na katalogu → nawiguj do środka
                    navigateTo(selected.substring(6));
                } else if (selected.startsWith("[FILE] ")) {
                    // Podwójne kliknięcie na pliku → pobierz (jak w WinSCP)
                    handleDownload();
                }
            }
        });

        // ── Menu kontekstowe (prawy przycisk) ────────────────────────────
        ContextMenu contextMenu = new ContextMenu();

        MenuItem openItem = new MenuItem("📂  Wejdź / Pobierz");
        openItem.setOnAction(e -> {
            String sel = fileListView.getSelectionModel().getSelectedItem();
            if (sel == null)
                return;
            if (sel.startsWith("[DIR] "))
                navigateTo(sel.substring(6));
            else
                handleDownload();
        });

        MenuItem uploadItem = new MenuItem("↑  Upload pliku tutaj");
        uploadItem.setOnAction(e -> handleUpload());

        MenuItem downloadItem = new MenuItem("↓  Pobierz na dysk");
        downloadItem.setOnAction(e -> handleDownload());

        MenuItem separator = new MenuItem();
        separator.setDisable(true);

        MenuItem deleteItem = new MenuItem("🗑  Usuń");
        deleteItem.setOnAction(e -> handleDelete());

        contextMenu.getItems().addAll(openItem, uploadItem, downloadItem,
                new javafx.scene.control.SeparatorMenuItem(), deleteItem);
        fileListView.setContextMenu(contextMenu);

        // ── Drag & Drop: Upload przez przeciągnięcie pliku z Windowsa ────
        fileListView.setOnDragOver(event -> {
            if (event.getGestureSource() != fileListView && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        // Wizualna informacja zwrotna o akceptowaniu drop
        fileListView.setOnDragEntered(event -> {
            if (event.getDragboard().hasFiles()) {
                fileListView.getStyleClass().add("drag-over");
            }
        });
        fileListView.setOnDragExited(event -> {
            fileListView.getStyleClass().remove("drag-over");
        });

        fileListView.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasFiles()) {
                handleUploadFiles(db.getFiles());
                event.setDropCompleted(true);
            } else {
                event.setDropCompleted(false);
            }
            event.consume();
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // NAWIGACJA
    // ══════════════════════════════════════════════════════════════════════

    private void navigateTo(String dirName) {
        if (sftpService == null)
            return;
        setStatus("Nawiguję do: " + dirName);
        new Thread(() -> {
            try {
                sftpService.changeDirectory(dirName);
                refreshList();
            } catch (Exception e) {
                log.error("Błąd zmiany katalogu: {}", dirName, e);
                Platform.runLater(
                        () -> showError("Błąd nawigacji", "Nie można wejść do: " + dirName + "\n" + e.getMessage()));
            }
        }, "Apex-SFTP-Navigate").start();
    }

    private void refreshList() {
        if (sftpService == null)
            return;
        new Thread(() -> {
            try {
                List<String> files = sftpService.listCurrentDirectory();
                Platform.runLater(() -> {
                    fileListView.setItems(FXCollections.observableArrayList(files));
                    currentPathLabel.setText(sftpService.getCurrentPath());
                    setStatus("Elementy: " + files.size());
                });
            } catch (Exception e) {
                log.error("Błąd listowania katalogu", e);
                Platform.runLater(() -> setStatus("Błąd: " + e.getMessage()));
            }
        }, "Apex-SFTP-List").start();
    }

    @FXML
    private void handleGoUp() {
        navigateTo("..");
    }

    @FXML
    private void handleRefresh() {
        setStatus("Odświeżam...");
        refreshList();
    }

    // UPLOAD (dysk → serwer)

    @FXML
    private void handleUpload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Wybierz pliki do wysłania na serwer");
        List<File> files = chooser.showOpenMultipleDialog(fileListView.getScene().getWindow());
        if (files != null && !files.isEmpty()) {
            handleUploadFiles(files);
        }
    }

    private void handleUploadFiles(List<File> files) {
        if (files == null || files.isEmpty() || sftpService == null)
            return;

        // Filtrujemy tylko pliki (bez katalogów) — katalogi wymagają rekurencji
        List<File> regularFiles = files.stream().filter(File::isFile).toList();
        if (regularFiles.isEmpty()) {
            showError("Upload", "Wybrano wyłącznie katalogi. Obsługiwane są tylko pliki.");
            return;
        }

        FileTransferTask uploadTask = new FileTransferTask("Upload") {
            @Override
            protected Void call() throws Exception {
                for (int i = 0; i < regularFiles.size(); i++) {
                    File file = regularFiles.get(i);
                    updateStatusMsg("Wysyłam: " + file.getName() + "  (" + (i + 1) + "/" + regularFiles.size() + ")");
                    log.info("Upload: {} → {}/{}", file.getAbsolutePath(),
                            sftpService.getCurrentPath(), file.getName());
                    sftpService.uploadFile(file);
                    updateProgress(i + 1, regularFiles.size());
                }
                return null;
            }
        };

        uploadTask.setOnSucceeded(e -> {
            hideProgressBar();
            setTransferStatus("✓ Upload zakończony — wysłano " + regularFiles.size() + " plik(ów).");
            refreshList();
        });
        uploadTask.setOnFailed(e -> {
            hideProgressBar();
            Throwable ex = uploadTask.getException();
            setTransferStatus(" Błąd uploadu.");
            showError("Błąd Upload", ex != null ? ex.getMessage() : "Nieznany błąd.");
        });

        runTask(uploadTask);
    }

    // ══════════════════════════════════════════════════════════════════════
    // DOWNLOAD (serwer → dysk)
    // ══════════════════════════════════════════════════════════════════════

    @FXML
    private void handleDownload() {
        String selected = fileListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Download", "Nie wybrano żadnego elementu z listy.");
            return;
        }
        if (!selected.startsWith("[FILE] ")) {
            showError("Download", "Wybierz plik (nie katalog), aby go pobrać.");
            return;
        }

        String fileName = selected.substring(7); // usuń prefix "[FILE] "

        // DirectoryChooser: użytkownik wybiera katalog docelowy
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Wybierz folder docelowy dla: " + fileName);
        File targetDir = dirChooser.showDialog(fileListView.getScene().getWindow());
        if (targetDir == null)
            return; // Użytkownik anulował

        File targetFile = new File(targetDir, fileName);

        // Jeśli plik już istnieje — zapytaj o nadpisanie
        if (targetFile.exists()) {
            Alert overwrite = new Alert(Alert.AlertType.CONFIRMATION);
            overwrite.setTitle("Plik już istnieje");
            overwrite.setHeaderText("Plik \"" + fileName + "\" istnieje w wybranym folderze.");
            overwrite.setContentText("Nadpisać istniejący plik?");
            Optional<ButtonType> response = overwrite.showAndWait();
            if (response.isEmpty() || response.get() != ButtonType.OK)
                return;
        }

        FileTransferTask downloadTask = new FileTransferTask("Download") {
            @Override
            protected Void call() throws Exception {
                updateStatusMsg("Pobieranie: " + fileName);
                log.info("Download: {}/{} → {}", sftpService.getCurrentPath(),
                        fileName, targetFile.getAbsolutePath());
                sftpService.downloadFile(fileName, targetFile);
                return null;
            }
        };

        downloadTask.setOnSucceeded(e -> {
            hideProgressBar();
            setTransferStatus("✓ Pobrano: " + fileName + " → " + targetDir.getName());
        });
        downloadTask.setOnFailed(e -> {
            hideProgressBar();
            Throwable ex = downloadTask.getException();
            setTransferStatus("✗ Błąd pobierania.");
            showError("Błąd Download", ex != null ? ex.getMessage() : "Nieznany błąd.");
        });

        runTask(downloadTask);
    }

    // ══════════════════════════════════════════════════════════════════════
    // USUWANIE
    // ══════════════════════════════════════════════════════════════════════

    @FXML
    private void handleDelete() {
        String selected = fileListView.getSelectionModel().getSelectedItem();
        if (selected == null)
            return;
        // Nie pozwól usunąć katalogu nadrzędnego
        if (selected.equals("[DIR] ..")) {
            showError("Usuń", "Nie można usunąć katalogu nadrzędnego (..).");
            return;
        }

        // Wyodrębnij czystą nazwę z prefiksu
        String displayName = selected;
        String itemName = selected.startsWith("[DIR] ") ? selected.substring(6)
                : selected.startsWith("[FILE] ") ? selected.substring(7)
                        : selected;

        // Alert z potwierdzeniem — wyraźnie informuje o nieodwracalności
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Potwierdzenie usunięcia");
        confirm.setHeaderText("Czy na pewno chcesz usunąć:\n" + displayName);
        confirm.setContentText("⚠  Tej operacji nie można cofnąć!\n\n" +
                "Serwer: " + sftpService.getCurrentPath() + "/" + itemName);
        confirm.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.YES)
            return;

        new Thread(() -> {
            try {
                log.info("Usuwanie: {}/{}", sftpService.getCurrentPath(), itemName);
                sftpService.deleteItem(itemName);
                Platform.runLater(() -> {
                    setStatus("Usunięto: " + itemName);
                    refreshList();
                });
            } catch (Exception e) {
                log.error("Błąd usuwania: {}", itemName, e);
                Platform.runLater(() -> showError("Błąd usuwania", e.getMessage()));
            }
        }, "Apex-SFTP-Delete").start();
    }

    // ══════════════════════════════════════════════════════════════════════
    // FileTransferTask – klasa wewnętrzna bazowa dla transferów
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Bazowa klasa asynchronicznego zadania transferu pliku.
     *
     * Dziedziczy po Task<Void> (javafx.concurrent). Podklasy implementują
     * metodę call() gdzie wykonują właściwy transfer. Klasa udostępnia
     * wygodną metodę updateStatusMsg() do aktualizacji UI ze strumienia IO.
     *
     * Użycie:
     * FileTransferTask task = new FileTransferTask("Upload") { ... };
     * runTask(task);
     */
    private abstract class FileTransferTask extends Task<Void> {
        private final String operationName;

        protected FileTransferTask(String operationName) {
            this.operationName = operationName;
        }

        /**
         * Aktualizuje etykietę statusu transferu z wątku IO.
         * Bezpieczna do wywołania spoza wątku UI.
         */
        protected void updateStatusMsg(String message) {
            Platform.runLater(() -> setTransferStatus(message));
        }

        @Override
        protected void running() {
            super.running();
            Platform.runLater(() -> {
                showProgressBar();
                setTransferStatus(operationName + " w toku...");
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // POMOCNICZE METODY UI
    // ══════════════════════════════════════════════════════════════════════

    /** Uruchamia Task w tle i wiąże ProgressBar z jego postępem. */
    private void runTask(FileTransferTask task) {
        transferProgressBar.progressProperty().bind(task.progressProperty());
        Thread thread = new Thread(task, "Apex-SFTP-Transfer");
        thread.setDaemon(true);
        thread.start();
    }

    private void showProgressBar() {
        transferProgressBar.setVisible(true);
        transferProgressBar.setManaged(true);
    }

    private void hideProgressBar() {
        Platform.runLater(() -> {
            transferProgressBar.progressProperty().unbind();
            transferProgressBar.setProgress(0);
            transferProgressBar.setVisible(false);
            transferProgressBar.setManaged(false);
        });
    }

    private void setStatus(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }

    private void setTransferStatus(String msg) {
        Platform.runLater(() -> transferStatusLabel.setText(msg));
    }

    private void showError(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content != null ? content : "Nieznany błąd.");
            alert.showAndWait();
        });
    }
}
