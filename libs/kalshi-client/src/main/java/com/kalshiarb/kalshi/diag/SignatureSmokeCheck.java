package com.kalshiarb.kalshi.diag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kalshiarb.common.Clock;
import com.kalshiarb.kalshi.auth.AuthHeaders;
import com.kalshiarb.kalshi.auth.KalshiCredentials;
import com.kalshiarb.kalshi.auth.KalshiSigner;
import com.kalshiarb.model.Money;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Proves the Java signer produces headers Kalshi accepts, by calling
 * {@code GET /trade-api/v2/portfolio/balance} — the same endpoint the reference shell script
 * already verifies. A 200 here means the Java RSA-PSS implementation matches
 * {@code openssl dgst -sigopt rsa_pss_saltlen:32}; a 401 means a bug in this code, not in the
 * key.
 *
 * <p>Kept independent of the REST client on purpose. It builds its own {@link HttpClient} and
 * request so that an auth failure cannot be confused with a client-layer bug. That isolation is
 * the entire value of this class, so it stays even after the REST client exists.
 *
 * <p><b>Read-only.</b> The account holds real money; this issues a single GET and never posts.
 *
 * <p>Uses {@code readTree} to pull one field out of one response. That is fine here and
 * forbidden on the hot path, where the codec uses streaming {@code JsonParser}.
 */
public final class SignatureSmokeCheck {

    /**
     * Per CLAUDE.md section 5. Verified live: this host and the older
     * {@code api.elections.kalshi.com} alias both return 200 on
     * {@code /trade-api/v2/exchange/status}, while {@code trading-api.kalshi.com} (which still
     * appears in blog posts) returns 401 and is stale.
     */
    private static final String HOST = "https://external-api.kalshi.com";
    private static final String PATH = "/trade-api/v2/portfolio/balance";

    private SignatureSmokeCheck() {
    }

    public static void main(String[] args) throws Exception {
        Clock clock = Clock.system();

        System.out.println("Kalshi signature smoke check");
        System.out.println("  key path : " + KalshiCredentials.privateKeyPath());

        KalshiCredentials credentials = KalshiCredentials.fromEnvironment();
        KalshiSigner signer = KalshiSigner.from(credentials, clock);
        System.out.println("  key id   : " + credentials);
        System.out.println("  key bits : " + credentials.privateKey().getModulus().bitLength());

        AuthHeaders headers = signer.sign("GET", PATH);
        System.out.println("  signed   : " + headers.timestamp() + "GET" + PATH);
        System.out.println("  headers  : " + headers);

        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = headers.applyTo(HttpRequest.newBuilder()
                        .uri(URI.create(HOST + PATH))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json")
                        .GET())
                .build();

        long startNanos = clock.nanoTime();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsedMicros = (clock.nanoTime() - startNanos) / 1_000L;

        System.out.println();
        System.out.println("  HTTP     : " + response.statusCode() + " ("
                + response.version() + ") in " + elapsedMicros / 1000.0 + " ms");
        System.out.println("  body     : " + response.body());

        if (response.statusCode() != 200) {
            System.err.println();
            System.err.println("FAILED: expected 200. The shell script proves the credentials "
                    + "work, so a 401 here is a bug in the Java signer -- check that the "
                    + "timestamp is in milliseconds, that the signed path includes "
                    + "/trade-api/v2 and excludes any query string, and that the PSS salt "
                    + "length is 32.");
            System.exit(1);
        }

        // Always read the _dollars string field; the integer field loses sub-penny precision.
        JsonNode json = new ObjectMapper().readTree(response.body());
        JsonNode dollars = json.get("balance_dollars");
        if (dollars == null) {
            System.err.println("WARNING: no balance_dollars field; the response shape changed.");
        } else {
            Money balance = Money.fromFixedPointString(dollars.asText());
            System.out.println("  balance  : " + balance + "  (" + balance.micros()
                    + " micro-dollars)");
        }

        System.out.println();
        System.out.println("OK: RSA-PSS signature accepted by Kalshi.");
    }
}
