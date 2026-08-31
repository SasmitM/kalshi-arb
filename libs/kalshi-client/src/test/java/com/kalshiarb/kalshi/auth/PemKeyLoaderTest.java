package com.kalshiarb.kalshi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every key here is generated in-process and thrown away. Nothing reads {@code ~/.kalshi}, and
 * no key material is checked in.
 */
class PemKeyLoaderTest {

    private static KeyPair generate(int bits) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(bits);
        return gen.generateKeyPair();
    }

    /** Wraps DER bytes in a PEM block the way openssl would. */
    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    @Test
    @DisplayName("parses a PKCS#8 key and recovers the same modulus and exponent")
    void parsesPkcs8() throws Exception {
        KeyPair pair = generate(2048);
        RSAPrivateKey original = (RSAPrivateKey) pair.getPrivate();
        // getEncoded() on an RSA private key is exactly PKCS#8 DER, so this is the same bytes
        // openssl genpkey would write.
        String text = pem("PRIVATE KEY", original.getEncoded());

        RSAPrivateKey loaded = PemKeyLoader.parse(text, Path.of("generated-in-test"));

        assertThat(loaded.getModulus()).isEqualTo(original.getModulus());
        assertThat(loaded.getPrivateExponent()).isEqualTo(original.getPrivateExponent());
        assertThat(loaded.getModulus().bitLength()).isEqualTo(2048);
    }

    @Test
    @DisplayName("a key loaded from disk can actually sign, verifying against its public half")
    void loadedKeySigns(@TempDir Path dir) throws Exception {
        KeyPair pair = generate(2048);
        Path keyFile = dir.resolve("kalshi-private.pem");
        Files.writeString(keyFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));

        RSAPrivateKey loaded = PemKeyLoader.load(keyFile);
        AuthHeaders headers = new KalshiSigner(
                "test-key-id", loaded, com.kalshiarb.common.Clock.fixed(1_756_658_400_000L))
                .sign("GET", "/trade-api/v2/portfolio/balance");

        java.security.Signature verifier = java.security.Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(KalshiSigner.pssParameters());
        verifier.initVerify(pair.getPublic());
        verifier.update("1756658400000GET/trade-api/v2/portfolio/balance"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(verifier.verify(Base64.getDecoder().decode(headers.signature()))).isTrue();
    }

    @Test
    @DisplayName("PKCS#1 is detected and the error names the exact openssl fix")
    void detectsPkcs1() {
        // Body content is irrelevant: detection is on the header, before any parsing.
        String text = pem("RSA PRIVATE KEY", new byte[] {1, 2, 3, 4});

        assertThatThrownBy(() -> PemKeyLoader.parse(text, Path.of("/home/u/.kalshi/k.pem")))
                .isInstanceOf(PemKeyLoader.KeyLoadException.class)
                .hasMessageContaining("PKCS#1")
                .hasMessageContaining("openssl pkcs8 -topk8 -nocrypt")
                .hasMessageContaining("/home/u/.kalshi/k.pem");
    }

    @Test
    @DisplayName("an encrypted key is reported as such rather than failing to parse")
    void detectsEncrypted() {
        String text = pem("ENCRYPTED PRIVATE KEY", new byte[] {1, 2, 3, 4});

        assertThatThrownBy(() -> PemKeyLoader.parse(text, Path.of("k.pem")))
                .isInstanceOf(PemKeyLoader.KeyLoadException.class)
                .hasMessageContaining("passphrase-encrypted");
    }

    @Test
    @DisplayName("pointing at the public half gives a message that says so")
    void detectsPublicKey() throws Exception {
        String text = pem("PUBLIC KEY", generate(2048).getPublic().getEncoded());

        assertThatThrownBy(() -> PemKeyLoader.parse(text, Path.of("kalshi-public.pem")))
                .isInstanceOf(PemKeyLoader.KeyLoadException.class)
                .hasMessageContaining("PUBLIC key");
    }

    @Test
    @DisplayName("a file with no PEM block at all is rejected")
    void rejectsNonPem() {
        assertThatThrownBy(() -> PemKeyLoader.parse("not a pem file\n", Path.of("k.pem")))
                .isInstanceOf(PemKeyLoader.KeyLoadException.class)
                .hasMessageContaining("no PKCS#8 private key block");
    }

    @Test
    @DisplayName("a PKCS#8 header wrapping non-key bytes is rejected")
    void rejectsCorruptBody() {
        String text = pem("PRIVATE KEY", new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        assertThatThrownBy(() -> PemKeyLoader.parse(text, Path.of("k.pem")))
                .isInstanceOf(PemKeyLoader.KeyLoadException.class)
                .hasMessageContaining("did not parse as an RSA private key");
    }

    @Test
    @DisplayName("an undersized key is rejected instead of silently used")
    void rejectsWeakKey() throws Exception {
        String text = pem("PRIVATE KEY", generate(1024).getPrivate().getEncoded());

        assertThatThrownBy(() -> PemKeyLoader.parse(text, Path.of("k.pem")))
                .isInstanceOf(PemKeyLoader.KeyLoadException.class)
                .hasMessageContaining("1024 bits");
    }

    @Test
    @DisplayName("a missing file explains where the key is expected to live")
    void missingFileIsActionable(@TempDir Path dir) {
        assertThatThrownBy(() -> PemKeyLoader.load(dir.resolve("nope.pem")))
                .isInstanceOf(PemKeyLoader.KeyLoadException.class)
                .hasMessageContaining("KALSHI_PRIVATE_KEY")
                .hasMessageContaining("~/.kalshi/kalshi-private.pem");
    }

    @Test
    @DisplayName("the default key path is ~/.kalshi/kalshi-private.pem")
    void defaultKeyPath() {
        // KALSHI_PRIVATE_KEY may legitimately be set in this shell; assert on whichever branch
        // applies rather than mutating the environment.
        Path resolved = KalshiCredentials.privateKeyPath();
        if (System.getenv(KalshiCredentials.ENV_PRIVATE_KEY) == null) {
            assertThat(resolved)
                    .isEqualTo(Path.of(System.getProperty("user.home"))
                            .resolve(".kalshi/kalshi-private.pem"));
        } else {
            assertThat(resolved)
                    .isEqualTo(Path.of(System.getenv(KalshiCredentials.ENV_PRIVATE_KEY).trim()));
        }
    }
}
