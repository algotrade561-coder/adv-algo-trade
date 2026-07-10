package com.algo.trade.strategy.filter;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Resolves the effective per-underlying <b>max entry premium</b> dynamically, anchored to the
 * <i>live ATM straddle</i> instead of a fixed rupee number.
 *
 * <h3>Why</h3>
 * A fixed cap (e.g. NIFTY {@code maxEntryPremium=300}) can't track the contract lifecycle: early in
 * the weekly the ATM itself costs ~₹250–320 so the fixed cap blocks legitimate ATM/ITM entries. The
 * live ATM premium already encodes DTE + IV + spot, so anchoring the cap to it ({@code cap = k × ATM})
 * scales with the week automatically.
 *
 * <h3>Calibration (2026-07-09 tape — read before enabling)</h3>
 * The original k=1.4 assumed the deep-ITM ₹400–900 class was junk to exclude; the 07-09 broker book
 * showed the opposite — that class held the day's biggest winners (+2,851 / +1,710 / +1,522) at
 * 2.8–3.3× ATM, and k=1.4 would have blocked ~₹12k of them. k=3.5 admits it with headroom. The
 * expiry-no-tighten guard exists because the current-expiry ATM theta-decays into the close, which
 * would strangle the 15:00 expiry collapse harvest. Validate k on multi-day tape before enabling.
 *
 * <h3>Safety / non-disturbance contract</h3>
 * <ul>
 *   <li>DEFAULT OFF ({@code dynamic-entry-premium.enabled=false}) → returns the configured cap
 *       byte-for-byte. Existing behaviour is unchanged until explicitly enabled.</li>
 *   <li>Only <b>replaces an existing</b> fixed cap ({@code configured > 0}). It never invents a cap
 *       where the underlying had none (configured {@code 0} = "no cap" stays "no cap").</li>
 *   <li>Falls back to the configured cap whenever live ATM data is missing (pre-open, illiquid,
 *       cache not warm) — so a data gap can never make the gate more permissive than today.</li>
 *   <li>Floors the dynamic cap at the configured {@code minEntryPremium} so the tradable band
 *       {@code [min, cap]} can never invert.</li>
 * </ul>
 *
 * This is the single source of truth used by both the scan pre-filter (ScanContextBuilder) and the
 * execution gate (ExecutionEngine#premiumRangeGuard).
 */
@Component
public class DynamicEntryPremiumService {

    private static final Logger log = LoggerFactory.getLogger(DynamicEntryPremiumService.class);

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService;

    /** Master switch. OFF → configured fixed cap is returned unchanged (current behaviour). */
    @Value("${dynamic-entry-premium.enabled:false}")
    private boolean enabled;

    /** Cap = this multiple of the live ATM straddle-average premium. RECALIBRATED 2026-07-09 from the
     *  original 1.4 ("within ~1 strike of ATM") against that day's broker book: 1.4 would have blocked
     *  ~₹12k of the day's winners — 23900CE @217 (+1,710) missed by ₹4 at cap 213, 76900CE @484
     *  (+2,851) at cap 224 — the ₹400–900 deep-ITM winner class sits at 2.8–3.3× ATM, so "deep-ITM is
     *  junk" was empirically backwards on our tape. 3.5 admits that class with headroom. */
    @Value("${dynamic-entry-premium.atm-multiplier:3.5}")
    private double atmMultiplier;

    /** Hard floor for the dynamic cap in ₹ (0 = disabled) — a decayed/bad ATM read can never strangle the band. */
    @Value("${dynamic-entry-premium.min-cap:250}")
    private double minCapFloor;

    /** Hard ceiling for the dynamic cap in ₹ (0 = disabled). Guards against a bad ATM read. */
    @Value("${dynamic-entry-premium.max-cap:1200}")
    private double maxCapCeiling;

    /** On the EXPIRING index's expiry day the dynamic cap may only LOOSEN vs the configured cap, never
     *  tighten: the current-expiry ATM theta-decays toward 0 into the close, which would strangle
     *  exactly the 15:00 collapse harvest (07-09 replay: +₹5,750 on nine 15:00-hour entries at
     *  ₹200–400 premiums while the ATM straddle average had decayed to ~₹100). */
    @Value("${dynamic-entry-premium.expiry-no-tighten:true}")
    private boolean expiryNoTighten;

    public DynamicEntryPremiumService(LiveInstrumentCache liveInstrumentCache,
                                      ExpiryCalendar expiryCalendar,
                                      com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.underlyingConfigService = underlyingConfigService;
    }

    /**
     * Effective max entry premium for an underlying. Drop-in replacement for
     * {@code underlyingConfigService.getMaxEntryPremium(underlying)} — identical result when disabled.
     *
     * @return the dynamic cap when enabled and live ATM data is available; otherwise the configured
     *         fixed cap ({@link BigDecimal#ZERO} = no cap, preserved).
     */
    public BigDecimal effectiveMaxEntryPremium(UnderlyingSymbol underlying) {
        BigDecimal configured = underlyingConfigService.getMaxEntryPremium(underlying); // 0 = no cap

        // Disabled, or no fixed cap to replace → behave exactly as today.
        if (!enabled || configured == null || configured.signum() <= 0) {
            return configured;
        }

        IndexType idx = IndexType.from(underlying);
        double atmRef = atmReferencePremium(idx);
        if (atmRef <= 0) {
            // No live ATM read (pre-open / illiquid / cache cold) → keep the configured cap.
            return configured;
        }

        double dyn = atmRef * atmMultiplier;

        // Never let the cap fall below the configured min-entry floor (would invert the [min,cap] band).
        BigDecimal minEntry = underlyingConfigService.getMinEntryPremium(underlying);
        if (minEntry != null && minEntry.signum() > 0) {
            dyn = Math.max(dyn, minEntry.doubleValue());
        }
        // Optional sanity bounds.
        if (minCapFloor > 0) dyn = Math.max(dyn, minCapFloor);
        if (maxCapCeiling > 0) dyn = Math.min(dyn, maxCapCeiling);

        // Expiry-day guard (2026-07-09 tape): the current-expiry ATM decays toward 0 into the close, so
        // an ATM-anchored cap tightens hardest exactly when the expiry collapse harvest needs room.
        // On the expiring index's expiry day the dynamic cap may only loosen vs the configured cap.
        if (expiryNoTighten && expiryCalendar.isExpiryDay(idx)) {
            dyn = Math.max(dyn, configured.doubleValue());
        }

        BigDecimal dynamicCap = BigDecimal.valueOf(dyn).setScale(0, RoundingMode.HALF_UP);
        if (log.isDebugEnabled()) {
            log.debug("[DynPremium] {} atmRef=₹{} x{} → cap=₹{} (configured=₹{})",
                    underlying, String.format("%.1f", atmRef), atmMultiplier,
                    dynamicCap, configured.setScale(0, RoundingMode.HALF_UP));
        }
        return dynamicCap;
    }

    /** True when the dynamic override is switched on (for logging / diagnostics). */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Live ATM reference premium = average of the ATM CE and PE LTP (straddle / 2) on the current
     * (nearest) expiry. Symmetric and side-agnostic; degrades to whichever single leg is available.
     * Returns 0 when no usable live quote exists so the caller falls back to the configured cap.
     */
    private double atmReferencePremium(IndexType idx) {
        try {
            double spot = liveInstrumentCache.getFuturesPrice(idx);
            if (spot <= 0) return 0;
            LocalDate expiry = expiryCalendar.getCurrentExpiry(idx);
            if (expiry == null) return 0;
            int atm = idx.roundToATM(spot);
            double ce = liveInstrumentCache.getOption(idx, atm, "CE", expiry)
                    .map(OptionInstrument::getLastPrice).orElse(0.0);
            double pe = liveInstrumentCache.getOption(idx, atm, "PE", expiry)
                    .map(OptionInstrument::getLastPrice).orElse(0.0);
            if (ce > 0 && pe > 0) return (ce + pe) / 2.0;
            if (ce > 0) return ce;
            if (pe > 0) return pe;
            return 0;
        } catch (Exception e) {
            log.debug("[DynPremium] ATM reference lookup failed for {}: {}", idx, e.getMessage());
            return 0;
        }
    }
}
