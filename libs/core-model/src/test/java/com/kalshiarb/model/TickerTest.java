package com.kalshiarb.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TickerTest {

    @DisplayName("every real KXHIGHNY-26SEP01 market ticker resolves to its event and series")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "KXHIGHNY-26SEP01-T83",
            "KXHIGHNY-26SEP01-B83.5",
            "KXHIGHNY-26SEP01-B85.5",
            "KXHIGHNY-26SEP01-B87.5",
            "KXHIGHNY-26SEP01-B89.5",
            "KXHIGHNY-26SEP01-T90",
    })
    void derivesEventAndSeries(String raw) {
        MarketTicker market = MarketTicker.of(raw);
        assertThat(market.eventTicker()).isEqualTo(EventTicker.of("KXHIGHNY-26SEP01"));
        assertThat(market.seriesTicker()).isEqualTo(SeriesTicker.of("KXHIGHNY"));
    }

    @Test
    @DisplayName("all six brackets share one event ticker — that is the shard key")
    void bracketsShareOneShardKey() {
        String[] raw = {
                "KXHIGHNY-26SEP01-T83", "KXHIGHNY-26SEP01-B83.5", "KXHIGHNY-26SEP01-B85.5",
                "KXHIGHNY-26SEP01-B87.5", "KXHIGHNY-26SEP01-B89.5", "KXHIGHNY-26SEP01-T90"};

        assertThat(java.util.Arrays.stream(raw)
                .map(MarketTicker::of)
                .map(MarketTicker::eventTicker)
                .distinct())
                .containsExactly(EventTicker.of("KXHIGHNY-26SEP01"));
    }

    @Test
    @DisplayName("a different city is a different series, so a different shard")
    void differentCityDifferentShard() {
        MarketTicker nyc = MarketTicker.of("KXHIGHNY-26SEP01-B87.5");
        MarketTicker chi = MarketTicker.of("KXHIGHCHI-26SEP01-B87.5");

        assertThat(nyc.seriesTicker()).isNotEqualTo(chi.seriesTicker());
        assertThat(nyc.eventTicker()).isNotEqualTo(chi.eventTicker());
    }

    @Test
    @DisplayName("a different day is a different event, so a different shard")
    void differentDayDifferentShard() {
        MarketTicker sep01 = MarketTicker.of("KXHIGHNY-26SEP01-B87.5");
        MarketTicker sep02 = MarketTicker.of("KXHIGHNY-26SEP02-B87.5");

        assertThat(sep01.eventTicker()).isNotEqualTo(sep02.eventTicker());
        assertThat(sep01.seriesTicker()).isEqualTo(sep02.seriesTicker());
    }

    @Test
    @DisplayName("event ticker resolves its series")
    void eventResolvesSeries() {
        assertThat(EventTicker.of("KXHIGHNY-26SEP01").seriesTicker())
                .isEqualTo(SeriesTicker.of("KXHIGHNY"));
    }

    @Test
    @DisplayName("a market ticker with no strike segment is rejected, not silently mangled")
    void rejectsMalformedMarketTicker() {
        assertThatThrownBy(() -> MarketTicker.of("KXHIGHNY-26SEP01").eventTicker())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SERIES-EVENTDATE-STRIKE");
        assertThatThrownBy(() -> MarketTicker.of("KXHIGHNY").eventTicker())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventTicker.of("KXHIGHNY").seriesTicker())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no series prefix");
    }

    @Test
    @DisplayName("blank and null tickers are rejected at construction")
    void rejectsBlank() {
        assertThatThrownBy(() -> MarketTicker.of("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventTicker.of(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeriesTicker.of(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
