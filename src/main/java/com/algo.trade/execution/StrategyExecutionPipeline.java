package com.algo.trade.execution;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.*;
import com.algo.trade.indicator.AtrIndicator;
import com.algo.trade.indicator.EmaIndicator;
import com.algo.trade.indicator.RsiIndicator;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.ml.MlShadowRecorder;
import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.algo.trade.risk.WeeklyExposureTracker;
import com.algo.trade.strategy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles post-evaluation work for any strategy signal: enrichment, risk gates,
 * ML shadow recording, execution routing, DB persistence, and CSV recording.
 * Eliminates the duplicate ML shadow blocks that previously existed in AlgoTradeExecution.
 */
@Component
public class StrategyExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(StrategyExecutionPipeline.class);

    private final GlobalConfigService globalConfigService;
    private final ExecutionEngine executionEngine;
    private final StrategyDecisionRepository decisionRepository;
    private final WeeklyExposureTracker weeklyExposureTracker;
    private final MarketDataService marketDataService;
    private final StrategySignalCsvRecorder signalCsvRecorder;
    private final MlShadowRecorder mlShadowRecorder;
    private final RsiIndicator rsiIndicator;
    private final AtrIndicator atrIndicator;
    private final EmaIndicator emaIndicator;
    private final InstrumentCache instrumentCache;
    private final TradingProperties properties;

    public StrategyExecutionPipeline(
            GlobalConfigService globalConfigService,
            ExecutionEngine executionEngine,
            StrategyDecisionRepository decisionRepository,
            WeeklyExposureTracker weeklyExposureTracker,
            MarketDataService marketDataService,
            StrategySignalCsvRecorder signalCsvRecorder,
            MlShadowRecorder mlShadowRecorder,
            RsiIndicator rsiIndicator,
            AtrIndicator atrIndicator,
            EmaIndicator emaIndicator,
            InstrumentCache instrumentCache,
            TradingProperties properties
    ) {
        this.globalConfigService = globalConfigService;
        this.executionEngine = executionEngine;
        this.decisionRepository = decisionRepository;
        this.weeklyExposureTracker = weeklyExposureTracker;
        this.marketDataService = marketDataService;
        this.signalCsvRecorder = signalCsvRecorder;
        this.mlShadowRecorder = mlShadowRecorder;
        this.rsiIndicator = rsiIndicator;
        this.atrIndicator = atrIndicator;
        this.emaIndicator = emaIndicator;
        this.instrumentCache = instrumentCache;
        this.properties = properties;
    }

    // ── ML shadow recording ────────────────────────────────────────────────────

    /**
     * Record ML shadow score using the given candles for indicator computation.
     * Called from both directional buy path and additional strategies path.
     *
     * @param bidAskSpread real spread for directional buy, "0" for other strategies
     */
    public void recordMlShadow(
            StrategyDecision decision,
            StrategyContext ctx,
            List<Candle> candles,
            String decisionKey,
            String bidAskSpread
    ) {
        recordMlShadow(decision, ctx, candles, decisionKey, bidAskSpread, 0, 0, 0, 0, 0, 0);
    }

    public void recordMlShadow(
            StrategyDecision decision,
            StrategyContext ctx,
            List<Candle> candles,
            String decisionKey,
            String bidAskSpread,
            double delta,
            double gamma,
            double theta,
            double vega,
            double realizedVol5d,
            double ivSkew
    ) {
        try {
            List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
            double rsi = rsiIndicator.calculate(closes, 14).doubleValue();
            double atr = atrIndicator.calculateATR(candles, 14);
            double emaGap = closes.size() >= 21
                    ? emaIndicator.calculate(closes, 9).subtract(emaIndicator.calculate(closes, 21)).doubleValue()
                    : 0.0;
            List<String> reasons = decision.reasons();
            Map<String, String> csvData = Map.ofEntries(
                    Map.entry("underlyingPrice", String.valueOf(decision.underlyingPrice())),
                    Map.entry("optionLastPrice", decision.optionPrice().map(String::valueOf).orElse("0")),
                    Map.entry("optionVolume", "0"),
                    Map.entry("optionOpenInterest", decision.optionOpenInterest().map(String::valueOf).orElse("0")),
                    Map.entry("optionImpliedVolatility", ""),
                    Map.entry("nearbyPutCallOiImbalance", decision.imbalance().map(String::valueOf).orElse("0")),
                    Map.entry("nearbyCallOpenInterest", "0"),
                    Map.entry("nearbyPutOpenInterest", "0"),
                    Map.entry("resistanceCallOiChange", "0"),
                    Map.entry("supportPutOiChange", "0"),
                    Map.entry("ivRank", String.valueOf(ctx.ivRank())),
                    Map.entry("vwapPassed", String.valueOf(decision.vwapConditionPassed())),
                    Map.entry("breakoutPassed", String.valueOf(reasonContains(reasons, "Breakout condition passed"))),
                    Map.entry("volumeSpike", String.valueOf(decision.volumeSpike())),
                    Map.entry("oiPassed", String.valueOf(reasonContains(reasons, "OI behavior supports"))),
                    Map.entry("ivPassed", String.valueOf(reasonContains(reasons, "IV filter passed"))),
                    Map.entry("liquidityPassed", String.valueOf(reasonContains(reasons, "Liquidity filter passed"))),
                    Map.entry("rsiPassed", String.valueOf(reasonContains(reasons, "RSI momentum gate passed"))),
                    Map.entry("optionType", decision.optionType().map(Enum::name).orElse("CE")),
                    Map.entry("marketTime", ctx.marketTime().toString()),
                    Map.entry("underlying", ctx.underlying().name()),
                    Map.entry("confidenceScore", String.valueOf(decision.confidenceScore())),
                    Map.entry("rsiValue", String.valueOf(rsi)),
                    Map.entry("atrValue", String.valueOf(atr)),
                    Map.entry("ema9Ema21Gap", String.valueOf(emaGap)),
                    Map.entry("bidAskSpread", bidAskSpread),
                    Map.entry("vixLevel", String.valueOf(ctx.vixLevel())),
                    Map.entry("daysToExpiry", String.valueOf(ctx.daysToExpiry())),
                    Map.entry("delta", String.valueOf(delta)),
                    Map.entry("gamma", String.valueOf(gamma)),
                    Map.entry("theta", String.valueOf(theta)),
                    Map.entry("vega", String.valueOf(vega)),
                    Map.entry("realizedVol5d", String.valueOf(realizedVol5d)),
                    Map.entry("ivSkew", String.valueOf(ivSkew))
            );
            mlShadowRecorder.recordShadowScore(decision, csvData, decisionKey,
                    globalConfigService.getMinSignalScorePercent(),
                    globalConfigService.getMlVirtualTradeThreshold());
        } catch (Exception e) {
            log.debug("ML shadow recording failed: {}", e.getMessage());
        }
    }

    // ── Additional strategy full pipeline ─────────────────────────────────────

    /**
     * Full post-evaluation pipeline for additional strategies (non-directional-buy).
     * Handles: enrichment, risk gates, ML shadow, execution, DB persistence, CSV recording.
     *
     * @return true if a trade was executed
     */
    public boolean process(
            StrategyDiagnostics.WithSignal result,
            StrategyContext ctx,
            StrategyConfig config,
            StrategyType type,
            String ivRankSource
    ) {
        StrategyDiagnostics diag = result.diagnostics();
        Optional<StrategyDecision> signalOpt = result.signal();

        if (signalOpt.isEmpty()) {
            persistNoTradeAdditional(ctx, config, type, ivRankSource, diag);
            return false;
        }

        StrategyDecision decision = signalOpt.get();
        String signalName = decision.signalType().name();

        if (!signalName.startsWith("BUY_") && !signalName.startsWith("SELL_")) {
            persistNoTradeAdditional(ctx, config, type, ivRankSource, diag);
            return false;
        }

        StrategyDecision enriched = decision.selectedInstrumentKey().isPresent()
                ? decision : enrichWithOptionData(decision, ctx);

        // Risk gates (paper trades bypass)
        if (!config.isPaperTrading()) {
            int effectiveOpen = executionEngine.effectiveOpenTradeCount();
            if (effectiveOpen >= globalConfigService.getMaxOpenTrades()) {
                log.info("Entry blocked: max open trades reached ({} >= {})",
                        effectiveOpen, globalConfigService.getMaxOpenTrades());
                StrategyDecisionEntity blocked = persistStrategyDecision(enriched, type.name(), config, ivRankSource);
                blocked.setExecutionStage("NOT_EXECUTED");
                blocked.setExecutionReason("Max open trades reached (" + effectiveOpen + "/" + globalConfigService.getMaxOpenTrades() + ")");
                decisionRepository.save(blocked);
                return false;
            }
            BigDecimal estimatedCost = estimatedCost(enriched, config, ctx.underlying());
            if (!weeklyExposureTracker.canTrade(estimatedCost)) {
                log.info("Entry blocked: weekly exposure cap reached");
                StrategyDecisionEntity blocked = persistStrategyDecision(enriched, type.name(), config, ivRankSource);
                blocked.setExecutionStage("NOT_EXECUTED");
                blocked.setExecutionReason("Weekly exposure cap reached");
                decisionRepository.save(blocked);
                return false;
            }
        }

        // ML shadow recording using trend candles
        Timeframe trendTf = resolveTimeframe(config.getTrendTimeframe(), Timeframe.FIVE_MINUTE);
        List<Candle> trendCandles = ctx.candles(trendTf);
        String decisionKey = Integer.toUnsignedString(
                (type.name() + "|" + enriched.timestamp() + "|" + ctx.underlying()
                        + "|" + enriched.optionType().map(Enum::name).orElse("")).hashCode(), 16);
        recordMlShadow(enriched, ctx, trendCandles, decisionKey, "0");

        boolean executed = executeSignal(enriched, config, ctx.underlying());
        if (executed && !config.isPaperTrading()) {
            weeklyExposureTracker.recordTrade(estimatedCost(enriched, config, ctx.underlying()));
        }

        Timeframe csvTf = resolveTimeframe(config.getCandleTimeframe(), Timeframe.ONE_MINUTE);
        // Compute ML enrichment values for unified recording
        List<Candle> csvCandles = ctx.candles(csvTf);
        Timeframe trendTfForCsv = resolveTimeframe(config.getTrendTimeframe(), Timeframe.FIVE_MINUTE);
        List<Candle> trendCandlesForCsv = ctx.candles(trendTfForCsv);
        Double rsiVal = computeRsi(csvCandles, 14);
        Double atrVal = computeAtr(csvCandles, 14);
        Double emaGap = computeEmaGap(csvCandles);
        Double bidAsk = enriched.optionPrice().isPresent() && enriched.selectedInstrumentKey().isPresent()
                ? computeBidAskFromQuote(ctx, enriched) : null;

        signalCsvRecorder.recordUnified(SignalRecordContext.builder()
                .strategyType(type.name())
                .underlying(ctx.underlying())
                .decision(enriched)
                .underlyingCandles(csvCandles)
                .trendCandles(trendCandlesForCsv)
                .ivRank(ctx.ivRank())
                .vixLevel(ctx.vixLevel() > 0 ? ctx.vixLevel() : null)
                .daysToExpiry(ctx.daysToExpiry() > 0 ? ctx.daysToExpiry() : null)
                .rsiValue(rsiVal)
                .atrValue(atrVal)
                .ema9Ema21Gap(emaGap)
                .bidAskSpread(bidAsk)
                .executed(executed)
                .executionStage(executed ? "EXECUTED" : "NOT_EXECUTED")
                .diagnostics(diag)
                .build());

        return executed;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private boolean executeSignal(StrategyDecision decision, StrategyConfig config, UnderlyingSymbol underlying) {
        int qty = config.getLots() * IndexType.from(underlying).lotSize();
        BigDecimal premium = decision.optionPrice().orElse(decision.underlyingPrice());

        if (decision.signalType().name().startsWith("BUY_") && decision.selectedInstrumentKey().isPresent()) {
            if (config.isPaperTrading()) {
                executionEngine.executePaperEntry(decision, premium, qty, config);
            } else {
                executionEngine.executeEntry(decision, premium, qty, config);
            }
            return true;
        }
        if (decision.signalType().name().startsWith("SELL_") && decision.selectedInstrumentKey().isPresent()) {
            if (config.isPaperTrading()) {
                executionEngine.executePaperEntry(decision, premium, qty, config);
                return true;
            }
            log.warn("SELL signal from {} but paperTrading=false — blocking real SELL execution", decision.signalType());
            return false;
        }
        if (decision.signalType().name().startsWith("BUY_")) {
            log.info("Signal persisted but not executed (no instrument resolved): underlying={}", underlying);
        }
        return false;
    }

    private void persistNoTradeAdditional(
            StrategyContext ctx,
            StrategyConfig config, StrategyType type, String ivRankSource, StrategyDiagnostics diag
    ) {
        BigDecimal spotPrice = ctx.spotPrice();
        String noTradeReason = diag.firstFailedFilter() != null
                ? diag.firstFailedFilter()
                : "No signal conditions met for " + type.displayName();

        Timeframe csvTf = resolveTimeframe(config.getCandleTimeframe(), Timeframe.ONE_MINUTE);
        for (OptionType ot : new OptionType[]{OptionType.CE, OptionType.PE}) {
            Instrument atmInst = resolveAtmInstrument(ctx.underlying(), ot, spotPrice).orElse(null);
            StrategyDecisionEntity noTrade = StrategyDecisionEntity.forStrategy(
                    type.name(), Instant.now(), ctx.underlying().name(), "NO_TRADE",
                    ot.name(), spotPrice, BigDecimal.ZERO, noTradeReason);
            noTrade.setPaperTrade(config.isPaperTrading());
            noTrade.setIvRank(ctx.ivRank());
            noTrade.setIvRankSource(ivRankSource);
            noTrade.setFirstFailedFilter(diag.firstFailedFilter());
            if (diag.ema9() != null) noTrade.setFastEma(diag.ema9());
            if (diag.ema21() != null) noTrade.setSlowEma(diag.ema21());
            if (diag.emaCrossType() != null) noTrade.setEmaCrossType(diag.emaCrossType());
            if (diag.emaCrossConfirmCount() != null) noTrade.setEmaCrossConfirmCount(diag.emaCrossConfirmCount());
            if (diag.bbBandwidth() != null) noTrade.setBollingerBandwidth(diag.bbBandwidth());
            if (diag.bbUpper() != null) noTrade.setBbUpper(diag.bbUpper());
            if (diag.bbLower() != null) noTrade.setBbLower(diag.bbLower());
            if (diag.bbSqueeze() != null) noTrade.setBbSqueeze(diag.bbSqueeze());
            noTrade.setConfigSnapshot(configToSnapshot(config));
            noTrade.setExecutionStage("NO_TRADE");
            noTrade.setExecutionReason(noTradeReason);
            if (atmInst != null) {
                noTrade.setSelectedInstrumentKey(atmInst.instrumentKey());
                noTrade.setSelectedStrike(atmInst.strike().orElse(null));
            }
            decisionRepository.save(noTrade);
            List<Candle> csvCandles = ctx.candles(csvTf);
            signalCsvRecorder.recordUnified(SignalRecordContext.builder()
                    .strategyType(type.name())
                    .underlying(ctx.underlying())
                    .decision(new StrategyDecision(
                            java.time.Instant.now(), ctx.underlying(),
                            com.algo.trade.domain.SignalType.NO_TRADE, spotPrice,
                            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                            java.util.Optional.of(ot), false, java.util.Optional.empty(), false,
                            java.math.BigDecimal.ZERO, java.util.List.of(noTradeReason)))
                    .underlyingCandles(csvCandles)
                    .ivRank(ctx.ivRank())
                    .vixLevel(ctx.vixLevel() > 0 ? ctx.vixLevel() : null)
                    .daysToExpiry(ctx.daysToExpiry() > 0 ? ctx.daysToExpiry() : null)
                    .rsiValue(computeRsi(csvCandles, 14))
                    .atrValue(computeAtr(csvCandles, 14))
                    .ema9Ema21Gap(computeEmaGap(csvCandles))
                    .selectedInstrumentKey(atmInst != null ? atmInst.instrumentKey() : null)
                    .selectedStrike(atmInst != null ? atmInst.strike().orElse(null) : null)
                    .firstFailedFilter(diag.firstFailedFilter())
                    .executionStage("NO_TRADE")
                    .diagnostics(diag)
                    .build());
        }
    }

    StrategyDecisionEntity persistStrategyDecision(
            StrategyDecision decision, String strategyType, StrategyConfig config, String ivRankSrc
    ) {
        StrategyDecisionEntity entity = StrategyDecisionEntity.forStrategy(
                strategyType,
                decision.timestamp(),
                decision.underlying().name(),
                decision.signalType().name(),
                decision.optionType().map(Enum::name).orElse(null),
                decision.underlyingPrice(),
                decision.confidenceScore(),
                String.join("; ", decision.reasons())
        );
        entity.setPaperTrade(config.isPaperTrading());
        entity.setSelectedInstrumentKey(decision.selectedInstrumentKey().orElse(null));
        entity.setSelectedStrike(decision.selectedStrike().orElse(null));
        entity.setOptionPrice(decision.optionPrice().orElse(null));
        entity.setLotSize(config.getLots());
        if (decision.vwapConditionPassed()) entity.setVwapConditionPassed(true);
        if (decision.volumeSpike()) entity.setVolumeSpike(true);
        decision.imbalance().ifPresent(entity::setImbalance);
        decision.optionOpenInterest().ifPresent(entity::setOptionOpenInterest);
        for (String reason : decision.reasons()) {
            if (reason.startsWith("EMA9=")) {
                try {
                    String[] parts = reason.split("\\s+");
                    for (String p : parts) {
                        if (p.startsWith("EMA9=")) entity.setFastEma(Double.parseDouble(p.substring(5)));
                        if (p.startsWith("EMA21=")) entity.setSlowEma(Double.parseDouble(p.substring(6)));
                    }
                } catch (NumberFormatException ignored) {}
            }
            if (reason.startsWith("BB squeeze breakout: bandwidth=")) {
                try {
                    entity.setBollingerBandwidth(Double.parseDouble(
                            reason.substring("BB squeeze breakout: bandwidth=".length()).replace("%", "")));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (ivRankSrc != null) entity.setIvRankSource(ivRankSrc);
        entity.setConfigSnapshot(configToSnapshot(config));
        decision.selectedInstrumentKey().ifPresent(key ->
                marketDataService.quote(key).ifPresent(q -> {
                    q.bid().ifPresent(entity::setOptionBid);
                    q.ask().ifPresent(entity::setOptionAsk);
                    q.averageTradedPrice().ifPresent(entity::setOptionAtp);
                })
        );
        return decisionRepository.save(entity);
    }

    private StrategyDecision enrichWithOptionData(StrategyDecision decision, StrategyContext ctx) {
        try {
            OptionType optionType = decision.optionType().orElse(OptionType.CE);
            Optional<LocalDate> expiry = instrumentCache.nearestExpiry(
                    ctx.underlying(), LocalDate.now(properties.timezone()), properties.symbols().defaultExpiry());
            if (expiry.isEmpty()) return decision;

            List<Instrument> options = instrumentCache.all().stream()
                    .filter(Instrument::tradable)
                    .filter(i -> i.underlying().filter(ctx.underlying()::equals).isPresent())
                    .filter(i -> i.expiry().filter(expiry.get()::equals).isPresent())
                    .filter(i -> i.optionType().filter(optionType::equals).isPresent())
                    .filter(i -> i.strike().isPresent())
                    .toList();
            if (options.isEmpty()) return decision;

            BigDecimal spotPrice = decision.underlyingPrice();
            Instrument nearest = options.stream()
                    .min(Comparator.comparing(i -> i.strike().orElse(BigDecimal.ZERO).subtract(spotPrice).abs()))
                    .orElse(null);
            if (nearest == null) return decision;

            Optional<Quote> quote = marketDataService.quote(nearest.instrumentKey());
            BigDecimal optionPrice = quote.map(Quote::lastPrice).orElse(null);
            Long oi = quote.map(Quote::openInterest).orElse(null);

            return new StrategyDecision(
                    decision.timestamp(), decision.underlying(), decision.signalType(),
                    decision.underlyingPrice(),
                    Optional.ofNullable(optionPrice),
                    Optional.ofNullable(oi),
                    Optional.of(nearest.lotSize()),
                    optionPrice != null
                            ? Optional.of(optionPrice.multiply(BigDecimal.valueOf(nearest.lotSize())))
                            : Optional.empty(),
                    Optional.of(nearest.instrumentKey()),
                    nearest.strike(),
                    Optional.of(optionType),
                    decision.vwapConditionPassed(),
                    decision.imbalance(),
                    decision.volumeSpike(),
                    decision.confidenceScore(),
                    decision.reasons()
            );
        } catch (Exception e) {
            log.debug("Option enrichment failed for {}: {}", ctx.underlying(), e.getMessage());
            return decision;
        }
    }

    private Optional<Instrument> resolveAtmInstrument(
            UnderlyingSymbol underlying, OptionType optionType, BigDecimal spotPrice
    ) {
        try {
            Optional<LocalDate> expiry = instrumentCache.nearestExpiry(
                    underlying, LocalDate.now(properties.timezone()), properties.symbols().defaultExpiry());
            if (expiry.isEmpty()) return Optional.empty();
            return instrumentCache.all().stream()
                    .filter(Instrument::tradable)
                    .filter(i -> i.underlying().filter(underlying::equals).isPresent())
                    .filter(i -> i.expiry().filter(expiry.get()::equals).isPresent())
                    .filter(i -> i.optionType().filter(optionType::equals).isPresent())
                    .filter(i -> i.strike().isPresent())
                    .min(Comparator.comparing(i -> i.strike().orElse(BigDecimal.ZERO).subtract(spotPrice).abs()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private BigDecimal estimatedCost(StrategyDecision decision, StrategyConfig config, UnderlyingSymbol underlying) {
        BigDecimal premium = decision.optionPrice().orElse(decision.underlyingPrice());
        return premium.multiply(BigDecimal.valueOf(config.getLots() * IndexType.from(underlying).lotSize()));
    }

    private static boolean reasonContains(List<String> reasons, String prefix) {
        return reasons.stream().anyMatch(r -> r.contains(prefix));
    }

    private static String configToSnapshot(StrategyConfig c) {
        if (c == null) return null;
        return String.format(
                "{\"type\":\"%s\",\"underlying\":\"%s\",\"lots\":%d,\"sl\":%.0f,\"target\":%.0f," +
                "\"maxHold\":%d,\"paper\":%b,\"otm\":%d,\"spread\":%d," +
                "\"maxIvBuy\":%s,\"minIvSell\":%s,\"candle\":\"%s\",\"trend\":\"%s\"}",
                c.getStrategyType() != null ? c.getStrategyType().name() : "",
                c.getUnderlying() != null ? c.getUnderlying() : "",
                c.getLots(),
                c.getStopLossPercent() != null ? c.getStopLossPercent().doubleValue() : 0,
                c.getTargetPercent() != null ? c.getTargetPercent().doubleValue() : 0,
                c.getMaxHoldMinutes(),
                c.isPaperTrading(),
                c.getOtmStrikes(),
                c.getSpreadStrikes(),
                c.getMaxIvRankForBuying() != null ? c.getMaxIvRankForBuying().toPlainString() : "null",
                c.getMinCombinedPremium() != null ? c.getMinCombinedPremium().toPlainString() : "null",
                c.getCandleTimeframe() != null ? c.getCandleTimeframe() : "",
                c.getTrendTimeframe() != null ? c.getTrendTimeframe() : ""
        );
    }

    private static Timeframe resolveTimeframe(String configured, Timeframe defaultTf) {
        if (configured == null || configured.isBlank()) return defaultTf;
        try { return Timeframe.valueOf(configured); }
        catch (IllegalArgumentException e) { return defaultTf; }
    }

    // ── ML enrichment helpers ─────────────────────────────────────────────

    private static Double computeRsi(List<Candle> candles, int period) {
        try {
            if (candles == null || candles.size() < period + 1) return null;
            List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
            return new com.algo.trade.indicator.RsiIndicator().calculate(closes, period).doubleValue();
        } catch (Exception e) { return null; }
    }

    private static Double computeAtr(List<Candle> candles, int period) {
        try {
            if (candles == null || candles.size() < period + 1) return null;
            double sum = 0;
            for (int i = candles.size() - period; i < candles.size(); i++) {
                Candle c = candles.get(i);
                Candle prev = candles.get(i - 1);
                double tr = Math.max(c.high().subtract(c.low()).doubleValue(),
                        Math.max(Math.abs(c.high().subtract(prev.close()).doubleValue()),
                                Math.abs(c.low().subtract(prev.close()).doubleValue())));
                sum += tr;
            }
            return sum / period;
        } catch (Exception e) { return null; }
    }

    private static Double computeEmaGap(List<Candle> candles) {
        try {
            if (candles == null || candles.size() < 22) return null;
            List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
            var ema = new com.algo.trade.indicator.EmaIndicator();
            BigDecimal ema9 = ema.calculate(closes, 9);
            BigDecimal ema21 = ema.calculate(closes, 21);
            if (ema21.signum() == 0) return null;
            return ema9.subtract(ema21).divide(ema21, java.math.MathContext.DECIMAL64)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        } catch (Exception e) { return null; }
    }

    private Double computeBidAskFromQuote(StrategyContext ctx, StrategyDecision decision) {
        try {
            String instrumentKey = decision.selectedInstrumentKey().orElse(null);
            if (instrumentKey == null) return null;
            return marketDataService.quote(instrumentKey)
                    .flatMap(q -> {
                        if (q.bid().isEmpty() || q.ask().isEmpty()) return java.util.Optional.<Double>empty();
                        BigDecimal bid = q.bid().get();
                        BigDecimal ask = q.ask().get();
                        if (bid.signum() <= 0 || ask.signum() <= 0 || q.lastPrice().signum() <= 0) return java.util.Optional.<Double>empty();
                        return java.util.Optional.of(ask.subtract(bid).divide(q.lastPrice(), java.math.MathContext.DECIMAL64)
                                .multiply(BigDecimal.valueOf(100)).doubleValue());
                    }).orElse(null);
        } catch (Exception e) { return null; }
    }
}
