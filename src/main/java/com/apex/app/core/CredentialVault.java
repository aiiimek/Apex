package com.apex.app.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Menedżer poświadczeń (Credential Vault) odpowiedzialny za zapis,
 * odczyt oraz bezpieczne (symetryczne AES) szyfrowanie haseł profili SSH.
 */
public class CredentialVault {
    private static final Logger log = LoggerFactory.getLogger(CredentialVault.class);
    
    private static final String ALGORITHM = "AES";
    
    // Statyczny klucz na potrzeby MVP (128 bitów). 
    // W rozwiązaniu klasy Enterprise klucz ten powinien być wywodzony (PBKDF2) 
    // z hasła głównego użytkownika (Master Password).
    private static final byte[] STATIC_KEY = "ApexVaultSecret!".getBytes(StandardCharsets.UTF_8);
    
    private static final Path VAULT_FILE = Paths.get(System.getProperty("user.home"), ".apex", "apex_vault.dat");

    public CredentialVault() {
        try {
            if (!Files.exists(VAULT_FILE.getParent())) {
                Files.createDirectories(VAULT_FILE.getParent());
            }
        } catch (IOException e) {
            log.error("Nie można utworzyć struktury katalogów dla Vaulta", e);
        }
    }

    public String encryptPassword(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) return "";
        try {
            SecretKeySpec keySpec = new SecretKeySpec(STATIC_KEY, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encryptedBytes = cipher.doFinal(plainPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            log.error("Błąd kryptograficzny podczas szyfrowania hasła", e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decryptPassword(String encryptedPassword) {
        if (encryptedPassword == null || encryptedPassword.isEmpty()) return "";
        try {
            SecretKeySpec keySpec = new SecretKeySpec(STATIC_KEY, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedPassword);
            byte[] decryptedBytes = cipher.doFinal(decodedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Błąd kryptograficzny podczas deszyfrowania hasła", e);
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public void saveProfiles(List<HostProfile> profiles) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(VAULT_FILE.toFile()))) {
            oos.writeObject(profiles);
            log.info("Zapisano {} profili sprzętowych do magazynu {}", profiles.size(), VAULT_FILE);
        } catch (IOException e) {
            log.error("Błąd wejścia/wyjścia podczas zapisu profili", e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<HostProfile> loadProfiles() {
        if (!Files.exists(VAULT_FILE)) {
            return new ArrayList<>();
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(VAULT_FILE.toFile()))) {
            return (List<HostProfile>) ois.readObject();
        } catch (Exception e) {
            log.error("Krytyczny błąd odczytu struktury profili z pliku", e);
            return new ArrayList<>();
        }
    }
}
