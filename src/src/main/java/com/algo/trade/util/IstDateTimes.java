package com.algo.trade.util;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class IstDateTimes {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter IST_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(IST);
    private static final DateTimeFormatter IST_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private IstDateTimes() {
    }

    public static String formatInstant(Instant instant) {
        if (instant == null) {
            return "";
        }
        return IST_TIMESTAMP.format(instant);
    }

    public static String formatLocalTime(LocalTime time) {
        if (time == null) {
            return "";
        }
        return IST_TIME.format(time);
    }

    public static LocalTime istTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalTime.ofInstant(instant, IST);
    }
}
