package com.datacube.config;

import com.datacube.spi.model.ConnConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure save-time migration of readable legacy credentials to the current versioned format. */
public final class CredentialMigration {

    private CredentialMigration() {
    }

    public static List<ConnConfig> upgradeAll(List<ConnConfig> configs, CredentialCipher cipher) {
        Objects.requireNonNull(configs, "configs");
        Objects.requireNonNull(cipher, "cipher");
        List<ConnConfig> upgraded = new ArrayList<>(configs.size());
        for (ConnConfig config : configs) {
            String before = config.encryptedPassword();
            String after = cipher.upgrade(before);
            upgraded.add(Objects.equals(before, after) ? config : config.withEncryptedPassword(after));
        }
        return List.copyOf(upgraded);
    }
}
