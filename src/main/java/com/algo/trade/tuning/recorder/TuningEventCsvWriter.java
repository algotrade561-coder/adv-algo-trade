package com.algo.trade.tuning.recorder;

import com.algo.trade.tuning.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serializes {@link TuningEvent} subtypes to CSV rows. Phase 1: no hot-column
 * promotion — every value in {@code attributes()} is rolled into a single
 * {@code attr_extra} JSON column. Phase 2's adapter contract will let adapters
 * declare hot columns that get promoted out of the JSON sidecar; that change is
 * additive and won't break Phase 1 readers.
 *
 * <p>The strategy + index pair is intentionally <strong>omitted</strong> from the
 * row body — both are encoded in the file path
 * ({@code reports/tuning/events/&lt;date&gt;/&lt;strategy&gt;/&lt;event_type&gt;.csv})
 * so we don't repeat them millions of times per day.</p>
 *
 * <p>{@code recordedAtDeltaUs} stores the microsecond offset between
 * {@link TuningEvent#eventTime()} and {@link TuningEvent#recordedAt()}; on disk this
 * is a small integer instead of a full second timestamp — much cheaper to encode and
 * easy to reconstruct in DuckDB if needed.</p>
 */
public class TuningEventCsvWriter {

    private static final Logger log = LoggerFactory.getLogger(TuningEventCsvWriter.class);

    private final ObjectMapper jsonMapper;

    public TuningEventCsvWriter() {
        this(new ObjectMapper());
    }

    public TuningEventCsvWriter(ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String headerFor(TuningEventType type) {
        return switch (type) {
            case EVALUATION -> "eventTime,recordedAtDeltaUs,index,correlationKey,"
                    + "outcome,blocker,episodeTickCount,attr_extra\n";
            case SIGNAL -> "eventTime,recordedAtDeltaUs,index,correlationKey,"
                    + "instrumentKey,strike,optionType,entryPremium,attr_extra\n";
            case EXECUTION -> "eventTime,recordedAtDeltaUs,index,correlationKey,"
                    + "orderId,stage,requestedQty,filledQty,avgFillPrice,slippagePct,"
                    + "brokerRejectionReason,attr_extra\n";
            case EXIT -> "eventTime,recordedAtDeltaUs,index,correlationKey,"
                    + "tradeId,exitReason,entryPrice,exitPrice,realizedPnlPct,holdSec,"
                    + "maePct,mfePct,timeToMaeSec,timeToMfeSec,reversal,attr_extra\n";
            case FORWARD_CHECKPOINT -> "eventTime,recordedAtDeltaUs,index,correlationKey,"
                    + "fwdSpot30s,fwdSpot1m,fwdSpot5m,fwdSpot15m,fwdSpot30m,"
                    + "fwdMfe30mPct,fwdMae30mPct,attr_extra\n";
            case SHADOW_GATE -> "eventTime,recordedAtDeltaUs,index,correlationKey,"
                    + "gateName,passed,bandValue,attr_extra\n";
            case LEG -> "eventTime,recordedAtDeltaUs,index,correlationKey,"
                    + "legNumber,legSide,legOptionType,legStrike,legInstrumentKey,stage,"
                    + "requestedQty,filledQty,avgFillPrice,attr_extra\n";
        };
    }

    public String format(TuningEvent event) {
        return switch (event) {
            case EvaluationEvent e -> formatEvaluation(e);
            case SignalEvent e -> formatSignal(e);
            case ExecutionEvent e -> formatExecution(e);
            case ExitEvent e -> formatExit(e);
            case ForwardCheckpointEvent e -> formatForward(e);
            case ShadowGateEvent e -> formatShadow(e);
            case LegEvent e -> formatLeg(e);
        };
    }

    /** Phase 5 — LegEvent serializer. Matches the headerFor(LEG) column order. */
    private String formatLeg(LegEvent e) {
        return join(
                e.eventTime().toString(),
                deltaUs(e.eventTime(), e.recordedAt()),
                e.index().name(),
                csv(e.correlationKey()),
                Integer.toString(e.legNumber()),
                csv(e.legSide()),
                e.legOptionType() != null ? e.legOptionType().name() : "",
                Integer.toString(e.legStrike()),
                csv(e.legInstrumentKey()),
                csv(e.stage()),
                Integer.toString(e.requestedQty()),
                Integer.toString(e.filledQty()),
                e.avgFillPrice() != null ? e.avgFillPrice().toPlainString() : "0",
                json(e.attributes())
        ) + "\n";
    }

    // ── Per-type serializers ──────────────────────────────────────────────

    private String formatEvaluation(EvaluationEvent e) {
        return join(
                e.eventTime().toString(),
                deltaUs(e.eventTime(), e.recordedAt()),
                e.index().name(),
                csv(e.correlationKey()),
                e.outcome().name(),
                csv(e.blocker()),
                Integer.toString(e.episodeTickCount()),
                json(e.attributes())
        ) + "\n";
    }

    private String formatSignal(SignalEvent e) {
        return join(
                e.eventTime().toString(),
                deltaUs(e.eventTime(), e.recordedAt()),
                e.index().name(),
                csv(e.correlationKey()),
                csv(e.instrumentKey()),
                Integer.toString(e.strike()),
                e.optionType().name(),
                bigDecimal(e.entryPremium()),
                json(e.attributes())
        ) + "\n";
    }

    private String formatExecution(ExecutionEvent e) {
        return join(
                e.eventTime().toString(),
                deltaUs(e.eventTime(), e.recordedAt()),
                e.index().name(),
                csv(e.correlationKey()),
                csv(e.orderId()),
                csv(e.stage()),
                Integer.toString(e.requestedQty()),
                Integer.toString(e.filledQty()),
                bigDecimal(e.avgFillPrice()),
                doubleOrEmpty(e.slippagePct()),
                csv(e.brokerRejectionReason()),
                json(e.attributes())
        ) + "\n";
    }

    private String formatExit(ExitEvent e) {
        return join(
                e.eventTime().toString(),
                deltaUs(e.eventTime(), e.recordedAt()),
                e.index().name(),
                csv(e.correlationKey()),
                csv(e.tradeId()),
                csv(e.exitReason()),
                bigDecimal(e.entryPrice()),
                bigDecimal(e.exitPrice()),
                fixed3(e.realizedPnlPct()),
                Long.toString(e.holdSec()),
                fixed3(e.maePct()),
                fixed3(e.mfePct()),
                Long.toString(e.timeToMaeSec()),
                Long.toString(e.timeToMfeSec()),
                Boolean.toString(e.reversal()),
                json(e.attributes())
        ) + "\n";
    }

    private String formatForward(ForwardCheckpointEvent e) {
        return join(
                e.eventTime().toString(),
                deltaUs(e.eventTime(), e.recordedAt()),
                e.index().name(),
                csv(e.correlationKey()),
                doubleOrEmpty(e.fwdSpot30s()),
                doubleOrEmpty(e.fwdSpot1m()),
                doubleOrEmpty(e.fwdSpot5m()),
                doubleOrEmpty(e.fwdSpot15m()),
                doubleOrEmpty(e.fwdSpot30m()),
                doubleOrEmpty(e.fwdMfe30mPct()),
                doubleOrEmpty(e.fwdMae30mPct()),
                json(e.attributes())
        ) + "\n";
    }

    private String formatShadow(ShadowGateEvent e) {
        return join(
                e.eventTime().toString(),
                deltaUs(e.eventTime(), e.recordedAt()),
                e.index().name(),
                csv(e.correlationKey()),
                csv(e.gateName()),
                Boolean.toString(e.passed()),
                doubleOrEmpty(e.bandValue()),
                json(e.attributes())
        ) + "\n";
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String join(String... cols) {
        return String.join(",", cols);
    }

    private static String deltaUs(Instant eventTime, Instant recordedAt) {
        long us = ChronoUnit.MICROS.between(eventTime, recordedAt);
        return Long.toString(us);
    }

    private static String bigDecimal(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static String doubleOrEmpty(Double v) {
        return v == null ? "" : fixed3(v);
    }

    private static String fixed3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** CSV-quote a string value when it contains a comma, quote, or newline. */
    static String csv(String s) {
        if (s == null) {
            return "";
        }
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0;
        if (!needsQuote) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Serialize the attributes map as a JSON string, CSV-quoted. Empty → {@code {}}. */
    private String json(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return csv("{}");
        }
        try {
            return csv(jsonMapper.writeValueAsString(attributes));
        } catch (JsonProcessingException ex) {
            log.warn("[TuningEventCsvWriter] attribute JSON serialization failed: {}", ex.getMessage());
            return csv("{}");
        }
    }
}
