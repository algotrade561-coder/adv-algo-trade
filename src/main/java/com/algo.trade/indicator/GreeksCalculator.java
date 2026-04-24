package com.algo.trade.indicator;

import com.algo.trade.domain.OptionInstrument;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
    private static final double DIVIDEND_YIELD = 0.0;
    private static final int IV_MAX_ITERATIONS = 100;
    private static final double IV_PRECISION = 0.0001;
    private static final double MIN_IV = 0.01;
    private static final double MAX_IV = 5.0;

    /** Calculate all Greeks and update the OptionInstrument in-place. */
    public void calculateAndUpdate(OptionInstrument option, double underlyingPrice) {
        if (option.getLastPrice() <= 0 || underlyingPrice <= 0) return;
        double T = timeToExpiry(option.getExpiry());
        if (T <= 0) return;

        double S = underlyingPrice;
        double K = option.getStrikePrice();
        boolean isCall = option.isCE();
        double marketPrice = option.getLastPrice();

        double iv = calculateIV(S, K, T, RISK_FREE_RATE, DIVIDEND_YIELD, marketPrice, isCall);
        if (iv <= 0) return;

        option.setImpliedVolatility(iv * 100); // store as percentage

        double[] greeks = calculateGreeks(S, K, T, RISK_FREE_RATE, DIVIDEND_YIELD, iv, isCall);
        option.setDelta(greeks[0]);
        option.setGamma(greeks[1]);
        option.setTheta(greeks[2]);
        option.setVega(greeks[3]);
    }

    public double calculateIV(double S, double K, double T, double r, double q,
                               double marketPrice, boolean isCall) {
        double sigma = Math.sqrt(2 * Math.PI / T) * (marketPrice / S);
        sigma = Math.max(MIN_IV, Math.min(MAX_IV, sigma));
        for (int i = 0; i < IV_MAX_ITERATIONS; i++) {
            double price = bsPrice(S, K, T, r, q, sigma, isCall);
            double vega  = bsVega(S, K, T, r, q, sigma);
            if (vega < 1e-10) break;
            double diff = price - marketPrice;
            if (Math.abs(diff) < IV_PRECISION) break;
            sigma -= diff / vega;
            sigma = Math.max(MIN_IV, Math.min(MAX_IV, sigma));
        }
        return sigma;
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
        long days = ChronoUnit.DAYS.between(LocalDate.now(), expiry);
        return Math.max(0, days / 365.0);
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
