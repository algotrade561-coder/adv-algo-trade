package com.algo.trade.execution.exit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** NSE cash-session window for exit evaluation (IST). */
public final class MarketSessionHelper {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime CLOSE = LocalTime.of(15, 30);

    /** Muhurat trading session (Diwali evening — typically 18:15 to 19:15 IST). */
    private static final LocalTime MUHURAT_OPEN = LocalTime.of(18, 15);
    private static final LocalTime MUHURAT_CLOSE = LocalTime.of(19, 15);

    /** Half-day session close (e.g., pre-holiday sessions). */
    private static final LocalTime HALF_DAY_CLOSE = LocalTime.of(12, 30);

    private MarketSessionHelper() {}

    public static boolean isRegularSessionNow() {
        LocalDate today = LocalDate.now(IST);
        return isRegularSession(today, LocalTime.now(IST));
    }

    public static boolean isRegularSession(LocalDate date, LocalTime time) {
        if (date.getDayOfWeek().getValue() >= 6) {
            return false;
        }
        return time.isAfter(OPEN) && time.isBefore(CLOSE);
    }

    /**
     * Returns true if current time falls within any active trading session
     * (regular, Muhurat, or half-day).
     */
    public static boolean isAnySessionActiveNow() {
        LocalTime now = LocalTime.now(IST);
        LocalDate today = LocalDate.now(IST);
        if (today.getDayOfWeek().getValue() >= 6) {
            // Weekend — only Muhurat possible (Diwali can fall on any day)
            return now.isAfter(MUHURAT_OPEN) && now.isBefore(MUHURAT_CLOSE);
        }
        if (now.isAfter(OPEN) && now.isBefore(CLOSE)) {
            return true; // Regular session
        }
        // Muhurat evening session
        return now.isAfter(MUHURAT_OPEN) && now.isBefore(MUHURAT_CLOSE);
    }

    /**
     * Returns true if currently in a Muhurat trading session.
     */
    public static boolean isMuhuratSessionNow() {
        LocalTime now = LocalTime.now(IST);
        return now.isAfter(MUHURAT_OPEN) && now.isBefore(MUHURAT_CLOSE);
    }

    public static boolean isSameTradingDay(Instant a, Instant b) {
        if (a == null || b == null) {
            return false;
        }
        return LocalDate.ofInstant(a, IST).equals(LocalDate.ofInstant(b, IST));
    }

    public static ZoneId ist() {
        return IST;
    }

    public static LocalTime marketClose() {
        return CLOSE;
    }

    /** Minutes since 09:15 IST on the current trading day (0 before open). */
    public static long minutesSinceSessionOpen() {
        LocalTime now = LocalTime.now(IST);
        if (!now.isAfter(OPEN)) {
            return 0;
        }
        return java.time.Duration.between(OPEN, now).toMinutes();
    }
}
