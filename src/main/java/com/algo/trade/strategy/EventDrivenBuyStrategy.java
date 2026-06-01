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
 * Entry: 1-2 days before event, when IV rank < maxIvRank.
 * This is a BUYING strategy — risk limited to premium paid.
 *
 * 2026-06-01: added evaluateWithDiagnostics so SKIPPED rows carry a real
 * firstFailedFilter (noScheduledEvent / eventIvTooHigh / noSpotPrice).
 */
@Component
public class EventDrivenBuyStrategy {

    private static final Logger log = LoggerFactory.getLogger(EventDrivenBuyStrategy.class);

    private static final List<LocalDate> EVENT_DATES = List.of(
        LocalDate.of(2025, 6, 6),  LocalDate.of(2025, 8, 6),
        LocalDate.of(2025, 10, 8), LocalDate.of(2025, 12, 5),
        LocalDate.of(2026, 2, 1),  LocalDate.of(2026, 4, 9),
        LocalDate.of(2026, 6, 5),  LocalDate.of(2026, 8, 5),
        LocalDate.of(2026, 10, 7), LocalDate.of(2026, 12, 4)
    );

    public Optional<StrategyDecision> evaluate(double ivRank, StrategyConfig config,
                                               UnderlyingSymbol underlying, BigDecimal spotPrice) {
        return evaluateWithDiagnostics(ivRank, config, underlying, spotPrice).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(double ivRank, StrategyConfig config,
                                                                    UnderlyingSymbol underlying, BigDecimal spotPrice) {
        if (config == null) {
            return noTrade("noConfig");
        }

        LocalDate today = LocalDate.now();
        Optional<LocalDate> upcomingEvent = EVENT_DATES.stream()
                .filter(d -> d.equals(today.plusDays(1)) || d.equals(today.plusDays(2)))
                .findFirst();

        if (upcomingEvent.isEmpty()) {
            return noTrade("noScheduledEvent");
        }

        if (config.getMaxIvRankForBuying() != null && ivRank > config.getMaxIvRankForBuying().doubleValue()) {
            log.debug("[EventDriven] IV rank {} too high for event buy (max {})", ivRank,
                    config.getMaxIvRankForBuying());
            return noTrade(String.format("eventIvTooHigh(ivRank=%.1f,max=%.1f)",
                    ivRank, config.getMaxIvRankForBuying().doubleValue()));
        }

        if (spotPrice == null || spotPrice.signum() <= 0) {
            log.warn("[EventDriven] Skipping pre-event signal - spotPrice is null/zero");
            return noTrade("noSpotPrice");
        }

        log.info("[EventDriven] Pre-event signal: event={} ivRank={} spot={}", upcomingEvent.get(), ivRank, spotPrice);

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, SignalType.BUY_CE,
                spotPrice,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(OptionType.CE),
                true, Optional.empty(), true,
                BigDecimal.valueOf(80),
                List.of("Pre-event straddle: event=" + upcomingEvent.get(),
                        "IV rank=" + String.format("%.0f", ivRank) + " (cheap, good to buy)")
        );
        return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                new StrategyDiagnostics("", null, null, null, null, null, null, null, null));
    }

    private static StrategyDiagnostics.WithSignal noTrade(String reason) {
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(reason, null, null, null, null, null, null, null, null));
    }
}
