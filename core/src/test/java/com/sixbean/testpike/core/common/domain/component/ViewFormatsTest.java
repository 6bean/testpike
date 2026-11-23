package com.sixbean.testpike.core.common.domain.component;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Fixed-width contract for the shared second-precision timestamp formatter.
 * List views render timestamps as 'yyyy-MM-dd HH:mm:ss'; LocalDateTime.toString()
 * silently omits zero-valued fields, so a timestamp landing on a whole minute
 * printed without the seconds segment (intermittent 1/60 failures across the
 * 48 formatTs consumers). This contract pins the padded formatter.
 */
class ViewFormatsTest {

    private static Timestamp ts(int hour, int minute, int second, int nano) {
        return Timestamp.valueOf(LocalDateTime.of(2026, 11, 7, hour, minute, second, nano));
    }

    @Test
    void zeroSecondsStillRenderFullWidth() {
        assertEquals("2026-11-07 14:29:00", ViewFormats.formatTs(ts(14, 29, 0, 0)));
    }

    @Test
    void zeroMinutesAndSecondsStillRenderFullWidth() {
        assertEquals("2026-11-07 08:00:00", ViewFormats.formatTs(ts(8, 0, 0, 123456789)));
    }

    @Test
    void nanosAreTruncatedToSecondPrecision() {
        assertEquals("2026-11-07 23:59:58", ViewFormats.formatTs(ts(23, 59, 58, 999_000_000)));
    }

    @Test
    void nullTimestampStaysNull() {
        assertNull(ViewFormats.formatTs(null));
    }
}
