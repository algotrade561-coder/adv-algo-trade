package com.algo.trade.marketdata;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.UnderlyingSymbol;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local in-memory cache for broker instruments with option lookup helpers.
 */
public class InstrumentCache {

    private static final Logger log = LoggerFactory.getLogger(InstrumentCache.class);

    private final BrokerClient brokerClient;
    private final Map<String, Instrument> byInstrumentKey = new ConcurrentHashMap<>();

    public InstrumentCache(BrokerClient brokerClient) {
        this.brokerClient = brokerClient;
    }

    public synchronized List<Instrument> refresh() {
        log.info("Instrument cache refresh started");
        List<Instrument> instruments = brokerClient.downloadInstruments();
        byInstrumentKey.clear();
        instruments.forEach(instrument -> byInstrumentKey.put(instrument.instrumentKey(), instrument));
        log.info("Instrument cache refresh completed: instrumentCount={}", instruments.size());
        return instruments;
    }

    public List<Instrument> all() {
        log.debug("Instrument cache read: instrumentCount={}", byInstrumentKey.size());
        return List.copyOf(byInstrumentKey.values());
    }

    public Optional<Instrument> findByKey(String instrumentKey) {
        Optional<Instrument> instrument = Optional.ofNullable(byInstrumentKey.get(instrumentKey));
        log.debug("Instrument lookup by key: instrumentKey={}, present={}", instrumentKey, instrument.isPresent());
        return instrument;
    }

    public Optional<LocalDate> nearestExpiry(UnderlyingSymbol underlying, LocalDate asOf) {
        return nearestExpiry(underlying, asOf, "NEAREST");
    }

    public Optional<LocalDate> nearestExpiry(UnderlyingSymbol underlying, LocalDate asOf, String expiryPreference) {
        List<LocalDate> expiries = byInstrumentKey.values().stream()
                .filter(Instrument::tradable)
                .filter(instrument -> instrument.underlying().filter(underlying::equals).isPresent())
                .flatMap(instrument -> instrument.expiry().stream())
                .filter(candidateExpiry -> !candidateExpiry.isBefore(asOf))
                .distinct()
                .sorted()
                .toList();
        if (expiries.isEmpty()) {
            log.debug("Nearest expiry lookup: underlying={}, asOf={}, preference={}, expiry=null",
                    underlying, asOf, expiryPreference);
            return Optional.empty();
        }

        String preference = expiryPreference == null ? "NEAREST" : expiryPreference.trim().toUpperCase(Locale.ROOT);
        Optional<LocalDate> nearestExpiry = switch (preference) {
            case "NEAREST_WEEKLY" -> nearestWeeklyExpiry(expiries).or(() -> Optional.of(expiries.getFirst()));
            case "NEAREST_MONTHLY" -> nearestMonthlyExpiry(expiries).or(() -> Optional.of(expiries.getFirst()));
            default -> Optional.of(expiries.getFirst());
        };
        log.debug("Nearest expiry lookup: underlying={}, asOf={}, preference={}, expiry={}",
                underlying, asOf, preference, nearestExpiry.orElse(null));
        return nearestExpiry;
    }

    private Optional<LocalDate> nearestWeeklyExpiry(List<LocalDate> expiries) {
        List<LocalDate> monthlyExpiries = monthlyExpiries(expiries);
        return expiries.stream()
                .filter(expiry -> !monthlyExpiries.contains(expiry))
                .findFirst();
    }

    private Optional<LocalDate> nearestMonthlyExpiry(List<LocalDate> expiries) {
        return monthlyExpiries(expiries).stream().findFirst();
    }

    private List<LocalDate> monthlyExpiries(List<LocalDate> expiries) {
        return expiries.stream()
                .collect(java.util.stream.Collectors.groupingBy(YearMonth::from))
                .values().stream()
                .map(monthExpiries -> monthExpiries.stream().max(Comparator.naturalOrder()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    public Optional<Instrument> findOption(
            UnderlyingSymbol underlying,
            LocalDate expiry,
            BigDecimal strike,
            OptionType optionType
    ) {
        Optional<Instrument> matchingInstrument = byInstrumentKey.values().stream()
                .filter(Instrument::tradable)
                .filter(candidate -> candidate.underlying().filter(underlying::equals).isPresent())
                .filter(candidate -> candidate.expiry().filter(expiry::equals).isPresent())
                .filter(candidate -> candidate.strike().filter(strike::equals).isPresent())
                .filter(candidate -> candidate.optionType().filter(optionType::equals).isPresent())
                .findFirst();
        log.debug("Option lookup: underlying={}, expiry={}, strike={}, optionType={}, present={}",
                underlying, expiry, strike, optionType, matchingInstrument.isPresent());
        return matchingInstrument;
    }
}
