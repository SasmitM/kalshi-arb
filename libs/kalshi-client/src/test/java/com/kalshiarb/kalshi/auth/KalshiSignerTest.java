package com.kalshiarb.kalshi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kalshiarb.common.Clock;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Uses a throwaway keypair generated in-process. No real key material is read, and nothing
 * here touches {@code ~/.kalshi}.
 *
 * <p>RSA-PSS salts randomly, so a signature is not reproducible across runs. Every assertion
 * about the signature is therefore a verification against the public half, not a comparison
 * with a recorded string.
 */
class KalshiSignerTest {

    private static final long FIXED_MILLIS = 1_756_658_400_000L;
    private static final String KEY_ID = "11111111-2222-3333-4444-555555555555";

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    private KalshiSigner signerAt(long millis) {
        return new KalshiSigner(KEY_ID, (RSAPrivateKey) keyPair.getPrivate(), Clock.fixed(millis));
    }

    /** Verifies a base64 signature over {@code message} using the public half. */
    private static boolean verifies(String message, String base64Signature) throws Exception {
        Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(KalshiSigner.pssParameters());
        verifier.initVerify(keyPair.getPublic());
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(base64Signature));
    }

    @Test
    @DisplayName("sign-then-verify round trip over the canonical message")
    void signThenVerifyRoundTrip() throws Exception {
        AuthHeaders headers = signerAt(FIXED_MILLIS).sign("GET", "/trade-api/v2/portfolio/balance");

        String expectedMessage = FIXED_MILLIS + "GET" + "/trade-api/v2/portfolio/balance";
        assertThat(verifies(expectedMessage, headers.signature()))
                .as("signature must verify against timestamp + METHOD + path")
                .isTrue();
    }

    @Test
    @DisplayName("the signed message is exactly timestamp + METHOD + path, no separators")
    void canonicalMessageFormat() {
        assertThat(KalshiSigner.canonicalMessage(
                1_756_658_400_000L, "GET", "/trade-api/v2/portfolio/balance"))
                .isEqualTo("1756658400000GET/trade-api/v2/portfolio/balance");

        // The /trade-api/v2 prefix is part of the signed path.
        assertThat(KalshiSigner.canonicalMessage(1L, "GET", "/trade-api/v2/exchange/status"))
                .isEqualTo("1GET/trade-api/v2/exchange/status");

        // Milliseconds, not seconds: a 13-digit timestamp, not 10.
        assertThat(KalshiSigner.canonicalMessage(FIXED_MILLIS, "GET", "/x"))
                .startsWith("1756658400000");
    }

    @Test
    @DisplayName("the timestamp header matches the clock and is the value actually signed")
    void timestampComesFromInjectedClock() throws Exception {
        long millis = 1_700_000_000_123L;
        AuthHeaders headers = signerAt(millis).sign("GET", "/trade-api/v2/portfolio/balance");

        assertThat(headers.timestamp()).isEqualTo("1700000000123");
        assertThat(verifies(millis + "GET/trade-api/v2/portfolio/balance", headers.signature()))
                .isTrue();
        // A different timestamp must not verify — proves the timestamp is genuinely covered.
        assertThat(verifies((millis + 1) + "GET/trade-api/v2/portfolio/balance",
                headers.signature()))
                .isFalse();
    }

    @Test
    @DisplayName("the key id passes through as KALSHI-ACCESS-KEY")
    void keyIdPassesThrough() {
        AuthHeaders headers = signerAt(FIXED_MILLIS).sign("GET", "/trade-api/v2/exchange/status");
        assertThat(headers.accessKey()).isEqualTo(KEY_ID);
    }

    @Test
    @DisplayName("PSS is randomised: two signatures differ, both verify")
    void signaturesAreNonDeterministic() throws Exception {
        KalshiSigner signer = signerAt(FIXED_MILLIS);
        String path = "/trade-api/v2/portfolio/balance";
        String message = FIXED_MILLIS + "GET" + path;

        String first = signer.sign("GET", path).signature();
        String second = signer.sign("GET", path).signature();

        assertThat(first)
                .as("RSA-PSS salts randomly, so a fixed expected-value test would be wrong")
                .isNotEqualTo(second);
        assertThat(verifies(message, first)).isTrue();
        assertThat(verifies(message, second)).isTrue();
    }

    @Test
    @DisplayName("a signature over one path does not verify for another")
    void signatureIsPathSpecific() throws Exception {
        AuthHeaders headers = signerAt(FIXED_MILLIS).sign("GET", "/trade-api/v2/portfolio/balance");

        assertThat(verifies(FIXED_MILLIS + "GET/trade-api/v2/portfolio/positions",
                headers.signature())).isFalse();
        // Also method-specific.
        assertThat(verifies(FIXED_MILLIS + "POST/trade-api/v2/portfolio/balance",
                headers.signature())).isFalse();
    }

    @Test
    @DisplayName("a query string in the path is rejected, not silently stripped")
    void rejectsQueryString() {
        assertThatThrownBy(() -> KalshiSigner.canonicalMessage(
                1L, "GET", "/trade-api/v2/markets?event_ticker=KXHIGHNY-26SEP01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must exclude the query string");
    }

    @Test
    @DisplayName("a lowercase method is rejected, not silently upcased")
    void rejectsLowercaseMethod() {
        assertThatThrownBy(() -> KalshiSigner.canonicalMessage(1L, "get", "/trade-api/v2/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase");
    }

    @Test
    @DisplayName("a path without a leading slash is rejected")
    void rejectsRelativePath() {
        assertThatThrownBy(() -> KalshiSigner.canonicalMessage(1L, "GET", "trade-api/v2/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with '/'");
    }

    @Test
    @DisplayName("a blank key id is rejected at construction")
    void rejectsBlankKeyId() {
        assertThatThrownBy(() -> new KalshiSigner(
                "  ", (RSAPrivateKey) keyPair.getPrivate(), Clock.fixed(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString does not leak the key id or signature")
    void doesNotLeakSecrets() {
        assertThat(signerAt(FIXED_MILLIS)).hasToString("KalshiSigner[accessKeyId=11111111...]");

        AuthHeaders headers = signerAt(FIXED_MILLIS).sign("GET", "/trade-api/v2/exchange/status");
        assertThat(headers.toString()).doesNotContain(headers.signature());
    }
}
