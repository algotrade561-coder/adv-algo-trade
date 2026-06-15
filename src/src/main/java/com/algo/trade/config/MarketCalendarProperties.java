package com.algo.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Trading calendar configuration (exchange holidays).
 *
 * <p>Important: only include weekday trading holidays here. Holidays that fall on Saturday/Sunday
 * do not change trading availability and should be omitted to keep the list clean.
 */
@Component
@ConfigurationProperties(prefix = "expiry")
public class MarketCalendarProperties {

    /**
     * NSE/BSE trading holidays (weekdays only). Used for expiry preponement and trading-day checks.
     */
    private List<LocalDate> holidays = new ArrayList<>();

    public List<LocalDate> getHolidays() { return holidays; }
    public void setHolidays(List<LocalDate> holidays) { this.holidays = holidays; }
}

