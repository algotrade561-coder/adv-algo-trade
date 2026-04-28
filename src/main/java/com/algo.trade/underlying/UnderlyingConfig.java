package com.algo.trade.underlying;

import com.algo.trade.domain.UnderlyingSymbol;
import jakarta.persistence.*;

/**
 * Per-underlying index configuration stored in DB.
 * One row per tradeable index (NIFTY, BANKNIFTY, SENSEX, FINNIFTY, MIDCPNIFTY).
 * Metadata that differs per underlying lives here; strategy params live in StrategyConfig.
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

    protected UnderlyingConfig() {}

    public UnderlyingConfig(UnderlyingSymbol underlying, boolean enabled, String displayName) {
        this.underlying = underlying;
        this.enabled = enabled;
        this.displayName = displayName;
    }

    public UnderlyingSymbol getUnderlying() { return underlying; }
    public boolean isEnabled() { return enabled; }
    public String getDisplayName() { return displayName; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
