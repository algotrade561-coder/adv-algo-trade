package com.algo.trade.backtest;

import com.algo.trade.domain.Timeframe;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One-time converter for Global Datafeeds NIFTY option ZIP archives into the backtest candle CSV format.
 *
 * Usage:
 * java com.algo.trade.backtest.GlobalDataFeedsOptionConverter
 *     "C:\data\Nifty _Option_15.04.2025_to_15.04.2026_1 _Min_data"
 *     "C:\data\backtest\imports\global-datafeeds"
 *
 * Output:
 * - C:\data\backtest\imports\global-datafeeds/by-day/YYYY/YYYY-MM-DD.csv
 * - C:\data\backtest\imports\global-datafeeds/by-contract/<trading-symbol>.csv
 * - C:\data\backtest\imports\global-datafeeds/manifest.csv
 */
public final class GlobalDataFeedsOptionConverter {

    private static final String HEADER = "timestamp,instrumentKey,timeframe,open,high,low,close,volume,openInterest";
    private static final String MANIFEST_HEADER =
            "tradingDate,zipPath,dailyOutputPath,rowCount,contractCount,firstTimestamp,lastTimestamp";
    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter SOURCE_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);
    private static final DateTimeFormatter SOURCE_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter ZIP_DATE = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ROOT);
    private static final Pattern ZIP_DATE_PATTERN = Pattern.compile("(\\d{8})");
    private static final Comparator<NormalizedRow> BY_INSTRUMENT_THEN_TIMESTAMP =
            Comparator.comparing(NormalizedRow::instrumentKey).thenComparing(NormalizedRow::timestamp);
    private static final Comparator<NormalizedRow> BY_TIMESTAMP =
            Comparator.comparing(NormalizedRow::timestamp);

    private GlobalDataFeedsOptionConverter() {
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.fromArgs(args);
        convert(config);
    }

    public static void convert(Config config) throws IOException {
        Objects.requireNonNull(config, "config");
        if (!Files.isDirectory(config.sourceRoot())) {
            throw new IllegalArgumentException("Source root does not exist or is not a directory: "
                    + config.sourceRoot());
        }

        Files.createDirectories(config.outputRoot());
        Path byDayRoot = config.outputRoot().resolve("by-day");
        Path byContractRoot = config.outputRoot().resolve("by-contract");
        Files.createDirectories(byDayRoot);
        Files.createDirectories(byContractRoot);

        Path manifestPath = config.outputRoot().resolve("manifest.csv");
        ensureHeader(manifestPath, MANIFEST_HEADER);

        List<Path> zipFiles;
        try (var stream = Files.walk(config.sourceRoot())) {
            zipFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(GlobalDataFeedsOptionConverter::zipTradingDate)
                            .thenComparing(Path::toString))
                    .toList();
        }

        int zipIndex = 0;
        for (Path zipPath : zipFiles) {
            zipIndex++;
            System.out.printf("[%d/%d] Converting %s%n", zipIndex, zipFiles.size(), zipPath);
            List<NormalizedRow> rows = readZipRows(zipPath, config);
            if (rows.isEmpty()) {
                continue;
            }

            rows.sort(BY_INSTRUMENT_THEN_TIMESTAMP);
            LocalDate tradingDate = rows.getFirst().tradingDate();

            Path dailyOutputPath = byDayRoot
                    .resolve(String.valueOf(tradingDate.getYear()))
                    .resolve(tradingDate + ".csv");
            ensureHeader(dailyOutputPath, HEADER);
            appendLines(dailyOutputPath, rows.stream().map(NormalizedRow::csvLine).toList());

            Map<String, List<NormalizedRow>> byContract = new LinkedHashMap<>();
            for (NormalizedRow row : rows) {
                byContract.computeIfAbsent(row.instrumentKey(), key -> new ArrayList<>()).add(row);
            }
            for (Map.Entry<String, List<NormalizedRow>> entry : byContract.entrySet()) {
                entry.getValue().sort(BY_TIMESTAMP);
                Path contractPath = byContractRoot.resolve(safeFileName(entry.getKey()) + ".csv");
                ensureHeader(contractPath, HEADER);
                appendLines(contractPath, entry.getValue().stream().map(NormalizedRow::csvLine).toList());
            }

            NormalizedRow first = rows.getFirst();
            NormalizedRow last = rows.getLast();
            appendLines(manifestPath, List.of(csvRow(
                    tradingDate.toString(),
                    quote(zipPath.toString()),
                    quote(dailyOutputPath.toString()),
                    Integer.toString(rows.size()),
                    Integer.toString(byContract.size()),
                    first.timestamp().toString(),
                    last.timestamp().toString()
            )));
        }
    }

    private static List<NormalizedRow> readZipRows(Path zipPath, Config config) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipFile.stream()
                    .filter(candidate -> !candidate.isDirectory())
                    .findFirst()
                    .orElse(null);
            if (entry == null) {
                return List.of();
            }

            List<NormalizedRow> rows = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8))) {
                String line;
                boolean headerSkipped = false;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (!headerSkipped) {
                        headerSkipped = true;
                        continue;
                    }
                    NormalizedRow row = parseRow(line, config);
                    if (row != null) {
                        rows.add(row);
                    }
                }
            }
            return rows;
        }
    }

    private static NormalizedRow parseRow(String line, Config config) {
        String[] columns = line.split(",", -1);
        if (columns.length < 8) {
            throw new IllegalArgumentException("Unexpected Global Datafeeds row: " + line);
        }

        String instrumentKey = columns[0].trim();
        if (!instrumentKey.regionMatches(true, 0, config.underlyingPrefix(), 0, config.underlyingPrefix().length())) {
            return null;
        }

        LocalDate tradingDate = LocalDate.parse(columns[1].trim(), SOURCE_DATE);
        LocalDateTime sourceDateTime = LocalDateTime.parse(
                columns[1].trim() + " " + columns[2].trim(),
                SOURCE_DATE_TIME
        );
        Instant timestamp = sourceDateTime.atZone(SOURCE_ZONE).toInstant();
        String openInterest = columns.length > 8 && !columns[8].isBlank()
                ? Long.toString(Long.parseLong(columns[8].trim()))
                : "0";
        String csvLine = csvRow(
                timestamp.toString(),
                instrumentKey,
                config.timeframe().name(),
                normalizeDecimal(columns[3]),
                normalizeDecimal(columns[4]),
                normalizeDecimal(columns[5]),
                normalizeDecimal(columns[6]),
                Long.toString(Long.parseLong(columns[7].trim())),
                openInterest
        );
        return new NormalizedRow(tradingDate, timestamp, instrumentKey, csvLine);
    }

    private static void ensureHeader(Path path, String header) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.notExists(path) || Files.size(path) == 0L) {
            Files.writeString(path, header + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static void appendLines(Path path, List<String> lines) throws IOException {
        if (lines.isEmpty()) {
            return;
        }
        Files.write(path,
                lines.stream().map(line -> line + System.lineSeparator()).toList(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String normalizeDecimal(String value) {
        return new BigDecimal(value.trim()).toPlainString();
    }

    private static String csvRow(String... columns) {
        return String.join(",", columns);
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String safeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static LocalDate zipTradingDate(Path zipPath) {
        Matcher matcher = ZIP_DATE_PATTERN.matcher(zipPath.getFileName().toString());
        if (!matcher.find()) {
            throw new IllegalArgumentException("Unable to derive trading date from zip file name: " + zipPath);
        }
        return LocalDate.parse(matcher.group(1), ZIP_DATE);
    }

    public record Config(
            Path sourceRoot,
            Path outputRoot,
            String underlyingPrefix,
            Timeframe timeframe
    ) {
        public Config {
            Objects.requireNonNull(sourceRoot, "sourceRoot");
            Objects.requireNonNull(outputRoot, "outputRoot");
            Objects.requireNonNull(underlyingPrefix, "underlyingPrefix");
            Objects.requireNonNull(timeframe, "timeframe");
        }

        static Config fromArgs(String[] args) {
            Map<String, String> namedArgs = parseNamedArgs(args);
            Path sourceRoot = namedArgs.containsKey("source")
                    ? Path.of(namedArgs.get("source"))
                    : args.length > 0 && !args[0].startsWith("--")
                    ? Path.of(args[0])
                    : Path.of("C:\\data\\Nifty _Option_15.04.2025_to_15.04.2026_1 _Min_data");
            Path outputRoot = namedArgs.containsKey("output")
                    ? Path.of(namedArgs.get("output"))
                    : args.length > 1 && !args[1].startsWith("--")
                    ? Path.of(args[1])
                    : Path.of("C:\\data\\backtest\\imports\\global-datafeeds");
            String underlyingPrefix = namedArgs.getOrDefault("underlying",
                    args.length > 2 && !args[2].startsWith("--") ? args[2] : "NIFTY");
            Timeframe timeframe = namedArgs.containsKey("timeframe")
                    ? Timeframe.valueOf(namedArgs.get("timeframe"))
                    : args.length > 3 && !args[3].startsWith("--")
                    ? Timeframe.valueOf(args[3])
                    : Timeframe.ONE_MINUTE;
            return new Config(sourceRoot, outputRoot, underlyingPrefix, timeframe);
        }

        private static Map<String, String> parseNamedArgs(String[] args) {
            Map<String, String> namedArgs = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if (!arg.startsWith("--")) {
                    continue;
                }
                String key = arg.substring(2).trim().toLowerCase(Locale.ROOT);
                if (key.isBlank()) {
                    continue;
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for argument --" + key);
                }
                namedArgs.put(key, args[++index]);
            }
            return namedArgs;
        }
    }

    private record NormalizedRow(
            LocalDate tradingDate,
            Instant timestamp,
            String instrumentKey,
            String csvLine
    ) {
    }
}
