package com.algo.trade.backtest;

import com.algo.trade.domain.OptionChainLevel;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Constructs {@link OptionChainSnapshot} objects from Global DataFeeds by-day CSV files.
 *
 * <p>The by-day CSV format (after conversion by {@link GlobalDataFeedsOptionConverter}):
 * <pre>timestamp,instrumentKey,timeframe,open,high,low,close,volume,openInterest</pre>
 *
 * <p>Instrument keys follow patterns like {@code NIFTY26JAN24500CE.NFO} or {@code NIFTY2642124450CE}.
 * The builder parses strike and option type from these keys, groups rows by (strike, optionType),
 * and constructs sorted option chain levels.
 */
@Component
public class OptionChainBuilder {

    private static final Logger log = LoggerFactory.getLogger(OptionChainBuilder.class);

    /**
     * Pattern to extract strike and option type from instrument keys.
     * Matches the last occurrence of digits followed by CE or PE (optionally followed by .NFO).
     * Examples:
     * - NIFTY26JAN24500CE → strike=24500, type=CE
     * - NIFTY2642124450CE → strike=24450, type=CE
     * - BANKNIFTY26JAN52000PE.NFO → strike=52000, type=PE
     */
    private static final Pattern STRIKE_OPTION_TYPE_PATTERN =
            Pattern.compile("(\\d+)(CE|PE)(?:\\.NFO)?$", Pattern.CASE_INSENSITIVE);

    /**
     * Build an end-of-day option chain snapshot from a by-day CSV file.
     *
     * <p>Reads {@code byDayDirectory/YYYY/YYYY-MM-DD.csv}, filters rows matching the underlying,
     * groups by (strike, optionType), takes the LAST row per group as the closing price,
     * and returns an {@link OptionChainSnapshot} with levels sorted by ascending strike.
     *
     * @param byDayDirectory root directory containing year subdirectories
     * @param date           the trading date
     * @param underlying     the underlying symbol to filter (NIFTY, BANKNIFTY)
     * @param underlyingPrice the underlying spot price for the snapshot
     * @return the option chain snapshot, or empty if the CSV file is missing or has no matching rows
     */
    public Optional<OptionChainSnapshot> buildFromByDay(
            Path byDayDirectory,
            LocalDate date,
            UnderlyingSymbol underlying,
            BigDecimal underlyingPrice) {

        Path csvPath = resolveCsvPath(byDayDirectory, date);
        if (!Files.exists(csvPath)) {
            log.warn("By-day CSV not found for {}: {}", date, csvPath);
            return Optional.empty();
        }

        List<ParsedRow> rows;
        try {
            rows = readAndParseRows(csvPath, underlying);
        } catch (IOException e) {
            log.error("Failed to read by-day CSV {}: {}", csvPath, e.getMessage());
            return Optional.empty();
        }

        if (rows.isEmpty()) {
            log.debug("No matching rows for {} in {}", underlying, csvPath);
            return Optional.empty();
        }

        // Group by (strike, optionType), take the LAST row per group (closing price)
        Map<StrikeKey, ParsedRow> lastByStrikeAndType = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            lastByStrikeAndType.put(new StrikeKey(row.strike, row.optionType), row);
        }

        // Build levels grouped by strike
        Instant lastTimestamp = rows.getLast().timestamp;
        List<OptionChainLevel> levels = buildLevels(lastByStrikeAndType);

        if (levels.isEmpty()) {
            log.debug("No valid levels constructed for {} on {}", underlying, date);
            return Optional.empty();
        }

        return Optional.of(new OptionChainSnapshot(
                underlying,
                lastTimestamp,
                underlyingPrice,
                levels
        ));
    }

    /**
     * Build intraday option chain snapshots, one per time bucket of the given aggregation timeframe.
     *
     * <p>Same CSV parsing as {@link #buildFromByDay}, but rows are grouped by time bucket
     * (e.g., 15-minute intervals). Within each bucket, the last row per (strike, optionType)
     * is used to build the chain snapshot.
     *
     * @param byDayDirectory       root directory containing year subdirectories
     * @param date                 the trading date
     * @param underlying           the underlying symbol to filter
     * @param underlyingPrice      the underlying spot price
     * @param aggregationTimeframe the timeframe for bucketing (e.g., FIFTEEN_MINUTE)
     * @return list of timestamped option chains, one per time bucket, sorted chronologically
     */
    public List<TimestampedOptionChain> buildIntradayChains(
            Path byDayDirectory,
            LocalDate date,
            UnderlyingSymbol underlying,
            BigDecimal underlyingPrice,
            Timeframe aggregationTimeframe) {

        Path csvPath = resolveCsvPath(byDayDirectory, date);
        if (!Files.exists(csvPath)) {
            log.warn("By-day CSV not found for {}: {}", date, csvPath);
            return List.of();
        }

        List<ParsedRow> rows;
        try {
            rows = readAndParseRows(csvPath, underlying);
        } catch (IOException e) {
            log.error("Failed to read by-day CSV {}: {}", csvPath, e.getMessage());
            return List.of();
        }

        if (rows.isEmpty()) {
            return List.of();
        }

        long bucketMillis = aggregationTimeframe.duration().toMillis();

        // Group rows by time bucket
        Map<Long, List<ParsedRow>> buckets = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            long epochMillis = row.timestamp.toEpochMilli();
            long bucketStart = epochMillis - (epochMillis % bucketMillis);
            buckets.computeIfAbsent(bucketStart, k -> new ArrayList<>()).add(row);
        }

        List<TimestampedOptionChain> result = new ArrayList<>();
        for (Map.Entry<Long, List<ParsedRow>> entry : buckets.entrySet()) {
            Instant bucketTimestamp = Instant.ofEpochMilli(entry.getKey());
            List<ParsedRow> bucketRows = entry.getValue();

            Map<StrikeKey, ParsedRow> lastByStrikeAndType = new LinkedHashMap<>();
            for (ParsedRow row : bucketRows) {
                lastByStrikeAndType.put(new StrikeKey(row.strike, row.optionType), row);
            }

            List<OptionChainLevel> levels = buildLevels(lastByStrikeAndType);
            if (!levels.isEmpty()) {
                OptionChainSnapshot snapshot = new OptionChainSnapshot(
                        underlying, bucketTimestamp, underlyingPrice, levels);
                result.add(new TimestampedOptionChain(bucketTimestamp, snapshot));
            }
        }

        return result;
    }

    // ---- Internal helpers ----

    private Path resolveCsvPath(Path byDayDirectory, LocalDate date) {
        return byDayDirectory
                .resolve(String.valueOf(date.getYear()))
                .resolve(date.toString() + ".csv");
    }

    private List<ParsedRow> readAndParseRows(Path csvPath, UnderlyingSymbol underlying) throws IOException {
        String prefix = underlying.name();
        List<ParsedRow> rows = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String line;
            boolean headerSkipped = false;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }
                ParsedRow row = parseRow(line, prefix);
                if (row != null) {
                    rows.add(row);
                }
            }
        }

        return rows;
    }

    /**
     * Parse a single CSV row into a {@link ParsedRow}, or return null if it doesn't match
     * the underlying prefix or can't be parsed.
     */
    private ParsedRow parseRow(String line, String underlyingPrefix) {
        String[] columns = line.split(",", -1);
        if (columns.length < 7) {
            return null;
        }

        // columns: timestamp, instrumentKey, timeframe, open, high, low, close, volume, openInterest
        String instrumentKey = columns[1].trim();

        // Strip .NFO suffix for prefix matching
        String cleanKey = instrumentKey.endsWith(".NFO")
                ? instrumentKey.substring(0, instrumentKey.length() - 4)
                : instrumentKey;

        if (!cleanKey.toUpperCase(Locale.ROOT).startsWith(underlyingPrefix.toUpperCase(Locale.ROOT))) {
            return null;
        }

        // Extract strike and option type
        Matcher matcher = STRIKE_OPTION_TYPE_PATTERN.matcher(instrumentKey);
        if (!matcher.find()) {
            return null;
        }

        BigDecimal strike = extractStrike(cleanKey, underlyingPrefix, matcher);
        if (strike == null) {
            return null;
        }

        OptionType optionType = OptionType.valueOf(matcher.group(2).toUpperCase(Locale.ROOT));

        Instant timestamp;
        try {
            timestamp = Instant.parse(columns[0].trim());
        } catch (Exception e) {
            return null;
        }

        BigDecimal closePrice;
        try {
            closePrice = new BigDecimal(columns[6].trim());
        } catch (NumberFormatException e) {
            return null;
        }

        long openInterest = 0;
        if (columns.length > 8 && !columns[8].isBlank()) {
            try {
                openInterest = Long.parseLong(columns[8].trim());
            } catch (NumberFormatException e) {
                // default to 0
            }
        }

        return new ParsedRow(timestamp, instrumentKey, strike, optionType, closePrice, openInterest);
    }

    /**
     * Extract the strike price from the instrument key.
     * The strike is the numeric portion immediately before CE/PE.
     * For keys like NIFTY26JAN24500CE, the regex captures "24500" in group(1),
     * but we need to be careful: the group may include expiry-encoded digits.
     *
     * Strategy: find the position of CE/PE, then walk backwards to collect the strike digits.
     * The underlying prefix length + expiry encoding gives us the start of the strike portion.
     */
    private BigDecimal extractStrike(String cleanKey, String underlyingPrefix, Matcher matcher) {
        // The matcher matched on the original key (possibly with .NFO).
        // group(1) = digits before CE/PE, group(2) = CE or PE
        // For NIFTY26JAN24500CE: after removing NIFTY prefix → "26JAN24500CE"
        // For NIFTY2642124450CE: after removing NIFTY prefix → "2642124450CE"
        // We need to find where the strike starts.

        // Approach: find CE/PE position in cleanKey, then scan backwards for the strike digits.
        // The strike is typically 3-6 digits. We look for the longest trailing numeric sequence
        // before CE/PE that represents a valid strike (>= 100).
        String upper = cleanKey.toUpperCase(Locale.ROOT);
        int cepeIndex = upper.lastIndexOf("CE");
        if (cepeIndex < 0) {
            cepeIndex = upper.lastIndexOf("PE");
        }
        if (cepeIndex < 0) {
            return null;
        }

        // Walk backwards from cepeIndex to find the start of the numeric strike
        int strikeEnd = cepeIndex;
        int strikeStart = strikeEnd;
        while (strikeStart > 0 && Character.isDigit(upper.charAt(strikeStart - 1))) {
            strikeStart--;
        }

        if (strikeStart >= strikeEnd) {
            return null;
        }

        String strikeStr = upper.substring(strikeStart, strikeEnd);

        // For patterns like NIFTY2642124450CE, the digits before CE are "2642124450"
        // The underlying prefix is "NIFTY", so after prefix we have "2642124450CE"
        // The expiry encoding is variable length (e.g., "26JAN" or "26421")
        // Since we can't reliably separate expiry digits from strike digits in the
        // compact format, we use a heuristic: NIFTY strikes are typically 5 digits (10000-99999),
        // BANKNIFTY strikes are also 5 digits (30000-99999).
        // If the digit sequence is longer than expected, take the last 5 digits as strike.

        // But first check if there's a month code (JAN, FEB, etc.) separating expiry from strike
        String afterPrefix = upper.substring(underlyingPrefix.length());
        int monthCodeEnd = findMonthCodeEnd(afterPrefix);
        if (monthCodeEnd > 0) {
            // Format: NIFTY02MAR2624500CE → afterPrefix = "02MAR2624500CE"
            // After month code (pos 5): "2624500CE"
            // The next 2 digits are the year (26), then the strike (24500), then CE/PE.
            String afterMonth = afterPrefix.substring(monthCodeEnd);
            // Skip 2-digit year if present (digits immediately after month code)
            if (afterMonth.length() >= 2 && Character.isDigit(afterMonth.charAt(0))
                    && Character.isDigit(afterMonth.charAt(1))) {
                afterMonth = afterMonth.substring(2);
            }
            int typeIdx = afterMonth.indexOf("CE");
            if (typeIdx < 0) typeIdx = afterMonth.indexOf("PE");
            if (typeIdx > 0) {
                try {
                    return new BigDecimal(afterMonth.substring(0, typeIdx));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }

        // Compact format: NIFTY2642124450CE — no month code
        // After prefix: "2642124450CE"
        // Expiry is encoded as YYWDD (5 chars) or YYMDD (5 chars), so skip first 5 digits
        // and the rest before CE/PE is the strike
        String digitsBeforeType = afterPrefix.substring(0, afterPrefix.indexOf("CE") >= 0
                ? afterPrefix.indexOf("CE")
                : afterPrefix.indexOf("PE"));

        if (digitsBeforeType.length() > 5) {
            // Skip the first 5 characters (expiry encoding: YY + week/month code + day)
            String strikePart = digitsBeforeType.substring(5);
            if (!strikePart.isEmpty()) {
                try {
                    return new BigDecimal(strikePart);
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
        }

        // Fallback: use the full digit sequence
        try {
            return new BigDecimal(strikeStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Find the end index of a month code (JAN, FEB, ..., DEC) in the string.
     * Returns the index after the month code, or -1 if not found.
     */
    private int findMonthCodeEnd(String s) {
        String[] months = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
        String upper = s.toUpperCase(Locale.ROOT);
        for (String month : months) {
            int idx = upper.indexOf(month);
            if (idx >= 0) {
                return idx + month.length();
            }
        }
        return -1;
    }

    /**
     * Build sorted option chain levels from the last-row-per-(strike, optionType) map.
     */
    private List<OptionChainLevel> buildLevels(Map<StrikeKey, ParsedRow> lastByStrikeAndType) {
        // Group by strike, merge CE and PE data
        TreeMap<BigDecimal, LevelBuilder> byStrike = new TreeMap<>();

        for (Map.Entry<StrikeKey, ParsedRow> entry : lastByStrikeAndType.entrySet()) {
            StrikeKey key = entry.getKey();
            ParsedRow row = entry.getValue();
            LevelBuilder builder = byStrike.computeIfAbsent(key.strike, k -> new LevelBuilder());
            if (key.optionType == OptionType.CE) {
                builder.callLastPrice = row.closePrice;
                builder.callOpenInterest = row.openInterest;
            } else {
                builder.putLastPrice = row.closePrice;
                builder.putOpenInterest = row.openInterest;
            }
        }

        List<OptionChainLevel> levels = new ArrayList<>();
        for (Map.Entry<BigDecimal, LevelBuilder> entry : byStrike.entrySet()) {
            LevelBuilder b = entry.getValue();
            levels.add(new OptionChainLevel(
                    entry.getKey(),
                    b.callOpenInterest,
                    b.putOpenInterest,
                    0L, // callOpenInterestChange — not available from single-day data
                    0L, // putOpenInterestChange
                    b.callLastPrice,
                    b.putLastPrice
            ));
        }

        return levels;
    }

    // ---- Internal data structures ----

    private record ParsedRow(
            Instant timestamp,
            String instrumentKey,
            BigDecimal strike,
            OptionType optionType,
            BigDecimal closePrice,
            long openInterest
    ) {}

    private record StrikeKey(BigDecimal strike, OptionType optionType) {}

    private static class LevelBuilder {
        BigDecimal callLastPrice = BigDecimal.ZERO;
        BigDecimal putLastPrice = BigDecimal.ZERO;
        long callOpenInterest = 0;
        long putOpenInterest = 0;
    }
}
