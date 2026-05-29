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
    private final com.algo.trade.strategy.RegimeAwareStrikeSelector regimeAwareStrikeSelector;
    private final com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService;

    /** Previous scan quotes for OI change delta calculation. */
    private final Map<String, Quote> previousQuotes = new ConcurrentHashMap<>();

    public ScanContextBuilder(TradingProperties properties,
                               GlobalConfigService globalConfigService,
                               StrategyConfigService strategyConfigService,
                               InstrumentCache instrumentCache,
                               MarketDataService marketDataService,
                               LiveInstrumentCache liveInstrumentCache,
                               com.algo.trade.strategy.RegimeAwareStrikeSelector regimeAwareStrikeSelector,
                               com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService) {
        this.properties = properties;
        this.globalConfigService = globalConfigService;
        this.strategyConfigService = strategyConfigService;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.liveInstrumentCache = liveInstrumentCache;
        this.regimeAwareStrikeSelector = regimeAwareStrikeSelector;
        this.underlyingConfigService = underlyingConfigService;
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
        return build(underlying, failReason, null, null);
    }

    /**
     * Build a full scan context with dynamic regime-aware strike selection.
     *
     * <p>When regime and session are provided, the {@link com.algo.trade.strategy.RegimeAwareStrikeSelector}
     * computes a dynamic strike offset. If the offset is -1 (block sentinel), the method returns
     * {@code Optional.empty()} with failReason "regimeBlocked". Otherwise, the offset is used to
     * select options at that distance from ATM.
     *
     * @param underlying the underlying symbol
     * @param failReason single-element array — set to the failure reason if context cannot be built
     * @param regime current market regime (nullable — if null, uses default ATM selection)
     * @param session current session window (nullable — if null, uses default ATM selection)
     * @return the scan context, or empty if data is unavailable or regime blocks entry
     */
    public Optional<ScanContext> build(UnderlyingSymbol underlying, String[] failReason,
                                        com.algo.trade.regime.RegimeFilter.MarketRegime regime,
                                        com.algo.trade.strategy.AlgoFlowOrchestrator.SessionWindow session) {
        // Compute dynamic strike offset if regime and session are available
        int dynamicOffset = 0; // default: ATM
        if (regime != null && session != null) {
            dynamicOffset = regimeAwareStrikeSelector.computeStrikeOffset(regime, session);
            if (dynamicOffset < 0) {
                failReason[0] = "regimeBlocked";
                log.info("ScanContext blocked by regime strike selector: underlying={}, regime={}, session={}",
                        underlying, regime, session);
                return Optional.empty();
            }
        }

        Optional<Quote> spotQuote = spotQuote(underlying);
        if (spotQuote.isEmpty()) {
            failReason[0] = "noSpotQuote";
            log.warn("ScanContext skipped: no spot quote, underlying={}", underlying);
            return Optional.empty();
        }

        Optional<LocalDate> expiry = instrumentCache.nearestExpiry(
                underlying, LocalDate.now(properties.timezone()),
                underlyingConfigService.getExpiryPreference(underlying));
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

        Map<OptionType, Instrument> selectedOptions = selectedOptionsWithOffset(underlying, options,
                underlyingPrice, optionQuotes, dynamicOffset);
        if (selectedOptions.isEmpty()) {
            // Fix (29 May 2026): apply per-underlying cap when displaying maxPremium.
            // Previously this only logged the risk-math-derived cap (e.g. 1778), which
            // hid the fact that a much lower underlying-cap (e.g. 400) was actually
            // doing the filtering. Now show the effective min so the log points
            // directly at whichever cap is binding.
            BigDecimal riskBasedCap = maxTradablePremium(underlying, options.stream().findFirst()
                    .map(Instrument::lotSize).orElse(0));
            BigDecimal underlyingCap = underlyingConfigService.getMaxEntryPremium(underlying);
            BigDecimal effectiveCap = riskBasedCap;
            String capSource = "risk-based";
            if (underlyingCap.signum() > 0 && underlyingCap.compareTo(riskBasedCap) < 0) {
                effectiveCap = underlyingCap;
                capSource = "underlying-config";
            }
            failReason[0] = "noAffordableOption(maxPremium="
                    + effectiveCap.setScale(0, java.math.RoundingMode.HALF_UP)
                    + ",source=" + capSource + ")";
            log.warn("ScanContext skipped: no affordable option, underlying={} effectiveCap=₹{} ({})",
                    underlying,
                    effectiveCap.setScale(0, java.math.RoundingMode.HALF_UP),
                    capSource);
            return Optional.empty();
        }

        OptionChainSnapshot snapshot = new OptionChainSnapshot(underlying, Instant.now(), underlyingPrice, levels);
        Map<String, Quote> allQuotes = new LinkedHashMap<>(optionQuotes);
        allQuotes.put(spotQuote.get().instrumentKey(), spotQuote.get());

        log.info("ScanContext built: underlying={}, spot={}, expiry={}, strikes={}, levels={}, options={}, dynamicOffset={}",
                underlying, underlyingPrice, expiry.get(), selectedStrikes.size(), levels.size(), selectedOptions.size(), dynamicOffset);
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
                    underlying, LocalDate.now(properties.timezone()),
                    underlyingConfigService.getExpiryPreference(underlying));
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
        return selectedOptionsWithOffset(underlying, options, underlyingPrice, quotes, 0);
    }

    /**
     * Select affordable options with a dynamic strike offset from ATM.
     *
     * @param dynamicOffset 0=ATM, +1=1 OTM, +2=2 OTM. Shifts the starting candidate strike.
     */
    private Map<OptionType, Instrument> selectedOptionsWithOffset(UnderlyingSymbol underlying, List<Instrument> options,
                                                                   BigDecimal underlyingPrice, Map<String, Quote> quotes,
                                                                   int dynamicOffset) {
        List<BigDecimal> strikes = options.stream()
                .flatMap(i -> i.strike().stream()).distinct().sorted().toList();
        if (strikes.isEmpty()) return Map.of();
        Map<OptionType, Instrument> selected = new EnumMap<>(OptionType.class);
        selected.put(OptionType.CE, selectedAffordableOption(underlying, options, strikes, underlyingPrice, OptionType.CE, quotes, dynamicOffset).orElse(null));
        selected.put(OptionType.PE, selectedAffordableOption(underlying, options, strikes, underlyingPrice, OptionType.PE, quotes, dynamicOffset).orElse(null));
        selected.values().removeIf(Objects::isNull);
        return Map.copyOf(selected);
    }

    private Optional<Instrument> selectedAffordableOption(UnderlyingSymbol underlying, List<Instrument> options,
                                                          List<BigDecimal> strikes, BigDecimal underlyingPrice,
                                                          OptionType optionType, Map<String, Quote> quotes) {
        return selectedAffordableOption(underlying, options, strikes, underlyingPrice, optionType, quotes, 0);
    }

    private Optional<Instrument> selectedAffordableOption(UnderlyingSymbol underlying, List<Instrument> options,
                                                          List<BigDecimal> strikes, BigDecimal underlyingPrice,
                                                          OptionType optionType, Map<String, Quote> quotes,
                                                          int dynamicOffset) {
        BigDecimal atm = nearestStrike(strikes, underlyingPrice);
        int atmIndex = indexOfStrike(strikes, atm);
        int nearby = properties.strike().nearbyStrikes();
        int start = Math.max(0, atmIndex - nearby);
        int end = Math.min(strikes.size(), atmIndex + nearby + 1);
        List<BigDecimal> nearbyStrikes = strikes.subList(start, end);

        List<BigDecimal> candidates = optionType == OptionType.CE
                ? nearbyStrikes.stream().filter(s -> s.compareTo(atm) >= 0).sorted().toList()
                : nearbyStrikes.stream().filter(s -> s.compareTo(atm) <= 0).sorted(Comparator.reverseOrder()).toList();

        // Apply dynamic offset: skip the first N candidates to start further OTM
        if (dynamicOffset > 0 && candidates.size() > dynamicOffset) {
            candidates = candidates.subList(dynamicOffset, candidates.size());
        }

        for (BigDecimal strike : candidates) {
            Optional<Instrument> instrument = findOption(options, strike, optionType);
            if (instrument.isEmpty()) continue;
            BigDecimal maxPremium = maxTradablePremium(underlying, instrument.get().lotSize());
            // Also apply per-underlying premium cap from UnderlyingConfig
            BigDecimal underlyingCap = underlyingConfigService.getMaxEntryPremium(underlying);
            if (underlyingCap.signum() > 0) {
                maxPremium = maxPremium.min(underlyingCap);
            }
            Quote quote = quotes.get(instrument.get().instrumentKey());
            if (quote == null || quote.lastPrice() == null || quote.lastPrice().signum() <= 0) continue;
            if (quote.lastPrice().compareTo(maxPremium) <= 0) return instrument;
            // If the first valid candidate (nearest to ATM) exceeds the premium cap,
            // don't hunt for far OTM — those are low-quality trades. Skip this underlying.
            if (underlyingCap.signum() > 0 && quote.lastPrice().compareTo(underlyingCap) > 0) {
                log.info("ScanContext: {} {} ATM premium ₹{} exceeds cap ₹{} — skipping underlying (no OTM hunting)",
                        underlying, optionType, quote.lastPrice().setScale(0, java.math.RoundingMode.HALF_UP),
                        underlyingCap.setScale(0, java.math.RoundingMode.HALF_UP));
                return Optional.empty();
            }
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
