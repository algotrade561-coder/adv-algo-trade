package com.kiteapioptions.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiteapioptions.domain.Quote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OiChangeTrackerTest {

    @Test
    void tracksOpenInterestDelta() {
        OiChangeTracker tracker = new OiChangeTracker();
        Quote first = quote(100, 1000);
        Quote second = quote(110, 1300);

        tracker.update(first);
        var change = tracker.update(second);

        assertThat(change.change()).isEqualTo(300);
        assertThat(tracker.priceAndOiRising(first, second)).isTrue();
    }

    private Quote quote(int price, long oi) {
        return new Quote("NFO:NIFTY24APR24000CE", Instant.now(), BigDecimal.valueOf(price),
                10_000, oi, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
