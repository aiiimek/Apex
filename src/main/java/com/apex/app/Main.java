package com.apex.app;

/**
 * Klasa 'wrapper' ułatwiająca start aplikacji JavaFX bez wymogu konfiguracji
 * parametrów maszyny wirtualnej (VM args) i systemu modułów w IDE.
 */
public class Main {
    public static void main(String[] args) {
        ApexApplication.main(args);
    }
}
