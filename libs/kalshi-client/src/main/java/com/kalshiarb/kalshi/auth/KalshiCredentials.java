package com.kalshiarb.kalshi.auth;

import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.util.Objects;

/**
 * Resolved Kalshi API credentials: the Key ID plus the loaded private key.
 *
 * <p>Both are read from the environment and never from the repository. The key id is a UUID
 * and merely identifying; the private key is generated locally, registered by public half only,
 * and never leaves the machine. Neither is ever written into source, tests, or fixtures.
 *
 * @param accessKeyId value of {@code KALSHI_KEY_ID}
 * @param privateKey  key parsed from {@code KALSHI_PRIVATE_KEY} (default
 *                    {@code ~/.kalshi/kalshi-private.pem})
 */
public record KalshiCredentials(String accessKeyId, RSAPrivateKey privateKey) {

    public static final String ENV_KEY_ID = "KALSHI_KEY_ID";
    public static final String ENV_PRIVATE_KEY = "KALSHI_PRIVATE_KEY";
    public static final String DEFAULT_KEY_PATH = ".kalshi/kalshi-private.pem";

    public KalshiCredentials {
        Objects.requireNonNull(accessKeyId, "accessKeyId");
        Objects.requireNonNull(privateKey, "privateKey");
    }

    /**
     * Reads credentials from the environment.
     *
     * @throws PemKeyLoader.KeyLoadException if the key id is unset or the key cannot be loaded
     */
    public static KalshiCredentials fromEnvironment() {
        String keyId = System.getenv(ENV_KEY_ID);
        if (keyId == null || keyId.isBlank()) {
            throw new PemKeyLoader.KeyLoadException(
                    ENV_KEY_ID + " is not set. Export your Kalshi API Key ID:\n"
                            + "  export " + ENV_KEY_ID + "=<your-key-id-uuid>");
        }
        return new KalshiCredentials(keyId.trim(), PemKeyLoader.load(privateKeyPath()));
    }

    /** {@code KALSHI_PRIVATE_KEY} if set, else {@code ~/.kalshi/kalshi-private.pem}. */
    public static Path privateKeyPath() {
        String override = System.getenv(ENV_PRIVATE_KEY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return Path.of(System.getProperty("user.home")).resolve(DEFAULT_KEY_PATH);
    }

    @Override
    public String toString() {
        return "KalshiCredentials[accessKeyId=" + accessKeyId.substring(
                0, Math.min(8, accessKeyId.length())) + "..., privateKey=<loaded>]";
    }
}
