package com.kalshiarb.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImpliedAskAndInvariantsTest {

    private static final MarketTicker TICKER = MarketTicker.of("KXHIGHNY-26SEP01-T83");

    private static PriceLevel level(long cents, long contracts) {
        return new PriceLevel(Price.ofCents(cents), Quantity.ofContracts(contracts));
    }

    private static Book book(long yesBidCents, long yesSize, long noBidCents, long noSize) {
        Book b = new Book(TICKER);
        b.applySnapshot(
                yesBidCents < 0 ? List.of() : List.of(level(yesBidCents, yesSize)),
                noBidCents < 0 ? List.of() : List.of(level(noBidCents, noSize)));
        return b;
    }

    @Test
    @DisplayName("a NO bid at $0.45 IS a YES ask at $0.55, with the NO bid's size")
    void impliedAskFromOppositeBid() {
        Book b = book(30, 7, 45, 20);

        assertThat(ImpliedAsk.bestAsk(b, Side.YES)).contains(Price.ofCents(55));
        // The contracts you would buy come from the NO bidder.
        assertThat(ImpliedAsk.bestAskSize(b, Side.YES)).contains(Quantity.ofContracts(20));

        assertThat(ImpliedAsk.bestAsk(b, Side.NO)).contains(Price.ofCents(70));
        assertThat(ImpliedAsk.bestAskSize(b, Side.NO)).contains(Quantity.ofContracts(7));
    }

    @Test
    @DisplayName("the spread is identical on both sides, as the algebra requires")
    void spreadIsSideIndependent() {
        Book b = book(30, 1, 45, 1);

        // $1.00 - (0.30 + 0.45) = $0.25 either way.
        assertThat(ImpliedAsk.spread(b, Side.YES)).contains(Price.ofCents(25));
        assertThat(ImpliedAsk.spread(b, Side.NO)).contains(Price.ofCents(25));
    }

    @Test
    @DisplayName("no opposite bid means NO ASK AT ALL, which is not an ask at $1.00")
    void missingOppositeBidMeansNoAsk() {
        // The live KXHIGHNY-26SEP01-T90 shape: NO bids only.
        Book b = book(-1, 0, 99, 557);

        assertThat(ImpliedAsk.bestAsk(b, Side.NO)).isEmpty();
        assertThat(ImpliedAsk.bestAskSize(b, Side.NO)).isEmpty();
        assertThat(ImpliedAsk.spread(b, Side.NO)).isEmpty();
        assertThat(ImpliedAsk.mid(b, Side.NO)).isEmpty();

        // The YES side is still buyable, from the NO bids.
        assertThat(ImpliedAsk.bestAsk(b, Side.YES)).contains(Price.ofCents(1));
    }

    @Test
    @DisplayName("touching bids give a zero spread and an ask equal to the bid")
    void touchingBooksHaveZeroSpread() {
        Book b = book(60, 1, 40, 1);

        assertThat(ImpliedAsk.spread(b, Side.YES)).contains(Price.ZERO);
        assertThat(ImpliedAsk.bestAsk(b, Side.YES)).contains(Price.ofCents(60));
        // Legal: the two sides touch, they do not cross.
        BookInvariants.check(b);
        assertThat(BookInvariants.isCrossed(b)).isFalse();
    }

    @Test
    @DisplayName("bids summing above $1.00 throw, and say it is our bug")
    void crossedBookThrows() {
        Book b = book(60, 1, 41, 1);

        assertThat(BookInvariants.isCrossed(b)).isTrue();
        assertThatThrownBy(() -> BookInvariants.check(b))
                .isInstanceOf(BookInvariants.CrossedBookException.class)
                .hasMessageContaining("crossed book on KXHIGHNY-26SEP01-T83")
                .hasMessageContaining("$0.6000")
                .hasMessageContaining("$0.4100")
                .hasMessageContaining("1.0100")
                .hasMessageContaining("not an opportunity");
    }

    @Test
    @DisplayName("a single micro-dollar of crossing is caught")
    void detectsSmallestCrossing() {
        Book justUnder = new Book(TICKER);
        justUnder.applySnapshot(
                List.of(new PriceLevel(new Price(500_000), Quantity.ofContracts(1))),
                List.of(new PriceLevel(new Price(499_999), Quantity.ofContracts(1))));
        BookInvariants.check(justUnder);

        Book justOver = new Book(TICKER);
        justOver.applySnapshot(
                List.of(new PriceLevel(new Price(500_001), Quantity.ofContracts(1))),
                List.of(new PriceLevel(new Price(500_000), Quantity.ofContracts(1))));
        assertThatThrownBy(() -> BookInvariants.check(justOver))
                .isInstanceOf(BookInvariants.CrossedBookException.class);
    }

    @Test
    @DisplayName("a one-sided book passes the invariant vacuously, not by luck")
    void oneSidedPassesVacuously() {
        BookInvariants.check(book(-1, 0, 99, 5));
        BookInvariants.check(book(1, 5, -1, 0));
        BookInvariants.check(new Book(TICKER));
    }

    @Test
    @DisplayName("a delta that crosses the book is detectable right after it is applied")
    void crossingIntroducedByDelta() {
        Book b = book(30, 5, 40, 5);
        BookInvariants.check(b);

        // A bogus delta creates a YES bid at $0.65 while NO is bid $0.40 -> sums to $1.05.
        b.applyDelta(Side.YES, Price.ofCents(65), 1_000_000L);

        assertThat(BookInvariants.isCrossed(b)).isTrue();
        assertThatThrownBy(() -> BookInvariants.check(b))
                .isInstanceOf(BookInvariants.CrossedBookException.class);
    }

    @Test
    @DisplayName("mid rounds down on an odd micro total and never invents precision")
    void midRoundsDown() {
        Book b = new Book(TICKER);
        b.applySnapshot(
                List.of(new PriceLevel(new Price(300_001), Quantity.ofContracts(1))),
                List.of(new PriceLevel(new Price(400_000), Quantity.ofContracts(1))));

        // bid 0.300001, ask 0.600000 -> mid 0.4500005 -> floors to 450000 micros.
        assertThat(ImpliedAsk.mid(b, Side.YES).orElseThrow().micros()).isEqualTo(450_000L);
    }
}
