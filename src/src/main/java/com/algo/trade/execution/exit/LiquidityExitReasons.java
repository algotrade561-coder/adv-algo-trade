package com.algo.trade.execution.exit;

/** Standardized Tier-1 liquidity exit reason codes (ML / analytics). */
public final class LiquidityExitReasons {

    public static final String NO_QUOTE = "LIQUIDITY_NO_QUOTE";
    public static final String NO_BID_ASK = "LIQUIDITY_NO_BID_ASK";
    public static final String STALE_NO_TIMESTAMP = "LIQUIDITY_STALE_NO_TIMESTAMP";
    public static final String STALE_FORCE = "LIQUIDITY_STALE_FORCE_EXIT";
    public static final String SPREAD_WIDENING = "LIQUIDITY_SPREAD_WIDENING";
    public static final String SPREAD_VS_ENTRY = "LIQUIDITY_SPREAD_VS_ENTRY";
    public static final String VOLUME_FLOOR = "LIQUIDITY_VOLUME_FLOOR";
    public static final String VOLUME_COLLAPSE = "LIQUIDITY_VOLUME_COLLAPSE";
    public static final String OI_COLLAPSE = "LIQUIDITY_OI_COLLAPSE";

    private LiquidityExitReasons() {}
}
