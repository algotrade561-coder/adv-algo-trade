package com.algo.trade.strategy;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Event-Driven Long Option Strategy — BUY straddle BEFORE major events.
 *
 * Logic: Before RBI policy, budget, earnings, global events:
 * - IV is usually low (market hasn't priced in the event yet)
 * - Buy ATM straddle (CE + PE) to profit from the big move
 * - Exit after the event when IV crushes
 *
 * Entry: 1-2 days before event, when IV rank < maxIvRank
 * This is a BUYING strategy — risk limited to premium paid.
 */
@Component
public class EventDrivenBuyStrategy {

    private static final Logger log = LoggerFactory.getLogger(EventDrivenBuyStrategy.class);

    // Known high-impact event dates — update as announced
    private static final List<LocalDate> EVENT_DATES = List.of(
        LocalDate.of(2025, 6, 6),  LocalDate.of(2025, 8, 6),
        LocalDate.of(2025, 10, 8), LocalDate.of(2025, 12, 5),
        LocalDate.of(2026, 2, 1),  LocalDate.of(2026, 4, 9),
        LocalDate.of(2026, 6, 5),  LocalDate.of(2026, 8, 5)
    );

    public Optional<StrategyDecision> evaluate(double ivRank, StrategyConfig config,
                                               UnderlyingSymbol underlying) {
        LocalDate today = LocalDate.now();
        Optional<LocalDate> upcomingEvent = EVENT_DATES.stream()
                .filter(d -> d.equals(today.plusDays(1)) || d.equals(today.plusDays(2)))
                .findFirst();

        if (upcomingEvent.isEmpty()) return Optional.empty();

        if (ivRank > config.getMaxIvRankForBuying().doubleValue()) {
            log.debug("[EventDriven] IV rank {} too high for event buy (max {})", ivRank,
                    config.getMaxIvRankForBuying());
            return Optional.empty();
        }

        log.info("[EventDriven] Pre-event signal: event={} ivRank={}", upcomingEvent.get(), ivRank);

        // Signal BUY_CE as proxy for straddle — both legs handled by execution
        return Optional.of(new StrategyDecision(
                Instant.now(), underlying, SignalType.BUY_CE,
                BigDecimal.ZERO,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(OptionType.CE),
                true, Optional.empty(), true,
                BigDecimal.valueOf(80),
                List.of("Pre-event straddle: event=" + upcomingEvent.get(),
                        "IV rank=" + String.format("%.0f", ivRank) + " (cheap, good to buy)")
        ));
    }
}
