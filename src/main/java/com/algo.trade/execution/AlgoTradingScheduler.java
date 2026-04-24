package com.algo.trade.execution;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.broker.zerodha.KiteAccessTokenStore;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.HistoricalDataRequest;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.MarketDataMode;
import com.algo.trade.domain.OptionChainLevel;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.TradingMode;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.algo.trade.strategy.EventDrivenBuyStrategy;
import com.algo.trade.strategy.RuleBasedOptionsStrategy;
import com.algo.trade.strategy.ScalpingStrategy;
import com.algo.trade.strategy.SpreadStrategyEvaluator;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategySignalCsvRecorder;
import com.algo.trade.strategy.StrategyEvaluationRequest;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.strategy.VolatilityBreakoutStrategy;
import com.algo.trade.domain.CandleClosedEvent;
import com.algo.trade.risk.MarketGuard;
import com.algo.trade.util.IstDateTimes;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled live/paper orchestration: market data -> strategy -> risk/execution.
 */
@Service
public class AlgoTradingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlgoTradingScheduler.class);
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final TradingProperties properties;
    private final TradingStateService tradingStateService;
    private final InstrumentCache instrumentCache;
    private final MarketDataService marketDataService;
    private final RuleBasedOptionsStrategy strategy;
    private final ExecutionEngine executionEngine;
    private final KiteAccessTokenStore tokenStore;
    private final StrategyDecisionRepository decisionRepository;
    private final StrategyConfigService strategyConfigService;
    private final ScalpingStrategy scalpingStrategy;
    private final VolatilityBreakoutStrategy volatilityBreakoutStrategy;
    private final SpreadStrategyEvaluator spreadStrategyEvaluator;
    private final EventDrivenBuyStrategy eventDrivenBuyStrategy;
    private final MarketGuard marketGuard;
    private final com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache;
    private final com.algo.trade.marketdata.LiveCandleBuilder candleBuilder;
    private final StrategySignalCsvRecorder signalCsvRecorder;
    private final Map<String, Quote> previousQuotes = new ConcurrentHashMap<>();
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

    public AlgoTradingScheduler(
            TradingProperties properties,
            TradingStateService tradingStateService,
            InstrumentCache instrumentCache,
            MarketDataService marketDataService,
            RuleBasedOptionsStrategy strategy,
            ExecutionEngine executionEngine,
            KiteAccessTokenStore tokenStore,
            StrategyDecisionRepository decisionRepository,
            StrategyConfigService strategyConfigService,
            ScalpingStrategy scalpingStrategy,
            VolatilityBreakoutStrategy volatilityBreakoutStrategy,
            SpreadStrategyEvaluator spreadStrategyEvaluator,
            EventDrivenBuyStrategy eventDrivenBuyStrategy,
            MarketGuard marketGuard,
            com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache,
            com.algo.trade.marketdata.LiveCandleBuilder candleBuilder,
            StrategySignalCsvRecorder signalCsvRecorder
    ) {
        this.properties = properties;
        this.tradingStateService = tradingStateService;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.strategy = strategy;
        this.executionEngine = executionEngine;
        this.tokenStore = tokenStore;
        this.decisionRepository = decisionRepository;
        this.strategyConfigService = strategyConfigService;
        this.scalpingStrategy = scalpingStrategy;
        this.volatilityBreakoutStrategy = volatilityBreakoutStrategy;
        this.spreadStrategyEvaluator = spreadStrategyEvaluator;
        this.eventDrivenBuyStrategy = eventDrivenBuyStrategy;
        this.marketGuard = marketGuard;
        this.liveInstrumentCache = liveInstrumentCache;
        this.candleBuilder = candleBuilder;
        this.signalCsvRecorder = signalCsvRecorder;
    }

    /**
     * PRIMARY trigger: fires the moment a 5-minute candle closes from WebSocket tick data.
     * No polling delay — strategies react immediately.
     */
    @EventListener
    public void onCandleClose(CandleClosedEvent event) {
        if (event.timeframe() != Timeframe.ONE_MINUTE) return;
        // WebSocket trigger is independent of REST poll scheduler.
        // Only block on kill switch or hard halt — not on scanner stopped state.
        if (tradingStateService.killSwitchEnabled()) return;
        if (tradingStateService.haltMode() == com.algo.trade.risk.HaltMode.HARD) return;
        if (!tradingStateService.running() && !tradingStateService.schedulerEnabled()) {
            // Both scanner and REST poll are off — user explicitly stopped everything
            return;
        }
        if (!scanInProgress.compareAndSet(false, true)) return;
        try {
            log.info("CandleClosedEvent triggered scan: token={} tf={}", event.instrumentToken(), event.timeframe());
            runScan();
        } catch (Exception ex) {
            log.warn("CandleClosedEvent scan failed: {}", ex.getMessage(), ex);
        } finally {
            scanInProgress.set(false);
        }
    }

    /**
     * FALLBACK trigger: REST poll every 60s when WebSocket is not connected.
     */
    @Scheduled(
            initialDelayString = "${trading.algo.initial-delay-ms:5}",
            fixedDelayString = "${trading.algo.scan-interval-ms:6}"
    )
    public void scan() {
        if (!tradingStateService.schedulerEnabled()) {
            log.debug("Algo scan skipped: REST poll scheduler disabled at runtime");
            return;
        }
        if (!tradingStateService.running()) {
            log.info("Algo scan skipped: trading state is stopped");
            return;
        }
        if (tradingStateService.killSwitchEnabled()) {
            log.info("Algo scan skipped: kill switch is enabled");
            return;
        }
        if (properties.mode() == TradingMode.BACKTEST) {
            log.info("Algo scan skipped: configured mode is BACKTEST");
            return;
        }
        if (tradingStateService.marketDataMode() == MarketDataMode.ZERODHA && !tokenStore.authenticated()) {
            log.info("Algo scan skipped: Zerodha access token is missing. Set KITE_ACCESS_TOKEN or call /auth/kite/session before /start.");
            return;
        }
        if (!scanInProgress.compareAndSet(false, true)) {
            log.info("Algo scan skipped: previous scan is still running");
            return;
        }

        try {
            runScan();
        } catch (Exception ex) {
            log.warn("Algo scan failed: {}", ex.getMessage(), ex);
        } finally {
            scanInProgress.set(false);
        }
    }

    private void runScan() {
        LocalTime marketTime = LocalTime.now(properties.timezone());

        // Skip scan outside configured entry window
        if (marketTime.isBefore(properties.entry().entryStartTime())
                || marketTime.isAfter(properties.exit().forcedExitTime())) {
            log.debug("Algo scan skipped: outside trading window ({}, allowed {}-{})",
                    marketTime, properties.entry().entryStartTime(), properties.exit().forcedExitTime());
            return;
        }

        List<UnderlyingSymbol> enabledUnderlyings = tradingStateService.enabledUnderlyings();
        log.info("Algo scan started: mode={}, marketTime={}, underlyings={}, maxEntriesPerScan={}",
                properties.mode(), marketTime, enabledUnderlyings, properties.algo().maxEntriesPerScan());

        ensureInstrumentsLoaded();

        int entriesSubmitted = 0;
        for (UnderlyingSymbol underlying : enabledUnderlyings) {
            if (entriesSubmitted >= properties.algo().maxEntriesPerScan()) {
                log.info("Algo scan entry limit reached for this cycle: entriesSubmitted={}", entriesSubmitted);
                break;
            }

            Optional<ScanContext> context = buildScanContext(underlying, marketTime);
            if (context.isEmpty()) {
                continue;
            }

            entriesSubmitted += evaluateAndExecute(underlying, context.get(), marketTime,
                    properties.algo().maxEntriesPerScan() - entriesSubmitted);
            previousQuotes.putAll(context.get().quotes());
        }

        log.info("Algo scan completed: entriesSubmitted={}", entriesSubmitted);

        // ── Evaluate additional enabled strategies ────────────────────────────
        runAdditionalStrategies(marketTime, enabledUnderlyings);
    }

    /**
     * Evaluates all enabled non-directional strategies (scalping, volatility breakout,
     * spreads, event-driven) and persists their signals to H2.
     */
    private void runAdditionalStrategies(LocalTime marketTime, List<UnderlyingSymbol> underlyings) {
        List<StrategyConfig> enabledConfigs = strategyConfigService.getEnabled();
        if (enabledConfigs.isEmpty()) return;

        for (UnderlyingSymbol underlying : underlyings) {
            // Fetch 15-min candles for spread/scalping/breakout strategies
            List<Candle> candles15m = candles(underlyingHistoricalKey(underlying),
                    com.algo.trade.domain.Timeframe.FIFTEEN_MINUTE);
            List<Candle> candles5m = candles(underlyingHistoricalKey(underlying),
                    com.algo.trade.domain.Timeframe.FIVE_MINUTE);

            // Approximate IV rank from recent option quotes (simplified — use 50 as neutral if unavailable)
            double ivRank = 50.0;

            for (StrategyConfig config : enabledConfigs) {
                if (!config.getUnderlying().equals(underlying.name())) continue;
                StrategyType type = config.getStrategyType();

                try {
                    Optional<StrategyDecision> signal = switch (type) {
                        case SCALPING -> scalpingStrategy.evaluate(candles5m, marketTime, config, underlying);
                        case VOLATILITY_BREAKOUT -> volatilityBreakoutStrategy.evaluate(candles15m, ivRank, config, underlying);
                        case EVENT_DRIVEN_BUY -> eventDrivenBuyStrategy.evaluate(ivRank, config, underlying);
                        case BULL_CALL_SPREAD, BEAR_PUT_SPREAD,
                             LONG_STRADDLE, LONG_STRANGLE,
                             SHORT_STRADDLE, SHORT_STRANGLE,
                             IRON_CONDOR, BUTTERFLY, CALENDAR_SPREAD ->
                                spreadStrategyEvaluator.evaluate(candles15m, ivRank, config, underlying);
                        default -> Optional.empty(); // DIRECTIONAL_BUY handled by main scan loop
                    };

                    if (signal.isPresent()) {
                        StrategyDecision enriched = enrichWithOptionData(signal.get(), underlying);
                        persistStrategyDecision(enriched, type.name(), config);
                        boolean executed = false;
                        if (enriched.signalType().name().startsWith("BUY_")
                                && enriched.selectedInstrumentKey().isPresent()) {
                            log.info("Additional strategy signal: type={} underlying={} signal={} instrument={}",
                                    type, underlying, enriched.signalType(), enriched.selectedInstrumentKey().orElse(""));
                            BigDecimal premium = enriched.optionPrice().orElse(enriched.underlyingPrice());
                            executionEngine.executeEntry(enriched, premium,
                                    config.getLots() * com.algo.trade.domain.IndexType.from(underlying).lotSize());
                            executed = true;
                        } else if (enriched.signalType().name().startsWith("BUY_")) {
                            log.info("Additional strategy signal persisted but not executed (no instrument resolved): type={} underlying={}",
                                    type, underlying);
                        }
                        signalCsvRecorder.recordAdditionalStrategy(type.name(), enriched, executed);
                    } else if (type != StrategyType.DIRECTIONAL_BUY) {
                        // Persist NO_TRADE with spot price for analysis
                        BigDecimal spotPrice = candles5m.isEmpty() ? BigDecimal.ZERO : candles5m.getLast().close();
                        StrategyDecisionEntity noTrade = StrategyDecisionEntity.forStrategy(
                                type.name(), Instant.now(), underlying.name(), "NO_TRADE",
                                null, spotPrice, BigDecimal.ZERO,
                                "No signal conditions met for " + type.displayName());
                        noTrade.setIvRank(ivRank);
                        decisionRepository.save(noTrade);
                        signalCsvRecorder.recordAdditionalNoTrade(type.name(), underlying.name(), spotPrice,
                                "No signal conditions met for " + type.displayName());
                    }
                } catch (Exception e) {
                    log.warn("Additional strategy evaluation failed: type={} underlying={}: {}",
                            type, underlying, e.getMessage());
                }
            }
        }
    }

    /** Persist a signal from any strategy type to H2. */
    private void persistStrategyDecision(StrategyDecision decision, String strategyType, StrategyConfig config) {
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
        entity.setSelectedInstrumentKey(decision.selectedInstrumentKey().orElse(null));
        entity.setSelectedStrike(decision.selectedStrike().orElse(null));
        entity.setOptionPrice(decision.optionPrice().orElse(null));
        entity.setLotSize(config.getLots());
        // Directional fields
        if (decision.vwapConditionPassed()) entity.setVwapConditionPassed(true);
        if (decision.volumeSpike()) entity.setVolumeSpike(true);
        decision.imbalance().ifPresent(entity::setImbalance);
        decision.optionOpenInterest().ifPresent(entity::setOptionOpenInterest);
        decisionRepository.save(entity);
    }

    /**
     * Enrich a strategy signal with actual option instrument data (strike, price, OI)
     * by looking up the nearest ATM option from the instrument cache.
     */
    private StrategyDecision enrichWithOptionData(StrategyDecision decision, UnderlyingSymbol underlying) {
        try {
            OptionType optionType = decision.optionType().orElse(OptionType.CE);
            Optional<LocalDate> expiry = instrumentCache.nearestExpiry(
                    underlying, LocalDate.now(properties.timezone()), properties.symbols().defaultExpiry());
            if (expiry.isEmpty()) return decision;

            List<Instrument> options = instrumentCache.all().stream()
                    .filter(Instrument::tradable)
                    .filter(i -> i.underlying().filter(underlying::equals).isPresent())
                    .filter(i -> i.expiry().filter(expiry.get()::equals).isPresent())
                    .filter(i -> i.optionType().filter(optionType::equals).isPresent())
                    .filter(i -> i.strike().isPresent())
                    .toList();
            if (options.isEmpty()) return decision;

            BigDecimal spotPrice = decision.underlyingPrice();
            Instrument nearest = options.stream()
                    .min(java.util.Comparator.comparing(i -> i.strike().orElse(BigDecimal.ZERO).subtract(spotPrice).abs()))
                    .orElse(null);
            if (nearest == null) return decision;

            // Get live quote for the selected option
            Optional<Quote> quote = marketDataService.quote(nearest.instrumentKey());
            BigDecimal optionPrice = quote.map(Quote::lastPrice).orElse(null);
            Long oi = quote.map(Quote::openInterest).orElse(null);

            return new StrategyDecision(
                    decision.timestamp(), decision.underlying(), decision.signalType(),
                    decision.underlyingPrice(),
                    Optional.ofNullable(optionPrice),
                    Optional.ofNullable(oi),
                    Optional.of(nearest.lotSize()),
                    optionPrice != null ? Optional.of(optionPrice.multiply(BigDecimal.valueOf(nearest.lotSize()))) : Optional.empty(),
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
            log.debug("Option enrichment failed for {}: {}", underlying, e.getMessage());
            return decision;
        }
    }

    private void ensureInstrumentsLoaded() {
        if (!instrumentCache.all().isEmpty()) {
            return;
        }
        if (!properties.algo().refreshInstrumentsOnStart()) {
            log.warn("Instrument cache is empty and refreshInstrumentsOnStart=false; scan cannot continue");
            return;
        }
        List<Instrument> instruments = instrumentCache.refresh();
        log.info("Algo scan loaded instrument cache: instrumentCount={}", instruments.size());
    }

    private Optional<ScanContext> buildScanContext(UnderlyingSymbol underlying, LocalTime marketTime) {
        Optional<Quote> spotQuote = spotQuote(underlying);
        if (spotQuote.isEmpty()) {
            log.warn("Algo scan skipped underlying: no spot quote available, underlying={}", underlying);
            return Optional.empty();
        }

        Optional<LocalDate> expiry = instrumentCache.nearestExpiry(
                underlying,
                LocalDate.now(properties.timezone()),
                properties.symbols().defaultExpiry()
        );
        if (expiry.isEmpty()) {
            log.warn("Algo scan skipped underlying: no expiry found in instrument cache, underlying={}", underlying);
            return Optional.empty();
        }

        List<Instrument> options = optionUniverse(underlying, expiry.get());
        if (options.isEmpty()) {
            log.warn("Algo scan skipped underlying: no option instruments found, underlying={}, expiry={}",
                    underlying, expiry.get());
            return Optional.empty();
        }

        BigDecimal underlyingPrice = spotQuote.get().lastPrice();
        List<BigDecimal> selectedStrikes = selectedStrikes(options, underlyingPrice);
        Map<String, Quote> optionQuotes = marketDataService.quotes(options.stream()
                .filter(option -> option.strike().filter(selectedStrikes::contains).isPresent())
                .map(Instrument::instrumentKey)
                .toList());

        List<OptionChainLevel> levels = optionChainLevels(options, selectedStrikes, optionQuotes);
        if (levels.isEmpty()) {
            log.warn("Algo scan skipped underlying: option chain levels could not be built, underlying={}, expiry={}",
                    underlying, expiry.get());
            return Optional.empty();
        }

        Map<OptionType, Instrument> selectedOptions = selectedOptions(options, underlyingPrice, optionQuotes);
        if (selectedOptions.isEmpty()) {
            log.warn("Algo scan skipped underlying: no selected option instruments, underlying={}, expiry={}",
                    underlying, expiry.get());
            return Optional.empty();
        }

        OptionChainSnapshot snapshot = new OptionChainSnapshot(underlying, Instant.now(), underlyingPrice, levels);
        Map<String, Quote> allQuotes = new LinkedHashMap<>(optionQuotes);
        allQuotes.put(spotQuote.get().instrumentKey(), spotQuote.get());

        // Update PCR in MarketGuard from the live option chain
        long totalCallOi = levels.stream().mapToLong(OptionChainLevel::callOpenInterest).sum();
        long totalPutOi  = levels.stream().mapToLong(OptionChainLevel::putOpenInterest).sum();
        if (totalCallOi > 0) marketGuard.updatePcr((double) totalPutOi / totalCallOi);

        log.info("Algo scan context built: underlying={}, spotPrice={}, expiry={}, selectedStrikes={}, chainLevels={}, selectedOptions={}",
                underlying, underlyingPrice, expiry.get(), selectedStrikes, levels.size(), selectedOptions);
        return Optional.of(new ScanContext(spotQuote.get(), snapshot, selectedOptions, allQuotes));
    }

    private Optional<Quote> spotQuote(UnderlyingSymbol underlying) {
        String spotQuoteKey = properties.symbols().spotQuoteKeys().get(underlying);
        if (spotQuoteKey == null || spotQuoteKey.isBlank()) {
            log.warn("Spot quote key missing for underlying={}", underlying);
            return Optional.empty();
        }
        return marketDataService.quote(spotQuoteKey);
    }

    private List<Instrument> optionUniverse(UnderlyingSymbol underlying, LocalDate expiry) {
        return instrumentCache.all().stream()
                .filter(Instrument::tradable)
                .filter(instrument -> instrument.underlying().filter(underlying::equals).isPresent())
                .filter(instrument -> instrument.expiry().filter(expiry::equals).isPresent())
                .filter(instrument -> instrument.optionType().isPresent())
                .filter(instrument -> instrument.strike().isPresent())
                .sorted(Comparator.comparing(instrument -> instrument.strike().orElse(BigDecimal.ZERO)))
                .toList();
    }

    private List<BigDecimal> selectedStrikes(List<Instrument> options, BigDecimal underlyingPrice) {
        List<BigDecimal> strikes = options.stream()
                .flatMap(instrument -> instrument.strike().stream())
                .distinct()
                .sorted()
                .toList();
        if (strikes.isEmpty()) {
            return List.of();
        }

        BigDecimal atm = nearestStrike(strikes, underlyingPrice);
        int atmIndex = strikes.indexOf(atm);
        int start = Math.max(0, atmIndex - properties.strike().nearbyStrikes());
        int end = Math.min(strikes.size(), atmIndex + properties.strike().nearbyStrikes() + 1);
        return strikes.subList(start, end);
    }

    private Map<OptionType, Instrument> selectedOptions(List<Instrument> options, BigDecimal underlyingPrice,
                                                        Map<String, Quote> quotes) {
        List<BigDecimal> strikes = options.stream()
                .flatMap(instrument -> instrument.strike().stream())
                .distinct()
                .sorted()
                .toList();
        if (strikes.isEmpty()) {
            return Map.of();
        }

        Map<OptionType, Instrument> selected = new EnumMap<>(OptionType.class);
        selected.put(OptionType.CE, selectedAffordableOption(options, strikes, underlyingPrice, OptionType.CE,
                quotes).orElse(null));
        selected.put(OptionType.PE, selectedAffordableOption(options, strikes, underlyingPrice, OptionType.PE,
                quotes).orElse(null));
        selected.values().removeIf(java.util.Objects::isNull);
        selected.forEach((optionType, instrument) -> log.info(
                "Selected option: optionType={}, instrument={}, token={}, parsedStrike={}, expiry={}, underlyingPrice={}",
                optionType, instrument.instrumentKey(), instrument.instrumentToken(),
                instrument.strike().orElse(null), instrument.expiry().orElse(null), underlyingPrice));
        return Map.copyOf(selected);
    }

    private Optional<Instrument> selectedAffordableOption(List<Instrument> options, List<BigDecimal> strikes,
                                                          BigDecimal underlyingPrice, OptionType optionType,
                                                          Map<String, Quote> quotes) {
        List<BigDecimal> candidateStrikes = candidateStrikes(optionType, strikes, underlyingPrice);
        for (BigDecimal strike : candidateStrikes) {
            Optional<Instrument> instrument = findOption(options, strike, optionType);
            if (instrument.isEmpty()) {
                continue;
            }
            BigDecimal maxPremium = maxTradablePremium(instrument.get().lotSize());
            Quote quote = quotes.get(instrument.get().instrumentKey());
            if (quote == null || quote.lastPrice() == null || quote.lastPrice().signum() <= 0) {
                log.info("Option selection skipped: missing/invalid quote, optionType={}, instrument={}, strike={}",
                        optionType, instrument.get().instrumentKey(), strike);
                continue;
            }
            if (quote.lastPrice().compareTo(maxPremium) <= 0) {
                log.info("Risk-budget option selected: optionType={}, instrument={}, strike={}, premium={}, maxPremium={}",
                        optionType, instrument.get().instrumentKey(), strike, quote.lastPrice(), maxPremium);
                return instrument;
            }
            log.info("Option selection rejected by premium budget: optionType={}, instrument={}, strike={}, premium={}, maxPremium={}",
                    optionType, instrument.get().instrumentKey(), strike, quote.lastPrice(), maxPremium);
        }
        log.warn("No option fits risk-budget premium: optionType={}, candidateStrikes={}",
                optionType, candidateStrikes);
        return Optional.empty();
    }

    private List<BigDecimal> candidateStrikes(OptionType optionType, List<BigDecimal> strikes,
                                              BigDecimal underlyingPrice) {
        BigDecimal atm = nearestStrike(strikes, underlyingPrice);
        int atmIndex = strikes.indexOf(atm);
        int start = Math.max(0, atmIndex - properties.strike().nearbyStrikes());
        int end = Math.min(strikes.size(), atmIndex + properties.strike().nearbyStrikes() + 1);
        List<BigDecimal> nearby = strikes.subList(start, end);
        if (optionType == OptionType.CE) {
            return nearby.stream()
                    .filter(strike -> strike.compareTo(atm) >= 0)
                    .sorted()
                    .toList();
        }
        return nearby.stream()
                .filter(strike -> strike.compareTo(atm) <= 0)
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    static BigDecimal maxTradablePremium(TradingProperties properties, BigDecimal stopLossPercent, int lotSize) {
        BigDecimal riskAmount = properties.risk().totalCapital()
                .multiply(properties.risk().maxRiskPerTradePercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        if (lotSize <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal riskPerLotPercent = BigDecimal.valueOf(lotSize)
                .multiply(stopLossPercent, MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        if (riskPerLotPercent.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return riskAmount.divide(riskPerLotPercent, MATH_CONTEXT);
    }

    private BigDecimal maxTradablePremium(int lotSize) {
        return maxTradablePremium(properties, strategyConfigService.getDirectionalBuyConfig().getStopLossPercent(), lotSize);
    }

    private BigDecimal selectedStrikeFor(OptionType optionType, List<BigDecimal> strikes, BigDecimal underlyingPrice) {
        BigDecimal atm = nearestStrike(strikes, underlyingPrice);
        int atmIndex = strikes.indexOf(atm);
        return switch (properties.strike().selectionMode()) {
            case ATM -> atm;
            case ONE_STRIKE_ITM -> optionType == OptionType.CE
                    ? strikes.get(Math.max(0, atmIndex - 1))
                    : strikes.get(Math.min(strikes.size() - 1, atmIndex + 1));
            case ONE_STRIKE_OTM -> optionType == OptionType.CE
                    ? strikes.get(Math.min(strikes.size() - 1, atmIndex + 1))
                    : strikes.get(Math.max(0, atmIndex - 1));
        };
    }

    private Optional<Instrument> findOption(List<Instrument> options, BigDecimal strike, OptionType optionType) {
        return options.stream()
                .filter(instrument -> instrument.strike().filter(strike::equals).isPresent())
                .filter(instrument -> instrument.optionType().filter(optionType::equals).isPresent())
                .findFirst();
    }

    private List<OptionChainLevel> optionChainLevels(
            List<Instrument> options,
            List<BigDecimal> selectedStrikes,
            Map<String, Quote> quotes
    ) {
        return selectedStrikes.stream()
                .flatMap(strike -> {
                    Optional<Instrument> call = findOption(options, strike, OptionType.CE);
                    Optional<Instrument> put = findOption(options, strike, OptionType.PE);
                    Optional<Quote> callQuote = call.map(Instrument::instrumentKey).map(quotes::get);
                    Optional<Quote> putQuote = put.map(Instrument::instrumentKey).map(quotes::get);
                    if (callQuote.isEmpty() || putQuote.isEmpty()) {
                        return java.util.stream.Stream.empty();
                    }
                    return java.util.stream.Stream.of(new OptionChainLevel(
                            strike,
                            callQuote.get().openInterest(),
                            putQuote.get().openInterest(),
                            openInterestChange(callQuote.get()),
                            openInterestChange(putQuote.get()),
                            callQuote.get().lastPrice(),
                            putQuote.get().lastPrice()
                    ));
                })
                .toList();
    }

    private long openInterestChange(Quote quote) {
        // First try LiveInstrumentCache — updated on every WebSocket tick
        if (quote.instrumentKey() != null && quote.instrumentKey().contains(":")) {
            String symbol = quote.instrumentKey().split(":", 2)[1];
            long liveOiChange = liveInstrumentCache.getBySymbol(symbol)
                    .map(o -> o.getOiChange())
                    .orElse(0L);
            if (liveOiChange != 0) return liveOiChange;
        }
        // Fall back to scan-to-scan previousQuotes delta
        return Optional.ofNullable(previousQuotes.get(quote.instrumentKey()))
                .map(previous -> quote.openInterest() - previous.openInterest())
                .orElse(0L);
    }

    private int evaluateAndExecute(
            UnderlyingSymbol underlying,
            ScanContext context,
            LocalTime marketTime,
            int remainingEntries
    ) {
        int entriesSubmitted = 0;
        List<EntryCandidate> candidates = new ArrayList<>();
        for (Map.Entry<OptionType, Instrument> entry : context.selectedOptions().entrySet()) {
            if (!optionTypeEnabled(properties, entry.getKey())) {
                log.info("Strategy evaluation skipped: option type disabled for live execution, underlying={}, optionType={}, enabledOptionTypes={}",
                        underlying, entry.getKey(), properties.entry().enabledOptionTypes());
                continue;
            }
            Instrument selectedInstrument = entry.getValue();
            Quote selectedQuote = context.quotes().get(selectedInstrument.instrumentKey());
            if (selectedQuote == null) {
                log.warn("Strategy evaluation skipped: selected option quote missing, instrument={}",
                        selectedInstrument.instrumentKey());
                continue;
            }

            List<Candle> underlyingCandles = candles(underlyingHistoricalKey(underlying), properties.entry().timeframe());
            List<Candle> trendUnderlyingCandles = trendCandles(underlying);
            // Option candles from REST are often empty/stale — build a synthetic candle from live WebSocket data
            List<Candle> optionCandles = buildOptionCandles(selectedInstrument, selectedQuote);
            if (underlyingCandles.isEmpty() || trendUnderlyingCandles.isEmpty() || optionCandles.isEmpty()) {
                log.warn("Strategy evaluation skipped: missing candles, underlying={}, instrument={}, underlyingCandles={}, trendUnderlyingCandles={}, optionCandles={}",
                        underlying, selectedInstrument.instrumentKey(), underlyingCandles.size(),
                        trendUnderlyingCandles.size(), optionCandles.size());
                continue;
            }
            if (!freshQuote(context.spotQuote()) || !freshQuote(selectedQuote) || !freshCandles(underlyingCandles) || !freshCandles(trendUnderlyingCandles)) {
                log.warn("Strategy evaluation skipped: stale market data, underlying={}, instrument={}, spotQuoteTime={}, optionQuoteTime={}, latestUnderlyingCandleTime={}, latestTrendCandleTime={}, threshold={}",
                        underlying, selectedInstrument.instrumentKey(), context.spotQuote().timestamp(),
                        selectedQuote.timestamp(), latestCandleTimestamp(underlyingCandles).orElse(null),
                        latestCandleTimestamp(trendUnderlyingCandles).orElse(null), properties.safety().staleMarketDataThreshold());
                continue;
            }

            Instant evaluationTimestamp = Instant.now();
            StrategyEvaluationRequest request = new StrategyEvaluationRequest(
                    evaluationTimestamp,
                    IstDateTimes.istTime(evaluationTimestamp),
                    underlying,
                    underlyingCandles,
                    trendUnderlyingCandles,
                    optionCandles,
                    context.optionChainSnapshot(),
                    selectedInstrument.instrumentKey(),
                    selectedInstrument.strike().orElse(null),
                    selectedInstrument.lotSize(),
                    entry.getKey(),
                    selectedQuote,
                    Optional.ofNullable(previousQuotes.get(selectedInstrument.instrumentKey()))
            );

            StrategyDecision decision = strategy.evaluateEntry(request);
            if (decision.signalType().name().startsWith("BUY_")) {
                log.info("Algo scan generated entry signal: underlying={}, instrument={}, signalType={}, premium={}",
                        underlying, selectedInstrument.instrumentKey(), decision.signalType(), selectedQuote.lastPrice());
                log.info("Entry candidate details: instrument={}, token={}, parsedStrike={}, optionType={}, confidenceScore={}",
                        selectedInstrument.instrumentKey(), selectedInstrument.instrumentToken(),
                        selectedInstrument.strike().orElse(null), entry.getKey(), decision.confidenceScore());
                candidates.add(new EntryCandidate(decision, selectedQuote, selectedInstrument));
            } else {
                persistNoTradeDecision(decision);
                log.info("Algo scan no-trade decision: underlying={}, instrument={}, optionType={}, reasons={}",
                        underlying, selectedInstrument.instrumentKey(), entry.getKey(), decision.reasons());
            }
        }

        List<EntryCandidate> selectedCandidates = candidates.stream()
                .sorted(Comparator.comparing((EntryCandidate candidate) -> candidate.decision().confidenceScore()).reversed())
                .limit(remainingEntries)
                .toList();
        for (EntryCandidate candidate : selectedCandidates) {
            executionEngine.executeEntry(candidate.decision(), candidate.quote().lastPrice(), candidate.instrument().lotSize());
            entriesSubmitted++;
        }
        return entriesSubmitted;
    }

    static boolean optionTypeEnabled(TradingProperties properties, OptionType optionType) {
        return properties.entry().enabledOptionTypes().contains(optionType);
    }

    private List<Candle> candles(String instrumentKey, Timeframe timeframe) {
        if (instrumentKey == null || instrumentKey.isBlank()) {
            return List.of();
        }
        Instant to = Instant.now();
        Instant from = to.minus(timeframe.duration().multipliedBy(properties.algo().candleLookback()));
        try {
            return marketDataService.historicalCandles(new HistoricalDataRequest(instrumentKey, from, to, timeframe, true));
        } catch (Exception ex) {
            log.warn("Historical candle request failed: instrumentKey={}, timeframe={}, message={}",
                    instrumentKey, timeframe, ex.getMessage());
            return List.of();
        }
    }

    private List<Candle> trendCandles(UnderlyingSymbol underlying) {
        Timeframe trendTimeframe = properties.entry().trendTimeframe();
        if (!properties.entry().trendFilterEnabled() || trendTimeframe == properties.entry().timeframe()) {
            return candles(underlyingHistoricalKey(underlying), properties.entry().timeframe());
        }
        return candles(underlyingHistoricalKey(underlying), trendTimeframe);
    }

    private boolean freshQuote(Quote quote) {
        return quote.timestamp().plus(properties.safety().staleMarketDataThreshold()).isAfter(Instant.now());
    }

    private boolean freshCandles(List<Candle> candles) {
        if (candles.isEmpty()) {
            return false;
        }
        Candle latest = candles.getLast();
        Duration allowedAge = latest.timeframe().duration().plus(properties.safety().staleMarketDataThreshold());
        return latest.timestamp().plus(allowedAge).isAfter(Instant.now());
    }

    private Optional<Instant> latestCandleTimestamp(List<Candle> candles) {
        return candles.isEmpty() ? Optional.empty() : Optional.of(candles.getLast().timestamp());
    }

    private String historicalKey(Instrument instrument) {
        if (tradingStateService.marketDataMode() == MarketDataMode.ZERODHA) {
            return String.valueOf(instrument.instrumentToken());
        }
        return instrument.instrumentKey();
    }

    private String underlyingHistoricalKey(UnderlyingSymbol underlying) {
        String configuredKey = properties.symbols().spotHistoricalKeys().get(underlying);
        if (configuredKey == null || configuredKey.isBlank() || tradingStateService.marketDataMode() != MarketDataMode.ZERODHA) {
            return configuredKey;
        }
        if (configuredKey.chars().allMatch(Character::isDigit)) {
            return configuredKey;
        }
        Optional<Instrument> instrument = instrumentCache.findByKey(configuredKey);
        if (instrument.isEmpty()) {
            log.warn("Underlying historical key could not be resolved to an instrument token: underlying={}, configuredKey={}",
                    underlying, configuredKey);
            return configuredKey;
        }
        return String.valueOf(instrument.get().instrumentToken());
    }

    private void persistNoTradeDecision(StrategyDecision decision) {
        StrategyDecisionEntity entity = new StrategyDecisionEntity(
                decision.timestamp(), decision.underlying().name(),
                decision.signalType().name(), decision.underlyingPrice(),
                decision.optionPrice().orElse(null),
                decision.optionOpenInterest().orElse(null),
                decision.lotSize().orElse(null),
                decision.lotPrice().orElse(null),
                decision.selectedInstrumentKey().orElse(null),
                decision.selectedStrike().orElse(null),
                decision.optionType().map(Enum::name).orElse(null),
                decision.vwapConditionPassed(),
                decision.imbalance().orElse(null),
                decision.volumeSpike(),
                decision.confidenceScore(),
                String.join("; ", decision.reasons()));
        entity.setStrategyType("DIRECTIONAL_BUY");
        decisionRepository.save(entity);
    }

    /**
     * Build option candles from LiveCandleBuilder history (WebSocket ticks).
     * Falls back to a single synthetic candle from the live quote if no history yet.
     * This avoids the REST historical candle API for options which is often empty/delayed.
     */
    private List<Candle> buildOptionCandles(Instrument instrument, Quote liveQuote) {
        // Try LiveCandleBuilder history first
        List<Candle> history = candleBuilder.getHistory(
                instrument.instrumentToken(), properties.entry().timeframe());
        if (!history.isEmpty()) return history;
        // Fall back: single synthetic candle from live quote so scan is not skipped
        if (liveQuote != null && liveQuote.lastPrice() != null && liveQuote.lastPrice().signum() > 0) {
            Candle synthetic = new Candle(
                    instrument.instrumentKey(), liveQuote.timestamp(),
                    properties.entry().timeframe(),
                    liveQuote.lastPrice(), liveQuote.lastPrice(),
                    liveQuote.lastPrice(), liveQuote.lastPrice(),
                    liveQuote.volume(), liveQuote.openInterest());
            return List.of(synthetic);
        }
        return List.of();
    }

    private BigDecimal nearestStrike(List<BigDecimal> strikes, BigDecimal price) {
        return strikes.stream()
                .min(Comparator.comparing(strike -> strike.subtract(price).abs()))
                .orElse(price);
    }

    private record ScanContext(
            Quote spotQuote,
            OptionChainSnapshot optionChainSnapshot,
            Map<OptionType, Instrument> selectedOptions,
            Map<String, Quote> quotes
    ) {
    }

    private record EntryCandidate(
            StrategyDecision decision,
            Quote quote,
            Instrument instrument
    ) {
    }
}
