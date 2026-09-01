# kalshi-arb — Kalshi Mispricing Detection Engine

A real-time market data pipeline that maintains live order books across Kalshi
prediction-market contracts and detects **logical constraint violations** between
related markets.

**It is not a trading bot, and it places no orders.** The deliverable is a
measurement: how often related contracts are priced inconsistently, and what
fees and latency do to those inconsistencies. The answer turned out to be a
clean null result, which is the finding rather than a disappointment.

---

## 1. The idea in one page

We never predict anything. No weather model, no view on Bitcoin, no estimate of
any probability. The strategy is purely **relative value**: find two prices that
cannot both be correct.

Two such relations exist on Kalshi:

**Exhaustive brackets must sum to $1.00.** A day's high temperature in New York
falls in exactly one of six brackets (`≤82°`, `83-84°`, `85-86°`, `87-88°`,
`89-90°`, `≥91°`). Exactly one pays $1 and the rest pay $0, so their prices must
total $1.00. If you can buy all six for $0.96, you have bought $1.00 for $0.96
regardless of the weather.

**Nested thresholds must be monotone.** `price(X ≥ 60) ≥ price(X ≥ 65)`, always,
because every world where X clears 65 is also a world where it clears 60. The
lower threshold pays out in a strict superset of cases, so it cannot be cheaper.

Neither relation requires knowing anything about the underlying. That is the
whole appeal: the edge, if any, is arithmetic rather than forecasting.

### The catch, which is the actual subject of the project

Kalshi charges a **quadratic taker fee**, `ceil(0.07 × contracts × P × (1−P))`,
rounded up to the next cent **per order**. It peaks at 1.75¢/contract at P=$0.50
and it is charged **per leg**. A six-bracket arbitrage pays six fees.

So a 4¢ gross edge across five legs is not a 4¢ profit — it is a ~5.2¢ loss.
Working that out honestly, over real recorded data, is what this repository does.

---

## 2. What was actually found

Measured over **118,528 recorded frames** across two days, 24 markets in 4 event
families:

| | result |
|---|---|
| Book states evaluated | 118,444 |
| Gross bracket violations (1 contract) | 45,978 — **57% of states** |
| Gross bracket violations (100 contracts) | 13,148 — 17% of states |
| **Violations surviving fees** | **0** |
| Largest gross edge seen | $1.85 (fees on it: $4.62) |
| Monotonicity violations (900 live pairs, KXBTCD) | 0 |

Violations are *common*. Not one of them is exploitable.

### Latency is not the binding constraint — fees are

Tracking each violation as an **episode** (the span during which it exists) and
asking how many outlive a given reaction time:

```
measured one-way delivery latency: p50=23ms  p90=81ms  p99=167ms

budget    episodes surviving    of which clear fees
    0ms    69 / 73  (94.5%)             1
   10ms    64 / 73  (87.7%)             0
   50ms    50 / 73  (68.5%)             0
  200ms    39 / 73  (53.4%)             0
 1000ms    18 / 73  (24.7%)             0
```

Read that carefully: **94.5% of violations survive zero latency, and only one of
73 ever clears fees.** A colocated, zero-latency system would still lose money on
72 of them. The single profitable episode did not survive 10ms — and our measured
p50 delivery latency is 23ms, so it was never observable in time.

The conclusion is sharper than "no opportunities exist": **the market is
efficient exactly to the boundary of the fee structure**, and speed would not
change that.

### Why the fee model cannot be escaped by trading bigger

The fee is *linear* in size, so edge-per-contract must beat fee-per-contract.
Size only dilutes the per-order cent ceiling — it can never rescue a thin edge.
That is why `minViableSize` returns empty on the canonical $0.96 example at every
depth.

---

## 3. How it is put together

Gradle multi-module, Java 21. **Dependencies point strictly downward; never
sideways within a layer.**

```
libs/core-model    LAYER 0   value types, zero dependencies          593 lines
libs/common        LAYER 0   Clock, Backoff                          173 lines
libs/kalshi-client LAYER 1   auth, rest, ws, codec                 4,029 lines
libs/persistence   LAYER 1   frame archive behind FrameWriter         505 lines
libs/book          LAYER 1   order book reconstruction                756 lines
libs/detector      LAYER 2   pure, no I/O whatsoever                  619 lines
apps/recorder      LAYER 3   runnable + verification tools          1,369 lines
```

**288 tests, 0 failures.**

Three architectural rules do most of the work:

**`Clock` is an injected interface.** Nothing below `apps/` may call
`System.currentTimeMillis()`. `SystemClock` is package-private, reachable only
via `Clock.system()`, so "who reads the real time" is answerable by grepping one
call. This is what will later let a replay drive identical code against a
simulated clock.

**The detector is a pure function** over book state — no I/O, no clock, no
network. That is why the same detector code runs over live books and over books
rebuilt from an archive and cannot tell the difference.

**`long` micro-dollars everywhere** ($1.00 = 1,000,000). No `double`, no `float`
anywhere near a price. We sum brackets and compare against *exactly* $1.00;
binary floating point cannot represent $0.01 and the error is the same order of
magnitude as the edge being measured.

**`libs/book` deliberately does not depend on `libs/kalshi-client`** — both are
layer 1. So the book layer speaks only value types, and translating a WebSocket
frame into a book update is the app layer's job.

---

## 4. The parts worth understanding

### Order books: Kalshi publishes bids only

There is no ask book. An ask is always derived by complementing the opposite
side's bid, because buying NO at $0.45 is identical to selling YES at $0.55 — the
two contracts are minted as a pair for $1.00.

```
best YES ask = $1.00 − best NO bid
```

This has a consequence that shapes the whole project: **`best_yes_bid +
best_no_bid ≤ $1.00` always**. A crossed book cannot exist, because the matching
engine would already have paired those orders. So observing one is never an
opportunity — it is proof our own reconstruction is broken. `BookInvariants`
throws rather than reporting it.

It is also why intra-market YES/NO arbitrage is structurally impossible here, and
therefore why the project targets relations *between* markets.

### Delta application: the three rules where books silently rot

`DeltaApplier` exists as its own class because it holds the rules whose violation
produces no error signal at all:

1. **A delta is a change, not a new value.** Assignment semantics track reality
   for a while, then diverge with no warning.
2. **A level reaching zero is removed**, not stored as zero — a stored zero would
   still win `lastEntry()`, so the book would quote a best bid with nothing behind it.
3. **A level going negative throws.** Never clamp. Negative means a delta was
   double-applied or applied across an unrecovered gap. Clamping hides the bug and
   leaves a book that is merely wrong, which the detector would read as a violation.

### Verification: the only check that can catch a book bug

Delta application has no natural error signal, so the recorder periodically asks
the exchange for a fresh snapshot (every 5 minutes; the WebSocket consumes no
rate-limit tokens, so checkpoints are free). `BookReplay` then replays the archive
and compares the reconstructed ladder against each snapshot **exactly, at every
price level**.

Result on real data: **24 checkpoints, all exact, across 57,727 deltas.**

The harness distinguishes "no failures" from "verified" — an archive with zero
checkpoints reports `UNVERIFIED` and exits non-zero, because those are different
claims and must not print the same word.

### Sharding: one WebSocket per event family

`seq` is scoped to the subscription, not the market, so one dropped frame
invalidates every book on that subscription. And Kalshi assigns **one `sid` per
channel per connection** — four subscribe commands on one socket all came back on
`sid=1` with `seq` running continuously across all four families. Sending separate
subscribe commands buys no isolation at all.

So isolation requires a separate connection per family. The event is the right
grain because `mutually_exclusive` is an event-level flag: a constraint group
never spans two sockets, so a gap can never leave the detector comparing a fresh
book against a stale one.

A useful property falls out: each family's books are touched by exactly one pump
thread. **Single-writer-per-shard is the entire concurrency model — there are no
locks anywhere in the book layer.**

### Archive format: gzipped JSON Lines

One line per frame, one file per UTC day, several families multiplexed with the
origin recorded on each line. Frames are stored **raw**, before decoding.

Both choices are deliberate. Line orientation means a truncated file costs the
final line rather than the whole day, and the recording cannot be regenerated.
Storing raw text means a frame this codebase cannot yet parse is still archived
intact — which mattered: a field-name bug would otherwise have produced a day of
archives full of frames we rejected.

A flush **finishes the gzip member** rather than sync-flushing, so the file on
disk is always completely valid. (`Deflater.SYNC_FLUSH` puts the bytes on disk but
leaves no member trailer, and `GZIPInputStream` then cannot extract the line — 92
bytes unreadable, the same file readable at 102 once closed.)

---

## 5. Rejected alternatives

Deliberately absent, because each would add operational weight without buying
anything the measurement needs:

| Not used | Why |
|---|---|
| Postgres | The archive is append-only, read sequentially, never queried by key. A file is the right shape. |
| Parquet | Columnar efficiency loses to crash-resilience for data that cannot be regenerated. Reachable through `FrameWriter` when it earns its place. |
| LP solver (ojAlgo / OR-Tools) | A bracket sum is addition. An LP would obscure it. |
| Kafka / Redis | One producer, one consumer, one process. |
| Micrometer / Prometheus | Plain `AtomicLong` counters, printed. Nothing is being alerted on yet. |
| Spring / any ORM | No dependency injection problem exists here; `Clock` is passed by hand in one place. |
| `double` for prices | Cannot represent $0.01. The error is the size of the signal. |
| `BigDecimal` on the hot path | Allocates per operation. `long` micro-dollars are exact and free. (`BigInteger` appears in exactly one place — the fee model, off the hot path, where the cent ceiling makes exactness decide viability.) |

Optimising the detector was also rejected: the network round trip is ~98% of the
latency budget, so the levers are deployment location and GC configuration, not
constraint-checking speed.

---

## 6. Hard-won API facts

Six documented facts turned out to be wrong, each caught by running against the
real exchange rather than by reasoning. They are recorded in `CLAUDE.md` section 5
with the evidence.

| Belief | Reality |
|---|---|
| WS shares the REST host | `external-api` **404s** on `/trade-api/ws/v2`; WS is on `api.elections`. Signed path is `/trade-api/ws/v2`, not the REST `/trade-api/v2`. |
| `get_snapshot` takes `sids` + `action` | Rejected: `code 14, "Market Ticker required"`. **Gap recovery had never worked** — it had simply never fired. |
| One `sid` per subscribe command | One `sid` per *channel per connection*. Sharding needs separate sockets. |
| Only book frames carry `seq` | `ok` **and** `error` frames carry `seq` too. The rule is *anything with a `seq` advances the counter* — enumerating types failed twice. |
| Ping every ~10s | ~45s per connection. A 35s staleness timeout would reconnect-loop all night. |
| Fee multiplier higher for crypto | `KXBTCD` reports `fee_multiplier: 1`, same as weather. Read it per-series from `/series`. |

Two more that cost real debugging time:

- **WS snapshots use `yes_dollars_fp`; REST uses `yes_dollars`.** Assuming the
  REST spelling made every snapshot fail to decode while deltas kept working — so
  the connection looked healthy and every book silently never initialised.
- **Kalshi terminates each WS text frame with a newline.** Copied through raw it
  pushed the archive envelope's closing brace onto the next line, so every record
  spanned two lines. All health metrics read green while the archive was unusable;
  only reading it back with an independent tool exposed it.

---

## 7. Running it

Requires Java 21, an RSA keypair registered with Kalshi, and:

```bash
export KALSHI_KEY_ID="<your-key-id-uuid>"
```

The private key is read from `KALSHI_PRIVATE_KEY`, defaulting to
`~/.kalshi/kalshi-private.pem`. It never enters the repository.

Verify the build and that no credential file is tracked:

```bash
./gradlew build && git ls-files | grep -Ei '\.(pem|key|p8|p12|pfx|jks)$|(^|/)\.env'
```

Confirm authentication end to end (read-only, one `GET`):

```bash
./gradlew :libs:kalshi-client:smokeCheck
```

Print live order books for one event with derived asks, spreads and the
crossed-book invariant (no credentials needed — market data is public):

```bash
./gradlew :libs:kalshi-client:orderbookDemo --args="KXHIGHNY-26SEP01"
```

Record frames indefinitely. **This is the thing worth leaving running** —
recorded market data cannot be backfilled:

```bash
./gradlew :apps:recorder:run --args="data/frames KXHIGHNY KXHIGHLAX"
```

The analysis tools read the archive while the recorder is still writing to it,
which the member-per-flush format guarantees. Run them from a second terminal.
Use plain `java` rather than Gradle, since two concurrent Gradle builds in one
project can deadlock on the project lock:

```bash
./gradlew :apps:recorder:installDist
```

```bash
java -cp "apps/recorder/build/install/recorder/lib/*" com.kalshiarb.recorder.BookReplay data/frames
```

```bash
java -cp "apps/recorder/build/install/recorder/lib/*" com.kalshiarb.recorder.DetectorReplay data/frames 100
```

```bash
java -cp "apps/recorder/build/install/recorder/lib/*" com.kalshiarb.recorder.LatencyStudy data/frames 1
```

Scan a live nested-threshold series for monotonicity violations:

```bash
./gradlew :apps:recorder:monotoneScan --args="KXBTCD 25 100"
```

---

## 8. Not built

Order placement — the account holds real money and nothing here writes.
Also absent: the latency-aware replay *simulator* (the analysis in §2 needs no
simulated clock), live paper trading, a supervisor process, and the
`libs/universe` / `libs/execution` modules. An `ExecutionVenue`-shaped seam is
left open but unimplemented.
