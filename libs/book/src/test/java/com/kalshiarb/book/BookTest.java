package com.kalshiarb.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BookTest {

    private static final MarketTicker TICKER = MarketTicker.of("KXHIGHNY-26SEP01-T83");

    private Book book;

    @BeforeEach
    void setUp() {
        book = new Book(TICKER);
    }

    private static PriceLevel level(long cents, long contracts) {
        return new PriceLevel(Price.ofCents(cents), Quantity.ofContracts(contracts));
    }

    @Test
    @DisplayName("a fresh book is empty and quotes nothing")
    void freshBookIsEmpty() {
        assertThat(book.isEmpty()).isTrue();
        assertThat(book.isTwoSided()).isFalse();
        assertThat(book.bestBid(Side.YES)).isEmpty();
        assertThat(book.bestBid(Side.NO)).isEmpty();
        assertThat(book.levelCount(Side.YES)).isZero();
        assertThat(book.totalDepth(Side.YES)).isEqualTo(Quantity.ZERO);
    }

    @Test
    @DisplayName("best bid is the HIGHEST price, regardless of insertion order")
    void bestBidIsHighest() {
        book.applySnapshot(
                List.of(level(10, 5), level(50, 1), level(30, 9)),
                List.of(level(20, 4)));

        assertThat(book.bestBid(Side.YES)).contains(Price.ofCents(50));
        assertThat(book.bestBidSize(Side.YES)).contains(Quantity.ofContracts(1));
        assertThat(book.bestBid(Side.NO)).contains(Price.ofCents(20));
    }

    @Test
    @DisplayName("a snapshot REPLACES state; stale levels do not survive it")
    void snapshotReplacesState() {
        book.applySnapshot(List.of(level(10, 5), level(20, 5)), List.of(level(70, 5)));
        assertThat(book.levelCount(Side.YES)).isEqualTo(2);

        // The whole point of a re-snapshot is to discard state we no longer trust. Merging
        // would leave the $0.20 level alive even though the exchange stopped reporting it.
        book.applySnapshot(List.of(level(30, 1)), List.of());

        assertThat(book.levelCount(Side.YES)).isEqualTo(1);
        assertThat(book.levelCount(Side.NO)).isZero();
        assertThat(book.sizeAt(Side.YES, Price.ofCents(20))).isEqualTo(Quantity.ZERO);
        assertThat(book.bestBid(Side.YES)).contains(Price.ofCents(30));
        assertThat(book.snapshotsApplied()).isEqualTo(2);
    }

    @Test
    @DisplayName("zero-size levels in a snapshot are dropped, not stored")
    void snapshotDropsZeroLevels() {
        book.applySnapshot(
                List.of(level(10, 5), new PriceLevel(Price.ofCents(60), Quantity.ZERO)),
                List.of());

        // A stored zero at $0.60 would become a phantom best bid.
        assertThat(book.levelCount(Side.YES)).isEqualTo(1);
        assertThat(book.bestBid(Side.YES)).contains(Price.ofCents(10));
    }

    @Test
    @DisplayName("a snapshot repeating a price is rejected rather than silently resolved")
    void snapshotRejectsDuplicatePrice() {
        assertThatThrownBy(() -> book.applySnapshot(
                List.of(level(10, 5), level(10, 7)), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeats price");
    }

    @Test
    @DisplayName("deltas accumulate onto snapshot state")
    void deltasApplyOverSnapshot() {
        book.applySnapshot(List.of(level(45, 100)), List.of(level(50, 10)));

        book.applyDelta(Side.YES, Price.ofCents(45), 25_000_000L);   // +25
        book.applyDelta(Side.YES, Price.ofCents(46), 5_000_000L);    // new level

        assertThat(book.sizeAt(Side.YES, Price.ofCents(45)))
                .isEqualTo(Quantity.ofContracts(125));
        assertThat(book.bestBid(Side.YES)).contains(Price.ofCents(46));
        assertThat(book.deltasApplied()).isEqualTo(2);
    }

    @Test
    @DisplayName("emptying the best level promotes the next one down")
    void removingBestLevelPromotesNext() {
        book.applySnapshot(List.of(level(40, 5), level(45, 3), level(50, 1)), List.of());
        assertThat(book.bestBid(Side.YES)).contains(Price.ofCents(50));

        book.applyDelta(Side.YES, Price.ofCents(50), -1_000_000L);

        assertThat(book.bestBid(Side.YES)).contains(Price.ofCents(45));
        assertThat(book.levelCount(Side.YES)).isEqualTo(2);
    }

    @Test
    @DisplayName("a negative level propagates the throw rather than swallowing it")
    void negativeLevelPropagates() {
        book.applySnapshot(List.of(level(45, 5)), List.of());

        assertThatThrownBy(() -> book.applyDelta(Side.YES, Price.ofCents(45), -6_000_000L))
                .isInstanceOf(DeltaApplier.NegativeLevelException.class);
    }

    @Test
    @DisplayName("total depth sums every level; sides are tracked separately")
    void depthAndSideIsolation() {
        book.applySnapshot(
                List.of(level(10, 5), level(20, 15)),
                List.of(level(70, 100)));

        assertThat(book.totalDepth(Side.YES)).isEqualTo(Quantity.ofContracts(20));
        assertThat(book.totalDepth(Side.NO)).isEqualTo(Quantity.ofContracts(100));

        book.applyDelta(Side.YES, Price.ofCents(10), -5_000_000L);

        assertThat(book.totalDepth(Side.YES)).isEqualTo(Quantity.ofContracts(15));
        assertThat(book.totalDepth(Side.NO))
                .as("a YES delta must not touch the NO ladder")
                .isEqualTo(Quantity.ofContracts(100));
    }

    @Test
    @DisplayName("levelsBestFirst returns descending price order")
    void levelsBestFirst() {
        book.applySnapshot(List.of(level(10, 1), level(30, 2), level(20, 3)), List.of());

        assertThat(book.levelsBestFirst(Side.YES))
                .extracting(l -> l.price().micros())
                .containsExactly(300_000L, 200_000L, 100_000L);
    }

    @Test
    @DisplayName("the ladder view is unmodifiable")
    void ladderIsUnmodifiable() {
        book.applySnapshot(List.of(level(10, 1)), List.of());

        assertThatThrownBy(() -> book.ladder(Side.YES).put(1L, 1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a real one-sided book is valid: T90 genuinely had no YES bids")
    void oneSidedBookIsValid() {
        book.applySnapshot(List.of(), List.of(level(99, 557), level(98, 75)));

        assertThat(book.isEmpty()).isFalse();
        assertThat(book.isTwoSided()).isFalse();
        assertThat(book.bestBid(Side.YES)).isEmpty();
        assertThat(book.bestBid(Side.NO)).contains(Price.ofCents(99));
        BookInvariants.check(book);
    }
}
