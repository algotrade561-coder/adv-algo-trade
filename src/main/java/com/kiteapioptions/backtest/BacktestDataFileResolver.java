package com.kiteapioptions.backtest;

import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.Timeframe;
import com.kiteapioptions.domain.UnderlyingSymbol;
import java.nio.file.Path;
import java.util.Locale;

public final class BacktestDataFileResolver {

    private BacktestDataFileResolver() {
    }

    public static Path forTimeframe(String configuredCsvImportPath, Timeframe timeframe) {
        return resolve(configuredCsvImportPath, slug(timeframe));
    }

    public static Path forSelection(String configuredCsvImportPath, UnderlyingSymbol underlying, OptionType optionType,
                                    Timeframe timeframe) {
        return resolve(configuredCsvImportPath, slug(underlying) + "-" + slug(optionType) + "-" + slug(timeframe));
    }

    public static Path forToken(String configuredCsvImportPath, String instrumentToken, Timeframe timeframe) {
        return resolve(configuredCsvImportPath, "token-" + instrumentToken + "-" + slug(timeframe));
    }

    private static Path resolve(String configuredCsvImportPath, String suffix) {
        Path configured = Path.of(configuredCsvImportPath);
        Path fileName = configured.getFileName();
        if (fileName == null) {
            return configured;
        }

        String name = fileName.toString();
        int extensionIndex = name.lastIndexOf('.');
        String stem = extensionIndex > 0 ? name.substring(0, extensionIndex) : name;
        String extension = extensionIndex > 0 ? name.substring(extensionIndex) : "";
        String resolvedName = stem + "-" + suffix + extension;

        Path parent = configured.getParent();
        return parent == null ? Path.of(resolvedName) : parent.resolve(resolvedName);
    }

    private static String slug(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
