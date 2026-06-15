package com.algo.trade.monitoring;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.indicator.VwapIndicator;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates 5 health signals per open position to produce a composite strength score.
 * Reads exclusively from in-memory caches — no external API calls.
 */
@Component
public class PositionStrengthService {

    private static final Logger log = LoggerFactory.getLogger(PositionStrengthService.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final long STALE_THRESHOLD_MS = 60_000; // 60 seconds

    private final TradeRepository tradeRepository;
    private final LiveInstrumentCache liveInstrumentCache;
    private final LiveCandleBuilder liveCandleBuilder;
    private final VwapIndicator vwapIndicator;

    /** Previous cycle scores for directional indicators. */
    private final ConcurrentHashMap<String, ComponentScores> previousScores = new ConcurrentHashMap<>();

    public PositionStrengthService(TradeRepository tradeRepository,
                                   LiveInstrumentCache liveInstrumentCache,
                                   LiveCandleBuilder liveCandleBuilder,
                                   VwapIndicator vwapIndicator) {
        this.tradeRepository = tradeRepository;
        this.liveInstrumentCache = liveInstrumentCache;
        this.liveCandleBuilder = liveCandleBuilder;
        this.vwapIndicator = vwapIndicator;
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record ComponentScores(
        int oiTrend, String oiTrendDirection,
        int ivChange, String ivChangeDirection,
        int momentum, String momentumDirection,
        int bidAskHealth, String bidAskDirection,
        int thetaPressure, String thetaDirection
    ) {}

    public record StrengthResult(
        String instrumentKey, String tradeId, String underlying, String optionType,
        int quantity, BigDecimal entryPrice, BigDecimal currentPrice, BigDecimal unrealizedPnl,
        double profitPercent, long holdMinutes,
        int strengthScore, String recommendation, String status,
        ComponentScores components
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Compute strength scores for all open positions.
     */
    public List<StrengthResult> evaluateAll() {
        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        return openTrades.stream()
                .filter(t -> !t.isPaperTrade())
                .map(this::evaluate)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Compute strength for a single position.
     */
    StrengthResult evaluate(TradeEntity trade) {
        String instrumentKey = trade.getInstrumentKey();
        if (instrumentKey == null || instrumentKey.isBlank()) {
            return buildNoDataResult(trade, "NO_DATA");
        }

        // Try to find the option instrument by symbol
        // instrumentKey format is "NFO:SYMBOL" — strip exchange prefix for cache lookup
        String symbol = instrumentKey.contains(":") ? instrumentKey.split(":", 2)[1] : instrumentKey;
        Optional<OptionInstrument> optOpt = liveInstrumentCache.getBySymbol(symbol);
        if (optOpt.isEmpty()) {
            return buildNoDataResult(trade, "NO_DATA");
        }

        OptionInstrument opt = optOpt.get();

        // Check staleness
        long lastTickMs = opt.getLastTickTimeMs();
        if (lastTickMs <= 0) {
            return buildNoDataResult(trade, "NO_DATA");
        }
        long ageMs = System.currentTimeMillis() - lastTickMs;
        if (ageMs > STALE_THRESHOLD_MS) {
            return buildNoDataResult(trade, "STALE");
        }

        // Compute component scores
        int oiScore = computeOiTrend(opt);
        int ivScore = computeIvChange(trade, opt);
        int momentumScore = computeMomentum(trade, opt);
        int bidAskScore = computeBidAskHealth(opt);
        int thetaScore = computeThetaPressure(trade, opt);

        // Weighted formula
        int strengthScore = (int) Math.round(
            oiScore * 0.25 + ivScore * 0.25 + momentumScore * 0.20
            + bidAskScore * 0.15 + thetaScore * 0.15
        );
        strengthScore = Math.max(0, Math.min(100, strengthScore));

        // Directional indicators
        String tradeKey = trade.getTradeId();
        ComponentScores prev = previousScores.get(tradeKey);
        String oiDir = direction(oiScore, prev != null ? prev.oiTrend() : oiScore);
        String ivDir = direction(ivScore, prev != null ? prev.ivChange() : ivScore);
        String momDir = direction(momentumScore, prev != null ? prev.momentum() : momentumScore);
        String baDir = direction(bidAskScore, prev != null ? prev.bidAskHealth() : bidAskScore);
        String thetaDir = direction(thetaScore, prev != null ? prev.thetaPressure() : thetaScore);

        ComponentScores components = new ComponentScores(
            oiScore, oiDir, ivScore, ivDir, momentumScore, momDir,
            bidAskScore, baDir, thetaScore, thetaDir
        );
        previousScores.put(tradeKey, components);

        // Compute trade metrics
        BigDecimal currentPrice = BigDecimal.valueOf(opt.getLastPrice());
        BigDecimal entryPrice = trade.getEntryPrice() != null ? trade.getEntryPrice() : BigDecimal.ZERO;
        BigDecimal unrealizedPnl = currentPrice.subtract(entryPrice)
                .multiply(BigDecimal.valueOf(trade.getQuantity()));
        double profitPercent = entryPrice.signum() > 0
                ? currentPrice.subtract(entryPrice).divide(entryPrice, MC).doubleValue() * 100
                : 0;
        long holdMinutes = trade.getEntryTime() != null
                ? Duration.between(trade.getEntryTime(), Instant.now()).toMinutes()
                : 0;

        return new StrengthResult(
            instrumentKey, trade.getTradeId(), trade.getUnderlying(), trade.getOptionType(),
            trade.getQuantity(), entryPrice, currentPrice, unrealizedPnl,
            profitPercent, holdMinutes,
            strengthScore, classify(strengthScore), "OK",
            components
        );
    }

    // ── Classification ────────────────────────────────────────────────────────

    /**
     * Classify score into recommendation.
     */
    static String classify(int score) {
        if (score >= 80) return "STRONG";
        if (score >= 60) return "HOLD";
        if (score >= 40) return "WATCH";
        return "WEAK";
    }

    // ── Component Score Computations ──────────────────────────────────────────

    /**
     * OI Trend (25%): Compare currentOI vs prevOI.
     * OI increased >5% → 100, flat ±2% → 50, decreased >5% → 0.
     */
    private int computeOiTrend(OptionInstrument opt) {
        long currentOI = opt.getOpenInterest();
        long prevOI = opt.getPrevOpenInterest();
        if (prevOI <= 0) return 50; // neutral if no previous data

        double changePercent = ((double)(currentOI - prevOI) / prevOI) * 100;

        if (changePercent >= 5) return 100;
        if (changePercent <= -5) return 0;
        // Linear interpolation: -5% → 0, 0% → 50, +5% → 100
        return (int) Math.round(50 + (changePercent / 5.0) * 50);
    }

    /**
     * IV Change (25%): Compare entryIV to current IV.
     * IV expanded >10% → 100, flat ±5% → 50, crushed >20% → 0.
     */
    private int computeIvChange(TradeEntity trade, OptionInstrument opt) {
        Double entryIV = trade.getEntryIV();
        double currentIV = opt.getImpliedVolatility();

        if (entryIV == null || entryIV <= 0 || currentIV <= 0) return 50; // neutral

        double changePercent = ((currentIV - entryIV) / entryIV) * 100;

        if (changePercent >= 10) return 100;
        if (changePercent <= -20) return 0;
        // Linear interpolation: -20% → 0, -5% → 50, +10% → 100
        if (changePercent >= -5) {
            // -5% to +10% maps to 50-100
            return (int) Math.round(50 + ((changePercent + 5) / 15.0) * 50);
        } else {
            // -20% to -5% maps to 0-50
            return (int) Math.round(((changePercent + 20) / 15.0) * 50);
        }
    }

    /**
     * Momentum (20%): Price vs VWAP + RSI direction.
     * For CE: price > VWAP → high score. For PE: price < VWAP → high score.
     */
    private int computeMomentum(TradeEntity trade, OptionInstrument opt) {
        String underlying = trade.getUnderlying();
        if (underlying == null) return 50;

        IndexType indexType = IndexType.fromName(underlying);
        double spotPrice = liveInstrumentCache.getFuturesPrice(indexType);
        if (spotPrice <= 0) return 50;

        // Get candle history for the underlying spot
        long spotToken = indexType.spotToken();
        List<Candle> candles = liveCandleBuilder.getHistory(spotToken, Timeframe.FIVE_MINUTE);
        if (candles.isEmpty()) return 50;

        // Compute VWAP
        BigDecimal vwap = vwapIndicator.calculate(candles);
        if (vwap.signum() <= 0) return 50;

        double vwapVal = vwap.doubleValue();
        boolean isCE = "CE".equalsIgnoreCase(trade.getOptionType());

        // Price vs VWAP component (0-70 points)
        double priceVsVwap = (spotPrice - vwapVal) / vwapVal * 100;
        int vwapScore;
        if (isCE) {
            // CE benefits from price above VWAP
            vwapScore = (int) Math.round(Math.max(0, Math.min(70, 35 + priceVsVwap * 35)));
        } else {
            // PE benefits from price below VWAP
            vwapScore = (int) Math.round(Math.max(0, Math.min(70, 35 - priceVsVwap * 35)));
        }

        // RSI direction component (0-30 points)
        int rsiScore = computeRsiComponent(candles, isCE);

        return Math.max(0, Math.min(100, vwapScore + rsiScore));
    }

    private int computeRsiComponent(List<Candle> candles, boolean isCE) {
        if (candles.size() < 15) return 15; // neutral

        // Simple RSI calculation over last 14 candles
        int period = 14;
        int start = candles.size() - period - 1;
        if (start < 0) start = 0;

        double gainSum = 0, lossSum = 0;
        for (int i = start + 1; i < candles.size(); i++) {
            double change = candles.get(i).close().subtract(candles.get(i - 1).close()).doubleValue();
            if (change > 0) gainSum += change;
            else lossSum += Math.abs(change);
        }

        int periods = candles.size() - start - 1;
        if (periods <= 0) return 15;
        double avgGain = gainSum / periods;
        double avgLoss = lossSum / periods;
        double rs = avgLoss == 0 ? 100 : avgGain / avgLoss;
        double rsi = 100 - (100 / (1 + rs));

        if (isCE) {
            // CE: RSI > 50 is bullish
            if (rsi >= 60) return 30;
            if (rsi >= 50) return 20;
            if (rsi >= 40) return 10;
            return 0;
        } else {
            // PE: RSI < 50 is bearish (good for PE)
            if (rsi <= 40) return 30;
            if (rsi <= 50) return 20;
            if (rsi <= 60) return 10;
            return 0;
        }
    }

    /**
     * Bid-Ask Health (15%): (ask - bid) / lastPrice.
     * Spread <2% → 100, >10% → 0, linear between.
     */
    private int computeBidAskHealth(OptionInstrument opt) {
        double lastPrice = opt.getLastPrice();
        double bid = opt.getBestBid();
        double ask = opt.getBestAsk();

        if (lastPrice <= 0 || bid <= 0 || ask <= 0) return 50; // neutral

        double spreadPercent = ((ask - bid) / lastPrice) * 100;

        if (spreadPercent <= 2) return 100;
        if (spreadPercent >= 10) return 0;
        // Linear interpolation: 2% → 100, 10% → 0
        return (int) Math.round(100 - ((spreadPercent - 2) / 8.0) * 100);
    }

    /**
     * Theta Pressure (15%): Compare unrealized P&L to estimated theta decay.
     * If profit > theta_decay → 100, if loss dominated by theta → 0.
     */
    private int computeThetaPressure(TradeEntity trade, OptionInstrument opt) {
        double theta = opt.getTheta();
        if (theta == 0) return 50; // neutral if no theta data

        BigDecimal entryPrice = trade.getEntryPrice() != null ? trade.getEntryPrice() : BigDecimal.ZERO;
        double currentPrice = opt.getLastPrice();
        double unrealizedPerLot = currentPrice - entryPrice.doubleValue();

        // Theta is typically negative for long options (decay per day)
        // Estimate decay since entry in minutes
        long holdMinutes = trade.getEntryTime() != null
                ? Duration.between(trade.getEntryTime(), Instant.now()).toMinutes()
                : 0;
        if (holdMinutes <= 0) return 50;

        // Theta is per day, convert to per-minute decay
        double thetaDecayPerMinute = Math.abs(theta) / (6.25 * 60); // 6.25 hours trading day
        double estimatedDecay = thetaDecayPerMinute * holdMinutes;

        if (estimatedDecay <= 0) return 50;

        // Ratio of profit to theta decay
        double ratio = unrealizedPerLot / estimatedDecay;

        if (ratio >= 2) return 100;  // Profit well exceeds theta decay
        if (ratio >= 1) return 80;   // Profit covers theta
        if (ratio >= 0) return 60;   // Breaking even vs theta
        if (ratio >= -1) return 30;  // Loss within theta range
        return 0;                     // Loss dominated by theta
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String direction(int current, int previous) {
        int delta = current - previous;
        if (delta > 5) return "improving";
        if (delta < -5) return "deteriorating";
        return "stable";
    }

    private StrengthResult buildNoDataResult(TradeEntity trade, String status) {
        BigDecimal entryPrice = trade.getEntryPrice() != null ? trade.getEntryPrice() : BigDecimal.ZERO;
        long holdMinutes = trade.getEntryTime() != null
                ? Duration.between(trade.getEntryTime(), Instant.now()).toMinutes()
                : 0;
        return new StrengthResult(
            trade.getInstrumentKey(), trade.getTradeId(), trade.getUnderlying(), trade.getOptionType(),
            trade.getQuantity(), entryPrice, BigDecimal.ZERO, BigDecimal.ZERO,
            0, holdMinutes,
            -1, status, status,
            new ComponentScores(0, "stable", 0, "stable", 0, "stable", 0, "stable", 0, "stable")
        );
    }
}
