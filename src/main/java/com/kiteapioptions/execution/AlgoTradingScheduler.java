package com.kiteapioptions.execution;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.broker.zerodha.KiteAccessTokenStore;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.HistoricalDataRequest;
import com.kiteapioptions.domain.Instrument;
import com.kiteapioptions.domain.MarketDataMode;
import com.kiteapioptions.domain.OptionChainLevel;
import com.kiteapioptions.domain.OptionChainSnapshot;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.Quote;
import com.kiteapioptions.domain.StrategyDecision;
import com.kiteapioptions.domain.Timeframe;
import com.kiteapioptions.domain.TradingMode;
import com.kiteapioptions.domain.UnderlyingSymbol;
import com.kiteapioptions.marketdata.InstrumentCache;
import com.kiteapioptions.marketdata.MarketDataService;
import com.kiteapioptions.persistence.StrategyDecisionEntity;
import com.kiteapioptions.persistence.StrategyDecisionRepository;
import com.kiteapioptions.strategy.RuleBasedOptionsStrategy;
import com.kiteapioptions.strategy.StrategyEvaluationRequest;
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
            StrategyDecisionRepository decisionRepository
    ) {
        this.properties = properties;
        this.tradingStateService = tradingStateService;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.strategy = strategy;
        this.executionEngine = executionEngine;
        this.tokenStore = tokenStore;
        this.decisionRepository = decisionRepository;
    }

    @Scheduled(
            initialDelayString = "${trading.algo.initial-delay-ms:5}",
            fixedDelayString = "${trading.algo.scan-interval-ms:6}"
    )
    public void scan() {
        if (!properties.algo().schedulerEnabled()) {
            log.info("Algo scan skipped: scheduler disabled by config");
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

        Optional<LocalDate> expiry = instrumentCache.nearestExpiry(underlying, LocalDate.now(properties.timezone()));
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
        BigDecimal maxPremium = maxTradablePremium();
        List<BigDecimal> candidateStrikes = candidateStrikes(optionType, strikes, underlyingPrice);
        for (BigDecimal strike : candidateStrikes) {
            Optional<Instrument> instrument = findOption(options, strike, optionType);
            if (instrument.isEmpty()) {
                continue;
            }
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
        log.warn("No option fits risk-budget premium: optionType={}, maxPremium={}, candidateStrikes={}",
                optionType, maxPremium, candidateStrikes);
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

    private BigDecimal maxTradablePremium() {
        BigDecimal riskAmount = properties.risk().totalCapital()
                .multiply(properties.risk().maxRiskPerTradePercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        BigDecimal riskPerLotPercent = BigDecimal.valueOf(properties.backtest().lotSize())
                .multiply(properties.exit().stopLossPercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        if (riskPerLotPercent.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return riskAmount.divide(riskPerLotPercent, MATH_CONTEXT);
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
            List<Candle> optionCandles = candles(historicalKey(selectedInstrument), properties.entry().timeframe());
            if (underlyingCandles.isEmpty() || trendUnderlyingCandles.isEmpty() || optionCandles.isEmpty()) {
                log.warn("Strategy evaluation skipped: missing candles, underlying={}, instrument={}, underlyingCandles={}, trendUnderlyingCandles={}, optionCandles={}",
                        underlying, selectedInstrument.instrumentKey(), underlyingCandles.size(),
                        trendUnderlyingCandles.size(), optionCandles.size());
                continue;
            }
            if (!freshQuote(context.spotQuote()) || !freshQuote(selectedQuote)
                    || !freshCandles(underlyingCandles) || !freshCandles(trendUnderlyingCandles) || !freshCandles(optionCandles)) {
                log.warn("Strategy evaluation skipped: stale market data, underlying={}, instrument={}, spotQuoteTime={}, optionQuoteTime={}, latestUnderlyingCandleTime={}, latestTrendCandleTime={}, latestOptionCandleTime={}, threshold={}",
                        underlying, selectedInstrument.instrumentKey(), context.spotQuote().timestamp(),
                        selectedQuote.timestamp(), latestCandleTimestamp(underlyingCandles).orElse(null),
                        latestCandleTimestamp(trendUnderlyingCandles).orElse(null),
                        latestCandleTimestamp(optionCandles).orElse(null), properties.safety().staleMarketDataThreshold());
                continue;
            }

            StrategyEvaluationRequest request = new StrategyEvaluationRequest(
                    Instant.now(),
                    marketTime,
                    underlying,
                    underlyingCandles,
                    trendUnderlyingCandles,
                    optionCandles,
                    context.optionChainSnapshot(),
                    selectedInstrument.instrumentKey(),
                    selectedInstrument.strike().orElse(null),
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
        decisionRepository.save(new StrategyDecisionEntity(decision.timestamp(), decision.underlying().name(),
                decision.signalType().name(), decision.underlyingPrice(), decision.selectedInstrumentKey().orElse(null),
                decision.selectedStrike().orElse(null), decision.optionType().map(Enum::name).orElse(null),
                decision.vwapConditionPassed(), decision.imbalance().orElse(null), decision.volumeSpike(),
                decision.confidenceScore(), String.join("; ", decision.reasons())));
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
