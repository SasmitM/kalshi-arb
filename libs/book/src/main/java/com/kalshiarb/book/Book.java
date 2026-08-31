package com.kalshiarb.book;

import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Live order book for one market: two bid ladders, kept as price to size.
 *
 * <p>Kalshi publishes <b>bids only, on both sides</b>. There is no ask ladder to store; an ask is
 * always derived by complementing the opposite side's bid (see {@link ImpliedAsk}). Storing only
 * what the exchange sends means there is no second representation to keep in sync.
 *
 * <h2>Why NavigableMap</h2>
 * {@code TreeMap<Long, Long>} keyed on price micros, per CLAUDE.md. Two operations justify the
 * ordering: the best bid is {@code lastEntry()} in O(log n), and {@link DepthWalker} needs to
 * walk levels outward from the top in price order. A {@code HashMap} would make both a full scan.
 * Boxing is accepted: the network round trip is ~98% of the latency budget, so a few nanoseconds
 * per level is not where this project's time goes.
 *
 * <h2>No locks</h2>
 * Deliberately not thread-safe, and that is the design rather than an omission. Subscriptions are
 * sharded one WebSocket per event family, each with its own pump thread, so every book is written
 * by exactly one thread and no constraint group spans threads. Single-writer-per-shard is the
 * entire concurrency model; adding synchronisation here would buy nothing and imply a sharing
 * pattern that does not exist.
 *
 * <h2>No sequence numbers</h2>
 * {@code seq} is scoped to the subscription, not the market, so it does not belong on a
 * per-market book. Sequencing, gap detection and recovery live in the WebSocket layer; this class
 * only knows how to hold state and mutate it. That separation is what lets the detector be a pure
 * function over book state and lets a replay drive identical code.
 */
public final class Book {

    private final MarketTicker ticker;

    /** Price micros to size in micro-contracts. Ascending, so the best bid is the last entry. */
    private final NavigableMap<Long, Long> yesBids = new TreeMap<>();
    private final NavigableMap<Long, Long> noBids = new TreeMap<>();

    private long snapshotsApplied;
    private long deltasApplied;

    public Book(MarketTicker ticker) {
        this.ticker = ticker;
    }

    public MarketTicker ticker() {
        return ticker;
    }

    public long snapshotsApplied() {
        return snapshotsApplied;
    }

    public long deltasApplied() {
        return deltasApplied;
    }

    private NavigableMap<Long, Long> side(Side side) {
        return side == Side.YES ? yesBids : noBids;
    }

    /**
     * Replaces all state with a snapshot.
     *
     * <p>Both sides are cleared first. A snapshot is authoritative and complete, so merging into
     * existing state would leave stale levels the exchange no longer reports — the exact failure
     * that a snapshot exists to repair.
     */
    public void applySnapshot(List<PriceLevel> yes, List<PriceLevel> no) {
        yesBids.clear();
        noBids.clear();
        load(yesBids, yes, Side.YES);
        load(noBids, no, Side.NO);
        snapshotsApplied++;
    }

    private void load(NavigableMap<Long, Long> target, List<PriceLevel> levels, Side bookSide) {
        if (levels == null) {
            return;
        }
        for (PriceLevel level : levels) {
            long size = level.quantity().microContracts();
            if (size == 0) {
                // A zero level in a snapshot carries no information and would corrupt
                // best-bid lookups if stored.
                continue;
            }
            Long previous = target.put(level.price().micros(), size);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "snapshot for " + ticker + " " + bookSide
                                + " repeats price " + level.price()
                                + "; cannot tell which size is current");
            }
        }
    }

    /**
     * Applies a signed size change at one level.
     *
     * @param deltaMicroContracts signed; negative is a cancel, fill, or expiry
     * @throws DeltaApplier.NegativeLevelException if the level would go negative
     */
    public void applyDelta(Side side, Price price, long deltaMicroContracts) {
        DeltaApplier.apply(side(side), price.micros(), deltaMicroContracts, ticker, side);
        deltasApplied++;
    }

    // ---------------------------------------------------------------- queries

    /** Highest resting bid on {@code side}, or empty if that ladder is empty. */
    public Optional<Price> bestBid(Side side) {
        Map.Entry<Long, Long> best = side(side).lastEntry();
        return best == null ? Optional.empty() : Optional.of(new Price(best.getKey()));
    }

    /** Size resting at the best bid on {@code side}. */
    public Optional<Quantity> bestBidSize(Side side) {
        Map.Entry<Long, Long> best = side(side).lastEntry();
        return best == null ? Optional.empty() : Optional.of(new Quantity(best.getValue()));
    }

    /** Size resting at an exact price, zero if that level does not exist. */
    public Quantity sizeAt(Side side, Price price) {
        return new Quantity(side(side).getOrDefault(price.micros(), 0L));
    }

    public int levelCount(Side side) {
        return side(side).size();
    }

    /** Total size resting across every level on {@code side}. */
    public Quantity totalDepth(Side side) {
        long total = 0;
        for (long size : side(side).values()) {
            total += size;
        }
        return new Quantity(total);
    }

    public boolean isEmpty() {
        return yesBids.isEmpty() && noBids.isEmpty();
    }

    public boolean isTwoSided() {
        return !yesBids.isEmpty() && !noBids.isEmpty();
    }

    /** Levels on {@code side}, best (highest) price first. */
    public List<PriceLevel> levelsBestFirst(Side side) {
        NavigableMap<Long, Long> map = side(side);
        List<PriceLevel> out = new ArrayList<>(map.size());
        for (Map.Entry<Long, Long> e : map.descendingMap().entrySet()) {
            out.add(new PriceLevel(new Price(e.getKey()), new Quantity(e.getValue())));
        }
        return out;
    }

    /**
     * Read-only view of one ladder, ascending by price.
     *
     * <p>Exposed for {@link DepthWalker} and {@link BookInvariants} so neither needs its own copy
     * of the traversal logic. Unmodifiable so a caller cannot mutate state that only the owning
     * thread should touch.
     */
    NavigableMap<Long, Long> ladder(Side side) {
        return Collections.unmodifiableNavigableMap(side(side));
    }

    @Override
    public String toString() {
        return "Book[" + ticker + " yes=" + yesBids.size() + " levels, no=" + noBids.size()
                + " levels, snapshots=" + snapshotsApplied + ", deltas=" + deltasApplied + "]";
    }
}
