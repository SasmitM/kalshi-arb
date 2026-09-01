# CLAUDE.md — Kalshi Mispricing Detection Engine

Context for Claude Code. Read this fully before writing any code.

---

## 1. What this project is

A real-time market data pipeline that maintains live order books across Kalshi
prediction-market event contracts and detects **logical constraint violations**
between related markets.

**It is explicitly not a trading bot.** The expected result is near-zero
exploitable edge after fees and latency. The deliverable is the *measurement*:
what fraction of detected opportunities survive at 10ms / 50ms / 200ms / 1s of
latency, and what fees do to the remainder. Reporting an honest null result is
the goal, not a fallback.

The strategy is **relative value, not directional forecasting.** We never
estimate the true probability of anything. We only find cases where two prices
cannot both be correct:

- **Exhaustive brackets must sum to $1.00.** A day's high temperature falls in
  exactly one bracket, so the bracket prices must sum to $1.00.
- **Nested thresholds must be monotone.** `price(temp >= 60) >= price(temp >= 65)`
  always, because every world where it's 65+ is also a world where it's 60+.
  Note the `KXHIGH*` weather events have **no** nested thresholds — each carries
  exactly one `less` and one `greater` market plus four `between` brackets, so
  there is no `>=X` pair to compare. `KXBTCD` publishes ~50 nested `greater`
  thresholds per event and is the series to test this constraint against.

### Why this framing matters

The author is interviewing for a Global Markets Technology role. The code is
the entry point to a technical conversation about market data infrastructure,
exchange microstructure, and latency. Design decisions must be defensible and
documented with rejected alternatives. Do not add technology that isn't
load-bearing.

---

## 2. Current status

### Done and verified

- Kalshi account created, KYC cleared, account funded with $10.
- RSA keypair generated locally with `openssl genpkey` (PKCS#8 format).
  Public key registered with Kalshi; private key never left the machine.
- API Key ID obtained.
- **Authentication verified end to end** via a shell script using
  `openssl dgst -sha256 -sign ... -sigopt rsa_padding_mode:pss
  -sigopt rsa_pss_saltlen:32`. `GET /trade-api/v2/portfolio/balance`
  returns HTTP 200.

### Credential locations (NEVER commit these, never read them into the repo)

```
~/.kalshi/kalshi-private.pem     PKCS#8, chmod 600
~/.kalshi/kalshi-public.pem
$KALSHI_KEY_ID                   environment variable (a UUID; not secret)
```

Read the key path from `KALSHI_PRIVATE_KEY` env var, defaulting to
`~/.kalshi/kalshi-private.pem`. Read the key id from `KALSHI_KEY_ID`.
Never hardcode either. Never write key material into the repo, tests, or
fixtures.

### Not started

Everything else. No Java has been written yet.

---

## 3. Scope: today vs. eventually

### Today's target (the resume-supportable slice)

In priority order. Each task must leave the build green and the repo in a
coherent state, because work may stop at any point.

1. `core-model` — value types
2. `kalshi-client/auth` — RSA-PSS signer with round-trip test
3. `kalshi-client/rest` — minimal REST client, market data endpoints
4. `kalshi-client/ws` — WebSocket: auth handshake, ping/pong, subscribe,
   `seq` gap detection, snapshot recovery
5. `apps/recorder` — archive raw frames to gzipped JSON Lines, run unattended
6. `book` — fixed-point decode, delta application, implied asks
7. `detector` — bracket-sum check with the fee model

If time runs out, stopping after task 5 still yields a working recorder
collecting real data, which is the single most valuable outcome.

### Explicitly deferred (do NOT build today)

Postgres, Parquet, LP solver (ojAlgo/OR-Tools), execution/order placement,
supervisor process, replay engine, Micrometer/Prometheus, ArchUnit, jqwik,
Docker, Kafka, Redis.

**No order-placement code today.** The account holds real money. This session
is read-only: market data and portfolio reads only. Do not write anything that
calls `POST /portfolio/orders`.

### Eventually — full end-to-end

The complete design is a Gradle monorepo with four applications (Recorder,
Engine, Supervisor, Replay) over seven library modules. Phases 4–6 add the
latency-aware replay simulator, live paper trading, and the research writeup.
Today's code should not foreclose that:

- **`Clock` must be an injected interface** from the start. Nothing below
  `apps/` may call `System.currentTimeMillis()` directly. This is what will
  later let the Replay app run byte-identical detector code against a
  simulated clock.
- **The frame archive must sit behind a `FrameWriter` / `FrameReader`
  interface** so gzipped JSONL can be swapped for Parquet without touching
  callers.
- **The detector must be a pure function** over book state — no I/O, no
  clock, no network. This is non-negotiable; it's what makes replay possible.
- Keep an `ExecutionVenue`-shaped seam in mind, but don't build it today.

---

## 4. Module layout

Gradle multi-module, Java 21, Kotlin DSL. Dependencies point strictly
downward; never sideways within a layer.

```
libs/core-model      LAYER 0  value types only, zero dependencies
libs/common          LAYER 0  Clock, Config, Backoff
libs/kalshi-client   LAYER 1  auth, rest, ws, codec   (knows nothing of arbitrage)
libs/book            LAYER 1  book reconstruction     (makes no network calls)
libs/detector        LAYER 2  pure, no I/O whatsoever
apps/recorder        LAYER 3  runnable
```

Later additions: `libs/persistence`, `libs/universe`, `libs/execution`,
`apps/engine`, `apps/supervisor`, `apps/replay`.

---

## 5. Kalshi API facts (hard-won — do not re-derive)

### Base URLs

```
REST  https://external-api.kalshi.com/trade-api/v2
WS    wss://api.elections.kalshi.com/trade-api/ws/v2
```

**REST and WS are on different hosts.** Verified by probing the upgrade
request (2026-08-31):

| Host | `/trade-api/ws/v2` | `/trade-api/v2/exchange/status` |
| --- | --- | --- |
| `external-api.kalshi.com` | **404** `404 page not found` | 200 |
| `api.elections.kalshi.com` | **401** `token_authentication_failure` | 200 |
| `trading-api.kalshi.com` | — | 401 (stale, from blogs) |

The 404 is a backend response, not CloudFront, and every path variant tried
on `external-api` 404s. The 401 on `api.elections` is the *good* answer: the
endpoint exists and wants credentials. Do not "fix" the WS host to match the
REST base URL — that was the original error here.

Kalshi's base URL has changed multiple times. Other hosts appear in blogs and
are stale. Verify against `docs.kalshi.com/welcome` and pin the OpenAPI spec
in `docs/kalshi-api-snapshot/`.

The signed path for the WS handshake is `/trade-api/ws/v2` — note `ws`, not
the REST `/trade-api/v2`. Signing the REST prefix yields a 401 with no other
symptom.

There are **three** API surfaces: event contracts (this project), perpetual
futures (`perps_openapi.yaml`), and Klear settlement. **Use event contracts
only.** Perps are a continuous-payoff margin product with no exhaustive
bracket structure, so the constraint algebra doesn't apply.

### Authentication

Three headers on every authenticated request:

```
KALSHI-ACCESS-KEY        the Key ID (UUID, not secret)
KALSHI-ACCESS-TIMESTAMP  current Unix time in MILLISECONDS
KALSHI-ACCESS-SIGNATURE  base64 RSA-PSS signature
```

Signed message is the concatenation `timestamp + METHOD + path`:

- METHOD uppercase (`GET`, `POST`)
- path **includes** the `/trade-api/v2` prefix
- path **excludes** the query string
- e.g. `1756658400000GET/trade-api/v2/portfolio/balance`

Java implementation:

```java
Signature sig = Signature.getInstance("RSASSA-PSS");
sig.setParameter(new PSSParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
```

Salt length **32** (SHA-256 digest length). Signatures are non-deterministic
by design, so the unit test must be **sign-then-verify round trip**, not a
comparison against a fixed expected value.

There is no login endpoint and no session token. Every request stands alone.

### Market data is PUBLIC

`GET /series`, `/events`, `/markets`, `/markets/{ticker}/orderbook` need no
authentication. Only trading and portfolio require signing. Useful for tests
and for the recorder.

The WebSocket **does** require auth on handshake, even for public channels.
Sign the WS path.

#### `/markets` query quirks (verified, both fail confusingly)

- The `status` **filter** value is `open`. Market objects report
  `"status": "active"`, so the filter vocabulary and the response vocabulary
  disagree. `status=active` returns **HTTP 400**, not an empty list.
- `status` requires a scoping filter. `/markets?status=open` alone is
  **HTTP 400**; combine it with `series_ticker` or `event_ticker`.

Both return 400 with a body, so a client that ignores the status code and
reads `markets: []` concludes "no open markets" and moves on. Always throw on
non-2xx.

#### `/series/{ticker}` publishes the fee schedule

`fee_type` (`"quadratic"`) and `fee_multiplier` (`1` for `KXHIGHNY`) come
straight from the API, per series. The fee model should read these rather
than hardcode the multiplier — same reasoning as fetching endpoint costs
instead of assuming them.

#### `mutually_exclusive` is the authority on bracket structure

It is an **event-level** flag. `KXHIGHNY-26SEP01` carries
`mutually_exclusive: true` and its six markets tile the range with no gap or
overlap (≤82, 83-84, 85-86, 87-88, 89-90, ≥91). Never infer exclusivity from
ticker shape; a wrong assumption manufactures constraint violations that
don't exist.

`floor_strike` / `cap_strike` are **off by one** from the human sub-title:
`T83` has `cap_strike: 83` and `strike_type: "less"` but means "82° or
below". Reconcile against `yes_sub_title` before deriving boundaries.

#### The bracket constraint is asymmetric across sides

- **Sell side** (short brackets, collect `Σbid`): exactly one bracket wins,
  so liability is capped at $1.00 whether or not every leg is quoted —
  dropping a leg only lowers the payout. So `Σbid > $1.00` is a real
  violation over **any subset**.
- **Buy side** (long brackets, pay `Σask`): the $1.00 only arrives if the
  winner is in the set, so the guarantee needs **every** bracket. One
  unquoted leg means there is no constraint to test.

This matters in practice: illiquid tail brackets genuinely have no bids
(live `KXHIGHNY-26SEP01-T90` had zero YES bids), so the two directions have
different coverage requirements.

### Fixed-point representation

Kalshi is mid-migration and returns BOTH legacy integers and fixed-point
strings. Example from a real `/portfolio/balance` response:

```json
{"balance": 1000, "balance_dollars": "10.0000"}
```

**Always parse the `_dollars` / `_fp` string fields.** Integer fields lose
precision under sub-penny pricing.

Order book levels arrive as string pairs `[price_dollars, count_fp]`, e.g.
`["0.0800", "300.00"]`. Decimal places are inconsistent across responses
(`"0.0800"` vs `"0.960"`) — parse properly, don't pattern-match on decimal
count. Contract counts can be fractional.

**Convert to `long` micro-dollars at the edge** (1.00 = 1_000_000). Never use
`double` or `float` anywhere near a price; we sum brackets and compare against
exactly $1.00. Never use `BigDecimal` on the hot path.

### Order book: bids only

Kalshi publishes only bids, on both sides. Asks are implied:

```
best YES bid = max(yes side)
best YES ask = $1.00 - max(no side)
```

Because a NO bid at $0.45 *is* a YES ask at $0.55.

**Invariant: `best_yes_bid + best_no_bid <= $1.00`, always.** Above $1.00 is a
crossed book, which the matching engine would have already matched into a
minted contract pair. Assert this on every update — a violation means a
delta-application bug, not an arbitrage opportunity.

This is also why simple intra-market YES/NO arbitrage is structurally
impossible on Kalshi and why the project targets cross-market constraints.

### WebSocket snapshot/delta protocol

On subscribe: one `orderbook_snapshot` per market. Then `orderbook_delta` on
every change.

- **A delta is a CHANGE, not a new absolute value.** Add it to the existing
  quantity at that price.
- Deltas can be negative (cancel, fill, or expiry — the protocol doesn't say
  which, and it doesn't matter for book maintenance).
- Level reaching zero: **remove the entry**, don't keep a zero level.
- Level going negative: **throw**. Never clamp to zero; that hides the bug.

**`seq` is scoped to the subscription (`sid`), NOT to the market.** One
dropped message invalidates every book on that subscription.

**Corrected: Kalshi assigns one `sid` per CHANNEL per CONNECTION, not one per
subscribe command.** Verified live — four `subscribe` commands for four event
families on one socket all came back on `sid=1`, with `seq` running
continuously 1→27 across every family, answered by one `subscribed` and three
`ok`. So sending separate subscribe commands buys **no isolation at all**.

Therefore: **one WebSocket connection per event family.** The sharding logic
is unchanged, but it has to drive connections, not subscriptions. Cost is one
socket + one pump thread + one watchdog thread per family; the guarantee is
that a gap on the NYC socket cannot touch the LAX books.

The side effect is now structural rather than aspirational: each family's
books are touched by exactly one pump thread, and constraint groups never
span families. **No locks anywhere in the book layer.**
Single-writer-per-shard is the entire concurrency model.

**`ok` frames occupy sequence numbers.** Verified: `ok id=1007 sid=1 seq=7`,
`seq=8`, `seq=9`, landing between snapshot bursts. Anything holding a `seq`
must advance the counter even when it changes no book, or the tracker reports
a false gap at every subscribe ack and resnapshots for nothing. A buffered
`ok` also has to count as an accounted-for number during gap replay, or the
contiguity check sees a phantom hole.

### Book frames carry a server timestamp — use it

Every `orderbook_delta` includes:

```json
"ts": "2026-08-31T20:51:05.261155Z", "ts_ms": 1788209465261
```

`ts_ms` minus the local receive time is a **directly measured** one-way
delivery latency. The latency study does not have to model delivery; it can
report the real distribution. Carries clock skew between Kalshi's host and
ours, so read it as a distribution, not a per-frame truth. Absent timestamps
must be `-1`, never `0` — a 1970 epoch would silently poison the histogram.

### WS field names differ from REST

| Frame | Levels / price | Size |
| --- | --- | --- |
| WS `orderbook_snapshot` | `yes_dollars_fp`, `no_dollars_fp` | — |
| WS `orderbook_delta` | `price_dollars` | `delta_fp` |
| REST `/orderbook` | `yes_dollars`, `no_dollars` (inside `orderbook_fp`) | — |

**The WS snapshot uses `_dollars_fp`; REST uses `_dollars`.** Assuming the
REST spelling makes every snapshot fail to decode while deltas keep working —
so the connection looks healthy and every book silently never initialises.
Snapshots also carry a `market_id` UUID. Try each known spelling rather than
assuming one, and throw listing the names tried if none match.

**The `get_snapshot` command requires `market_tickers`.** Sending only
`sids` + `action` is rejected with
`{"type":"error","sid":1,"seq":1261,"msg":{"code":14,"msg":"Market Ticker required"}}`.
Found by replaying an archive and tracing 12 phantom sequence gaps back to the
exchange rejecting our own requests — meaning gap recovery had never worked; it
had simply never fired.

**Error frames carry a `seq`, like `ok` frames.** The general rule is
*anything carrying a `seq` must advance the counter*, whether or not it changes
a book. Enumerating known types has now failed twice.

Gap recovery (do not tear down the connection):

```
on gap (seq != last_seq + 1):
    state = BUFFERING; buffer.clear()
    send update_subscription(sid, action="get_snapshot")
while BUFFERING:
    on delta    -> buffer.add(delta)          // hold, do not apply
    on snapshot -> book = build(snapshot)
                   for d in buffer:
                       if d.seq > snapshot.seq: book.apply(d)
                   state = STREAMING
```

Discarding deltas at or below `snapshot.seq` is essential — they're already
baked into the snapshot.

Kalshi sends Ping far less often than documented. **Measured: ~1 ping per 45s
per connection** (16 pings across 4 sockets in 3 minutes), not every 10s. Any
staleness timeout must exceed that or a quiet market reconnects forever; the
connection manager uses 120s and keys off the last frame *or* ping.

**Resolved: the JDK auto-responds. Do not send a Pong yourself.** The
`java.net.http.WebSocket.Listener.onPing` javadoc states that "the WebSocket
implementation will automatically send a reciprocal pong when a ping is
received", and `WebSocketImpl` carries a `lastAutomaticPong` field plus
buffer accounting for "2 automatic Pong replies". The default `onPing` body
is just `webSocket.request(1); return null;` — it does *not* send the Pong;
the transport does, below the listener. An explicit `sendPong` would put a
second, unsolicited Pong on the wire.

Use ping arrival as a **liveness signal** instead: a connection can die
without a close frame or an error, and ~10s pings make a long silence the
only available symptom. The connection manager treats 35s (three missed
intervals) as dead and reconnects.

### Fees

Taker fee per order: `ceil(0.07 * contracts * P * (1 - P))` rounded **up to
the next cent on the order total**.

- Parabola peaking at 1.75c/contract at P = $0.50, collapsing at the extremes
  (0.63c at $0.10, 0.33c at $0.05).
- **Charged per leg.** A 5-leg bracket arb pays 5 fees. Two-leg opportunities
  structurally beat five-leg ones.
- Therefore the break-even threshold is **price-dependent**. Never use a
  constant edge threshold.
- Apply the ceiling at the **order** level, not per contract.
  `ceil(0.07 * 8 * 0.10 * 0.90)` is NOT `8 * ceil(0.07 * 0.10 * 0.90)`.
- Rounding taxes small orders disproportionately (a 1-contract order at 10c
  pays a full cent — 59% overhead). Compute a **minimum viable size** per
  opportunity and reject below it.

Worked example that must produce a *negative* result — use it as a test case:
five brackets whose asks sum to $0.96 look like a 4c risk-free profit, but
fees at 0.30/0.25/0.20/0.13/0.08 total 5.21c, so the trade **loses 1.2c**.

Verify the current fee schedule against Kalshi's published PDF. Third-party
blogs get this wrong constantly. The multiplier is per-series and must be read
from `/series/{ticker}` rather than assumed by category — measured, `KXBTCD`
(bitcoin daily) reports `fee_multiplier: 1`, the same as weather, so "higher
for crypto" does not hold as a general rule.

### Rate limits

Token buckets, separate read and write. Basic tier roughly 200 read / 100
write tokens per second, most endpoints costing 10.

- **WebSocket consumes no tokens.** Everything readable goes over WS. Polling
  `/markets/{ticker}/orderbook` in a loop is a bug.
- 429s carry no `Retry-After` and no rate-limit headers, so you cannot react —
  mirror the bucket client-side.
- Fetch `GET /account/limits` and `GET /account/endpoint_costs` at startup.
  **Never hardcode costs.**
- Batch endpoints cost N x single, not a discount, and reject wholesale if the
  bucket can't cover the whole batch.

### Exchange sharding

`/portfolio/balance` returns `balance_breakdown` with `exchange_index` 0-3 —
four exchange shards. Writes draw from per-shard budgets. Not relevant today
(read-only), but keep the field in the DTOs.

---

## 6. Conventions

- **Java 21.** Records for value types, sealed interfaces where they fit,
  pattern matching. ZGC in `applicationDefaultJvmArgs` (`-XX:+UseZGC`).
- **`long` micro-dollars for all prices.** 1.00 = 1_000_000. No floats.
- **Jackson streaming (`JsonParser`)** on the hot path. Never `readTree`.
- **`Clock` injected.** No direct `System.currentTimeMillis()` below `apps/`.
- **The WebSocket callback thread must never block.** Hand off to a bounded
  queue. On overflow: drop, and let sequence-gap recovery rebuild. Instrument
  the drop counter.
- **JUnit 5 + AssertJ.** Test the book layer hardest; that's where silent
  bugs live.
- Commit in logical units with real messages. Commit history is part of the
  portfolio; interviewers read it. Not one giant "initial commit".

---

## 7. Task list

### Task 1 — `core-model`

Records: `Price` (long micros, factory from fixed-point string), `Quantity`,
`Side` (YES/NO), `MarketTicker`, `EventTicker`, `SeriesTicker`, `PriceLevel`,
`Money`.

`Price` needs `fromFixedPointString(String)`, `toDollarString()`, and a
`complement()` returning `$1.00 - this` for the implied-ask conversion.

**Done when:** parsing `"0.0800"`, `"0.960"`, and `"0.9"` all yield the right
micro-dollar values, and `complement()` round-trips.

### Task 2 — `kalshi-client/auth`

`PemKeyLoader` (PKCS#8; detect PKCS#1 and throw a message telling the user
the exact `openssl pkcs8 -topk8` command). `KalshiSigner` producing the three
header values.

**Done when:** a sign-then-verify round-trip test passes, and a test asserts
the signed message format is exactly `timestamp + METHOD + path`.

### Task 3 — `kalshi-client/rest`

JDK `HttpClient`, HTTP/2, persistent connection. Methods for `/exchange/status`,
`/series`, `/events`, `/markets`, `/markets/{ticker}/orderbook`,
`/portfolio/balance`. Signing optional per call, since market data is public.

**Done when:** a small main prints a live order book with derived best bid,
best ask, and spread, and asserts the crossed-book invariant.

### Task 4 — `kalshi-client/ws`

Connection manager with exponential backoff and jitter. Auth on handshake.
Ping/pong. `SubscriptionManager` sharding by event family. `SequenceTracker`
per sid. `SnapshotRecovery` implementing the buffer-and-discard algorithm
above.

**Done when:** it holds a connection for 30 minutes across at least one
forced reconnect, and logs a clean recovery on any gap.

### Task 5 — `apps/recorder`

`FrameWriter` interface, gzipped JSON Lines implementation. Every frame stored
with a **local receive timestamp** (needed later for latency analysis). Daily
file rotation. Subscribe to 6-10 weather markets (tickers starting `KXHIGH`).

**Done when:** it runs unattended and writes growing `.jsonl.gz` files.
**Start it and leave it running** — recorded data is the scarcest resource in
the project and the clock is already running.

### Task 6 — `book`

`Book` with two `NavigableMap<Long, Long>` sides, `DeltaApplier`,
`ImpliedAsk`, `DepthWalker` (cost to buy N contracts), `BookInvariants`.

**Done when:** replaying a day of recorded frames reproduces every periodic
snapshot exactly, and the crossed-book invariant never fires.

### Task 7 — `detector`

`FeeModel`, `ExhaustiveSumConstraint`, `EdgeThreshold`, `MinViableSize`,
`Opportunity`. Simple arithmetic — **no LP solver today**; a bracket sum is
`sum(asks) < feeAdjustedThreshold`.

**Done when:** running over recorded data prints violations with
fee-adjusted edge and depth-limited size, and the $0.96 five-bracket example
from section 5 correctly comes out negative.

---

## 8. Do not

- Commit key material. `.gitignore` covers `*.pem`, `*.key`, `.env`; verify
  with `git ls-files | grep -Ei 'pem|key|env'` before pushing.
- Write order-placement code. Real money is in the account.
- Add Kafka, Redis, Postgres, Kubernetes, Spring, or an ORM. Each was
  deliberately rejected; the README documents why. Adding them undoes the
  main signal of the project.
- Use `double` for prices.
- Poll REST for order books. That's what the WebSocket is for.
- Optimise the detector for speed. Network round-trip is ~98% of the latency
  budget. The levers are deployment location and GC configuration.
- Silently clamp a negative book level to zero.

## 9. Verification

```bash
./gradlew build
git ls-files | grep -Ei '\.(pem|key|p8|p12|pfx|jks)$|(^|/)\.env'   # must print nothing
```

The credential check matches credential *files* — by extension, plus dotenv files —
rather than any path containing the substrings `pem`, `key`, or `env`. The looser
substring form flags legitimate sources whose names describe what they handle
(`PemKeyLoader.java`, `KalshiCredentials.java`), and a check that always prints
something is a check nobody reads.
