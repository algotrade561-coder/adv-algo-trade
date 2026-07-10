package com.algo.trade.tuning.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-source enrichment (§3a) — joins option-chain snapshot context onto the trade {@link DecisionRecord}
 * by index + the nearest-prior 5-min snapshot (DuckDB {@code ASOF JOIN}). The snapshots are a separate source
 * (gz-JSON under {@code data/chain-snapshots/<date>/<INDEX>_HHMM.json.gz}); DuckDB reads them directly via
 * {@code read_json_auto}, and {@code UNNEST(strikes)} gives per-strike OI for the PCR / total-OI aggregates.
 *
 * <p>Validated against real 06-22 data: gz read, strike UNNEST/aggregation, and the ASOF join (all 50 signals
 * matched their nearest-prior snapshot). Timestamps in the snapshots are IST; event {@code eventTime} is UTC,
 * so the join shifts the event by +330 min. Enrichment is best-effort: if no snapshots exist for the range it
 * returns the trade SQL unchanged.
 */
public final class ChainContext {

    private ChainContext() {}

    /** Existing chain-snapshot gz files for the date range under {@code base/<date>/}. */
    public static List<Path> snapshotFiles(Path base, LocalDate from, LocalDate to) {
        List<Path> out = new ArrayList<>();
        if (base == null || from == null || to == null || from.isAfter(to)) {
            return out;
        }
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Path dir = base.resolve(d.toString());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".json.gz")).forEach(out::add);
            } catch (Exception ignore) {
                // best-effort
            }
        }
        return out;
    }

    /** Per-(index, snapshot-time) aggregate: spot/vix/atm + PCR and total OI from the strike ladder. */
    public static String aggregateSql(List<Path> files) {
        return ""
                + "SELECT idx, ts, ANY_VALUE(chain_spot) AS chain_spot, ANY_VALUE(chain_vix) AS chain_vix, "
                + "  ANY_VALUE(chain_atm) AS chain_atm, SUM(ce_oi) AS chain_ce_oi, SUM(pe_oi) AS chain_pe_oi, "
                + "  SUM(pe_oi) * 1.0 / NULLIF(SUM(ce_oi), 0) AS chain_pcr "
                + "FROM ("
                + "  SELECT underlying AS idx, timestamp::TIMESTAMP AS ts, spot AS chain_spot, vix AS chain_vix, "
                + "    atmStrike AS chain_atm, s.ceOI AS ce_oi, s.peOI AS pe_oi "
                + "  FROM (SELECT underlying, timestamp, spot, vix, atmStrike, UNNEST(strikes) AS s "
                + "        FROM read_json_auto([" + glob(files) + "]))"
                + ") GROUP BY idx, ts";
    }

    /**
     * Wraps {@code tradeRecordSql} with an ASOF LEFT JOIN to the chain aggregate, adding
     * {@code chain_spot/chain_vix/chain_atm/chain_ce_oi/chain_pe_oi/chain_pcr}. Returns the input unchanged
     * when there are no snapshots (so callers don't need to special-case the empty range).
     */
    public static String enrichTradeSql(String tradeRecordSql, List<Path> chainFiles) {
        if (chainFiles == null || chainFiles.isEmpty()) {
            return tradeRecordSql;
        }
        return ""
                + "SELECT tr.*, c.chain_spot, c.chain_vix, c.chain_atm, c.chain_ce_oi, c.chain_pe_oi, c.chain_pcr "
                + "FROM (" + tradeRecordSql + ") tr "
                + "ASOF LEFT JOIN (" + aggregateSql(chainFiles) + ") c "
                + "  ON tr.index = c.idx AND (TRY_CAST(tr.eventTime AS TIMESTAMP) + INTERVAL 330 MINUTE) >= c.ts";
    }

    private static String glob(List<Path> files) {
        List<String> quoted = new ArrayList<>(files.size());
        for (Path p : files) {
            quoted.add("'" + p.toString().replace("'", "''") + "'");
        }
        return String.join(",", quoted);
    }
}
