package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.reporting.SignalDecisionKey;
import com.algo.trade.util.IstDateTimes;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CSV capture for OI Momentum tuning — does not affect entry/exit logic.
 */
@Component
public class OiMomentumTuneRecorder {

    private static final Logger log = LoggerFactory.getLogger(OiMomentumTuneRecorder.class);
    private static final Path DIR = Path.of("reports", "entry-signals");
    private static final Path SIGNALS = DIR.resolve("oi-momentum-signals.csv");
    private static final Path REJECTS = DIR.resolve("oi-momentum-rejects.csv");
    private static final Path EXITS = DIR.resolve("oi-momentum-exits.csv");
    private static final Path CHAIN_LEVELS = DIR.resolve("option-chain-levels.csv");

    private static final String SIGNAL_HEADER = String.join(",",
            "decisionKey", "signalId", "timestamp", "marketTime", "indexType", "underlying", "signalType", "optionType",
            "instrumentKey", "strike", "spot", "premium", "spreadPct", "quantityLots",
            "entryCase", "momentumDir", "momentumType", "momentumMagnitudePct",
            "oiDir", "pcrDir", "pcr", "ceOiChange", "peOiChange", "oiAvailable",
            "spot30mHigh", "spot30mLow", "breakoutDistancePct", "spikeEpisodeId",
            "vix", "daysToExpiry", "isExpiryDay", "paperTrading", "reasons"
    ) + System.lineSeparator();

    private static final String REJECT_HEADER = String.join(",",
            "timestamp", "marketTime", "indexType", "rejectReason", "wouldBeCase",
            "momentumDir", "momentumType", "pcr", "oiDir", "ceOiChange", "peOiChange", "spot", "vix"
    ) + System.lineSeparator();

    private static final String EXIT_HEADER = String.join(",",
            "decisionKey", "tradeId", "timestamp", "indexType", "instrumentKey",
            "entryPrice", "exitPrice", "profitPct", "realizedPnl", "holdSeconds",
            "exitReason", "entryCase", "reversal"
    ) + System.lineSeparator();

    private static final String CHAIN_HEADER = String.join(",",
            "decisionKey", "evaluationTimestamp", "underlying", "optionType",
            "selectedInstrumentKey", "spotPrice", "strike",
            "callOpenInterest", "putOpenInterest",
            "callOpenInterestChange", "putOpenInterestChange",
            "callLastPrice", "putLastPrice"
    ) + System.lineSeparator();

    private static final Duration REJECT_SAMPLE_INTERVAL = Duration.ofSeconds(30);

    private final LiveInstrumentCache liveInstrumentCache;

    public OiMomentumTuneRecorder(LiveInstrumentCache liveInstrumentCache) {
        this.liveInstrumentCache = liveInstrumentCache;
    }

    public String recordBuy(StrategyDecision decision, OiMomentumEntryDiagnostics diag,
                            Quote entryQuote, int atm, Double spreadPct, int lotSize) {
        String decisionKey = SignalDecisionKey.from(decision);
        String signalId = java.util.UUID.randomUUID().toString().substring(0, 12);
        try {
            Files.createDirectories(DIR);
            String row = String.join(",",
                    csv(decisionKey),
                    csv(signalId),
                    csv(IstDateTimes.formatInstant(decision.timestamp())),
                    csv(IstDateTimes.formatLocalTime(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")))),
                    csv(diag.indexType().name()),
                    csv(decision.underlying()),
                    csv(decision.signalType()),
                    csv(decision.optionType().map(Enum::name).orElse(null)),
                    csv(decision.selectedInstrumentKey().orElse(null)),
                    csv(atm),
                    csv(decision.underlyingPrice()),
                    csv(entryQuote.lastPrice()),
                    csv(spreadPct),
                    csv(lotSize),
                    csv(diag.entryCase()),
                    csv(diag.momentumDir()),
                    csv(diag.momentumType()),
                    csv(diag.momentumMagnitudePct()),
                    csv(diag.oiDir()),
                    csv(diag.pcrDir()),
                    csv(diag.pcr()),
                    csv(diag.ceOiChange()),
                    csv(diag.peOiChange()),
                    csv(diag.oiAvailable()),
                    csv(diag.spot30mHigh()),
                    csv(diag.spot30mLow()),
                    csv(diag.breakoutDistancePct()),
                    csv(diag.spikeEpisodeId()),
                    csv(diag.vix()),
                    csv(diag.daysToExpiry()),
                    csv(diag.expiryDay()),
                    csv(diag.paperTrading()),
                    csv(decision.reasons().isEmpty() ? diag.signalReason() : String.join("; ", decision.reasons()))
            ) + System.lineSeparator();
            append(SIGNALS, SIGNAL_HEADER, row);
            writeChainLevels(decisionKey, decision, diag, atm);
        } catch (IOException ex) {
            log.warn("[OiMomentumTune] BUY record failed: {}", ex.getMessage());
        }
        return decisionKey;
    }

    /** @return sample time if a row was written (for throttle state). */
    public Instant recordReject(IndexType indexType, Instant lastSampleTime, String rejectReason,
                             OiMomentumEntryDiagnostics partial) {
        Instant now = Instant.now();
        if (lastSampleTime != null && Duration.between(lastSampleTime, now).compareTo(REJECT_SAMPLE_INTERVAL) < 0) {
            return lastSampleTime;
        }
        try {
            Files.createDirectories(DIR);
            String row = String.join(",",
                    csv(IstDateTimes.formatInstant(now)),
                    csv(IstDateTimes.formatLocalTime(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")))),
                    csv(indexType.name()),
                    csv(rejectReason),
                    csv(partial != null ? partial.entryCase() : ""),
                    csv(partial != null ? partial.momentumDir() : ""),
                    csv(partial != null ? partial.momentumType() : ""),
                    csv(partial != null ? partial.pcr() : ""),
                    csv(partial != null ? partial.oiDir() : ""),
                    csv(partial != null ? partial.ceOiChange() : ""),
                    csv(partial != null ? partial.peOiChange() : ""),
                    csv(partial != null ? partial.spot() : ""),
                    csv(partial != null ? partial.vix() : "")
            ) + System.lineSeparator();
            append(REJECTS, REJECT_HEADER, row);
            return now;
        } catch (IOException ex) {
            log.warn("[OiMomentumTune] reject record failed: {}", ex.getMessage());
        }
        return lastSampleTime;
    }

    public void recordExit(String decisionKey, IndexType indexType, TradeEntity trade,
                           double exitPrice, String exitReason, OiMomentumEntryDiagnostics entryDiag,
                           boolean reversal) {
        if (decisionKey == null || decisionKey.isBlank()) {
            return;
        }
        double entry = trade.getEntryPrice().doubleValue();
        double profitPct = entry > 0 ? (exitPrice - entry) / entry * 100 : 0;
        double pnl = (exitPrice - entry) * trade.getQuantity();
        long holdSeconds = trade.getEntryTime() != null
                ? Duration.between(trade.getEntryTime(), Instant.now()).getSeconds()
                : 0;
        try {
            Files.createDirectories(DIR);
            String row = String.join(",",
                    csv(decisionKey),
                    csv(trade.getTradeId()),
                    csv(IstDateTimes.formatInstant(Instant.now())),
                    csv(indexType.name()),
                    csv(trade.getInstrumentKey()),
                    csv(entry),
                    csv(exitPrice),
                    csv(profitPct),
                    csv(pnl),
                    csv(holdSeconds),
                    csv(exitReason),
                    csv(entryDiag != null ? entryDiag.entryCase() : ""),
                    csv(reversal)
            ) + System.lineSeparator();
            append(EXITS, EXIT_HEADER, row);
        } catch (IOException ex) {
            log.warn("[OiMomentumTune] exit record failed: {}", ex.getMessage());
        }
    }

    private void writeChainLevels(String decisionKey, StrategyDecision decision,
                                  OiMomentumEntryDiagnostics diag, int atm) {
        IndexType indexType = diag.indexType();
        int interval = indexType.strikeInterval();
        StringBuilder rows = new StringBuilder();
        BigDecimal spot = decision.underlyingPrice();
        OptionType optType = decision.optionType().orElse(OptionType.CE);
        for (int i = -3; i <= 3; i++) {
            int strike = atm + i * interval;
            long ceOi = 0, peOi = 0, ceCh = 0, peCh = 0;
            BigDecimal cePrice = BigDecimal.ZERO;
            BigDecimal pePrice = BigDecimal.ZERO;
            for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
                if (opt.getIndexType() != indexType) {
                    continue;
                }
                if (opt.getStrikePrice() != strike) {
                    continue;
                }
                if ("CE".equals(opt.getOptionType())) {
                    ceOi = opt.getOpenInterest();
                    ceCh = opt.getOiChangeSince(3);
                    cePrice = BigDecimal.valueOf(opt.getLastPrice());
                } else {
                    peOi = opt.getOpenInterest();
                    peCh = opt.getOiChangeSince(3);
                    pePrice = BigDecimal.valueOf(opt.getLastPrice());
                }
            }
            rows.append(String.join(",",
                    csv(decisionKey),
                    csv(IstDateTimes.formatInstant(decision.timestamp())),
                    csv(decision.underlying()),
                    csv(optType.name()),
                    csv(decision.selectedInstrumentKey().orElse(null)),
                    csv(spot),
                    csv(strike),
                    csv(ceOi), csv(peOi), csv(ceCh), csv(peCh),
                    csv(cePrice), csv(pePrice)
            )).append(System.lineSeparator());
        }
        if (!rows.isEmpty()) {
            try {
                append(CHAIN_LEVELS, CHAIN_HEADER, rows.toString());
            } catch (IOException ex) {
                log.warn("[OiMomentumTune] chain levels failed: {}", ex.getMessage());
            }
        }
    }

    private void append(Path path, String header, String rows) throws IOException {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        if (Files.notExists(path) || Files.size(path) == 0) {
            Files.writeString(path, header, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Files.writeString(path, rows, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String csv(Object value) {
        if (value == null) {
            return "\"\"";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
