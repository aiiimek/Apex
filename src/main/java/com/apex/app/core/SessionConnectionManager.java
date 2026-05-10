package com.apex.app.core;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Zarządca cyklu życia połączenia SSH.
 *
 * ZMIANA ARCHITEKTURALNA (Krok 14 – Multi-Tab):
 * Klasa NIE jest już Singletonem. Każda zakładka (Tab) tworzy własną instancję
 * SessionConnectionManager z dedykowanym SshClient. Dzięki temu zamknięcie
 * jednej zakładki nie wpływa na pozostałe połączenia.
 */
public class SessionConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionConnectionManager.class);
    private final SshClient client;

    /**
     * Tworzy nowy, niezależny klient SSH.
     * Każda instancja posiada własny SshClient z osobnym cyklem życia.
     */
    public SessionConnectionManager() {
        log.info("Inicjalizacja nowego SshClient (instancja per-tab)...");
        client = SshClient.setUpDefaultClient();

        // TODO: Na potrzeby MVP akceptujemy wszystkie hosty. W docelowej wersji 
        // należy zaimplementować weryfikację pliku known_hosts.
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);

        // Konfiguracja dla Azure/Cloud - keep-alive, zapobiegające ubijaniu martwych sesji przez load balancery
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofSeconds(15));

        client.start();
        log.info("SshClient uruchomiony pomyślnie.");
    }

    /**
     * Nawiązuje asynchroniczne połączenie SSH.
     *
     * @param host           Adres serwera
     * @param port           Port SSH
     * @param username       Nazwa użytkownika
     * @param password       Opcjonalne hasło
     * @param privateKeyPath Opcjonalna ścieżka do klucza prywatnego (Identity File)
     * @return CompletableFuture zawierające ustanowioną sesję ClientSession
     */
    public CompletableFuture<ClientSession> connect(String host, int port, String username, String password, Path privateKeyPath) {
        CompletableFuture<ClientSession> future = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                log.info("Próba nawiązania połączenia z {}@{}:{}", username, host, port);
                
                // Nawiązujemy sesję z limitem 15 sekund
                ClientSession session = client.connect(username, host, port)
                        .verify(Duration.ofSeconds(15))
                        .getSession();

                // Konfiguracja autoryzacji kluczem (obsługa m.in. RSA i ED25519)
                if (privateKeyPath != null) {
                    log.debug("Używam klucza prywatnego z: {}", privateKeyPath);
                    FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(privateKeyPath);
                    session.setKeyIdentityProvider(keyPairProvider);
                } 
                
                // Konfiguracja autoryzacji hasłem
                if (password != null && !password.isEmpty()) {
                    log.debug("Ustawiam autoryzację za pomocą hasła.");
                    session.addPasswordIdentity(password);
                }

                if (privateKeyPath == null && (password == null || password.isEmpty())) {
                    throw new IllegalArgumentException("Nie podano ani hasła, ani klucza prywatnego.");
                }

                // Autoryzacja i weryfikacja
                session.auth().verify(Duration.ofSeconds(15));
                log.info("Autoryzacja zakończona sukcesem dla {}@{}:{}", username, host, port);
                
                future.complete(session);
            } catch (Exception e) {
                log.error("Błąd podczas łączenia z {}@{}:{} - {}", username, host, port, e.getMessage());
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Bezpiecznie zamyka przekazaną sesję i klienta SSH tej instancji.
     * Każda instancja zarządza własnym klientem – zamknięcie nie wpływa na inne taby.
     * 
     * @param session Aktywna sesja do zamknięcia
     */
    public void disconnect(ClientSession session) {
        log.info("Zamykanie sesji i klienta SSH...");
        try {
            if (session != null && session.isOpen()) {
                session.close(false);
            }
            if (client != null && client.isStarted()) {
                client.stop();
            }
            log.info("Rozłączono pomyślnie.");
        } catch (Exception e) {
            log.error("Błąd podczas rozłączania", e);
        }
    }

    /**
     * Zamyka SshClient i zwalnia zasoby (na wypadek zamknięcia samej aplikacji).
     */
    public void shutdown() {
        log.info("Zamykanie SshClient...");
        if (client != null && client.isStarted()) {
            client.stop();
        }
    }
}
