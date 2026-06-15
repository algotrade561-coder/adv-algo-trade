package com.algo.trade.tuning.recorder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Small abstraction around "what IST date is it for this {@link Instant}?" so
 * {@link TuningEventRecorder} can be unit-tested for day rotation without waiting for
 * actual midnight. The production bean uses {@link Clock#systemUTC()}; tests inject
 * a fake.
 */
@Component
public class IstDayClock {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Clock clock;

    public IstDayClock() {
        this(Clock.systemUTC());
    }

    /** Test constructor — wraps any {@link Clock}. */
    public IstDayClock(Clock clock) {
        this.clock = clock;
    }

    /** IST date of the supplied instant. */
    public LocalDate istDate(Instant when) {
        return when.atZone(IST).toLocalDate();
    }

    /** IST date right now. */
    public LocalDate todayIst() {
        return istDate(clock.instant());
    }

    public Instant now() {
        return clock.instant();
    }
}
