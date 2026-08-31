package com.kalshiarb.kalshi.rest.dto;

import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.PriceLevel;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire shape of {@code GET /markets/{ticker}/orderbook}:
 *
 * <pre>{@code
 * {"orderbook_fp": {
 *     "yes_dollars": [["0.0100", "1408.00"], ["0.0200", "62.00"], ...],
 *     "no_dollars":  [["0.0100", "569.00"],  ...]}}
 * }</pre>
 *
 * <p>Each level is a two-element array of strings: price in dollars, then count. Both sides may
 * be absent or null when that half of the book is empty, which is why {@link #toOrderbook} does
 * not assume a list is present.
 *
 * @param orderbookFp the nested book object
 */
public record OrderbookResponse(OrderbookFp orderbookFp) {

    /** @param yesDollars YES bid levels; @param noDollars NO bid levels */
    public record OrderbookFp(List<List<String>> yesDollars, List<List<String>> noDollars) {
    }

    public Orderbook toOrderbook(MarketTicker ticker) {
        if (orderbookFp == null) {
            return new Orderbook(ticker, List.of(), List.of());
        }
        return new Orderbook(ticker,
                decodeLevels(orderbookFp.yesDollars(), ticker, "yes"),
                decodeLevels(orderbookFp.noDollars(), ticker, "no"));
    }

    private static List<PriceLevel> decodeLevels(
            List<List<String>> raw, MarketTicker ticker, String side) {
        if (raw == null) {
            return List.of();
        }
        List<PriceLevel> levels = new ArrayList<>(raw.size());
        for (List<String> pair : raw) {
            if (pair == null || pair.size() != 2) {
                throw new IllegalArgumentException(
                        "malformed " + side + " level on " + ticker
                                + ": expected [price_dollars, count_fp], got " + pair);
            }
            levels.add(PriceLevel.fromFixedPointStrings(pair.get(0), pair.get(1)));
        }
        return levels;
    }
}
