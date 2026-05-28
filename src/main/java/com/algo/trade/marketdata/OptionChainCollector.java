package com.algo.trade.marketdata;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Snapshots the live option chain every 5 minutes during market hours.
 * Builds historical data for backtesting strategies that need ATP, IV, Greeks.
 *
 * Data stored: data/historical/snapshots/YYYY-MM-DD/NIFTY_HH-MM.json
 */
@Component
public class OptionChainCollector {

    private static final Logger log = LoggerFactory.getLogger(OptionChainCollector.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper mapper = new ObjectMapper();

    private final MarketDataService marketDataService;
    private final InstrumentCache instrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final TradingProperties properties;

    public OptionChainCollector(MarketDataService marketDataService,
                                 InstrumentCache instrumentCache,
                                 ExpiryCalendar expiryCalendar,
                                 TradingProperties properties) {
        this.marketDataService = marketDataService;
        this.instrumentCache = instrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.properties = properties;
    }

    //@Scheduled(fixedDelay = 300_000) // every 5 minutes
    public void collectSnapshot() {
        if (!isMarketHours()) return;

        for (UnderlyingSymbol underlying : properties.symbols().underlyings()) {
            try {
                collectForUnderlying(underlying);
            } catch (Exception e) {
                log.debug("[Collector] Failed for {}: {}", underlying, e.getMessage());
            }
        }
    }

    private void collectForUnderlying(UnderlyingSymbol underlying) throws Exception {
        IndexType indexType = IndexType.from(underlying);
        String spotKey = properties.symbols().spotQuoteKeys().get(underlying);
        Optional<Quote> spotQuote = marketDataService.quote(spotKey);
        if (spotQuote.isEmpty() || spotQuote.get().lastPrice().signum() <= 0) return;

        double spot = spotQuote.get().lastPrice().doubleValue();
        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);

        // Collect ATM ± 15 strikes
        List<Map<String, Object>> strikes = new ArrayList<>();
        for (int i = -15; i <= 15; i++) {
            int strike = atm + (i * interval);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strike", strike);

            // Fetch CE and PE quotes
            for (OptionType optType : OptionType.values()) {
                Optional<Instrument> inst = instrumentCache.findOption(underlying, expiry,
                        java.math.BigDecimal.valueOf(strike), optType);
                if (inst.isEmpty()) continue;

                Optional<Quote> q = marketDataService.quote(inst.get().instrumentKey());
                if (q.isEmpty()) continue;

                String prefix = optType == OptionType.CE ? "ce" : "pe";
                row.put(prefix + "LTP", q.get().lastPrice());
                row.put(prefix + "ATP", q.get().averageTradedPrice().orElse(null));
                row.put(prefix + "OI", q.get().openInterest());
                row.put(prefix + "Volume", q.get().volume());
                row.put(prefix + "IV", q.get().impliedVolatility().orElse(null));
            }
            strikes.add(row);
        }

        // Build snapshot
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("index", underlying.name());
        snapshot.put("timestamp", LocalDateTime.now(IST).toString());
        snapshot.put("spot", spot);
        snapshot.put("expiry", expiry.toString());
        snapshot.put("strikes", strikes);

        // Save to file
        LocalDate today = LocalDate.now(IST);
        LocalTime now = LocalTime.now(IST);
        Path dir = Paths.get("data/historical/snapshots", today.format(DateTimeFormatter.ISO_LOCAL_DATE));
        Files.createDirectories(dir);
        String fileName = underlying.name() + "_" + now.format(DateTimeFormatter.ofPattern("HH-mm")) + ".json";
        Files.writeString(dir.resolve(fileName), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));

        log.info("[Collector] Saved {}/{} ({} strikes, spot={})", today, fileName, strikes.size(), spot);
    }

    private boolean isMarketHours() {
        LocalTime now = LocalTime.now(IST);
        DayOfWeek day = LocalDate.now(IST).getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        return now.isAfter(LocalTime.of(9, 16)) && now.isBefore(LocalTime.of(15, 31));
    }
}
