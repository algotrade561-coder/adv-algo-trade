package com.algo.trade.strategy.oishifttrap;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory MAE/MFE accumulator per open OI Shift Trap trade.
 */
@Component
public class ShiftTrapMaeMfeTracker {

    public record EntryContext(
            String decisionKey,
            String underlying,
            String trapSide,
            BigDecimal strike,
            BigDecimal spotAtEntry,
            int score,
            double imbalance,
            double proximityPct,
            long trappedOiAtEntry
    ) {}

    public static final class State {
        final EntryContext context;
        final BigDecimal entryPremium;
        final Instant entryTime;
        double maePct;
        double mfePct;
        Instant maeAt;
        Instant mfeAt;
        double spotAtMae;
        double spotAtMfe;
        long trappedOiAtExit;

        State(EntryContext context, BigDecimal entryPremium, Instant entryTime) {
            this.context = context;
            this.entryPremium = entryPremium;
            this.entryTime = entryTime;
        }
    }

    private final Map<String, State> byTradeId = new ConcurrentHashMap<>();
    private final Map<String, EntryContext> pendingByDecisionKey = new ConcurrentHashMap<>();

    public void registerSignalContext(EntryContext ctx) {
        if (ctx != null && ctx.decisionKey() != null && !ctx.decisionKey().isBlank()) {
            pendingByDecisionKey.put(ctx.decisionKey(), ctx);
        }
    }

    public void startTracking(String tradeId, EntryContext ctx, BigDecimal entryPremium, Instant entryTime) {
        if (tradeId == null || entryPremium == null || entryPremium.signum() <= 0) {
            return;
        }
        byTradeId.put(tradeId, new State(ctx, entryPremium, entryTime != null ? entryTime : Instant.now()));
    }

    public EntryContext resolveContext(String underlying, String optionType, Instant entryTime) {
        return pendingByDecisionKey.values().stream()
                .filter(c -> c.underlying().equalsIgnoreCase(underlying))
                .filter(c -> optionType != null && c.trapSide().equalsIgnoreCase(optionType))
                .filter(c -> entryTime == null || !c.decisionKey().isBlank())
                .reduce((a, b) -> b)
                .orElse(null);
    }

    public void update(String tradeId, BigDecimal currentPremium, double spot, long trappedOiNow) {
        State state = byTradeId.get(tradeId);
        if (state == null || currentPremium == null || currentPremium.signum() <= 0) {
            return;
        }
        state.trappedOiAtExit = trappedOiNow;
        double entry = state.entryPremium.doubleValue();
        double pnlPct = (currentPremium.doubleValue() - entry) / entry * 100.0;
        Instant now = Instant.now();
        if (pnlPct < state.maePct) {
            state.maePct = pnlPct;
            state.maeAt = now;
            state.spotAtMae = spot;
        }
        if (pnlPct > state.mfePct) {
            state.mfePct = pnlPct;
            state.mfeAt = now;
            state.spotAtMfe = spot;
        }
    }

    public State get(String tradeId) {
        return byTradeId.get(tradeId);
    }

    public State remove(String tradeId) {
        return byTradeId.remove(tradeId);
    }
}
