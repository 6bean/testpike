package com.sixbean.testpike.aiservice.aichat.domain.component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.sixbean.testpike.aiservice.aichat.domain.component.GroundingPoolFixtures.grade;
import static com.sixbean.testpike.aiservice.aichat.domain.component.GroundingPoolFixtures.knowledge;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pool data-moment stamp of the grounding kernel (behaviour kernel round-2
 * item 5: conclusions must carry the as-of moment). The timestamp is carried
 * by the retrieval channel that already reads the ledger column — no extra
 * fetch per turn. Three shapes, all honest:
 * <ul>
 *   <li>at least one material has a write time → one pool-level
 *       data-moment line (bracketed marker) naming the most recent moment;</li>
 *   <li>no material has a write time → an explicit "no recorded moment"
 *       line (never an invented timestamp);</li>
 *   <li>zero-hit pool → nothing is added, the untouched honest shape stays
 *       byte-identical.</li>
 * </ul>
 */
class GroundingDataMomentTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 11, 6, 9, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void poolWithWrittenMaterialsStampsTheMostRecentMoment() {
        var outcome = grade("回滚窗口阈值是多少", List.of(
                knowledge(1L, "回滚窗口说明", "字段：阈值=30分钟",
                        OffsetDateTime.of(2026, 10, 20, 8, 15, 0, 0, ZoneOffset.UTC)),
                knowledge(2L, "发布窗口说明", "字段：阈值=15分钟",
                        OffsetDateTime.of(2026, 11, 2, 14, 5, 0, 0, ZoneOffset.UTC))), NOW);
        assertTrue(outcome.contextBlock().contains("【数据时刻】"), outcome.contextBlock());
        assertTrue(outcome.contextBlock().contains("2026-11-02 14:05"),
                "the pool moment is the MOST RECENT write time: " + outcome.contextBlock());
        assertFalse(outcome.contextBlock().contains("未记录"),
                "a stamped pool never claims a missing moment: " + outcome.contextBlock());
    }

    @Test
    void poolWithoutAnyWriteTimeSaysSoInsteadOfInventingOne() {
        var outcome = grade("回滚窗口阈值是多少", List.of(
                knowledge(1L, "回滚窗口说明", "字段：阈值=30分钟", null)), NOW);
        assertTrue(outcome.contextBlock().contains("【数据时刻】"), outcome.contextBlock());
        assertTrue(outcome.contextBlock().contains("未记录"), outcome.contextBlock());
        assertFalse(outcome.contextBlock().matches("(?s).*2026-\\d{2}-\\d{2}.*"),
                "no material time exists, so no date may appear: " + outcome.contextBlock());
    }

    @Test
    void zeroHitPoolStaysTheUntouchedHonestShape() {
        var outcome = grade("回滚窗口阈值是多少", List.of(), NOW);
        assertTrue(outcome.zeroHit());
        assertFalse(outcome.contextBlock().contains("【数据时刻】"));
    }
}
