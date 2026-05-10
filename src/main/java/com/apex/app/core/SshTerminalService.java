package com.apex.app.core;

import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Serwis obsługujący interaktywny terminal (Shell) w trybie "character streaming".
 *
 * Architektura I/O:
 *  - INPUT  (UI -> Server):  Każde wciśnięcie klawisza przez użytkownika jest natychmiast
 *                             wysyłane jako surowe bajty przez PipedOutputStream -> channelIn.
 *  - OUTPUT (Server -> UI):  Dane z serwera (echo, wyniki komend, prompty) są odczytywane
 *                             asynchronicznie i przekazywane jako bloki bajtów do callbacku
 *                             renderującego widok terminala.
 *
 * Dzięki tej architekturze możliwe jest poprawne działanie:
 *  - Haseł ukrytych (sudo, su) - serwer blokuje echo i użytkownik nie widzi wpisywanego hasła.
 *  - Edytorów terminalowych (nano, vim) - poprawna obsługa klawiszy specjalnych.
 *  - Autouzupełniania (TAB) - serwer przetwarza żądanie i odsyła uzupełnienie.
 *  - Historii komend (Strzałki UP/DOWN) - shell odsyła odpowiednią komendę.
 */
public class SshTerminalService {
    private static final Logger log = LoggerFactory.getLogger(SshTerminalService.class);

    /** Rozmiar bufora do odczytu danych z serwera. 8KB pozwala na odczyt dużych pakietów. */
    private static final int READ_BUFFER_SIZE = 8192;

    /** Rozmiar wewnętrznego bufora Piped strumienia wejściowego. */
    private static final int PIPE_BUFFER_SIZE = 65536;

    private final ClientSession session;
    private ChannelShell channel;

    /** Strumień do ZAPISU danych (klawiszy) od użytkownika do serwera. */
    private PipedOutputStream commandWriter;

    /** Strumień do ODCZYTU danych (wyjścia terminala) z serwera do callbacku UI. */
    private PipedInputStream responseReader;

    /** Dedykowany wątek do asynchronicznego odczytu strumienia z serwera. */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ApexTerminal-IO-Reader");
        t.setDaemon(true);
        return t;
    });

    public SshTerminalService(ClientSession session) {
        this.session = session;
    }

    /**
     * Otwiera kanał powłoki (ChannelShell) i inicjuje asynchroniczne nasłuchiwanie na odpowiedzi.
     *
     * Metoda zgłasza żądanie otwarcia kanału do serwera SSH.
     * Po pomyślnym nawiązaniu połączenia uruchamiany jest wątek odczytu.
     *
     * @param onOutputReceived Callback wywoływany za każdym razem, gdy serwer wyśle dane.
     *                         Wywoływany z wątku IO - aktualizacje UI muszą korzystać z Platform.runLater().
     * @throws IOException Gdy wystąpi błąd I/O podczas konfiguracji lub otwierania kanału.
     */
    public void startShell(Consumer<byte[]> onOutputReceived) throws IOException {
        log.info("Inicjalizacja kanału ChannelShell...");
        channel = session.createShellChannel();
        channel.setPtyType("xterm");
        channel.setPtyColumns(120);
        channel.setPtyLines(40);

        // Strumień IN (UI -> Serwer): PipedOutputStream zapisuje dane, PipedInputStream czyta je do kanału.
        commandWriter = new PipedOutputStream();
        PipedInputStream channelIn = new PipedInputStream(commandWriter, PIPE_BUFFER_SIZE);
        channel.setIn(channelIn);

        // Strumień OUT (Serwer -> UI): PipedOutputStream zapisuje dane z serwera,
        // PipedInputStream czyta je w asynchronicznym wątku i przekazuje do callbacku.
        responseReader = new PipedInputStream(PIPE_BUFFER_SIZE);
        PipedOutputStream channelOut = new PipedOutputStream(responseReader);
        channel.setOut(channelOut);
        channel.setErr(channelOut); // Błędy (stderr) przekierowane do tego samego strumienia.

        // Otwarcie kanału z limitem czasu 15 sekund - wystarczający dla wolnych połączeń.
        channel.open().verify(Duration.ofSeconds(15));
        log.info("Kanał ChannelShell otwarty pomyślnie.");

        // Uruchomienie asynchronicznego wątku odczytującego dane z serwera.
        startReading(onOutputReceived);
    }

    /**
     * Główna pętla IO: Czyta surowe bajty ze strumienia serwera i przekazuje je do callbacku.
     *
     * Pętla działa do momentu zamknięcia połączenia (read() zwróci -1)
     * lub przerwania wątku (executor.shutdownNow()).
     *
     * @param onOutputReceived Callback przyjmujący tablicę bajtów danych z serwera.
     */
    private void startReading(Consumer<byte[]> onOutputReceived) {
        ioExecutor.submit(() -> {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int bytesRead;
            log.debug("Wątek IO: Rozpoczęcie nasłuchiwania na dane z serwera.");
            try {
                while ((bytesRead = responseReader.read(buffer)) != -1) {
                    // Kopiujemy tylko odczytane dane, aby uniknąć przekazywania pustych bajtów.
                    byte[] chunk = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                    onOutputReceived.accept(chunk);
                }
                log.info("Wątek IO: Serwer zamknął strumień - sesja zakończona.");
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.warn("Wątek IO: Błąd odczytu strumienia (możliwe rozłączenie): {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Wysyła surowe bajty (np. z wciśniętych klawiszy) bezpośrednio do strumienia wejściowego serwera.
     *
     * Ta metoda jest fundamentem emulacji terminala w trybie "character streaming".
     * Każde wywołanie tej metody jest natychmiast przesyłane do powłoki serwera bez buforowania.
     *
     * @param data Tablica bajtów do wysłania (np. `new byte[]{'\r'}` dla ENTER).
     * @throws IOException Gdy strumień jest zamknięty lub nastąpi błąd I/O.
     */
    public void sendBytes(byte[] data) throws IOException {
        if (commandWriter != null && data != null && data.length > 0) {
            commandWriter.write(data);
            commandWriter.flush();
        }
    }

    /**
     * Wygodna metoda do wysyłania sekwencji znaków zakodowanych jako UTF-8.
     * Używana głównie do wysyłania sekwencji escape (np. strzałek, kodów specjalnych).
     *
     * @param sequence Ciąg znaków do wysłania (np. "\033[A" dla strzałki w górę).
     * @throws IOException Gdy nastąpi błąd I/O.
     */
    public void sendSequence(String sequence) throws IOException {
        sendBytes(sequence.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Zamyka kanał terminala oraz wszystkie powiązane zasoby I/O.
     * Metoda jest bezpieczna do wywołania wielokrotnego.
     */
    public void disconnect() {
        log.info("Zamykanie usługi terminala SSH...");
        ioExecutor.shutdownNow();
        try {
            if (commandWriter != null) {
                commandWriter.close();
                commandWriter = null;
            }
            if (responseReader != null) {
                responseReader.close();
                responseReader = null;
            }
            if (channel != null && channel.isOpen()) {
                channel.close(false);
                channel = null;
            }
        } catch (IOException e) {
            log.error("Błąd podczas zamykania zasobów terminala", e);
        }
        log.info("Usługa terminala SSH zamknięta.");
    }
}
