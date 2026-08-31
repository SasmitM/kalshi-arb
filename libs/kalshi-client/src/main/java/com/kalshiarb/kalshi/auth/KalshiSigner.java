package com.kalshiarb.kalshi.auth;

import com.kalshiarb.common.Clock;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;
import java.util.Objects;

/**
 * Produces Kalshi's three authentication headers by signing a canonical request string with
 * RSA-PSS.
 *
 * <p>Kalshi has no login endpoint and issues no session token. Every request is authenticated
 * on its own, by signing:
 *
 * <pre>{@code   timestampMillis + METHOD + path}</pre>
 *
 * concatenated with no separators, e.g.
 * {@code 1756658400000GET/trade-api/v2/portfolio/balance}. Three rules that are easy to get
 * wrong and each produce an indistinguishable 401:
 *
 * <ul>
 *   <li>the timestamp is <b>milliseconds</b>, not seconds;
 *   <li>the path <b>includes</b> the {@code /trade-api/v2} prefix;
 *   <li>the path <b>excludes</b> the query string, so two requests differing only in query
 *       parameters sign the same message.
 * </ul>
 *
 * <p>The signature is RSASSA-PSS over SHA-256, with MGF1/SHA-256 and a salt length of 32 bytes
 * — the SHA-256 digest length. PSS salts randomly, so signing the same message twice yields
 * different bytes; that is correct and is why the unit test is a sign-then-verify round trip
 * rather than a comparison against a recorded value.
 *
 * <p>Instances are immutable and safe to share. {@link Signature} objects are stateful and are
 * therefore created per call rather than cached in a field.
 */
public final class KalshiSigner {

    /** JCA name for RSASSA-PSS as a first-class algorithm (not "SHA256withRSA"). */
    private static final String ALGORITHM = "RSASSA-PSS";

    /**
     * SHA-256 digest, MGF1 with SHA-256, 32-byte salt, trailer field 1.
     *
     * <p>The salt length is the parameter Kalshi is strict about and the one most third-party
     * examples get wrong; 32 matches the shell reference implementation's
     * {@code -sigopt rsa_pss_saltlen:32}.
     */
    private static final PSSParameterSpec PSS_PARAMS = new PSSParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);

    private final String accessKeyId;
    private final RSAPrivateKey privateKey;
    private final Clock clock;

    public KalshiSigner(String accessKeyId, RSAPrivateKey privateKey, Clock clock) {
        this.accessKeyId = Objects.requireNonNull(accessKeyId, "accessKeyId");
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (accessKeyId.isBlank()) {
            throw new IllegalArgumentException("accessKeyId is blank");
        }
    }

    public static KalshiSigner from(KalshiCredentials credentials, Clock clock) {
        return new KalshiSigner(credentials.accessKeyId(), credentials.privateKey(), clock);
    }

    /**
     * Signs a request and returns the three header values.
     *
     * @param method HTTP method; must already be uppercase
     * @param path   request path including {@code /trade-api/v2} and excluding any query string
     */
    public AuthHeaders sign(String method, String path) {
        long timestamp = clock.currentTimeMillis();
        String message = canonicalMessage(timestamp, method, path);
        return new AuthHeaders(accessKeyId, Long.toString(timestamp), signMessage(message));
    }

    /**
     * Builds the canonical string that gets signed.
     *
     * <p>Validates rather than repairs. Lowercasing a method or stripping a query string here
     * would silently paper over a caller bug whose only other symptom is a 401 with no
     * explanation from the server.
     */
    static String canonicalMessage(long timestampMillis, String method, String path) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        if (!method.equals(method.toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("HTTP method must be uppercase: " + method);
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/': " + path);
        }
        if (path.indexOf('?') >= 0) {
            throw new IllegalArgumentException(
                    "path must exclude the query string; Kalshi signs the path only: " + path);
        }
        return timestampMillis + method + path;
    }

    private String signMessage(String message) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            // Order matters: the PSS parameters must be set before initSign.
            signature.setParameter(PSS_PARAMS);
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM has no " + ALGORITHM + " provider", e);
        } catch (InvalidAlgorithmParameterException e) {
            throw new IllegalStateException("rejected PSS parameters", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("private key rejected for signing", e);
        } catch (SignatureException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    /** The PSS parameters used for signing, so a verifier can mirror them exactly. */
    public static PSSParameterSpec pssParameters() {
        return PSS_PARAMS;
    }

    /** Never render the key or the key id in logs by accident. */
    @Override
    public String toString() {
        return "KalshiSigner[accessKeyId=" + redact(accessKeyId) + "]";
    }

    private static String redact(String s) {
        return s.length() <= 8 ? "********" : s.substring(0, 8) + "...";
    }
}
