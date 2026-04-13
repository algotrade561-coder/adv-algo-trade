package com.kiteapioptions.marketdata;

import com.kiteapioptions.broker.BrokerClient;
import com.kiteapioptions.domain.Instrument;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.UnderlyingSymbol;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
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
        Optional<LocalDate> nearestExpiry = byInstrumentKey.values().stream()
                .filter(Instrument::tradable)
                .filter(instrument -> instrument.underlying().filter(underlying::equals).isPresent())
                .flatMap(instrument -> instrument.expiry().stream())
                .filter(candidateExpiry -> !candidateExpiry.isBefore(asOf))
                .min(Comparator.naturalOrder());
        log.debug("Nearest expiry lookup: underlying={}, asOf={}, expiry={}", underlying, asOf, nearestExpiry.orElse(null));
        return nearestExpiry;
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
