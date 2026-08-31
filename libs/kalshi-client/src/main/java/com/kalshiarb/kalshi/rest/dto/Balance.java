package com.kalshiarb.kalshi.rest.dto;

import com.kalshiarb.model.Money;
import java.util.List;
import java.util.Optional;

/**
 * {@code GET /portfolio/balance} — the one authenticated endpoint this project calls.
 *
 * <p>The canonical illustration of Kalshi's mid-migration dual representation:
 *
 * <pre>{@code {"balance": 1000, "balance_dollars": "10.0000"}}</pre>
 *
 * <p>{@link #balance} is the legacy integer (cents) and {@link #balanceDollars} the fixed-point
 * string. Only the string is read by {@link #available()}; the integer is retained solely so a
 * test can assert the two agree and catch the day they stop agreeing under sub-penny pricing.
 *
 * @param balance          legacy integer field, in cents
 * @param balanceDollars   authoritative fixed-point string
 * @param balanceBreakdown per-shard balances, {@code exchange_index} 0-3
 */
public record Balance(
        Long balance,
        String balanceDollars,
        List<ShardBalance> balanceBreakdown) {

    public Balance {
        balanceBreakdown = balanceBreakdown == null ? List.of() : List.copyOf(balanceBreakdown);
    }

    /**
     * One exchange shard's balance.
     *
     * <p>Writes draw from per-shard budgets, so a strategy spanning shards can be funded overall
     * yet unable to execute a specific leg. Irrelevant while read-only; carried anyway.
     *
     * @param exchangeIndex shard index 0-3
     * @param balance       legacy integer field, in cents
     * @param balanceDollars authoritative fixed-point string
     */
    public record ShardBalance(Integer exchangeIndex, Long balance, String balanceDollars) {

        public Optional<Money> available() {
            return balanceDollars == null || balanceDollars.isBlank()
                    ? Optional.empty()
                    : Optional.of(Money.fromFixedPointString(balanceDollars));
        }
    }

    /** Available balance, parsed from the fixed-point string field. */
    public Optional<Money> available() {
        return balanceDollars == null || balanceDollars.isBlank()
                ? Optional.empty()
                : Optional.of(Money.fromFixedPointString(balanceDollars));
    }

    /**
     * Whether the legacy integer (cents) and the fixed-point string agree.
     *
     * <p>Expected to hold today and expected to break eventually — that is the point. The
     * integer cannot represent a sub-penny balance, so a mismatch is the signal that any
     * remaining reliance on integer fields has become a precision bug.
     */
    public boolean legacyFieldAgrees() {
        Optional<Money> parsed = available();
        return balance != null && parsed.isPresent()
                && parsed.get().micros() == balance * 10_000L;
    }
}
