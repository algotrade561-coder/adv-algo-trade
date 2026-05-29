package com.algo.trade.strategy.oimomentum.v3;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class TimeOfDayModeTest {

    @Test
    void preMarket_isDeadClose() {
        assertEquals(TimeOfDayMode.DEAD_CLOSE, TimeOfDayMode.classify(LocalTime.of(9, 0)));
    }

    @Test
    void firstFiveMinutes_isDeadOpen() {
        assertEquals(TimeOfDayMode.DEAD_OPEN, TimeOfDayMode.classify(LocalTime.of(9, 17)));
    }

    @Test
    void openingDrive() {
        assertEquals(TimeOfDayMode.OPENING_DRIVE, TimeOfDayMode.classify(LocalTime.of(9, 30)));
        assertEquals(TimeOfDayMode.OPENING_DRIVE, TimeOfDayMode.classify(LocalTime.of(10, 29)));
    }

    @Test
    void middayDiscipline_requires4Of4() {
        TimeOfDayMode m = TimeOfDayMode.classify(LocalTime.of(12, 0));
        assertEquals(TimeOfDayMode.MIDDAY_DISCIPLINE, m);
        assertEquals(4, m.requiredGates());
    }

    @Test
    void afternoonPosition() {
        assertEquals(TimeOfDayMode.AFTERNOON_POSITION, TimeOfDayMode.classify(LocalTime.of(14, 0)));
    }

    @Test
    void lastHour() {
        assertEquals(TimeOfDayMode.LAST_HOUR, TimeOfDayMode.classify(LocalTime.of(14, 50)));
    }

    @Test
    void eodSqueezeOnly() {
        assertEquals(TimeOfDayMode.EOD_SQUEEZE_ONLY, TimeOfDayMode.classify(LocalTime.of(15, 15)));
    }

    @Test
    void deadClose_after15_20() {
        assertEquals(TimeOfDayMode.DEAD_CLOSE, TimeOfDayMode.classify(LocalTime.of(15, 25)));
    }

    @Test
    void modes_allowingEntry() {
        assertFalse(TimeOfDayMode.DEAD_OPEN.allowsEntry());
        assertFalse(TimeOfDayMode.DEAD_CLOSE.allowsEntry());
        assertTrue(TimeOfDayMode.OPENING_DRIVE.allowsEntry());
        assertTrue(TimeOfDayMode.MIDDAY_DISCIPLINE.allowsEntry());
    }
}
