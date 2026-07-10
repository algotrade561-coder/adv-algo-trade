package com.algo.trade.execution;

import com.algo.trade.broker.BrokerMarginClient;
import com.algo.trade.config.SpreadTradingProperties;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.MarginSnapshot;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.risk.AdaptivePositionSizer;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates broker funds vs basket margin (+ BUY premium + buffer) before spread entry.
 */
@Component
public class SpreadMarginPreflight {

    private static final Logger log = LoggerFactory.getLogger(SpreadMarginPreflight.class);
    private static final MathContext MC = MathContext.DECIMAL64;

    private final BrokerMarginClient marginClient;
    private final MarketDataService marketDataService;
    private final SpreadTradingProperties spreadProperties;
    private final TradingProperties tradingProperties;
    private final AdaptivePositionSizer adaptivePositionSizer;

    /** Resolver-backed config — getters resolve the CURRENT UserContext user's effective (risk-profile) values. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.config.GlobalConfigService globalConfigService;

    /** Opt-in: bound spread capital-at-risk to the user's profile risk budget (capital × risk%), for risk-%
     *  parity with single-leg. Default OFF so it's enabled deliberately (it can legitimately reject spreads
     *  whose margin exceeds a conservative profile's per-trade budget). */
    @org.springframework.beans.factory.annotation.Value("${spread.risk-profile-sizing.enabled:true}")
    private boolean riskProfileSizingEnabled;

    public SpreadMarginPreflight(BrokerMarginClient marginClient,
                                 MarketDataService marketDataService,
                                 SpreadTradingProperties spreadProperties,
                                 TradingProperties tradingProperties,
                                 AdaptivePositionSizer adaptivePositionSizer) {
        this.marginClient = marginClient;
        this.marketDataService = marketDataService;
        this.spreadProperties = spreadProperties;
        this.tradingProperties = tradingProperties;
        this.adaptivePositionSizer = adaptivePositionSizer;
    }

    public record PreflightResult(
            boolean allowed,
            List<SpreadLeg> legs,
            int lots,
            BigDecimal requiredMargin,
            BigDecimal buyPremium,
            BigDecimal bufferAmount,
            BigDecimal availableFunds,
            String reason
    ) {
        static PreflightResult skip(List<SpreadLeg> legs, int lots, String reason) {
            return new PreflightResult(true, legs, lots, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, reason);
        }

        static PreflightResult reject(String reason) {
            return new PreflightResult(false, List.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, reason);
        }
    }

    /**
     * Chooses the largest affordable lot count from {@code maxLots} down to 1.
     * Note: maxLots should already be adaptive-sized by the caller. This method
     * does NOT re-apply adaptive sizing to avoid double-haircut.
     */
    public PreflightResult evaluate(List<SpreadLeg> legs, int maxLots, int lotSize, boolean paperTrading) {
        if (legs == null || legs.isEmpty()) {
            return PreflightResult.reject("No spread legs");
        }
        // P2 #25 fix: use maxLots directly — caller already applied adaptive sizing
        int cappedLots = maxLots;
        if (paperTrading || !spreadProperties.marginPreflightEnabled()
                || tradingProperties.executionMode() != com.algo.trade.domain.ExecutionMode.ZERODHA) {
            return PreflightResult.skip(
                    SpreadLegs.withLotQuantity(legs, cappedLots * lotSize),
                    cappedLots,
                    "Margin preflight skipped (paper or disabled)");
        }

        Optional<MarginSnapshot> marginsOpt = marginClient.equityMargins();
        if (marginsOpt.isEmpty()) {
            return PreflightResult.reject("Could not fetch broker equity margins");
        }
        BigDecimal available = marginsOpt.get().netAvailable();
        if (available.signum() <= 0) {
            available = marginsOpt.get().availableCash();
        }

        // Risk-% parity (opt-in): bound the at-risk capital to the user's profile risk budget so multi-leg
        // honours the same risk-% as single-leg. Computed EXACTLY as RiskEngine (totalCapital ×
        // maxRiskPerTradePercent) for unit-consistency. Broker margin ≈ max-loss for defined-risk spreads, so
        // capping affordability at the risk budget is a conservative, correct risk-% bound. Resolver-backed →
        // the current user's assigned profile.
        if (riskProfileSizingEnabled && globalConfigService != null) {
            try {
                BigDecimal riskBudget = globalConfigService.getTotalCapital()
                        .multiply(globalConfigService.getMaxRiskPerTradePercent(), MC);
                if (riskBudget.signum() > 0 && riskBudget.compareTo(available) < 0) {
                    log.info("[SpreadMargin] risk-% cap: available {} → {} (profile risk budget)",
                            available, riskBudget);
                    available = riskBudget;
                }
            } catch (Exception e) {
                log.debug("[SpreadMargin] risk-% cap skipped (non-fatal): {}", e.getMessage());
            }
        }

        Map<String, Quote> quotes = marketDataService.quotes(
                legs.stream().map(SpreadLeg::instrumentKey).distinct().toList());
        double bufferPct = spreadProperties.marginBufferPercent() / 100.0;

        for (int lots = cappedLots; lots >= 1; lots--) {
            List<SpreadLeg> scaled = SpreadLegs.withLotQuantity(legs, lots * lotSize);
            List<OrderRequest> requests = buildMarginRequests(scaled, quotes);
            if (requests.size() != scaled.size()) {
                continue;
            }
            Optional<BigDecimal> marginOpt = marginClient.basketOrderMargin(requests, true);
            if (marginOpt.isEmpty()) {
                return PreflightResult.reject("Basket margin API unavailable");
            }
            BigDecimal margin = marginOpt.get();

            // P2 #29: Also check without consider_positions to avoid understating requirement
            // when residual positions from yesterday's settlement artificially reduce margin
            Optional<BigDecimal> standaloneMarginOpt = marginClient.basketOrderMargin(requests, false);
            if (standaloneMarginOpt.isPresent()) {
                BigDecimal standaloneMargin = standaloneMarginOpt.get();
                // Use the higher of the two — conservative approach
                margin = margin.max(standaloneMargin);
            }
            BigDecimal buyPremium = buyPremiumCash(scaled, quotes);
            BigDecimal buffer = margin.multiply(BigDecimal.valueOf(bufferPct), MC)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalRequired = margin.add(buyPremium, MC).add(buffer, MC);

            if (available.compareTo(totalRequired) >= 0) {
                log.info("[SpreadMargin] Approved lots={} margin={} buyPremium={} buffer={} available={}",
                        lots, margin, buyPremium, buffer, available);
                return new PreflightResult(true, scaled, lots, margin, buyPremium, buffer, available,
                        "Margin preflight passed");
            }
            log.debug("[SpreadMargin] lots={} needs {} but available {}", lots, totalRequired, available);
        }
        return PreflightResult.reject(String.format(
                "Insufficient margin: available=%s, min lots=1 failed buffer=%.0f%%",
                available.setScale(0, RoundingMode.HALF_UP), spreadProperties.marginBufferPercent()));
    }

    /**
     * Re-check margin for SELL legs only after BUY hedges are filled at broker.
     */
    public Optional<String> verifySellPhase(List<SpreadLeg> sellLegs, Map<String, Quote> quotes) {
        if (!spreadProperties.marginPreflightEnabled()
                || tradingProperties.executionMode() != com.algo.trade.domain.ExecutionMode.ZERODHA) {
            return Optional.empty();
        }
        List<OrderRequest> requests = buildMarginRequests(sellLegs, quotes);
        if (requests.isEmpty()) {
            return Optional.of("No quotes for SELL phase margin check");
        }
        Optional<MarginSnapshot> marginsOpt = marginClient.equityMargins();
        Optional<BigDecimal> marginOpt = marginClient.basketOrderMargin(requests, true);
        if (marginsOpt.isEmpty() || marginOpt.isEmpty()) {
            return Optional.of("Margin API unavailable before SELL phase");
        }
        BigDecimal available = marginsOpt.get().netAvailable();
        double bufferPct = spreadProperties.marginBufferPercent() / 100.0;
        BigDecimal buffer = marginOpt.get().multiply(BigDecimal.valueOf(bufferPct), MC);
        BigDecimal total = marginOpt.get().add(buffer, MC);
        if (available.compareTo(total) < 0) {
            return Optional.of(String.format("SELL phase margin fail: need %s, available %s", total, available));
        }
        return Optional.empty();
    }

    private static BigDecimal buyPremiumCash(List<SpreadLeg> legs, Map<String, Quote> quotes) {
        BigDecimal total = BigDecimal.ZERO;
        for (SpreadLeg leg : legs) {
            if (leg.side() != OrderSide.BUY) {
                continue;
            }
            Quote q = quotes.get(leg.instrumentKey());
            if (q == null || q.lastPrice().signum() <= 0) {
                continue;
            }
            total = total.add(q.lastPrice().multiply(BigDecimal.valueOf(leg.quantity()), MC), MC);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    static List<OrderRequest> buildMarginRequests(List<SpreadLeg> legs, Map<String, Quote> quotes) {
        List<OrderRequest> requests = new ArrayList<>();
        for (SpreadLeg leg : legs) {
            Quote q = quotes.get(leg.instrumentKey());
            if (q == null || q.lastPrice().signum() <= 0) {
                return List.of();
            }
            BigDecimal protection = q.lastPrice().multiply(BigDecimal.valueOf(0.015), MC);
            BigDecimal limit = leg.side() == OrderSide.BUY
                    ? q.lastPrice().add(protection)
                    : q.lastPrice().subtract(protection).max(BigDecimal.ONE);
            limit = ExecutionEngine.roundToTick(limit, leg.side());
            requests.add(new OrderRequest(
                    "MARGIN-" + leg.instrumentKey().hashCode(),
                    leg.instrumentKey(),
                    leg.side(),
                    com.algo.trade.domain.OrderType.LIMIT,
                    com.algo.trade.domain.ProductType.MIS,
                    leg.quantity(),
                    Optional.of(limit),
                    "margin-est"));
        }
        return requests;
    }
}
