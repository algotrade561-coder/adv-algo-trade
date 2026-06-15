package com.algo.trade.backtest.v2;

import com.algo.trade.data.ChainSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates ChainSnapshot data quality before backtesting.
 * Catches common data issues: missing strikes, zero prices, stale data.
 */
@Component
public class SnapshotValidator {

    private static final Logger log = LoggerFactory.getLogger(SnapshotValidator.class);

    /**
     * Validate a single snapshot. Returns list of issues (empty = valid).
     */
    public List<String> validate(ChainSnapshot snapshot) {
        List<String> issues = new ArrayList<>();

        if (snapshot == null) {
            issues.add("Snapshot is null");
            return issues;
        }

        if (snapshot.timestamp() == null) {
            issues.add("Missing timestamp");
        }
        if (snapshot.underlying() == null || snapshot.underlying().isBlank()) {
            issues.add("Missing underlying");
        }
        if (snapshot.spot() <= 0) {
            issues.add("Invalid spot price: " + snapshot.spot());
        }
        if (snapshot.vix() <= 0 || snapshot.vix() > 100) {
            issues.add("Suspicious VIX: " + snapshot.vix());
        }
        if (snapshot.strikes() == null || snapshot.strikes().isEmpty()) {
            issues.add("No strike data");
            return issues;
        }
        if (snapshot.strikes().size() < 5) {
            issues.add("Too few strikes: " + snapshot.strikes().size() + " (expected >= 5)");
        }
        if (snapshot.atmStrike() <= 0) {
            issues.add("Invalid ATM strike: " + snapshot.atmStrike());
        }

        // Validate ATM strike data
        boolean hasAtm = snapshot.strikes().stream()
                .anyMatch(s -> s.strike() == snapshot.atmStrike());
        if (!hasAtm) {
            issues.add("ATM strike " + snapshot.atmStrike() + " not found in strike data");
        }

        // Validate individual strikes
        int zeroLtpCount = 0;
        int zeroOiCount = 0;
        for (ChainSnapshot.StrikeData strike : snapshot.strikes()) {
            if (strike.ceLTP() <= 0 && strike.peLTP() <= 0) {
                zeroLtpCount++;
            }
            if (strike.ceOI() <= 0 && strike.peOI() <= 0) {
                zeroOiCount++;
            }
        }

        if (zeroLtpCount > snapshot.strikes().size() / 2) {
            issues.add("Too many zero-LTP strikes: " + zeroLtpCount + "/" + snapshot.strikes().size());
        }
        if (zeroOiCount > snapshot.strikes().size() / 2) {
            issues.add("Too many zero-OI strikes: " + zeroOiCount + "/" + snapshot.strikes().size());
        }

        return issues;
    }

    /**
     * Validate a list of snapshots for a day. Returns summary.
     */
    public ValidationResult validateDay(List<ChainSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return new ValidationResult(false, 0, 0, List.of("No snapshots to validate"));
        }

        int valid = 0;
        int invalid = 0;
        List<String> allIssues = new ArrayList<>();

        for (ChainSnapshot snapshot : snapshots) {
            List<String> issues = validate(snapshot);
            if (issues.isEmpty()) {
                valid++;
            } else {
                invalid++;
                allIssues.addAll(issues);
            }
        }

        boolean usable = valid >= snapshots.size() * 0.7; // At least 70% valid
        if (!usable) {
            log.warn("[SnapshotValidator] Day data quality too low: {}/{} valid", valid, snapshots.size());
        }

        return new ValidationResult(usable, valid, invalid, allIssues);
    }

    /**
     * Quick check if a snapshot is usable for trading decisions.
     */
    public boolean isUsable(ChainSnapshot snapshot) {
        return validate(snapshot).isEmpty();
    }

    public record ValidationResult(
            boolean usable,
            int validCount,
            int invalidCount,
            List<String> issues
    ) {}
}
