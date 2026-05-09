package com.algo.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for position synchronisation behaviour.
 * Bound to {@code position-sync.*} in application.yml.
 */
@ConfigurationProperties(prefix = "position-sync")
public record PositionSyncProperties(
        /**
         * When true (default), SYNC'd trades are fully managed by exit monitors —
         * intended for crash-recovery where the system re-discovers its own positions.
         *
         * Set to false when you place manual trades alongside the system and don't want
         * the exit monitors (SL/target/maxHold/squareoff) to touch those positions.
         * FailSafeSquareoffDaemon always runs regardless of this flag.
         */
        @DefaultValue("true") boolean manageSyncedTrades
) {}
