package com.algo.trade.commodity;

import com.algo.trade.domain.Instrument;
import com.algo.trade.marketdata.InstrumentCache;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Service to identify and track MCX Crude Oil futures contract.
 * 
 * Finds the nearest expiry MCX crude oil contract from Kite instruments
 * and provides the token for WebSocket subscription.
 */
@Service
public class McxCrudeOilService {

    private static final Logger log = LoggerFactory.getLogger(McxCrudeOilService.class);

    private final InstrumentCache instrumentCache;
    private final OilPriceTracker oilPriceTracker;

    public McxCrudeOilService(InstrumentCache instrumentCache, OilPriceTracker oilPriceTracker) {
        this.instrumentCache = instrumentCache;
        this.oilPriceTracker = oilPriceTracker;
    }

    /**
     * Find MCX crude oil contract at startup.
     * Called after instruments are loaded from Kite.
     */
    @PostConstruct
    public void initialize() {
        log.info("[MCX Crude Oil] Initializing...");
        findAndSetCrudeOilContract();
    }

    /**
     * Find the nearest expiry MCX crude oil futures contract.
     * MCX crude oil contracts expire on the 19th of every month.
     */
    public void findAndSetCrudeOilContract() {
        try {
            List<Instrument> allInstruments = instrumentCache.all();
            
            Optional<Instrument> crudeOil = allInstruments.stream()
                    .filter(i -> "MCX".equals(i.exchange()))
                    .filter(i -> i.tradingSymbol().startsWith("CRUDEOIL"))
                    .filter(i -> i.tradingSymbol().endsWith("FUT"))
                    .filter(i -> i.expiry().isPresent())
                    .filter(i -> i.expiry().get().isAfter(LocalDate.now()))
                    .min(Comparator.comparing(i -> i.expiry().get()));

            if (crudeOil.isPresent()) {
                Instrument inst = crudeOil.get();
                oilPriceTracker.setInstrumentDetails(
                        inst.instrumentToken(),
                        inst.tradingSymbol(),
                        inst.expiry().get()
                );
                log.info("[MCX Crude Oil] Found contract: {} (token: {}, expiry: {})",
                        inst.tradingSymbol(), inst.instrumentToken(), inst.expiry().get());
            } else {
                log.warn("[MCX Crude Oil] No active contract found in instruments");
            }
        } catch (Exception e) {
            log.error("[MCX Crude Oil] Failed to find contract: {}", e.getMessage(), e);
        }
    }

    /**
     * Get the instrument token for WebSocket subscription.
     */
    public long getCrudeOilToken() {
        return oilPriceTracker.getInstrumentToken();
    }

    /**
     * Check if crude oil contract is available.
     */
    public boolean isAvailable() {
        return oilPriceTracker.getInstrumentToken() > 0;
    }

    /**
     * Get current oil price snapshot.
     */
    public OilPriceTracker.OilPriceSnapshot getSnapshot() {
        return oilPriceTracker.getSnapshot();
    }
}
