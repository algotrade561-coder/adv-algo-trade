package com.algo.trade.strategy;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.persistence.EventDrivenFireLogEntity;
import com.algo.trade.persistence.EventDrivenFireLogRepository;
import com.algo.trade.risk.MarketGuard;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-Driven Long Option Strategy — buys premium BEFORE major events.
 *
 * <p>Premise: 1-2 days before RBI policy / budget / monetary-policy events,
 * IV is often low (market hasn't fully priced in the event). Buying premium
 * captures the IV expansion that typically follows.</p>
 *
 * <h2>4 Jun 2026 hardening</h2>
 * <ul>
 *   <li>Event-date source = MarketGuard.nextEventWithin — single source of truth
 *       with application.yml/risk.event-dates.</li>
 *   <li>Entry window 09:30-14:30 IST.</li>
 *   <li>One-shot per (underlying, day) — no more per-tick firing.</li>
 *   <li>VIX range gate [12, 25].</li>
 *   <li>Direction-aware CE/PE choice from trend candles.</li>
 *   <li>paperTrading=true stays as the safety net until backtest validates.</li>
 *   <li>4 Jun 2026 PM: lastFiredOn persisted to {@code event_driven_fire_log}
 *       so the one-shot gate survives app restarts.</li>
 * </ul>
 */
@Component
public class EventDrivenBuyStrategy {

    private static final Logger log = LoggerFactory.getLogger(EventDrivenBuyStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final LocalTime ENTRY_WINDOW_START = LocalTime.of(9, 30);
    private static final LocalTime ENTRY_WINDOW_END   = LocalTime.of(14, 30);
    private static final int LOOKAHEAD_DAYS = 2;
    private static final double MIN_VIX_FOR_ENTRY = 12.0;
    private static final double MAX_VIX_FOR_ENTRY = 25.0;
    private static final int VELOCITY_LOOKBACK_CANDLES = 6;
    private static final double MIN_DIRECTIONAL_MOVE_PCT = 0.15;

    private final MarketGuard marketGuard;
    private final Map<UnderlyingSymbol, LocalDate> lastFiredOn =
            new ConcurrentHashMap<>(new EnumMap<>(UnderlyingSymbol.class));

    /** Persistent backing store for lastFiredOn. Lazy/optional so the strategy
     *  still works if the repository isn't wired (tests, migrations, etc.). */
    @Autowired(required = false)
    private EventDrivenFireLogRepository fireLogRepository;

    public EventDrivenBuyStrategy(MarketGuard marketGuard) {
        this.marketGuard = marketGuard;
    }

    /** Bootstrap the in-memory lastFiredOn map from any rows persisted today.
     *  This restores the one-shot-per-day gate across restarts. */
    @PostConstruct
    public void loadFireLog() {
        if (fireLogRepository == null) {
            log.info("[EventDriven] fire-log repository not wired — in-memory only");
            return;
        }
        try {
            LocalDate today = LocalDate.now(IST);
            List<EventDrivenFireLogEntity> rows = fireLogRepository.findByFireDate(today);
            for (EventDrivenFireLogEntity row : rows) {
                try {
                    UnderlyingSymbol u = UnderlyingSymbol.valueOf(row.getUnderlying());
                    lastFiredOn.put(u, row.getFireDate());
                } catch (IllegalArgumentException ignored) {
                    log.warn("[EventDriven] skipping unknown underlying in fire-log: {}", row.getUnderlying());
                }
            }
            log.info("[EventDriven] loaded {} fire-log rows for {} → in-memory map has {} entries",
                    rows.size(), today, lastFiredOn.size());
        } catch (Exception ex) {
            log.warn("[EventDriven] fire-log load failed (non-fatal): {}", ex.getMessage());
        }
    }

    public Optional<StrategyDecision> evaluate(double ivRank, StrategyConfig config,
                                               UnderlyingSymbol underlying, BigDecimal spotPrice) {
        return evaluateWithDiagnostics(List.of(), ivRank, config, underlying, spotPrice).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> trendCandles,
                                                                    double ivRank, StrategyConfig config,
                                                                    UnderlyingSymbol underlying, BigDecimal spotPrice) {
        if (config == null) {
            return noTrade("noConfig");
        }
        LocalTime nowIst = LocalTime.now(IST);
        if (nowIst.isBefore(ENTRY_WINDOW_START)) {
            return noTrade("preEventBeforeWindow(" + ENTRY_WINDOW_START + ")");
        }
        if (nowIst.isAfter(ENTRY_WINDOW_END)) {
            return noTrade("preEventAfterWindow(" + ENTRY_WINDOW_END + ")");
        }
        Optional<LocalDate> upcomingEvent = marketGuard.nextEventWithin(LOOKAHEAD_DAYS);
        if (upcomingEvent.isEmpty()) {
            return noTrade("noScheduledEvent");
        }
        if (config.getMaxIvRankForBuying() != null
                && ivRank > config.getMaxIvRankForBuying().doubleValue()) {
            log.debug("[EventDriven] IV rank {} too high for event buy (max {})", ivRank,
                    config.getMaxIvRankForBuying());
            return noTrade(String.format("eventIvTooHigh(ivRank=%.1f,max=%.1f)",
                    ivRank, config.getMaxIvRankForBuying().doubleValue()));
        }
        double vix = marketGuard.getCurrentVix();
        if (vix > 0 && vix < MIN_VIX_FOR_ENTRY) {
            return noTrade(String.format("vixTooLow(%.1f,min=%.1f)", vix, MIN_VIX_FOR_ENTRY));
        }
        if (vix > MAX_VIX_FOR_ENTRY) {
            return noTrade(String.format("vixTooHigh(%.1f,max=%.1f)", vix, MAX_VIX_FOR_ENTRY));
        }
        if (spotPrice == null || spotPrice.signum() <= 0) {
            log.warn("[EventDriven] Skipping pre-event signal - spotPrice is null/zero");
            return noTrade("noSpotPrice");
        }
        if (trendCandles == null || trendCandles.size() < VELOCITY_LOOKBACK_CANDLES) {
            return noTrade("insufficientCandlesForDirection("
                    + (trendCandles == null ? 0 : trendCandles.size())
                    + ",need=" + VELOCITY_LOOKBACK_CANDLES + ")");
        }
        Candle first = trendCandles.get(trendCandles.size() - VELOCITY_LOOKBACK_CANDLES);
        Candle last  = trendCandles.getLast();
        if (first.close() == null || first.close().signum() <= 0) {
            return noTrade("noBaselineClose");
        }
        double moveRaw = last.close().doubleValue() - first.close().doubleValue();
        double movePct = (moveRaw / first.close().doubleValue()) * 100.0;
        if (Math.abs(movePct) < MIN_DIRECTIONAL_MOVE_PCT) {
            return noTrade(String.format("spotFlat(%.3f%%,min=%.2f%%)", movePct, MIN_DIRECTIONAL_MOVE_PCT));
        }
        OptionType leg     = movePct > 0 ? OptionType.CE : OptionType.PE;
        SignalType sigType = movePct > 0 ? SignalType.BUY_CE : SignalType.BUY_PE;
        LocalDate today = LocalDate.now(IST);
        LocalDate lastFired = lastFiredOn.get(underlying);
        if (lastFired != null && lastFired.equals(today)) {
            return noTrade("alreadyFiredToday");
        }
        // 4 Jun 2026 PM: persist before adding to in-memory map so a crash
        // between in-memory put + DB save can't leave us inconsistent.
        persistFireLog(underlying, today, leg.name(), spotPrice, ivRank, vix);
        log.info("[EventDriven] Pre-event {} signal: underlying={} event={} ivRank={} vix={} move={}% spot={}",
                leg, underlying, upcomingEvent.get(),
                String.format("%.1f", ivRank), String.format("%.1f", vix),
                String.format("%+.2f", movePct), spotPrice);
        lastFiredOn.put(underlying, today);

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, sigType,
                spotPrice,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(leg),
                true, Optional.empty(), true,
                BigDecimal.valueOf(80),
                List.of("Pre-event premium-buy: event=" + upcomingEvent.get(),
                        "IV rank=" + String.format("%.0f", ivRank) + " (cheap)",
                        "VIX=" + String.format("%.1f", vix) + " in [" + MIN_VIX_FOR_ENTRY + "-" + MAX_VIX_FOR_ENTRY + "]",
                        "Spot direction over " + VELOCITY_LOOKBACK_CANDLES + " candles: "
                                + String.format("%+.2f%%", movePct) + " -> " + leg,
                        "Entry window passed (" + ENTRY_WINDOW_START + "-" + ENTRY_WINDOW_END + ")",
                        "One-shot-per-day gate honored")
        );
        return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                new StrategyDiagnostics("", null, null, null, null, null, null, null, null));
    }

    /** Best-effort DB write. Repository-null and unique-violation are both
     *  swallowed because the in-memory map gives sufficient guard during the
     *  current run — DB persistence only matters across restarts. */
    private void persistFireLog(UnderlyingSymbol underlying, LocalDate today, String optionType,
                                 BigDecimal spotPrice, double ivRank, double vix) {
        if (fireLogRepository == null) return;
        try {
            EventDrivenFireLogEntity row = new EventDrivenFireLogEntity(
                    underlying.name(), today, Instant.now(), optionType, spotPrice, ivRank, vix);
            fireLogRepository.save(row);
        } catch (Exception ex) {
            // Likely unique-constraint violation — another tick won the race.
            // Either way, the in-memory map has been updated and the gate works.
            log.debug("[EventDriven] fire-log save skipped (likely duplicate): {}", ex.getMessage());
        }
    }

    private static StrategyDiagnostics.WithSignal noTrade(String reason) {
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(reason, null, null, null, null, null, null, null, null));
    }
}
