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

/**
 * Local in-memory cache for broker instruments with option lookup helpers.
 */
public class InstrumentCache {

    private final BrokerClient brokerClient;
    private final Map<String, Instrument> byInstrumentKey = new ConcurrentHashMap<>();

    public InstrumentCache(BrokerClient brokerClient) {
        this.brokerClient = brokerClient;
    }

    public synchronized List<Instrument> refresh() {
        List<Instrument> instruments = brokerClient.downloadInstruments();
        byInstrumentKey.clear();
        instruments.forEach(instrument -> byInstrumentKey.put(instrument.instrumentKey(), instrument));
        return instruments;
    }

    public List<Instrument> all() {
        return List.copyOf(byInstrumentKey.values());
    }

    public Optional<Instrument> findByKey(String instrumentKey) {
        return Optional.ofNullable(byInstrumentKey.get(instrumentKey));
    }

    public Optional<LocalDate> nearestExpiry(UnderlyingSymbol underlying, LocalDate asOf) {
        return byInstrumentKey.values().stream()
                .filter(Instrument::tradable)
                .filter(instrument -> instrument.underlying().filter(underlying::equals).isPresent())
                .flatMap(instrument -> instrument.expiry().stream())
                .filter(expiry -> !expiry.isBefore(asOf))
                .min(Comparator.naturalOrder());
    }

    public Optional<Instrument> findOption(
            UnderlyingSymbol underlying,
            LocalDate expiry,
            BigDecimal strike,
            OptionType optionType
    ) {
        return byInstrumentKey.values().stream()
                .filter(Instrument::tradable)
                .filter(instrument -> instrument.underlying().filter(underlying::equals).isPresent())
                .filter(instrument -> instrument.expiry().filter(expiry::equals).isPresent())
                .filter(instrument -> instrument.strike().filter(strike::equals).isPresent())
                .filter(instrument -> instrument.optionType().filter(optionType::equals).isPresent())
                .findFirst();
    }
}
