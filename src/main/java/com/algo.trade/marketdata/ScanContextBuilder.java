package com.algo.trade.marketdata;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.*;
import com.algo.trade.strategy.StrategyConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Builds the ScanContext (option chain, quotes, selected options) needed by
 * strategies that require option chain data (DIRECTIONAL_BUY, ITM_CONVICTION, OI_SHIFT_TRAP).
 *
 * Extracted from AlgoTradeExecution to keep the execution engine focused on orchestration.
 */
@Service
public class ScanContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ScanContextBuilder.class);
    private static final MathContext MC = MathContext.DECIMAL64;

    private final TradingProperties properties;
    private final GlobalConfigService globalConfigService;
    private final StrategyConfigService strategyConfigService;
    private final InstrumentCache instrumentCache;
    private final MarketDataService marketDataService;
    private final LiveInstrumentCache liveInstrumentCache;

    /** Previous scan quotes for OI change delta calculation. */
    private final Map<String, Quote> previousQuotes = new ConcurrentHashMap<>();

    public ScanContextBuilder(TradingProperties properties,
                               GlobalConfigService globalConfigService,
                               StrategyConfigService strategyConfigService,
                               InstrumentCache instrumentCache,
                               MarketDataService marketDataService,
                               LiveInstrumentCache liveInstrumentCache) {
        this.properties = properties;
        this.globalConfigService = globalConfigService;
        this.strategyConfigService = strategyConfigService;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.liveInstrumentCache = liveInstrumentCache;
    }

    // ── Result type ───────────────────────────────────────────────────────

    public record ScanContext(
            Quote spotQuote,
            OptionChainSnapshot optionChainSnapshot,
            Map<OptionType, Instrument> selectedOptions,
            Map<String, Quote> quotes
    ) {}

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Build a full scan context for a given underlying.
     * @param failReason single-element array — set to the failure reason if context cannot be built
     * @return the scan context, or empty if data is unavailable
     */
    public Optional<ScanContext> build(UnderlyingSymbol underlying, String[] failReason) {
        Optional<Quote> spotQuote = spotQuote(underlying);
        if (spotQuote.isEmpty()) {
            failReason[0] = "noSpotQuote";
            log.warn("ScanContext skipped: no spot quote, underlying={}", underlying);
            return Optional.empty();
        }

        Optional<LocalDate> expiry = instrumentCache.nearestExpiry(
                underlying, LocalDate.now(properties.timezone()), properties.symbols().defaultExpiry());
        if (expiry.isEmpty()) {
            failReason[0] = "noExpiry";
            log.warn("ScanContext skipped: no expiry found, underlying={}", underlying);
            return Optional.empty();
        }

        List<Instrument> options = optionUniverse(underlying, expiry.get());
        if (options.isEmpty()) {
            failReason[0] = "noOptionUniverse";
            log.warn("ScanContext skipped: no option instruments, underlying={}, expiry={}", underlying, expiry.get());
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
            failReason[0] = "noChainLevels(optionQuotesMissing)";
            log.warn("ScanContext skipped: option chain levels empty, underlying={}", underlying);
            return Optional.empty();
        }

        Map<OptionType, Instrument> selectedOptions = selectedOptions(underlying, options, underlyingPrice, optionQuotes);
        if (selectedOptions.isEmpty()) {
            BigDecimal maxPrem = maxTradablePremium(underlying, options.stream().findFirst()
                    .map(Instrument::lotSize).orElse(0));
            failReason[0] = "noAffordableOption(maxPremium=" + maxPrem.setScale(0, java.math.RoundingMode.HALF_UP) + ")";
            log.warn("ScanContext skipped: no affordable option, underlying={}", underlying);
            return Optional.empty();
        }

        OptionChainSnapshot snapshot = new OptionChainSnapshot(underlying, Instant.now(), underlyingPrice, levels);
        Map<String, Quote> allQuotes = new LinkedHashMap<>(optionQuotes);
        allQuotes.put(spotQuote.get().instrumentKey(), spotQuote.get());

        log.info("ScanContext built: underlying={}, spot={}, expiry={}, strikes={}, levels={}, options={}",
                underlying, underlyingPrice, expiry.get(), selectedStrikes.size(), levels.size(), selectedOptions.size());
        return Optional.of(new ScanContext(spotQuote.get(), snapshot, selectedOptions, allQuotes));
    }

    /**
     * Update previous quotes for OI change tracking. Call after each scan cycle.
     */
    public void updatePreviousQuotes(Map<String, Quote> currentQuotes) {
        previousQuotes.keySet().retainAll(currentQuotes.keySet());
        previousQuotes.putAll(currentQuotes);
    }

    /** Read-only access to previous quotes — used by scheduler for OI change in evaluateAndExecute. */
    public Map<String, Quote> getPreviousQuotes() {
        return Collections.unmodifiableMap(previousQuotes);
    }

    /** Resolve ATM instrument for a given underlying, option type, and spot price. */
    public Optional<Instrument> resolveAtmInstrument(UnderlyingSymbol underlying, OptionType optionType, BigDecimal spotPrice) {
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

    /** Ensure instrument cache is loaded. */
    public void ensureInstrumentsLoaded() {
        if (!instrumentCache.all().isEmpty()) return;
        if (!properties.algo().refreshInstrumentsOnStart()) {
            log.warn("Instrument cache is empty and refreshInstrumentsOnStart=false");
            return;
        }
        List<Instrument> instruments = instrumentCache.refresh();
        log.info("Instrument cache loaded: count={}", instruments.size());
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private Optional<Quote> spotQuote(UnderlyingSymbol underlying) {
        String spotQuoteKey = properties.symbols().spotQuoteKeys().get(underlying);
        if (spotQuoteKey == null || spotQuoteKey.isBlank()) return Optional.empty();
        double livePrice = liveInstrumentCache.getFuturesPrice(IndexType.from(underlying));
        if (livePrice > 0) {
            return Optional.of(new Quote(spotQuoteKey, Instant.now(),
                    BigDecimal.valueOf(livePrice), 0, 0,
                    Optional.empty(), Optional.empty(), Optional.empty()));
        }
        return marketDataService.quote(spotQuoteKey);
    }

    private List<Instrument> optionUniverse(UnderlyingSymbol underlying, LocalDate expiry) {
        return instrumentCache.all().stream()
                .filter(Instrument::tradable)
                .filter(i -> i.underlying().filter(underlying::equals).isPresent())
                .filter(i -> i.expiry().filter(expiry::equals).isPresent())
                .filter(i -> i.optionType().isPresent())
                .filter(i -> i.strike().isPresent())
                .sorted(Comparator.comparing(i -> i.strike().orElse(BigDecimal.ZERO)))
                .toList();
    }

    private List<BigDecimal> selectedStrikes(List<Instrument> options, BigDecimal underlyingPrice) {
        List<BigDecimal> strikes = options.stream()
                .flatMap(i -> i.strike().stream()).distinct().sorted().toList();
        if (strikes.isEmpty()) return List.of();
        BigDecimal atm = nearestStrike(strikes, underlyingPrice);
        int atmIndex = indexOfStrike(strikes, atm);
        int nearby = properties.strike().nearbyStrikes();
        int start = Math.max(0, atmIndex - nearby);
        int end = Math.min(strikes.size(), atmIndex + nearby + 1);
        return strikes.subList(start, end);
    }

    private Map<OptionType, Instrument> selectedOptions(UnderlyingSymbol underlying, List<Instrument> options,
                                                        BigDecimal underlyingPrice, Map<String, Quote> quotes) {
        List<BigDecimal> strikes = options.stream()
                .flatMap(i -> i.strike().stream()).distinct().sorted().toList();
        if (strikes.isEmpty()) return Map.of();
        Map<OptionType, Instrument> selected = new EnumMap<>(OptionType.class);
        selected.put(OptionType.CE, selectedAffordableOption(underlying, options, strikes, underlyingPrice, OptionType.CE, quotes).orElse(null));
        selected.put(OptionType.PE, selectedAffordableOption(underlying, options, strikes, underlyingPrice, OptionType.PE, quotes).orElse(null));
        selected.values().removeIf(Objects::isNull);
        return Map.copyOf(selected);
    }

    private Optional<Instrument> selectedAffordableOption(UnderlyingSymbol underlying, List<Instrument> options,
                                                          List<BigDecimal> strikes, BigDecimal underlyingPrice,
                                                          OptionType optionType, Map<String, Quote> quotes) {
        BigDecimal atm = nearestStrike(strikes, underlyingPrice);
        int atmIndex = indexOfStrike(strikes, atm);
        int nearby = properties.strike().nearbyStrikes();
        int start = Math.max(0, atmIndex - nearby);
        int end = Math.min(strikes.size(), atmIndex + nearby + 1);
        List<BigDecimal> nearbyStrikes = strikes.subList(start, end);

        List<BigDecimal> candidates = optionType == OptionType.CE
                ? nearbyStrikes.stream().filter(s -> s.compareTo(atm) >= 0).sorted().toList()
                : nearbyStrikes.stream().filter(s -> s.compareTo(atm) <= 0).sorted(Comparator.reverseOrder()).toList();

        for (BigDecimal strike : candidates) {
            Optional<Instrument> instrument = findOption(options, strike, optionType);
            if (instrument.isEmpty()) continue;
            BigDecimal maxPremium = maxTradablePremium(underlying, instrument.get().lotSize());
            Quote quote = quotes.get(instrument.get().instrumentKey());
            if (quote == null || quote.lastPrice() == null || quote.lastPrice().signum() <= 0) continue;
            if (quote.lastPrice().compareTo(maxPremium) <= 0) return instrument;
        }
        return Optional.empty();
    }

    private List<OptionChainLevel> optionChainLevels(List<Instrument> options, List<BigDecimal> selectedStrikes,
                                                      Map<String, Quote> quotes) {
        return selectedStrikes.stream()
                .flatMap(strike -> {
                    Optional<Quote> callQuote = findOption(options, strike, OptionType.CE).map(Instrument::instrumentKey).map(quotes::get);
                    Optional<Quote> putQuote = findOption(options, strike, OptionType.PE).map(Instrument::instrumentKey).map(quotes::get);
                    if (callQuote.isEmpty() || putQuote.isEmpty()) return java.util.stream.Stream.<OptionChainLevel>empty();
                    return java.util.stream.Stream.of(new OptionChainLevel(
                            strike,
                            callQuote.get().openInterest(), putQuote.get().openInterest(),
                            openInterestChange(callQuote.get()), openInterestChange(putQuote.get()),
                            callQuote.get().lastPrice(), putQuote.get().lastPrice(),
                            callQuote.get().impliedVolatility().map(BigDecimal::doubleValue).orElse(0.0),
                            putQuote.get().impliedVolatility().map(BigDecimal::doubleValue).orElse(0.0)
                    ));
                })
                .toList();
    }

    private long openInterestChange(Quote quote) {
        if (quote.instrumentKey() != null && quote.instrumentKey().contains(":")) {
            String symbol = quote.instrumentKey().split(":", 2)[1];
            long liveOiChange = liveInstrumentCache.getBySymbol(symbol).map(o -> o.getOiChange()).orElse(0L);
            if (liveOiChange != 0) return liveOiChange;
        }
        return Optional.ofNullable(previousQuotes.get(quote.instrumentKey()))
                .map(prev -> quote.openInterest() - prev.openInterest()).orElse(0L);
    }

    public BigDecimal maxTradablePremium(UnderlyingSymbol underlying, int lotSize) {
        BigDecimal stopLoss = strategyConfigService.getDirectionalBuyConfig(underlying.name()).getStopLossPercent();
        BigDecimal riskAmount = globalConfigService.getTotalCapital()
                .multiply(globalConfigService.getMaxRiskPerTradePercent(), MC)
                .divide(BigDecimal.valueOf(100), MC);
        if (lotSize <= 0) return BigDecimal.ZERO;
        BigDecimal riskPerLotPct = BigDecimal.valueOf(lotSize).multiply(stopLoss, MC).divide(BigDecimal.valueOf(100), MC);
        if (riskPerLotPct.signum() <= 0) return BigDecimal.ZERO;
        return riskAmount.divide(riskPerLotPct, MC);
    }

    private Optional<Instrument> findOption(List<Instrument> options, BigDecimal strike, OptionType optionType) {
        return options.stream()
                .filter(i -> i.strike().filter(strike::equals).isPresent())
                .filter(i -> i.optionType().filter(optionType::equals).isPresent())
                .findFirst();
    }

    private BigDecimal nearestStrike(List<BigDecimal> strikes, BigDecimal price) {
        return strikes.stream().min(Comparator.comparing(s -> s.subtract(price).abs())).orElse(price);
    }

    private int indexOfStrike(List<BigDecimal> strikes, BigDecimal target) {
        for (int i = 0; i < strikes.size(); i++) {
            if (strikes.get(i).compareTo(target) == 0) return i;
        }
        return strikes.size() / 2;
    }
}
