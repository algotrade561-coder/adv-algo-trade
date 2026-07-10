package com.algo.trade.indicator;

import com.algo.trade.domain.OptionInstrument;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Black-Scholes Greeks calculator for index options.
 * Ported from AlgoTradingOptions with adaptation to current project types.
 *
 * Calculates: IV (Newton-Raphson), Delta, Gamma, Theta, Vega.
 * Risk-free rate: 6.5% (RBI repo rate). Dividend yield: 0.
 */
@Component
public class GreeksCalculator {

    private static final double RISK_FREE_RATE = 0.065;
    private static final int IV_MAX_ITERATIONS = 100;
    private static final double IV_PRECISION = 0.0001;
    private static final double MIN_IV = 0.01;
    private static final double MAX_IV = 5.0;

    // ── IV convention config (2026-07-06 IV-drift fix) ───────────────────────
    // The bot historically solved IV from the option LTP on a TRADING-day/252 clock. Sensibull / NSE /
    // India-VIX mark IV off the bid/ask MID on a CALENDAR-day/365 clock. Live confirmation on the 2026-07-06
    // chain snapshots: the 252→365 clock is the DOMINANT driver of the ~4-vol-pt understatement on short-dated
    // options (NIFTY 1-DTE 8.95%→12.83% ≈ Sensibull 12.84%; SENSEX 3-DTE 9.55%→12.30% ≈ 12.32%; BANKNIFTY
    // 22-DTE already matched). The price source (LTP vs MID) is NEGLIGIBLE near ATM (≤0.03 pt). Both knobs are
    // flag-guarded and DEFAULT TO THE CORRECTED (Sensibull-matching) values — flipping them changes LIVE IV /
    // greeks and every IV-rank sample, so deploy deliberately.

    /** IV price source: {@code MID} = bid/ask midpoint (Sensibull convention, default) | {@code LTP} = legacy. */
    @org.springframework.beans.factory.annotation.Value("${greeks.iv-price-source:MID}")
    private String ivPriceSource = "MID";

    /** Trust the mid only when the book is two-sided and its spread ≤ this % of mid; else fall back to LTP. */
    @org.springframework.beans.factory.annotation.Value("${greeks.iv-mid-max-spread-pct:50}")
    private double ivMidMaxSpreadPct = 50;

    /** Time-to-expiry clock: {@code CALENDAR_365} (Sensibull/VIX, default) | {@code TRADING_252} (legacy). */
    @org.springframework.beans.factory.annotation.Value("${greeks.time-convention:CALENDAR_365}")
    private String timeConvention = "CALENDAR_365";

    /**
     * Calculate all Greeks and update the OptionInstrument in-place, using the option's
     * <b>FORWARD</b> price (Black-76), not spot.
     *
     * <p>For index options the forward — not spot — is the correct underlying: this is what NSE and
     * Sensibull use ("Black-76 … using futures prices directly", which captures the futures
     * premium/discount and dividends). Passing spot with dividend-yield 0 (the prior behaviour)
     * mis-states the forward and skews IV/greeks (2026-06-27 fix).</p>
     *
     * <p>Black-76 is obtained from the same BSM engine by setting q := r: then the engine's internal
     * forward {@code S·e^{(r-q)T}} collapses to {@code S}, i.e. {@code S} <i>is</i> the forward and
     * discounting is by {@code e^{-rT}} — exactly Black-76. Callers that only have spot can pass
     * {@link #forwardFromSpot(double, LocalDate)}, which reproduces the old spot+q=0 numbers exactly.</p>
     *
     * @param forwardPrice market forward for the option's expiry (e.g. from put-call parity / futures)
     */
    public void calculateAndUpdate(OptionInstrument option, double forwardPrice) {
        if (option.getLastPrice() <= 0 || forwardPrice <= 0) return;
        double T = timeToExpiry(option.getExpiry());
        if (T <= 0) return;

        double F = forwardPrice;
        double K = option.getStrikePrice();
        boolean isCall = option.isCE();
        double marketPrice = resolveIvMarketPrice(option);
        if (marketPrice <= 0) return;

        // q := RISK_FREE_RATE makes the BSM engine treat F as the forward → Black-76.
        double iv = calculateIV(F, K, T, RISK_FREE_RATE, RISK_FREE_RATE, marketPrice, isCall);
        if (Double.isNaN(iv) || iv <= 0) return;

        option.setImpliedVolatility(iv * 100); // store as percentage

        double[] greeks = calculateGreeks(F, K, T, RISK_FREE_RATE, RISK_FREE_RATE, iv, isCall);
        option.setDelta(greeks[0]);
        option.setGamma(greeks[1]);
        option.setTheta(greeks[2]);
        option.setVega(greeks[3]);
    }

    /**
     * The price the IV is solved from. Default {@code MID} (bid/ask midpoint, the Sensibull/NSE convention);
     * falls back to the LTP when configured to {@code LTP}, or whenever the book is missing / one-sided /
     * crossed / wider than {@link #ivMidMaxSpreadPct} of mid — so a thin or absurd quote never poisons IV.
     * Near ATM the mid and LTP coincide (≤0.03 vol pt), so this is a small, safe correction.
     */
    double resolveIvMarketPrice(OptionInstrument option) {
        double ltp = option.getLastPrice();
        if (!"MID".equalsIgnoreCase(ivPriceSource)) return ltp;
        double bid = option.getBestBid();
        double ask = option.getBestAsk();
        if (bid > 0 && ask > 0 && ask >= bid) {
            double mid = (bid + ask) / 2.0;
            if (mid > 0 && (ask - bid) / mid * 100.0 <= ivMidMaxSpreadPct) return mid;
        }
        return ltp; // missing / one-sided / crossed / too-wide book → LTP fallback
    }

    /**
     * Market forward from put-call parity at a strike: {@code F = K + e^{rT}·(C − P)}.
     * The ATM strike (smallest, most liquid C−P) gives the cleanest read. This captures the real
     * futures basis (dividends, demand/supply) the way Sensibull/NSE do. Returns NaN if inputs are
     * invalid so the caller can fall back to {@link #forwardFromSpot}.
     */
    public double forwardFromParity(double strike, double ceLtp, double peLtp, LocalDate expiry) {
        if (strike <= 0 || ceLtp <= 0 || peLtp <= 0) return Double.NaN;
        double T = timeToExpiry(expiry);
        if (T <= 0) return Double.NaN;
        return strike + Math.exp(RISK_FREE_RATE * T) * (ceLtp - peLtp);
    }

    /**
     * Theoretical forward when no put-call-parity forward is available: {@code F = spot·e^{rT}}.
     * Black-76 on this forward is mathematically identical to the prior BSM(spot, r, q=0) output,
     * so this is the safe, behaviour-preserving fallback.
     */
    public double forwardFromSpot(double spot, LocalDate expiry) {
        if (spot <= 0) return Double.NaN;
        double T = timeToExpiry(expiry);
        if (T <= 0) return spot;
        return spot * Math.exp(RISK_FREE_RATE * T);
    }

    public double calculateIV(double S, double K, double T, double r, double q,
                               double marketPrice, boolean isCall) {
        if (marketPrice <= 0 || S <= 0 || K <= 0 || T <= 0) return Double.NaN;

        // For ITM options, solve IV on time value only (premium − intrinsic).
        // The Brenner ATM seed uses total premium which is wrong for ITM, producing garbage IVs.
        double intrinsic = isCall
                ? Math.max(0, S * Math.exp(-q * T) - K * Math.exp(-r * T))
                : Math.max(0, K * Math.exp(-r * T) - S * Math.exp(-q * T));
        double timeValue = marketPrice - intrinsic;
        if (timeValue <= IV_PRECISION) {
            // Deep ITM with no time value — IV is essentially indeterminate.
            return Double.NaN;
        }

        // Corrado-Miller seed (better than Brenner for non-ATM strikes)
        double sigma = corradoMillerSeed(S, K, T, r, q, marketPrice, isCall);
        if (Double.isNaN(sigma) || sigma <= 0) {
            // Fallback: Brenner ATM approximation on time value
            sigma = Math.sqrt(2 * Math.PI / T) * (timeValue / S);
        }
        sigma = Math.max(MIN_IV, Math.min(MAX_IV, sigma));

        // Newton-Raphson with convergence check
        boolean converged = false;
        for (int i = 0; i < IV_MAX_ITERATIONS; i++) {
            double price = bsPrice(S, K, T, r, q, sigma, isCall);
            double vega  = bsVega(S, K, T, r, q, sigma);
            if (vega < 1e-10) {
                // Vega too small for Newton — switch to bisection for the remaining solve
                sigma = bisectionIV(S, K, T, r, q, marketPrice, isCall, MIN_IV, MAX_IV);
                converged = !Double.isNaN(sigma);
                break;
            }
            double diff = price - marketPrice;
            if (Math.abs(diff) < IV_PRECISION) {
                converged = true;
                break;
            }
            sigma -= diff / vega;
            sigma = Math.max(MIN_IV, Math.min(MAX_IV, sigma));
        }

        if (!converged) {
            // Non-convergence → return NaN instead of the unconverged seed
            return Double.NaN;
        }
        return sigma;
    }

    /**
     * Bisection fallback for IV when Newton-Raphson fails (low-vega regions: deep ITM/OTM).
     * Returns NaN if no solution found within bounds.
     */
    private double bisectionIV(double S, double K, double T, double r, double q,
                                double marketPrice, boolean isCall, double lo, double hi) {
        double priceLo = bsPrice(S, K, T, r, q, lo, isCall);
        double priceHi = bsPrice(S, K, T, r, q, hi, isCall);
        // Verify the root is bracketed
        if ((priceLo - marketPrice) * (priceHi - marketPrice) > 0) return Double.NaN;

        for (int i = 0; i < 100; i++) {
            double mid = (lo + hi) / 2.0;
            double priceMid = bsPrice(S, K, T, r, q, mid, isCall);
            double diff = priceMid - marketPrice;
            if (Math.abs(diff) < IV_PRECISION) return mid;
            if ((priceLo - marketPrice) * diff < 0) {
                hi = mid;
            } else {
                lo = mid;
                priceLo = priceMid;
            }
        }
        return (lo + hi) / 2.0; // best approximation after max iterations
    }

    /**
     * Corrado-Miller (2002) closed-form IV seed — better than Brenner for non-ATM.
     * Returns NaN if inputs don't permit a valid estimate.
     */
    private double corradoMillerSeed(double S, double K, double T, double r, double q,
                                      double marketPrice, boolean isCall) {
        double F = S * Math.exp((r - q) * T);
        double disc = Math.exp(-r * T);
        double c = isCall ? marketPrice : marketPrice + disc * (F - K); // convert put to call via parity
        double fk = F - K;
        double inner = (c - fk / 2.0);
        double sqrtTerm = inner * inner - fk * fk / Math.PI;
        if (sqrtTerm < 0) {
            // Fall back to simple approximation
            return Math.sqrt(2 * Math.PI / T) * (c / ((F + K) / 2.0));
        }
        double numerator = inner + Math.sqrt(sqrtTerm);
        double denom = (F + K) / 2.0;
        if (denom <= 0) return Double.NaN;
        return (numerator / denom) * Math.sqrt(2 * Math.PI / T);
    }

    public double[] calculateGreeks(double S, double K, double T, double r, double q,
                                     double sigma, boolean isCall) {
        double d1 = d1(S, K, T, r, q, sigma);
        double d2 = d1 - sigma * Math.sqrt(T);
        double nd1p = npdf(d1);

        double delta = isCall
            ? Math.exp(-q * T) * N(d1)
            : Math.exp(-q * T) * (N(d1) - 1);

        double gamma = Math.exp(-q * T) * nd1p / (S * sigma * Math.sqrt(T));

        double thetaAnnual = isCall
            ? -(S * nd1p * sigma * Math.exp(-q * T)) / (2 * Math.sqrt(T))
              - r * K * Math.exp(-r * T) * N(d2)
              + q * S * Math.exp(-q * T) * N(d1)
            : -(S * nd1p * sigma * Math.exp(-q * T)) / (2 * Math.sqrt(T))
              + r * K * Math.exp(-r * T) * N(-d2)
              - q * S * Math.exp(-q * T) * N(-d1);
        double theta = thetaAnnual / 365.0;

        double vega = S * Math.exp(-q * T) * nd1p * Math.sqrt(T) / 100.0;

        return new double[]{delta, gamma, theta, vega};
    }

    public double timeToExpiry(LocalDate expiry) {
        return timeToExpiry(expiry, LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
    }

    // Package-private for testing
    double timeToExpiry(LocalDate expiry, LocalDateTime now) {
        return "TRADING_252".equalsIgnoreCase(timeConvention)
                ? timeToExpiryTrading252(expiry, now)
                : timeToExpiryCalendar365(expiry, now);
    }

    /**
     * Calendar-day / 365 wall-clock time to the expiry 15:30 close — the Sensibull / NSE / India-VIX
     * convention. This is the default: it makes bot IV directly comparable to Sensibull and to the India-VIX
     * seed used by the IV-rank history. Counts overnight/weekend hours (continuous wall clock), matching the
     * live-confirmed reference (NIFTY 1-DTE bot 8.9%→12.8% ≈ Sensibull 12.84%).
     */
    double timeToExpiryCalendar365(LocalDate expiry, LocalDateTime now) {
        LocalDateTime expiryClose = expiry.atTime(15, 30);
        double years = ChronoUnit.SECONDS.between(now, expiryClose) / (365.0 * 24.0 * 60.0 * 60.0);
        // Floor at ~1 minute (as a fraction of a 365-day year) so an at/near-expiry option keeps a tiny
        // positive T instead of 0 (mirrors the floor on the 252 path).
        return Math.max(1.0 / (365.0 * 24.0 * 60.0), years);
    }

    /** Legacy business-day / 252 clock with an intraday session fraction (pre-2026-07-06 behaviour). */
    double timeToExpiryTrading252(LocalDate expiry, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        java.time.LocalTime nowTime = now.toLocalTime();
        java.time.LocalTime marketOpen = java.time.LocalTime.of(9, 15);
        java.time.LocalTime marketClose = java.time.LocalTime.of(15, 30);

        // Remaining fraction of today's trading session (0 on weekends or after close)
        double todayFraction = 0.0;
        java.time.DayOfWeek todayDow = today.getDayOfWeek();
        if (todayDow != java.time.DayOfWeek.SATURDAY && todayDow != java.time.DayOfWeek.SUNDAY) {
            if (nowTime.isBefore(marketOpen)) {
                todayFraction = 1.0;
            } else if (nowTime.isBefore(marketClose)) {
                todayFraction = ChronoUnit.MINUTES.between(nowTime, marketClose) / 375.0;
            }
        }

        if (!expiry.isAfter(today)) {
            // Expired or expires today — only today's remaining session counts
            return Math.max(1.0 / (252.0 * 375.0), todayFraction / 252.0);
        }

        // Count whole trading days from tomorrow through expiry (inclusive)
        long tradingDays = 0;
        LocalDate d = today.plusDays(1);
        while (!d.isAfter(expiry)) {
            java.time.DayOfWeek dow = d.getDayOfWeek();
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                tradingDays++;
            }
            d = d.plusDays(1);
        }
        return Math.max(1.0 / (252.0 * 375.0), (tradingDays + todayFraction) / 252.0);
    }

    private double bsPrice(double S, double K, double T, double r, double q, double sigma, boolean isCall) {
        double d1 = d1(S, K, T, r, q, sigma);
        double d2 = d1 - sigma * Math.sqrt(T);
        return isCall
            ? S * Math.exp(-q * T) * N(d1) - K * Math.exp(-r * T) * N(d2)
            : K * Math.exp(-r * T) * N(-d2) - S * Math.exp(-q * T) * N(-d1);
    }

    private double bsVega(double S, double K, double T, double r, double q, double sigma) {
        return S * Math.exp(-q * T) * npdf(d1(S, K, T, r, q, sigma)) * Math.sqrt(T);
    }

    private double d1(double S, double K, double T, double r, double q, double sigma) {
        return (Math.log(S / K) + (r - q + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
    }

    private double N(double x) { return 0.5 * (1 + erf(x / Math.sqrt(2))); }
    private double npdf(double x) { return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI); }
    private double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t)
                + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }
}
