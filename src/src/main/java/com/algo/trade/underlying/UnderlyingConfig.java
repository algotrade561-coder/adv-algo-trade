package com.algo.trade.underlying;

import com.algo.trade.domain.UnderlyingSymbol;
import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * Per-underlying index configuration stored in DB.
 * One row per tradeable index (NIFTY, BANKNIFTY, SENSEX, FINNIFTY, MIDCPNIFTY).
 *
 * Contains index-level behavioral settings that apply across ALL strategies for that underlying.
 * Strategy-specific tuning (SL%, target%, hold time) stays in StrategyConfig.
 *
 * Resolution order: StrategyConfig > UnderlyingConfig > GlobalConfig
 */
@Entity
@Table(name = "underlying_configs")
public class UnderlyingConfig {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", nullable = false)
    private UnderlyingSymbol underlying;

    private boolean enabled = false;

    @Column(columnDefinition = "VARCHAR(50)")
    private String displayName;

    // ── Expiry & DTE ──────────────────────────────────────────

    /** Whether this index has weekly expiry contracts (NIFTY/SENSEX=true, BANKNIFTY/FINNIFTY=false). */
    private boolean hasWeeklyExpiry = true;

    /** Expiry preference for instrument selection: NEAREST, NEAREST_WEEKLY, NEAREST_MONTHLY. */
    @Column(columnDefinition = "VARCHAR(20)")
    private String expiryPreference = "NEAREST";

    /** Max days-to-expiry for directional buying strategies. Beyond this, entry is blocked. */
    private int maxDteForBuying = 7;

    // ── Breakout & Entry Filters ──────────────────────────────

    /** Minimum breakout buffer % for this underlying (overrides global if > 0). */
    @Column(precision = 19, scale = 4)
    private BigDecimal breakoutBufferPercent = BigDecimal.ZERO;

    /** Minimum breakout in absolute points (0 = disabled, use % only). */
    @Column(precision = 19, scale = 2)
    private BigDecimal minBreakoutPoints = BigDecimal.ZERO;

    // ── Volume & OI ───────────────────────────────────────────

    /**
     * How to handle volume spike detection for this underlying.
     * NORMAL = standard volume spike check on option candles.
     * OI_PROXY = use OI change as proxy when underlying spot has no volume (e.g., BANKNIFTY index).
     * DISABLED = skip volume spike check entirely.
     */
    @Column(columnDefinition = "VARCHAR(20)")
    private String volumeSpikeMode = "NORMAL";

    // ── Session Overrides ─────────────────────────────────────

    /** Index-specific entry cutoff time (HH:mm). Null = use global. */
    @Column(columnDefinition = "VARCHAR(5)")
    private String entryCutoffTime;

    /** Index-specific midday chop start (HH:mm). Null = use global session classification. */
    @Column(columnDefinition = "VARCHAR(5)")
    private String middayChopStart;

    /** Index-specific midday chop end (HH:mm). Null = use global session classification. */
    @Column(columnDefinition = "VARCHAR(5)")
    private String middayChopEnd;

    // ── Confidence Score ───────────────────────────────────────

    /** Whether to normalize confidence score when underlying has no spot volume (index-based). */
    private boolean normalizeScoreForNoVolume = false;

    // ── Premium Cap ───────────────────────────────────────────

    /** Maximum option premium (₹) for directional buying strategies. 0 = no cap. */
    @Column(precision = 19, scale = 2)
    private BigDecimal maxEntryPremium = BigDecimal.ZERO;

    // ── Constructors ──────────────────────────────────────────

    protected UnderlyingConfig() {}

    public UnderlyingConfig(UnderlyingSymbol underlying, boolean enabled, String displayName) {
        this.underlying = underlying;
        this.enabled = enabled;
        this.displayName = displayName;
    }

    // ── Getters ───────────────────────────────────────────────

    public UnderlyingSymbol getUnderlying() { return underlying; }
    public boolean isEnabled() { return enabled; }
    public String getDisplayName() { return displayName; }
    public boolean isHasWeeklyExpiry() { return hasWeeklyExpiry; }
    public String getExpiryPreference() { return expiryPreference; }
    public int getMaxDteForBuying() { return maxDteForBuying; }
    public BigDecimal getBreakoutBufferPercent() { return breakoutBufferPercent; }
    public BigDecimal getMinBreakoutPoints() { return minBreakoutPoints; }
    public String getVolumeSpikeMode() { return volumeSpikeMode; }
    public String getEntryCutoffTime() { return entryCutoffTime; }
    public String getMiddayChopStart() { return middayChopStart; }
    public String getMiddayChopEnd() { return middayChopEnd; }
    public boolean isNormalizeScoreForNoVolume() { return normalizeScoreForNoVolume; }
    public BigDecimal getMaxEntryPremium() { return maxEntryPremium; }

    // ── Setters ───────────────────────────────────────────────

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setHasWeeklyExpiry(boolean hasWeeklyExpiry) { this.hasWeeklyExpiry = hasWeeklyExpiry; }
    public void setExpiryPreference(String expiryPreference) { this.expiryPreference = expiryPreference; }
    public void setMaxDteForBuying(int maxDteForBuying) { this.maxDteForBuying = maxDteForBuying; }
    public void setBreakoutBufferPercent(BigDecimal breakoutBufferPercent) { this.breakoutBufferPercent = breakoutBufferPercent; }
    public void setMinBreakoutPoints(BigDecimal minBreakoutPoints) { this.minBreakoutPoints = minBreakoutPoints; }
    public void setVolumeSpikeMode(String volumeSpikeMode) { this.volumeSpikeMode = volumeSpikeMode; }
    public void setEntryCutoffTime(String entryCutoffTime) { this.entryCutoffTime = entryCutoffTime; }
    public void setMiddayChopStart(String middayChopStart) { this.middayChopStart = middayChopStart; }
    public void setMiddayChopEnd(String middayChopEnd) { this.middayChopEnd = middayChopEnd; }
    public void setNormalizeScoreForNoVolume(boolean normalizeScoreForNoVolume) { this.normalizeScoreForNoVolume = normalizeScoreForNoVolume; }
    public void setMaxEntryPremium(BigDecimal maxEntryPremium) { this.maxEntryPremium = maxEntryPremium; }

    // ── Convenience ───────────────────────────────────────────

    /** Returns effective breakout buffer: this underlying's override if > 0, otherwise null (use global). */
    public BigDecimal getEffectiveBreakoutBuffer() {
        return breakoutBufferPercent != null && breakoutBufferPercent.signum() > 0
                ? breakoutBufferPercent : null;
    }

    /** Parse entryCutoffTime as LocalTime, or null if not set. */
    public java.time.LocalTime getEntryCutoffTimeAsLocalTime() {
        if (entryCutoffTime == null || entryCutoffTime.isBlank()) return null;
        return java.time.LocalTime.parse(entryCutoffTime);
    }

    /** Parse middayChopStart as LocalTime, or null if not set. */
    public java.time.LocalTime getMiddayChopStartAsLocalTime() {
        if (middayChopStart == null || middayChopStart.isBlank()) return null;
        return java.time.LocalTime.parse(middayChopStart);
    }

    /** Parse middayChopEnd as LocalTime, or null if not set. */
    public java.time.LocalTime getMiddayChopEndAsLocalTime() {
        if (middayChopEnd == null || middayChopEnd.isBlank()) return null;
        return java.time.LocalTime.parse(middayChopEnd);
    }
}
