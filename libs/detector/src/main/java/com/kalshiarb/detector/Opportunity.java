package com.kalshiarb.detector;

import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Money;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.Quantity;
import java.util.List;

/**
 * A detected constraint violation, priced end to end.
 *
 * <p>Carries the gross edge, the fees, and the net — because the entire point of the project is
 * that the first number routinely survives neither of the others. A reported opportunity with a
 * positive gross and a negative net is not a failure of the detector; it is the result.
 *
 * @param event         the event family whose brackets these are
 * @param direction     which side of the constraint was violated
 * @param legs          one per bracket traded, in ticker order
 * @param size          contracts per leg, bounded by the thinnest leg's depth
 * @param grossEdge     edge before fees, for the whole {@code size}
 * @param totalFees     sum of per-leg fees, each rounded up to a cent
 * @param netEdge       {@code grossEdge - totalFees}; negative is the expected case
 * @param bindingLeg    the leg whose depth capped {@code size}
 */
public record Opportunity(
        EventTicker event,
        Direction direction,
        List<Leg> legs,
        Quantity size,
        Money grossEdge,
        Money totalFees,
        Money netEdge,
        MarketTicker bindingLeg) {

    public Opportunity {
        legs = List.copyOf(legs);
    }

    /**
     * Which way the exhaustive-bracket constraint was violated.
     *
     * <p>The two are <b>not</b> symmetric, and conflating them invents or hides opportunities.
     */
    public enum Direction {
        /**
         * Short every bracket in the set for {@code sum(bid)}. Exactly one bracket wins, so the
         * liability is capped at $1.00 whether or not every leg is quoted — dropping a leg only
         * lowers the payout. So {@code sum(bid) > $1.00} is a genuine violation over <b>any
         * subset</b>, and an unquoted bracket does not invalidate it.
         */
        SELL_BRACKETS,
        /**
         * Buy every bracket in the set for {@code sum(ask)}. The $1.00 payout only arrives if the
         * winning bracket is in the set, so this needs <b>every</b> bracket quoted. One unquoted
         * leg means there is no constraint to test at all.
         */
        BUY_BRACKETS,
        /**
         * Buy the dominating (lower) threshold and sell the dominated (higher) one, because
         * {@code ask(lower) < bid(higher)} — the contract that pays out in a strict superset of
         * worlds is cheaper than the one it dominates. Two legs, so two fees rather than five.
         */
        MONOTONE_PAIR
    }

    /**
     * One bracket in the trade.
     *
     * @param market       the bracket
     * @param price        the price actually achievable for {@link #size}, from a depth walk —
     *                     not the touch, which is only available for the size resting there
     * @param availableSize how much this leg could absorb
     * @param fee          this leg's fee, already rounded up to a cent
     */
    public record Leg(MarketTicker market, Price price, Quantity availableSize, Money fee) {
    }

    /** True when the trade actually makes money after fees. Usually false. */
    public boolean isProfitable() {
        return netEdge.micros() > 0;
    }

    /** How much of the gross edge the fees consumed, as a percentage. */
    public double feeBurdenPercent() {
        if (grossEdge.micros() == 0) {
            return 0;
        }
        return 100.0 * totalFees.micros() / grossEdge.micros();
    }

    @Override
    public String toString() {
        return String.format(
                "%s %s x%s legs=%d gross=%s fees=%s net=%s%s (binding %s)",
                event, direction, size.toContractString(), legs.size(),
                grossEdge, totalFees, netEdge,
                isProfitable() ? "  PROFITABLE" : "",
                bindingLeg);
    }
}
