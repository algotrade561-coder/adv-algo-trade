package com.kiteapioptions.marketdata;

import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.Instrument;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.Quote;
import com.kiteapioptions.domain.Timeframe;
import com.kiteapioptions.domain.UnderlyingSymbol;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deterministic-enough local data generator for paper mode tests and demos.
 */
public class MockMarketDataGenerator {

    public List<Instrument> optionInstruments(LocalDate expiry) {
        List<Instrument> instruments = new ArrayList<>();
        addOptionSeries(instruments, UnderlyingSymbol.NIFTY, expiry, 24_000, 50, 50);
        addOptionSeries(instruments, UnderlyingSymbol.BANKNIFTY, expiry, 52_000, 100, 30);
        return List.copyOf(instruments);
    }

    public Quote quote(String instrumentKey) {
        BigDecimal price = instrumentKey.contains("BANKNIFTY") ? BigDecimal.valueOf(220) : BigDecimal.valueOf(120);
        BigDecimal noise = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(-2.0, 2.0));
        return new Quote(instrumentKey, Instant.now(), price.add(noise).max(BigDecimal.ONE),
                25_000, 100_000, Optional.of(BigDecimal.valueOf(18)), Optional.empty(), Optional.empty());
    }

    public List<Candle> candles(String instrumentKey, Instant from, Timeframe timeframe, int count) {
        List<Candle> candles = new ArrayList<>();
        BigDecimal base = instrumentKey.contains("BANKNIFTY") ? BigDecimal.valueOf(220) : BigDecimal.valueOf(120);
        for (int i = 0; i < count; i++) {
            BigDecimal open = base.add(BigDecimal.valueOf(i % 5));
            BigDecimal close = open.add(BigDecimal.valueOf((i % 3) - 1));
            BigDecimal high = open.max(close).add(BigDecimal.valueOf(2));
            BigDecimal low = open.min(close).subtract(BigDecimal.ONE).max(BigDecimal.ONE);
            candles.add(new Candle(instrumentKey, from.plus(timeframe.duration().multipliedBy(i)), timeframe,
                    open, high, low, close, 10_000L + i * 100L, 100_000L + i * 50L));
        }
        return List.copyOf(candles);
    }

    private void addOptionSeries(
            List<Instrument> instruments,
            UnderlyingSymbol underlying,
            LocalDate expiry,
            int atmStrike,
            int step,
            int count
    ) {
        for (int offset = -count / 2; offset <= count / 2; offset++) {
            BigDecimal strike = BigDecimal.valueOf((long) atmStrike + (long) offset * step);
            instruments.add(optionInstrument(instruments.size() + 1L, underlying, expiry, strike, OptionType.CE));
            instruments.add(optionInstrument(instruments.size() + 1L, underlying, expiry, strike, OptionType.PE));
        }
    }

    private Instrument optionInstrument(
            long token,
            UnderlyingSymbol underlying,
            LocalDate expiry,
            BigDecimal strike,
            OptionType optionType
    ) {
        String tradingSymbol = underlying.name() + expiry.getDayOfMonth() + strike.toPlainString() + optionType.name();
        return new Instrument(token, "NFO", tradingSymbol, underlying.name(), Optional.of(underlying),
                Optional.of(expiry), Optional.of(strike), Optional.of(optionType), lotSize(underlying),
                BigDecimal.valueOf(0.05), true);
    }

    private int lotSize(UnderlyingSymbol underlying) {
        return underlying == UnderlyingSymbol.NIFTY ? 75 : 35;
    }
}
