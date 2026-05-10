package com.apex.app.core;

import java.io.Serializable;

/**
 * Rekord przechowujący dane zapisanej sesji (profilu) SSH.
 */
public record HostProfile(
        String profileName,
        String host,
        int port,
        String username,
        String encryptedPassword
) implements Serializable {

    @Override
    public String toString() {
        if (profileName != null && !profileName.isBlank()) {
            return profileName;
        }
        return username + "@" + host;
    }
}
