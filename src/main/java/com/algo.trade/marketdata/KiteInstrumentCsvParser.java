package com.algo.trade.marketdata;

import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.UnderlyingSymbol;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parser for Zerodha's instrument CSV dump.
 */
public class KiteInstrumentCsvParser {

    private static boolean isTradableOption(String segment) {
        return "NFO-OPT".equals(segment) || "BFO-OPT".equals(segment);
    }


    public List<Instrument> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }

        String[] lines = csv.split("\\R");
        if (lines.length <= 1) {
            return List.of();
        }

        List<Instrument> instruments = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                instruments.add(parseLine(lines[i]));
            }
        }
        return List.copyOf(instruments);
    }

    private Instrument parseLine(String line) {
        String[] columns = parseCsvLine(line);
        if (columns.length < 12) {
            throw new IllegalArgumentException("Invalid Kite instrument row: " + line);
        }

        long instrumentToken = Long.parseLong(columns[0]);
        String tradingSymbol = columns[2];
        String name = columns[3].isBlank() ? tradingSymbol : columns[3];
        String expiryText = columns[5];
        String strikeText = columns[6];
        BigDecimal tickSize = decimalOrZero(columns[7]);
        int lotSize = integerOrZero(columns[8]);
        String instrumentType = columns[9];
        String segment = columns[10];
        String exchange = columns[11];

        Optional<UnderlyingSymbol> underlying = parseUnderlying(name);
        Optional<LocalDate> expiry = expiryText.isBlank() ? Optional.empty() : Optional.of(LocalDate.parse(expiryText));
        Optional<BigDecimal> strike = strikeText.isBlank() ? Optional.empty() : Optional.of(decimalOrZero(strikeText));
        Optional<OptionType> optionType = parseOptionType(instrumentType);
        boolean tradableOption = isTradableOption(segment) && optionType.isPresent();

        return new Instrument(instrumentToken, exchange, tradingSymbol, name, underlying, expiry, strike,
                optionType, lotSize, tickSize, tradableOption);
    }

    private String[] parseCsvLine(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                columns.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        columns.add(current.toString().trim());
        return columns.toArray(String[]::new);
    }

    private Optional<UnderlyingSymbol> parseUnderlying(String value) {
        for (UnderlyingSymbol symbol : UnderlyingSymbol.values()) {
            if (symbol.name().equalsIgnoreCase(value)) {
                return Optional.of(symbol);
            }
        }
        return Optional.empty();
    }

    private Optional<OptionType> parseOptionType(String value) {
        if ("CE".equalsIgnoreCase(value)) {
            return Optional.of(OptionType.CE);
        }
        if ("PE".equalsIgnoreCase(value)) {
            return Optional.of(OptionType.PE);
        }
        return Optional.empty();
    }

    private BigDecimal decimalOrZero(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private int integerOrZero(String value) {
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }
}
