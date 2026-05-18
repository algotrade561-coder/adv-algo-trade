package com.algo.trade.execution.exit;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.LiquidityExitProperties;
import com.algo.trade.domain.Quote;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.SpreadLegEntity;
import com.algo.trade.persistence.TradeEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tier-1 exit: stale quotes, bid–ask blowout, volume collapse vs entry.
 * Designed to run <b>before</b> SL/target so bad positions are not left open when the book dries up.
 */
@Component
public class LiquidityExitEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LiquidityExitEvaluator.class);
    private static final long VOLUME_FLOOR_MIN_MINUTES_AFTER_OPEN = 15;

    private final LiquidityExitProperties properties;
    private final GlobalConfigService globalConfigService;
    private final LiquidityExitStateTracker stateTracker;

    public LiquidityExitEvaluator(LiquidityExitProperties properties,
                                  GlobalConfigService globalConfigService,
                                  LiquidityExitStateTracker stateTracker) {
        this.properties = properties != null ? properties : LiquidityExitProperties.defaults();
        this.globalConfigService = globalConfigService;
        this.stateTracker = stateTracker;
    }

    public record LiquidityExitSignal(String reason, String detail, boolean force) {}

    public Optional<LiquidityExitSignal> evaluateMissingQuote(String instrumentKey, Instant entryTime) {
        if (!isActive() || entryTime == null) {
            return Optional.empty();
        }
        long ageSec = Duration.between(entryTime, Instant.now()).toSeconds();
        if (ageSec >= properties.staleQuoteForceExitAfter().toSeconds()) {
            return Optional.of(new LiquidityExitSignal(
                    LiquidityExitReasons.NO_QUOTE,
                    "No quote for " + instrumentKey + " for " + ageSec + "s since entry",
                    true));
        }
        return Optional.empty();
    }

    public Optional<LiquidityExitSignal> evaluateSpreadMissingQuote(PositionGroupEntity group) {
        if (!isActive() || group == null || group.getEntryTime() == null) {
            return Optional.empty();
        }
        long ageSec = Duration.between(group.getEntryTime(), Instant.now()).toSeconds();
        if (ageSec >= properties.staleQuoteForceExitAfter().toSeconds()) {
            return Optional.of(new LiquidityExitSignal(
                    LiquidityExitReasons.NO_QUOTE,
                    "No quotes for spread group " + group.getGroupId() + " for " + ageSec + "s",
                    true));
        }
        return Optional.empty();
    }

    public Optional<LiquidityExitSignal> evaluateSingleLeg(TradeEntity trade, Quote quote) {
        if (!isActive() || quote == null) {
            return Optional.empty();
        }
        Optional<LiquidityExitSignal> stale = evaluateStale(trade.getInstrumentKey(), quote);
        if (stale.isPresent()) {
            return stale;
        }
        return evaluateMicrostructure(
                trade.getInstrumentKey(),
                quote,
                trade.getEntryBidAskSpreadPercent(),
                trade.getEntryVolume(),
                trade.getEntryOpenInterest(),
                trade.getEntryTime());
    }

    public Optional<LiquidityExitSignal> evaluateSpreadLeg(SpreadLegEntity leg, Quote quote, Instant groupEntryTime) {
        if (!isActive() || quote == null) {
            return Optional.empty();
        }
        Optional<LiquidityExitSignal> stale = evaluateStale(leg.getInstrumentKey(), quote);
        if (stale.isPresent()) {
            return stale;
        }
        return evaluateMicrostructure(
                leg.getInstrumentKey(),
                quote,
                leg.getEntryBidAskSpreadPercent(),
                leg.getEntryVolume(),
                leg.getEntryOpenInterest(),
                groupEntryTime);
    }

    public Optional<LiquidityExitSignal> evaluateSpreadGroup(Iterable<SpreadLegEntity> legs,
                                                             Map<String, Quote> quotes,
                                                             Instant groupEntryTime) {
        if (!isActive()) {
            return Optional.empty();
        }
        LiquidityExitSignal worst = null;
        int legCount = 0;
        int missingQuotes = 0;
        for (SpreadLegEntity leg : legs) {
            legCount++;
            Quote q = quotes.get(leg.getInstrumentKey());
            if (q == null) {
                missingQuotes++;
                continue;
            }
            Optional<LiquidityExitSignal> signal = evaluateSpreadLeg(leg, q, groupEntryTime);
            if (signal.isPresent() && (worst == null || signal.get().force())) {
                worst = signal.get();
                if (worst.force()) {
                    return Optional.of(worst);
                }
            }
        }
        if (missingQuotes > 0 && missingQuotes == legCount) {
            return Optional.of(new LiquidityExitSignal(
                    LiquidityExitReasons.NO_QUOTE,
                    "No quotes for any spread leg",
                    true));
        }
        if (missingQuotes > 0) {
            log.warn("[LiquidityExit] Partial missing quotes ({}/{} legs) — evaluating available legs only",
                    missingQuotes, legCount);
        }
        return Optional.ofNullable(worst);
    }

    private boolean isActive() {
        if (!properties.enabled()) {
            return false;
        }
        return !properties.marketHoursOnly() || MarketSessionHelper.isRegularSessionNow();
    }

    private Optional<LiquidityExitSignal> evaluateStale(String instrumentKey, Quote quote) {
        Instant ts = quote.timestamp();
        if (ts == null) {
            log.warn("[LiquidityExit] Quote without timestamp for {} — treating as stale", instrumentKey);
            return Optional.of(new LiquidityExitSignal(
                    LiquidityExitReasons.STALE_NO_TIMESTAMP,
                    "Quote timestamp missing for " + instrumentKey,
                    true));
        }
        long ageSec = Duration.between(ts, Instant.now()).toSeconds();
        if (ageSec >= properties.staleQuoteForceExitAfter().toSeconds()) {
            log.warn("[LiquidityExit] Force exit — stale quote {}s for {}", ageSec, instrumentKey);
            return Optional.of(new LiquidityExitSignal(
                    LiquidityExitReasons.STALE_FORCE,
                    "Quote age " + ageSec + "s >= " + properties.staleQuoteForceExitAfter().toSeconds() + "s",
                    true));
        }
        if (ageSec >= properties.staleQuoteWarnAfter().toSeconds()) {
            log.debug("[LiquidityExit] Stale quote {}s for {} — microstructure checks tightened", ageSec, instrumentKey);
        }
        return Optional.empty();
    }

    private Optional<LiquidityExitSignal> evaluateMicrostructure(String instrumentKey,
                                                                  Quote quote,
                                                                  Double entrySpreadPct,
                                                                  Long entryVolume,
                                                                  Long entryOi,
                                                                  Instant entryTime) {
        EntryLiquiditySnapshot current = EntryLiquiditySnapshot.fromQuote(quote);
        double spreadPct = current.bidAskSpreadPercent();

        if (properties.exitWhenBidAskMissing() && !current.hasBidAsk()) {
            int streak = stateTracker.recordMissingBidAsk(instrumentKey);
            if (streak >= properties.missingBidAskTicksRequired()) {
                return Optional.of(new LiquidityExitSignal(
                        LiquidityExitReasons.NO_BID_ASK,
                        "No usable bid/ask on " + instrumentKey + " for " + streak + " ticks",
                        true));
            }
            return Optional.empty();
        }
        stateTracker.clearMissingBidAsk(instrumentKey);

        if (spreadPct >= properties.spreadExitPercent()) {
            return Optional.of(new LiquidityExitSignal(
                    LiquidityExitReasons.SPREAD_WIDENING,
                    String.format("Spread %.1f%% >= exit %.1f%%", spreadPct, properties.spreadExitPercent()),
                    true));
        }

        if (entrySpreadPct != null && entrySpreadPct > 0
                && spreadPct >= entrySpreadPct * properties.spreadVsEntryMultiplier()) {
            return Optional.of(new LiquidityExitSignal(
                    LiquidityExitReasons.SPREAD_VS_ENTRY,
                    String.format("Spread %.1f%% >= %.1fx entry %.1f%%",
                            spreadPct, properties.spreadVsEntryMultiplier(), entrySpreadPct),
                    true));
        }

        if (spreadPct >= properties.spreadWarnPercent()) {
            log.warn("[LiquidityExit] Wide spread {}% on {} (warn {}%)",
                    String.format("%.1f", spreadPct), instrumentKey,
                    String.format("%.1f", properties.spreadWarnPercent()));
        }

        if (properties.volumeFloorEnabled()
                && MarketSessionHelper.minutesSinceSessionOpen() >= VOLUME_FLOOR_MIN_MINUTES_AFTER_OPEN) {
            long minVol = globalConfigService.getMinLiquidityVolume();
            if (minVol > 0 && quote.volume() > 0 && quote.volume() < minVol) {
                return Optional.of(new LiquidityExitSignal(
                        LiquidityExitReasons.VOLUME_FLOOR,
                        "Volume " + quote.volume() + " < floor " + minVol,
                        true));
            }
        }

        if (entryVolume != null && entryVolume > 0 && quote.volume() > 0) {
            if (entryTime == null || MarketSessionHelper.isSameTradingDay(entryTime, Instant.now())) {
                if (quote.volume() < entryVolume) {
                    double ratio = (double) quote.volume() / entryVolume;
                    if (ratio <= properties.volumeCollapseRatio()) {
                        return Optional.of(new LiquidityExitSignal(
                                LiquidityExitReasons.VOLUME_COLLAPSE,
                                String.format("Volume %.0f%% of entry (%d vs %d)",
                                        ratio * 100, quote.volume(), entryVolume),
                                true));
                    }
                }
            }
        }

        if (entryOi != null && entryOi > 0 && quote.openInterest() > 0) {
            double oiRatio = (double) quote.openInterest() / entryOi;
            if (oiRatio <= properties.oiCollapseRatio()) {
                return Optional.of(new LiquidityExitSignal(
                        LiquidityExitReasons.OI_COLLAPSE,
                        String.format("OI %.0f%% of entry (%d vs %d)",
                                oiRatio * 100, quote.openInterest(), entryOi),
                        true));
            }
        }

        return Optional.empty();
    }
}
