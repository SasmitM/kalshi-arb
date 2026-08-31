package com.kalshiarb.kalshi.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Loads an unencrypted PKCS#8 RSA private key from a PEM file.
 *
 * <p>Only PKCS#8 ({@code -----BEGIN PRIVATE KEY-----}) is supported, because that is the only
 * format {@link java.security.spec.PKCS8EncodedKeySpec} accepts and the JDK ships no PKCS#1
 * parser. {@code openssl genrsa} still emits PKCS#1 by default on many systems, so that
 * mistake is common enough to deserve a diagnostic that names the exact fix rather than an
 * {@code InvalidKeySpecException} stack trace.
 *
 * <p>No method here logs, prints, or includes key material in an exception message. Errors
 * report the file path and the detected header only.
 */
public final class PemKeyLoader {

    private static final String PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PKCS8_END = "-----END PRIVATE KEY-----";
    private static final String PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String ENCRYPTED_BEGIN = "-----BEGIN ENCRYPTED PRIVATE KEY-----";
    private static final String PUBLIC_BEGIN = "-----BEGIN PUBLIC KEY-----";

    /** Kalshi issues 2048-bit keys; anything shorter is a generation mistake worth catching. */
    private static final int MIN_MODULUS_BITS = 2048;

    private PemKeyLoader() {
    }

    /**
     * Reads and parses the key at {@code pemPath}.
     *
     * @throws KeyLoadException if the file is missing, unreadable, not PKCS#8, encrypted, not
     *                        RSA, or suspiciously small
     */
    public static RSAPrivateKey load(Path pemPath) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(pemPath);
        } catch (IOException e) {
            throw new KeyLoadException(
                    "cannot read private key at " + pemPath + ": " + e.getMessage()
                            + "\nSet KALSHI_PRIVATE_KEY, or place the key at "
                            + "~/.kalshi/kalshi-private.pem (chmod 600).", e);
        }

        String pem = new String(raw, StandardCharsets.UTF_8);
        try {
            return parse(pem, pemPath);
        } finally {
            // The decoded copy is unavoidable inside KeyFactory, but there is no reason to leave
            // our own buffer sitting in the heap for a GC cycle.
            Arrays.fill(raw, (byte) 0);
        }
    }

    /** Parses PEM text directly. Exposed for tests, which generate their own throwaway keys. */
    static RSAPrivateKey parse(String pem, Path source) {
        rejectWrongFormat(pem, source);

        int begin = pem.indexOf(PKCS8_BEGIN);
        int end = pem.indexOf(PKCS8_END);
        if (begin < 0 || end < 0 || end < begin) {
            throw new KeyLoadException(
                    "no PKCS#8 private key block found in " + source
                            + " (expected a line '" + PKCS8_BEGIN + "').");
        }

        String base64 = pem.substring(begin + PKCS8_BEGIN.length(), end)
                .replace("\r", "")
                .replace("\n", "")
                .replace(" ", "")
                .replace("\t", "");

        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new KeyLoadException(
                    "PKCS#8 block in " + source + " is not valid base64: " + e.getMessage(), e);
        }

        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(spec);
            if (!(key instanceof RSAPrivateKey rsa)) {
                throw new KeyLoadException(
                        "key in " + source + " is a " + key.getAlgorithm()
                                + " key; Kalshi requires RSA.");
            }
            int bits = rsa.getModulus().bitLength();
            if (bits < MIN_MODULUS_BITS) {
                throw new KeyLoadException(
                        "RSA key in " + source + " is " + bits + " bits; expected at least "
                                + MIN_MODULUS_BITS + ".");
            }
            return rsa;
        } catch (NoSuchAlgorithmException e) {
            throw new KeyLoadException("JVM has no RSA KeyFactory", e);
        } catch (InvalidKeySpecException e) {
            throw new KeyLoadException(
                    "PKCS#8 block in " + source + " did not parse as an RSA private key: "
                            + e.getMessage(), e);
        } finally {
            Arrays.fill(der, (byte) 0);
        }
    }

    private static void rejectWrongFormat(String pem, Path source) {
        if (pem.contains(PKCS1_BEGIN)) {
            throw new KeyLoadException(
                    "key at " + source + " is in PKCS#1 format, which the JDK cannot read.\n"
                            + "Convert it to PKCS#8 in place:\n"
                            + "  openssl pkcs8 -topk8 -nocrypt -in " + source
                            + " -out " + source + ".pk8\n"
                            + "  mv " + source + ".pk8 " + source + "\n"
                            + "  chmod 600 " + source + "\n"
                            + "A PKCS#8 file starts with '" + PKCS8_BEGIN + "'.");
        }
        if (pem.contains(ENCRYPTED_BEGIN)) {
            throw new KeyLoadException(
                    "key at " + source + " is passphrase-encrypted; this loader does not prompt.\n"
                            + "Decrypt it to an unencrypted PKCS#8 file:\n"
                            + "  openssl pkcs8 -topk8 -nocrypt -in " + source
                            + " -out " + source + ".pk8");
        }
        if (!pem.contains(PKCS8_BEGIN) && pem.contains(PUBLIC_BEGIN)) {
            throw new KeyLoadException(
                    "file at " + source + " is a PUBLIC key. Point KALSHI_PRIVATE_KEY at the "
                            + "private half of the pair (the file you registered the public key "
                            + "from).");
        }
    }

    /** Thrown for every failure mode above, so callers have one thing to catch. */
    public static final class KeyLoadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        KeyLoadException(String message) {
            super(message);
        }

        KeyLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
