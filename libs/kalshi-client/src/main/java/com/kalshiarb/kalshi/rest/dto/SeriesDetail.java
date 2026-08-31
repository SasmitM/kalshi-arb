package com.kalshiarb.kalshi.rest.dto;

import com.kalshiarb.model.SeriesTicker;

/**
 * One series from {@code GET /series/{ticker}}.
 *
 * <p>Carries the two fee fields, which matter more than they look. CLAUDE.md warns that the fee
 * multiplier is category-specific and higher for crypto, and that third-party blogs get the fee
 * schedule wrong constantly — but the API states it per series:
 * {@code fee_type: "quadratic"}, {@code fee_multiplier: 1} for {@code KXHIGHNY}. The fee model
 * should read these rather than hardcode a constant, for the same reason endpoint costs are
 * fetched instead of assumed.
 *
 * <p>{@code fee_multiplier} is an integer in the observed payload but is modelled as a raw
 * {@code String} so a fractional multiplier cannot silently truncate or pull a {@code double}
 * into the fee path.
 *
 * @param ticker         e.g. {@code KXHIGHNY}
 * @param title          human title
 * @param category       e.g. {@code "Climate and Weather"}
 * @param frequency      e.g. {@code "daily"}
 * @param feeType        e.g. {@code "quadratic"}
 * @param feeMultiplier  per-series fee multiplier, as exact wire text
 * @param contractTermsUrl link to the authoritative contract terms PDF
 * @param exchangeIndex  exchange shard 0-3
 */
public record SeriesDetail(
        String ticker,
        String title,
        String category,
        String frequency,
        String feeType,
        String feeMultiplier,
        String contractTermsUrl,
        Integer exchangeIndex) {

    public SeriesTicker series() {
        return SeriesTicker.of(ticker);
    }
}
