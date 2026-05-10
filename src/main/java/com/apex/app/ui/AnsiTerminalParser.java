package com.apex.app.ui;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser sekwencji ucieczki ANSI do węzłów Text w JavaFX.
 * Obsługuje podstawowe kolory ANSI (30-37), resetowanie (0) 
 * oraz poprawne kasowanie znaków w TextFlow za pomocą Backspace (\b).
 */
public class AnsiTerminalParser {

    private static final Logger log = LoggerFactory.getLogger(AnsiTerminalParser.class);

    // Regex dopasowujący sekwencje ANSI, np. \u001B[31m lub \u001B[31;1m
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[([0-9;]*)([A-Za-z])");

    private final TextFlow textFlow;
    
    // Stan aktualnego stylu dla nowo tworzonych węzłów Text
    private String currentStyleClass = "ansi-0"; // Domyślny kolor
    private boolean isBold = false;

    public AnsiTerminalParser(TextFlow textFlow) {
        this.textFlow = textFlow;
    }

    /**
     * Główna metoda przyjmująca surowe bajty z serwera.
     * UWAGA: Musi być wywoływana w wątku JavaFX (Platform.runLater).
     */
    public void processRawOutput(byte[] rawBytes) {
        // Konwersja na string UTF-8 i ujednolicenie znaków końca linii
        String text = new String(rawBytes, StandardCharsets.UTF_8);
        text = text.replace("\r\n", "\n").replace("\r", "");

        // Najpierw musimy poradzić sobie z backspace'ami (często serwer wysyła \b \b)
        // Dla optymalizacji, przetwarzamy znaki sekwencyjnie.
        StringBuilder buffer = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\b' || c == '\u007F') {
                // Jeśli mamy coś w bieżącym buforze do dodania, skróćmy bufor.
                if (buffer.length() > 0) {
                    buffer.deleteCharAt(buffer.length() - 1);
                } else {
                    // Bufor pusty - musimy fizycznie usunąć znak z TextFlow
                    removeLastCharacterFromFlow();
                }
            } else if (c == '\u001B') {
                // Jeśli napotkaliśmy ESC, wyrzucamy dotychczasowy bufor na ekran
                flushBuffer(buffer);

                // Próbujemy wyłuskać sekwencję ANSI zaczynającą się od tego ESC
                Matcher matcher = ANSI_PATTERN.matcher(text.substring(i));
                if (matcher.lookingAt()) {
                    String codes = matcher.group(1);
                    String command = matcher.group(2);

                    if ("m".equals(command)) { // Komenda zmiany koloru/stylu
                        updateStyleState(codes);
                    } else {
                        // Inne komendy (np. przesunięcia kursora) - w uproszczonym terminalu ignorujemy
                        // log.debug("Ignorowana komenda ANSI: {}", matcher.group());
                    }

                    // Przeskakujemy przetworzoną sekwencję (minus 1, bo pętla zaraz zrobi i++)
                    i += matcher.end() - 1;
                } else {
                    // Jeśli to jest ESC, ale nie pasuje do naszego regexa ANSI (np. sekwencja tytułu okna OSC)
                    // Ignorujemy ten znak ESC. Alternatywnie, musielibyśmy napisać pełny parser OSC.
                    int bellIndex = text.indexOf('\u0007', i);
                    int stIndex = text.indexOf("\u001B\\", i);
                    
                    if (bellIndex > i && text.substring(i + 1).startsWith("]")) {
                         // Ignoruj sekwencje OSC (Operating System Command) np. tytuł okna kończące się BEL
                         i = bellIndex;
                    } else if (stIndex > i && text.substring(i + 1).startsWith("]")) {
                         // Ignoruj sekwencje OSC kończące się ESC \
                         i = stIndex + 1;
                    }
                }
            } else {
                buffer.append(c);
            }
        }

        // Zrzut reszty znaków
        flushBuffer(buffer);
    }

    /**
     * Tworzy nowy węzeł Text z zawartością bufora i aplikuje bieżące style.
     */
    private void flushBuffer(StringBuilder buffer) {
        if (buffer.length() == 0) {
            return;
        }

        String content = buffer.toString();
        buffer.setLength(0); // Wyczyść bufor natychmiast dla kolejnych danych

        Platform.runLater(() -> {
            Text textNode = new Text(content);
            textNode.getStyleClass().add("terminal-text");
            if (currentStyleClass != null && !currentStyleClass.isEmpty()) {
                textNode.getStyleClass().add(currentStyleClass);
            }
            if (isBold) {
                textNode.getStyleClass().add("ansi-bold");
            }
            
            // Jeśli to domyślny reset (ansi-0) ustawiamy jawny ratunkowy kolor jako fallback
            if ("ansi-0".equals(currentStyleClass)) {
                textNode.setFill(javafx.scene.paint.Color.WHITE);
            }

            textFlow.getChildren().add(textNode);
        });
    }

    /**
     * Usuwa fizycznie ostatni znak z węzłów w TextFlow.
     */
    private void removeLastCharacterFromFlow() {
        Platform.runLater(() -> {
            ObservableList<Node> children = textFlow.getChildren();
            if (children.isEmpty()) {
                return;
            }

            // Szukaj od końca ostatniego węzła Text, który nie jest pusty
            for (int i = children.size() - 1; i >= 0; i--) {
                Node node = children.get(i);
                if (node instanceof Text textNode) {
                    String content = textNode.getText();
                    if (content.length() > 0) {
                        if (content.length() == 1) {
                            children.remove(i);
                        } else {
                            textNode.setText(content.substring(0, content.length() - 1));
                        }
                        return; // Udało się usunąć 1 znak
                    }
                }
            }
        });
    }

    /**
     * Aktualizuje bieżący stan stylu na podstawie kodów (rozdzielonych średnikiem).
     */
    private void updateStyleState(String codesString) {
        if (codesString == null || codesString.isEmpty()) {
            currentStyleClass = "ansi-0";
            isBold = false;
            return;
        }

        String[] codes = codesString.split(";");
        for (String codeStr : codes) {
            if (codeStr.isEmpty()) continue;
            try {
                int code = Integer.parseInt(codeStr);
                
                if (code == 0) {
                    currentStyleClass = "ansi-0";
                    isBold = false;
                } else if (code == 1) {
                    isBold = true;
                } else if (code >= 30 && code <= 37) {
                    currentStyleClass = "ansi-" + code;
                } else if (code >= 90 && code <= 97) {
                    // Jasne (bright) kolory traktujemy tak samo lub jako zwykłe + bold (w CSS dodamy .ansi-90 itp. jako jasne warianty)
                    currentStyleClass = "ansi-" + code;
                }
            } catch (NumberFormatException ignored) {
                // Niepoprawny kod, ignorujemy
            }
        }
    }
}
