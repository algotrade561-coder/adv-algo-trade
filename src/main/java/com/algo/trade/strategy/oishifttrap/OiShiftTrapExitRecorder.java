package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.util.IstDateTimes;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Path-aware exit log for OI Shift Trap trades (MAE / MFE / timing).
 */
@Component
public class OiShiftTrapExitRecorder {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapExitRecorder.class);
    private static final Path FILE = Path.of("reports", "entry-signals", "oi-shift-trap-exits.csv");

    private static final String HEADER = String.join(",",
            "decisionKey", "tradeId", "entryTimestamp", "exitTimestamp", "underlying", "trapSide", "strike", "spot",
            "entryPremium", "exitPremium", "realizedPnlPct", "realizedPnl",
            "mae", "timeToMaeSec", "spotAtMae",
            "mfe", "timeToMfeSec", "spotAtMfe",
            "exitReason", "holdSeconds", "score", "imbalance", "proximityPct",
            "trappedOiAtEntry", "trappedOiAtExit"
    ) + System.lineSeparator();

    public void recordExit(TradeEntity trade, BigDecimal exitPrice, String exitReason,
                           ShiftTrapMaeMfeTracker.State maeState) {
        if (trade == null || exitPrice == null) {
            return;
        }
        try {
            Files.createDirectories(FILE.getParent());
            ShiftTrapMaeMfeTracker.EntryContext ctx = maeState != null ? maeState.context : null;
            BigDecimal entry = trade.getEntryPrice();
            double pnlPct = entry.signum() > 0
                    ? (exitPrice.doubleValue() - entry.doubleValue()) / entry.doubleValue() * 100.0 : 0;
            Instant exitTime = Instant.now();
            long holdSec = trade.getEntryTime() != null
                    ? Duration.between(trade.getEntryTime(), exitTime).getSeconds() : 0;
            long timeToMae = maeState != null && maeState.maeAt != null && trade.getEntryTime() != null
                    ? Duration.between(trade.getEntryTime(), maeState.maeAt).getSeconds() : 0;
            long timeToMfe = maeState != null && maeState.mfeAt != null && trade.getEntryTime() != null
                    ? Duration.between(trade.getEntryTime(), maeState.mfeAt).getSeconds() : 0;

            String row = String.join(",",
                    csv(ctx != null ? ctx.decisionKey() : ""),
                    csv(trade.getTradeId()),
                    csv(trade.getEntryTime() != null ? IstDateTimes.formatInstant(trade.getEntryTime()) : ""),
                    csv(IstDateTimes.formatInstant(exitTime)),
                    csv(trade.getUnderlying()),
                    csv(ctx != null ? ctx.trapSide() : trade.getOptionType()),
                    csv(ctx != null ? ctx.strike() : ""),
                    csv(ctx != null ? ctx.spotAtEntry() : ""),
                    csv(entry),
                    csv(exitPrice),
                    csv(String.format("%.2f", pnlPct)),
                    csv(trade.getRealizedPnl()),
                    csv(maeState != null ? String.format("%.2f", maeState.maePct) : ""),
                    csv(timeToMae),
                    csv(maeState != null ? String.format("%.2f", maeState.spotAtMae) : ""),
                    csv(maeState != null ? String.format("%.2f", maeState.mfePct) : ""),
                    csv(timeToMfe),
                    csv(maeState != null ? String.format("%.2f", maeState.spotAtMfe) : ""),
                    csv(exitReason),
                    csv(holdSec),
                    csv(ctx != null ? ctx.score() : ""),
                    csv(ctx != null ? ctx.imbalance() : ""),
                    csv(ctx != null ? ctx.proximityPct() : ""),
                    csv(ctx != null ? ctx.trappedOiAtEntry() : trade.getEntryOpenInterest()),
                    csv(maeState != null ? maeState.trappedOiAtExit : "")
            ) + System.lineSeparator();

            if (!Files.exists(FILE)) {
                Files.writeString(FILE, HEADER, StandardOpenOption.CREATE);
            }
            Files.writeString(FILE, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("[OiShiftTrapExit] record failed: {}", ex.getMessage());
        }
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
