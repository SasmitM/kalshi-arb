package com.kalshiarb.kalshi.rest.dto;

import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.Quantity;
import java.util.Optional;

/**
 * One market from {@code GET /markets}, capturing the fields this project uses.
 *
 * <p>Written against {@code samples/markets_KXHIGHNY-26SEP01.json}, not against the docs.
 * Unknown fields are ignored by the mapper, so Kalshi adding fields does not break parsing.
 *
 * <p>Two field-typing decisions worth stating:
 *
 * <ul>
 *   <li><b>Prices stay as raw strings here</b> and are converted through {@link Price} on
 *       demand. The {@code _dollars} fields are absent on some market types, and a null
 *       {@code String} models "not quoted" honestly, whereas a {@code Price} field would need a
 *       sentinel. Nothing downstream sees the string.
 *   <li><b>{@code floorStrike} / {@code capStrike} are {@code String}, not numbers.</b> They
 *       arrive as JSON numbers ({@code 87}) and Jackson coerces them to their exact text. Using
 *       {@code double} would violate the no-float rule; using {@code Integer} would break on any
 *       series with fractional strikes. Keeping the wire text loses nothing and decides nothing.
 * </ul>
 *
 * <p><b>Strike fields are off by one from the human sub-title.</b> Real example: {@code T83}
 * carries {@code capStrike: "83"} and {@code strikeType: "less"} but its {@code yesSubTitle} is
 * "82° or below". Do not derive bracket boundaries from these fields without reconciling
 * against {@code yesSubTitle} first.
 */
public record MarketSummary(
        String ticker,
        String eventTicker,
        String marketType,
        String status,
        String title,
        String yesSubTitle,
        String noSubTitle,
        String strikeType,
        String floorStrike,
        String capStrike,
        String yesBidDollars,
        String yesAskDollars,
        String noBidDollars,
        String noAskDollars,
        String lastPriceDollars,
        String volumeFp,
        // The snake_case strategy maps this to "volume24h_fp", but the wire field is
        // "volume_24h_fp" -- the strategy inserts a separator before uppercase letters only,
        // never around digit runs. Explicit name required.
        @com.fasterxml.jackson.annotation.JsonProperty("volume_24h_fp") String volume24hFp,
        String openInterestFp,
        String liquidityDollars,
        String notionalValueDollars,
        String openTime,
        String closeTime,
        // Which of the four exchange shards this market lives on (0-3). Unused while the
        // project is read-only, but writes draw from per-shard budgets, so the field is
        // carried from the start rather than retrofitted later.
        Integer exchangeIndex) {

    public MarketTicker marketTicker() {
        return MarketTicker.of(ticker);
    }

    public EventTicker event() {
        return EventTicker.of(eventTicker);
    }

    /** True when Kalshi reports this market as tradable. Note: the value is {@code "active"}. */
    public boolean isActive() {
        return "active".equals(status);
    }

    public Optional<Price> yesBid() {
        return price(yesBidDollars);
    }

    public Optional<Price> yesAsk() {
        return price(yesAskDollars);
    }

    public Optional<Price> noBid() {
        return price(noBidDollars);
    }

    public Optional<Price> noAsk() {
        return price(noAskDollars);
    }

    public Optional<Price> lastPrice() {
        return price(lastPriceDollars);
    }

    public Optional<Quantity> openInterest() {
        return openInterestFp == null || openInterestFp.isBlank()
                ? Optional.empty()
                : Optional.of(Quantity.fromFixedPointString(openInterestFp));
    }

    public Optional<Quantity> volume() {
        return volumeFp == null || volumeFp.isBlank()
                ? Optional.empty()
                : Optional.of(Quantity.fromFixedPointString(volumeFp));
    }

    private static Optional<Price> price(String dollars) {
        return dollars == null || dollars.isBlank()
                ? Optional.empty()
                : Optional.of(Price.fromFixedPointString(dollars));
    }
}
