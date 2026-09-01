package com.kalshiarb.detector;

import com.kalshiarb.model.FixedPoint;
import com.kalshiarb.model.Money;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.Quantity;
import java.math.BigInteger;

/**
 * Kalshi's quadratic taker fee.
 *
 * <pre>{@code   fee = ceil(0.07 * multiplier * contracts * P * (1 - P))   }</pre>
 *
 * rounded <b>up to the next cent on the order total</b>.
 *
 * <p>Four properties drive every decision downstream, and each is a trap:
 *
 * <ol>
 *   <li><b>It is a parabola, peaking at $0.0175 per contract at P = $0.50</b> and collapsing at
 *       the extremes ($0.0063 at $0.10, $0.0033 at $0.05). So the break-even edge is
 *       <b>price-dependent</b>, and a constant cent threshold is always wrong — too lax near
 *       $0.50, too strict in the tails.
 *   <li><b>It is charged per leg.</b> A five-bracket arbitrage pays five fees, so two-leg
 *       opportunities structurally beat five-leg ones before any market data is considered.
 *   <li><b>The ceiling applies to the ORDER, not per contract.</b>
 *       {@code ceil(0.07 * 8 * 0.10 * 0.90)} is not {@code 8 * ceil(0.07 * 0.10 * 0.90)} — the
 *       second is roughly eight times too large.
 *   <li><b>Rounding taxes small orders brutally.</b> One contract at $0.10 owes 0.63c and pays a
 *       full cent: 59% overhead. This is why an opportunity needs a minimum viable size rather
 *       than just a positive edge, and why a 3c gross edge on 1 contract is not a trade.
 * </ol>
 *
 * <p>The multiplier is per-series and comes from {@code GET /series/{ticker}}
 * ({@code fee_type: "quadratic"}, {@code fee_multiplier: 1} for {@code KXHIGHNY}). It is read
 * from the API rather than hardcoded, for the same reason endpoint costs are fetched instead of
 * assumed — it is category-specific and higher for crypto.
 *
 * <p><b>On BigInteger.</b> CLAUDE.md bans big-number arithmetic on the hot path, and this is not
 * the hot path: it runs per candidate opportunity, not per frame. Exactness is bought
 * deliberately, because the result is immediately rounded up to a cent — so a one-micro error in
 * the intermediate can flip a whole cent of fee at a knife-edge, and that cent decides whether an
 * opportunity is viable. The exact product needs 7 * 10^12 * 2.5 * 10^11, which overflows a long.
 */
public final class FeeModel {

    /** Numerator of the 0.07 rate, as an exact integer fraction 7/100. */
    private static final BigInteger RATE_NUMERATOR = BigInteger.valueOf(7);

    private static final BigInteger RATE_DENOMINATOR = BigInteger.valueOf(100);
    private static final BigInteger ONE_MICROS = BigInteger.valueOf(FixedPoint.ONE);

    /** Micro-dollars in one cent. Fees round up to a multiple of this. */
    public static final long CENT_MICROS = 10_000L;

    private final long multiplier;

    /**
     * @param multiplier the series {@code fee_multiplier}; 1 for weather markets
     */
    public FeeModel(long multiplier) {
        if (multiplier <= 0) {
            throw new IllegalArgumentException("fee multiplier must be positive: " + multiplier);
        }
        this.multiplier = multiplier;
    }

    /** The default for {@code KXHIGH*} weather series, whose published multiplier is 1. */
    public static FeeModel weather() {
        return new FeeModel(1);
    }

    /**
     * Parses the {@code fee_multiplier} string from {@code GET /series/{ticker}}.
     *
     * @throws IllegalArgumentException if the series uses a non-quadratic fee type, since the
     *                                 formula here would then simply be wrong
     */
    public static FeeModel fromSeries(String feeType, String feeMultiplier) {
        if (feeType != null && !"quadratic".equals(feeType)) {
            throw new IllegalArgumentException(
                    "fee_type is '" + feeType + "', not 'quadratic'; this model does not apply");
        }
        return new FeeModel(Long.parseLong(feeMultiplier.trim()));
    }

    /**
     * Fee for one order: {@code contracts} contracts at {@code price}, rounded up to a cent.
     *
     * <p>Order-level, which is the whole point — see property 3 above.
     */
    public Money orderFee(Quantity contracts, Price price) {
        long exact = exactFeeMicros(contracts.microContracts(), price.micros());
        return new Money(ceilToCent(exact));
    }

    /**
     * Fee before the cent ceiling, in micro-dollars.
     *
     * <p>Exposed because the unrounded value is the honest marginal cost as size grows: the
     * ceiling's relative bite shrinks toward zero, so this is the asymptote a minimum-viable-size
     * search is converging on.
     */
    public Money unroundedOrderFee(Quantity contracts, Price price) {
        return new Money(exactFeeMicros(contracts.microContracts(), price.micros()));
    }

    /** Fee per contract at {@code price}, unrounded. Peaks at $0.0175 at P = $0.50. */
    public Money feePerContract(Price price) {
        return new Money(exactFeeMicros(FixedPoint.ONE, price.micros()));
    }

    /**
     * {@code 0.07 * multiplier * contracts * P * (1 - P)} in micro-dollars, truncated toward
     * zero at micro resolution.
     */
    private long exactFeeMicros(long microContracts, long priceMicros) {
        if (microContracts <= 0) {
            return 0;
        }
        // fee_micros = 7 * mult * N_m * P_m * (ONE - P_m) / (100 * ONE^2)
        BigInteger numerator = RATE_NUMERATOR
                .multiply(BigInteger.valueOf(multiplier))
                .multiply(BigInteger.valueOf(microContracts))
                .multiply(BigInteger.valueOf(priceMicros))
                .multiply(BigInteger.valueOf(FixedPoint.ONE - priceMicros));
        BigInteger denominator = RATE_DENOMINATOR.multiply(ONE_MICROS).multiply(ONE_MICROS);
        return numerator.divide(denominator).longValueExact();
    }

    /** Rounds up to the next whole cent. Exact multiples are unchanged. */
    public static long ceilToCent(long micros) {
        if (micros <= 0) {
            return 0;
        }
        long remainder = micros % CENT_MICROS;
        return remainder == 0 ? micros : micros + (CENT_MICROS - remainder);
    }

    public long multiplier() {
        return multiplier;
    }
}
