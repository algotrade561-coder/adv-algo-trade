package com.kiteapioptions.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndicatorTest {

    @Test
    void calculatesVwap() {
        var vwap = new VwapIndicator().calculate(List.of(
                candle(100, 110, 90, 105, 1000),
                candle(105, 120, 100, 115, 2000)
        ));

        assertThat(vwap).isGreaterThan(BigDecimal.valueOf(106));
        assertThat(vwap).isLessThan(BigDecimal.valueOf(112));
    }

    @Test
    void calculatesEma() {
        var ema = new EmaIndicator().calculate(List.of(
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(110),
                BigDecimal.valueOf(120)
        ), 3);

        assertThat(ema).isEqualByComparingTo(BigDecimal.valueOf(112.5));
    }

    @Test
    void detectsVolumeSpike() {
        List<Candle> candles = List.of(
                candle(100, 101, 99, 100, 100),
                candle(100, 101, 99, 100, 110),
                candle(100, 101, 99, 100, 120),
                candle(100, 101, 99, 100, 400)
        );

        assertThat(new VolumeSpikeDetector().hasSpike(candles, 3, BigDecimal.valueOf(2))).isTrue();
    }

    @Test
    void detectsSwingBreakout() {
        List<Candle> candles = List.of(
                candle(100, 101, 99, 100, 100),
                candle(101, 102, 100, 101, 100),
                candle(102, 103, 101, 102, 100),
                candle(104, 108, 103, 107, 100)
        );

        assertThat(new BreakoutDetector().breaksAboveSwingHigh(candles, 3, BigDecimal.valueOf(0.1))).isTrue();
    }

    @Test
    void calculatesRsiAbove50OnUptrend() {
        List<BigDecimal> closes = List.of(
                BigDecimal.valueOf(100), BigDecimal.valueOf(102), BigDecimal.valueOf(104),
                BigDecimal.valueOf(103), BigDecimal.valueOf(105), BigDecimal.valueOf(107),
                BigDecimal.valueOf(106), BigDecimal.valueOf(108), BigDecimal.valueOf(110),
                BigDecimal.valueOf(109), BigDecimal.valueOf(111), BigDecimal.valueOf(113),
                BigDecimal.valueOf(112), BigDecimal.valueOf(114), BigDecimal.valueOf(116)
        );

        BigDecimal rsi = new RsiIndicator().calculate(closes, 14);

        assertThat(rsi).isGreaterThan(BigDecimal.valueOf(50));
    }

    @Test
    void calculatesRsiBelow50OnDowntrend() {
        List<BigDecimal> closes = List.of(
                BigDecimal.valueOf(116), BigDecimal.valueOf(114), BigDecimal.valueOf(112),
                BigDecimal.valueOf(113), BigDecimal.valueOf(111), BigDecimal.valueOf(109),
                BigDecimal.valueOf(110), BigDecimal.valueOf(108), BigDecimal.valueOf(106),
                BigDecimal.valueOf(107), BigDecimal.valueOf(105), BigDecimal.valueOf(103),
                BigDecimal.valueOf(104), BigDecimal.valueOf(102), BigDecimal.valueOf(100)
        );

        BigDecimal rsi = new RsiIndicator().calculate(closes, 14);

        assertThat(rsi).isLessThan(BigDecimal.valueOf(50));
    }

    @Test
    void rsiReturnsFiftyWhenInsufficientData() {
        BigDecimal rsi = new RsiIndicator().calculate(List.of(BigDecimal.valueOf(100)), 14);

        assertThat(rsi).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    @Test
    void sessionAnchoredVwapIgnoresPreviousDayCandles() {
        VwapIndicator indicator = new VwapIndicator();
        List<Candle> candles = List.of(
                new Candle("NSE:NIFTY 50", Instant.parse("2026-04-11T09:25:00Z"), Timeframe.ONE_MINUTE,
                        BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                        BigDecimal.valueOf(100), 1000, 0),
                new Candle("NSE:NIFTY 50", Instant.parse("2026-04-12T03:45:00Z"), Timeframe.ONE_MINUTE,
                        BigDecimal.valueOf(200), BigDecimal.valueOf(200), BigDecimal.valueOf(200),
                        BigDecimal.valueOf(200), 1000, 0),
                new Candle("NSE:NIFTY 50", Instant.parse("2026-04-12T03:46:00Z"), Timeframe.ONE_MINUTE,
                        BigDecimal.valueOf(300), BigDecimal.valueOf(300), BigDecimal.valueOf(300),
                        BigDecimal.valueOf(300), 1000, 0)
        );

        BigDecimal vwap = indicator.calculateSessionAnchored(candles, ZoneId.of("Asia/Kolkata"));

        assertThat(vwap).isEqualByComparingTo(BigDecimal.valueOf(250));
    }

    private Candle candle(int open, int high, int low, int close, long volume) {
        return new Candle("NSE:NIFTY 50", Instant.parse("2026-04-12T09:15:00Z"), Timeframe.ONE_MINUTE,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high), BigDecimal.valueOf(low),
                BigDecimal.valueOf(close), volume, 0);
    }
}
