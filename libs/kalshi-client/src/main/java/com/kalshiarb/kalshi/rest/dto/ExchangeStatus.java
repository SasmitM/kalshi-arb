package com.kalshiarb.kalshi.rest.dto;

import java.util.List;

/**
 * {@code GET /exchange/status} — whether the exchange and each of its four shards are up.
 *
 * <p>Public, unauthenticated, and therefore the cheapest end-to-end reachability check
 * available; the recorder uses it to distinguish "Kalshi is down" from "our connection is
 * broken".
 *
 * @param exchangeActive             exchange is up
 * @param tradingActive              trading is enabled
 * @param intraExchangeTransfersActive transfers between shards enabled
 * @param exchangeIndexStatuses      per-shard status, indices 0-3
 */
public record ExchangeStatus(
        Boolean exchangeActive,
        Boolean tradingActive,
        Boolean intraExchangeTransfersActive,
        List<IndexStatus> exchangeIndexStatuses) {

    public ExchangeStatus {
        exchangeIndexStatuses =
                exchangeIndexStatuses == null ? List.of() : List.copyOf(exchangeIndexStatuses);
    }

    /**
     * One exchange shard.
     *
     * <p>Observed descriptions: 0 "Default", 1 "Combos", 2 "Crypto", 3 "Tennis &amp; Baseball".
     * Weather markets live on shard 0.
     *
     * @param exchangeIndex shard index 0-3
     * @param description   human label
     * @param exchangeActive shard is up
     * @param tradingActive shard accepts trades
     * @param intraExchangeTransfersActive transfers enabled for this shard
     */
    public record IndexStatus(
            Integer exchangeIndex,
            String description,
            Boolean exchangeActive,
            Boolean tradingActive,
            Boolean intraExchangeTransfersActive) {
    }

    public boolean isUp() {
        return Boolean.TRUE.equals(exchangeActive);
    }

    public boolean isTradingActive() {
        return Boolean.TRUE.equals(tradingActive);
    }
}
