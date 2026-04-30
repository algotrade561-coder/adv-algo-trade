package com.algo.trade.ml;

import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.OptionChainAnalysis;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;

/**
 * Extracts ML feature vectors from live strategy evaluation context.
 * Also provides CSV-based extraction for training data generation.
 */
@Component
public class MlFeatureExtractor {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

    /**
     * Extract features from live evaluation context.
     */
    public MlFeatureVector extract(
            BigDecimal underlyingPrice,
            Quote optionQuote,
            OptionChainAnalysis chain,
            double ivRank,
            boolean vwapPassed,
            boolean breakoutPassed,
            boolean volumeSpike,
            boolean oiPassed,
            boolean ivPassed,
            boolean liquidityPassed,
            boolean rsiPassed,
            OptionType optionType,
            LocalTime marketTime,
            UnderlyingSymbol underlying,
            BigDecimal ruleBasedScore
    ) {
        double ulPrice = underlyingPrice.doubleValue();
        double optPrice = optionQuote.lastPrice().doubleValue();
        double optVol = optionQuote.volume();
        double optOi = optionQuote.openInterest();
        double optIv = optionQuote.impliedVolatility().map(BigDecimal::doubleValue).orElse(0.0);

        double imbalance = chain.nearbyPutCallOiImbalance().doubleValue();
        double callOi = chain.nearbyCallOpenInterest();
        double putOi = chain.nearbyPutOpenInterest();
        double resCallOiChange = chain.resistanceCallOiChange();
        double supPutOiChange = chain.supportPutOiChange();

        double optTypeVal = optionType == OptionType.CE ? 0.0 : 1.0;
        double minSinceOpen = java.time.Duration.between(MARKET_OPEN, marketTime).toMinutes();
        double ulVal = encodeUnderlying(underlying);

        double premiumRatio = ulPrice > 0 ? optPrice / ulPrice : 0;
        double imbalanceAbs = Math.abs(imbalance);

        return new MlFeatureVector(
                ulPrice, optPrice, optVol, optOi, optIv,
                imbalance, callOi, putOi, resCallOiChange, supPutOiChange,
                ivRank,
                b(vwapPassed), b(breakoutPassed), b(volumeSpike), b(oiPassed),
                b(ivPassed), b(liquidityPassed), b(rsiPassed),
                optTypeVal, minSinceOpen, ulVal,
                premiumRatio, imbalanceAbs,
                ruleBasedScore.doubleValue(),
                0, 0, 0, 0, 0, 0, // rsi, atr, emaGap, spread, vix, dte — filled by caller via csvRowData
                0, 0, 0, 0, 0, 0, 0 // delta, gamma, theta, vega, rv5d, ivRvSpread, ivSkew
        );
    }

    /**
     * Extract features from a CSV row (for training data generation).
     * Column names match entry-signals.csv headers.
     */
    public MlFeatureVector extractFromCsvRow(java.util.Map<String, String> row) {
        double ulPrice = parseDouble(row.get("underlyingPrice"));
        double optPrice = parseDouble(row.get("optionLastPrice"));
        double optVol = parseDouble(row.get("optionVolume"));
        double optOi = parseDouble(row.get("optionOpenInterest"));
        double optIv = parseDouble(row.get("optionImpliedVolatility"));

        double imbalance = parseDouble(row.get("nearbyPutCallOiImbalance"));
        double callOi = parseDouble(row.get("nearbyCallOpenInterest"));
        double putOi = parseDouble(row.get("nearbyPutOpenInterest"));
        double resCallOiChange = parseDouble(row.get("resistanceCallOiChange"));
        double supPutOiChange = parseDouble(row.get("supportPutOiChange"));

        double ivRank = parseDouble(row.get("ivRank"));

        double vwapPassed = parseBool(row.get("vwapPassed"));
        double breakoutPassed = parseBool(row.get("breakoutPassed"));
        double volumeSpike = parseBool(row.get("volumeSpike"));
        double oiPassed = parseBool(row.get("oiPassed"));
        double ivPassed = parseBool(row.get("ivPassed"));
        double liquidityPassed = parseBool(row.get("liquidityPassed"));
        double rsiPassed = parseBool(row.get("rsiPassed"));

        String optType = row.getOrDefault("optionType", "CE");
        double optTypeVal = "PE".equalsIgnoreCase(optType) ? 1.0 : 0.0;

        double minSinceOpen = parseMinutesSinceOpen(row.get("marketTime"));

        String ul = row.getOrDefault("underlying", "NIFTY");
        double ulVal = encodeUnderlyingStr(ul);

        double premiumRatio = ulPrice > 0 ? optPrice / ulPrice : 0;
        double imbalanceAbs = Math.abs(imbalance);
        double ruleScore = parseDouble(row.get("confidenceScore"));

        double rv5d = parseDouble(row.get("realizedVol5d"));
        double ivRvSpread = optIv > 0 && rv5d > 0 ? optIv - rv5d : 0.0;

        return new MlFeatureVector(
                ulPrice, optPrice, optVol, optOi, optIv,
                imbalance, callOi, putOi, resCallOiChange, supPutOiChange,
                ivRank,
                vwapPassed, breakoutPassed, volumeSpike, oiPassed,
                ivPassed, liquidityPassed, rsiPassed,
                optTypeVal, minSinceOpen, ulVal,
                premiumRatio, imbalanceAbs, ruleScore,
                parseDouble(row.get("rsiValue")),
                parseDouble(row.get("atrValue")),
                parseDouble(row.get("ema9Ema21Gap")),
                parseDouble(row.get("bidAskSpread")),
                parseDouble(row.get("vixLevel")),
                parseDouble(row.get("daysToExpiry")),
                parseDouble(row.get("delta")),
                parseDouble(row.get("gamma")),
                parseDouble(row.get("theta")),
                parseDouble(row.get("vega")),
                rv5d,
                ivRvSpread,
                parseDouble(row.get("ivSkew"))
        );
    }

    private static double b(boolean v) { return v ? 1.0 : 0.0; }

    private static double encodeUnderlying(UnderlyingSymbol u) {
        return switch (u) {
            case NIFTY -> 0.0;
            case BANKNIFTY -> 1.0;
            case SENSEX -> 2.0;
            case FINNIFTY -> 3.0;
            case MIDCPNIFTY -> 4.0;
        };
    }

    private static double encodeUnderlyingStr(String u) {
        if (u == null) return 0.0;
        return switch (u.toUpperCase()) {
            case "BANKNIFTY" -> 1.0;
            case "SENSEX" -> 2.0;
            default -> 0.0;
        };
    }

    private static double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private static double parseBool(String s) {
        if (s == null || s.isBlank()) return 0.0;
        return "true".equalsIgnoreCase(s.trim()) ? 1.0 : 0.0;
    }

    private static double parseMinutesSinceOpen(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return 0.0;
        try {
            // Handle formats like "09:30:03.288" or "09:30"
            String clean = timeStr.trim();
            if (clean.contains(".")) clean = clean.substring(0, clean.indexOf('.'));
            LocalTime t = LocalTime.parse(clean);
            return java.time.Duration.between(MARKET_OPEN, t).toMinutes();
        } catch (Exception e) { return 0.0; }
    }
}
