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
         * Default false: manual SYNC'd trades are tracked but NOT auto-managed by exit monitors.
         * Set true if you want exit monitors (SL/target/maxHold/squareoff) to manage SYNC'd
         * positions — primarily intended for crash-recovery of algo-placed positions.
         * FailSafeSquareoffDaemon always runs at EOD regardless.
         *
         * This value only seeds the GlobalConfig row on FIRST boot (empty DB). After that,
         * the runtime value comes from the GlobalConfig DB row and is editable from the
         * Settings UI (manage-synced-trades toggle).
         */
        @DefaultValue("false") boolean manageSyncedTrades
) {}
