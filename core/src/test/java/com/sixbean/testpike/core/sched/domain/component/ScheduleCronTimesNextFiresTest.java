package com.sixbean.testpike.core.sched.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Next-fire preview contract for the wizard's scheduled-firing section (B-8):
 * the preview must come from the same ScheduleCronTimes single source as the
 * firing scanner — what the operator sees is what the scanner will do.
 * Test↔task mapping anchor: segment e 1.
 */
class ScheduleCronTimesNextFiresTest {

    private static final Instant NOW =
            LocalDateTime.of(2026, 10, 25, 10, 0, 0).atZone(ZoneId.systemDefault()).toInstant();

    @Test
    void dailyCronPreviewsConsecutiveDaysInStrictOrder() {
        // "0 30 2 * * *" = every day at 02:30 -> next fires on Oct 26, 27, 28
        List<Instant> fires = ScheduleCronTimes.nextFires("0 30 2 * * *", NOW, 3);
        assertEquals(3, fires.size());
        ZoneId zone = ZoneId.systemDefault();
        assertEquals(LocalDateTime.of(2026, 10, 26, 2, 30).atZone(zone).toInstant(), fires.get(0));
        assertEquals(LocalDateTime.of(2026, 10, 27, 2, 30).atZone(zone).toInstant(), fires.get(1));
        assertEquals(LocalDateTime.of(2026, 10, 28, 2, 30).atZone(zone).toInstant(), fires.get(2));
        assertTrue(Duration.between(fires.get(0), fires.get(1)).toHours() == 24);
    }

    @Test
    void hourlyCronNextFireIsStrictlyAfterNow() {
        List<Instant> fires = ScheduleCronTimes.nextFires("0 0 * * * *", NOW, 2);
        assertEquals(2, fires.size());
        assertTrue(fires.get(0).isAfter(NOW));
        assertTrue(fires.get(1).isAfter(fires.get(0)));
        // now = 10:00:00 exactly, cron fires on the hour: first preview = 11:00 (exclusive of now)
        assertEquals(LocalDateTime.of(2026, 10, 25, 11, 0).atZone(ZoneId.systemDefault()).toInstant(),
                fires.get(0));
    }

    @Test
    void blankCronPreviewsEmptyWithoutThrowing() {
        // A blank schedule is a legal "not scheduled" state — the wizard shows no rows, not an error.
        assertTrue(ScheduleCronTimes.nextFires(null, NOW, 3).isEmpty());
        assertTrue(ScheduleCronTimes.nextFires("  ", NOW, 3).isEmpty());
    }

    @Test
    void invalidCronIsRefusedFailClosedWithHumanMessage() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ScheduleCronTimes.nextFires("not a cron", NOW, 3));
        assertTrue(ex.getMessage().contains("cron"));
    }

    @Test
    void previewCountIsClampedToAtLeastOne() {
        assertEquals(1, ScheduleCronTimes.nextFires("0 0 * * * *", NOW, 0).size());
        assertEquals(1, ScheduleCronTimes.nextFires("0 0 * * * *", NOW, -5).size());
    }
}
