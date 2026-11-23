package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.document.api.dto.ExtractionEventRow;
import com.sixbean.testpike.core.events.api.dto.TaskEventRunRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revisit door contract of the channel-B spec extraction process ledger
 * (tp_ai_task_event kind "spec-extract", job ref = document id). The run
 * token lives only inside the ledger, so the standing door resolves the
 * newest run from the ledger itself; the R67 door adds per-run history rows
 * and an explicit-token replay without touching that default posture.
 * Pinned behaviours: the implicit window stays byte-identical to the newest
 * run, an explicit historical token replays that run with no skipped or
 * duplicated frames, the run-history door aggregates newest run first with
 * frame totals and error flags, and an unknown document honestly answers
 * empty lists. Real slot database; fixture rows are self-created and
 * cleaned by job ref (no TRUNCATE of shipped tables).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentSpecExtractionTailTest {

    private static final String KIND = "spec-extract";

    @Autowired
    private DocumentExtractionTestRig rig;

    private Long docId;

    @AfterEach
    void cleanUpFixtures() {
        if (docId != null) {
            rig.jdbcTemplate.update(
                    "DELETE FROM tp_ai_task_event WHERE job_kind=? AND job_ref=?",
                    KIND, String.valueOf(docId));
            docId = null;
        }
    }

    /** Fresh synthetic channel-B anchor: the ledger face keys on the
     *  document id string only, no task-state row exists for this kind. */
    private long freshAnchor() {
        docId = System.currentTimeMillis() * 31 + Math.floorMod(docId == null ? 0 : docId.hashCode(), 7);
        return docId;
    }

    private void landEvents(long documentId, String token, int rows) {
        for (int i = 1; i <= rows; i++) {
            rig.jdbcTemplate.update("INSERT INTO tp_ai_task_event"
                            + " (job_kind, job_ref, run_token, level, summary, detail)"
                            + " VALUES (?,?,?,?,?,?)",
                    KIND, String.valueOf(documentId), token,
                    i % 3 == 0 ? "error" : i % 2 == 0 ? "step" : "thinking",
                    "契约提取事件 " + i, "{\"seq\":" + i + "}");
        }
    }

    @Test
    void implicitWindowStaysNewestRunWhenHistoryDoorIsWired() {
        long id = freshAnchor();
        String oldToken = UUID.randomUUID().toString();
        String liveToken = UUID.randomUUID().toString();
        landEvents(id, oldToken, 5);
        landEvents(id, liveToken, 2);

        List<ExtractionEventRow> implicitWindow =
                rig.extractionService.specExtractionEvents(id, 0L, 10);
        List<ExtractionEventRow> explicitWindow =
                rig.extractionService.specExtractionEvents(id, 0L, 10, liveToken);
        assertEquals(2, implicitWindow.size(), "隐式窗口=最新轮 2 条，旧轮不得混入");
        assertEquals(implicitWindow.stream().map(ExtractionEventRow::cursor).toList(),
                explicitWindow.stream().map(ExtractionEventRow::cursor).toList(),
                "缺省态与显式最新轮字节一致：回放门零漂移");
    }

    @Test
    void explicitRunTokenReplaysHistoricalRunSameCursorNoSkipNoDup() {
        long id = freshAnchor();
        String oldToken = UUID.randomUUID().toString();
        landEvents(id, oldToken, 12);
        landEvents(id, UUID.randomUUID().toString(), 2);

        List<ExtractionEventRow> first =
                rig.extractionService.specExtractionEvents(id, 0L, 10, oldToken);
        assertEquals(10, first.size(), "显式 runToken 回放历史轮：12 条截取窗口 10");
        assertEquals("契约提取事件 1", first.get(0).summary());
        List<ExtractionEventRow> again =
                rig.extractionService.specExtractionEvents(id, 0L, 10, oldToken);
        assertEquals(first.stream().map(ExtractionEventRow::cursor).toList(),
                again.stream().map(ExtractionEventRow::cursor).toList(),
                "同轮次同游标重放=同一窗口，不跳不重");
        List<ExtractionEventRow> tail =
                rig.extractionService.specExtractionEvents(id, first.get(9).cursor(), 10, oldToken);
        assertEquals(2, tail.size(), "跟随窗口从游标之后继续，只剩 2 条");
    }

    @Test
    void runHistoryDoorAggregatesNewestFirstWithCountsAndErrorFlags() {
        long id = freshAnchor();
        String oldToken = UUID.randomUUID().toString();
        String liveToken = UUID.randomUUID().toString();
        landEvents(id, oldToken, 5);
        landEvents(id, liveToken, 2);

        List<TaskEventRunRow> runs = rig.extractionService.specExtractionRuns(id, 10);
        assertEquals(2, runs.size(), "两轮次折叠为两条历史行");
        TaskEventRunRow newest = runs.get(0);
        TaskEventRunRow older = runs.get(1);
        assertEquals(liveToken, newest.runToken(), "最新轮次排前");
        assertEquals(2, newest.eventCount());
        assertFalse(newest.hasError(), "最新轮 2 条无 error 级帧");
        assertEquals(oldToken, older.runToken());
        assertEquals(5, older.eventCount());
        assertTrue(older.hasError(), "旧轮第 3 条为 error 级帧");
        assertTrue(newest.firstAt() != null && newest.lastAt() != null
                        && newest.firstAt().length() >= 19,
                "轮次起止时刻 ISO 串可达");
    }

    @Test
    void unknownDocumentAnswersEmptyListsHonestly() {
        long id = freshAnchor();
        assertTrue(rig.extractionService.specExtractionRuns(id, 10).isEmpty(),
                "未发起过的文档=空历史，不报错");
        assertTrue(rig.extractionService.specExtractionEvents(id, 0L, 10).isEmpty(),
                "未发起过的文档=空窗口，不报错");
        assertTrue(rig.extractionService
                        .specExtractionEvents(id, 0L, 10, UUID.randomUUID().toString())
                        .isEmpty(),
                "陌生 runToken 回放=空窗口，不报错");
    }
}
