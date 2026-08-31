package com.kalshiarb.model;

/**
 * One resting level in an order book: a price and the size available there.
 *
 * <p>On the wire these arrive as string pairs, e.g. {@code ["0.0800", "300.00"]}.
 *
 * @param price the level price
 * @param quantity the size resting at that price
 */
public record PriceLevel(Price price, Quantity quantity) {

    public PriceLevel {
        if (price == null) {
            throw new IllegalArgumentException("price level has null price");
        }
        if (quantity == null) {
            throw new IllegalArgumentException("price level has null quantity");
        }
    }

    /** Parses a wire pair such as {@code ["0.0800", "300.00"]}. */
    public static PriceLevel fromFixedPointStrings(String priceDollars, String countFp) {
        return new PriceLevel(
                Price.fromFixedPointString(priceDollars),
                Quantity.fromFixedPointString(countFp));
    }

    /**
     * Notional value of the level: price times size, in micro-dollars.
     *
     * <p>Multiply first, divide once, in {@code long} throughout. {@code multiplyExact} makes an
     * overflow loud instead of wrapping into a plausible-looking wrong number.
     */
    public Money notional() {
        long product = Math.multiplyExact(price.micros(), quantity.microContracts());
        return new Money(product / FixedPoint.ONE);
    }

    @Override
    public String toString() {
        return quantity.toContractString() + " @ " + price;
    }
}
