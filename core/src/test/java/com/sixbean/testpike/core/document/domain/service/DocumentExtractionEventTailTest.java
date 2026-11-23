package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.document.api.dto.ExtractionEventRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cursor contract of the process-event tail read (core echo of
 * tp_ai_task_event written by the ai-service door). Pinned behaviours:
 * strictly ascending window after the cursor, replaying the same cursor
 * returns exactly the same window (nothing skipped, nothing duplicated),
 * the requested limit is clamped into 10~1000 (hot key default when unset),
 * and a rotated run token never replays the previous round's steps.
 * Real slot database, real service door — fixture rows are self-created and
 * cleaned by id (no TRUNCATE of shipped tables).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentExtractionEventTailTest {

    private static final String KIND = "doc-extract";

    @Autowired
    private DocumentExtractionTestRig rig;

    private Long projectId;
    private Long docId;

    @AfterEach
    void cleanUpFixtures() {
        if (docId != null) {
            rig.jdbcTemplate.update(
                    "DELETE FROM tp_ai_task_event WHERE job_kind=? AND job_ref=?",
                    KIND, String.valueOf(docId));
        }
        ExtractionPreviewFixtures.cleanDocsFixtures(rig.jdbcTemplate, projectId, docId);
        docId = null;
        projectId = null;
    }

    /** Upload a parseable MD doc, arm the generation, land N process events. */
    private long armedDocWithEvents(String token, int rows) {
        ExtractionPreviewFixtures.Docs docs = ExtractionPreviewFixtures.preparingDoc(
                "过程尾项目", rig.projectService, rig.documentService, rig.jdbcTemplate);
        projectId = docs.projectId();
        docId = docs.docId();
        ExtractionPreviewFixtures.armExtracting(rig.jdbcTemplate, docId, token);
        for (int i = 1; i <= rows; i++) {
            rig.jdbcTemplate.update("INSERT INTO tp_ai_task_event"
                            + " (job_kind, job_ref, run_token, level, summary, detail)"
                            + " VALUES (?,?,?,?,?,?)",
                    KIND, String.valueOf(docId), token,
                    i % 3 == 0 ? "error" : i % 2 == 0 ? "step" : "thinking",
                    "过程事件 " + i, "{\"seq\":" + i + "}");
        }
        return docId;
    }

    @Test
    void 游标窗口严格升序_重放同游标返回同一窗口() {
        String token = UUID.randomUUID().toString();
        long id = armedDocWithEvents(token, 12);

        List<ExtractionEventRow> first =
                rig.extractionService.extractionEvents(id, 0L, 10);
        assertEquals(10, first.size(), "窗口=请求量 10（可用 12 条中截取）");
        for (int i = 1; i < first.size(); i++) {
            assertTrue(first.get(i).cursor() > first.get(i - 1).cursor(),
                    "行 id 游标必须严格升序");
        }
        List<ExtractionEventRow> replay =
                rig.extractionService.extractionEvents(id, 0L, 10);
        assertEquals(first.stream().map(ExtractionEventRow::cursor).toList(),
                replay.stream().map(ExtractionEventRow::cursor).toList(),
                "重放同游标=同一窗口，不跳不重");
        List<ExtractionEventRow> next = rig.extractionService.extractionEvents(id,
                first.get(first.size() - 1).cursor(), 10);
        assertEquals(2, next.size(), "跟随窗口从游标之后继续，只剩 2 条");
        assertEquals("过程事件 1", first.get(0).summary());
        assertEquals("{\"seq\":1}", first.get(0).detail());
        assertTrue(first.get(0).landedAt() != null && first.get(0).landedAt().length() >= 19,
                "落账时刻 ISO 串可达");
    }

    @Test
    void 请求量钳制在十到一千_缺省走热更键() {
        String token = UUID.randomUUID().toString();
        long id = armedDocWithEvents(token, 3);

        assertEquals(3, rig.extractionService.extractionEvents(id, 0L, 1).size(),
                "limit<10 钳到 10：3 条全部可见（窗口下界不截内容）");
        assertEquals(3, rig.extractionService.extractionEvents(id, 0L, 999_999).size(),
                "limit>1000 钳到 1000：窗口有界");
        assertEquals(3, rig.extractionService.extractionEvents(id, 0L, null).size(),
                "缺省窗口走 ai.stream.chunk-size（出厂 200 ≥ 3）");
    }

    /** Explicit run token replays that historical run; the absent token keeps
     *  the latest-run semantics byte-identical (revisit door, zero drift). */
    @Test
    void 显式runToken可回放历史轮次_同游标重放不跳不重() {
        String oldToken = UUID.randomUUID().toString();
        long id = armedDocWithEvents(oldToken, 12);
        // Rotate the live generation: the standing (implicit) door must now
        // honestly show an empty window for the fresh run.
        ExtractionPreviewFixtures.armExtracting(rig.jdbcTemplate, id, UUID.randomUUID().toString());

        List<ExtractionEventRow> replay =
                rig.extractionService.extractionEvents(id, 0L, 10, oldToken);
        assertEquals(10, replay.size(), "显式 runToken 回放历史轮次：可用 12 条截取窗口 10");
        assertEquals("过程事件 1", replay.get(0).summary());
        List<ExtractionEventRow> again =
                rig.extractionService.extractionEvents(id, 0L, 10, oldToken);
        assertEquals(replay.stream().map(ExtractionEventRow::cursor).toList(),
                again.stream().map(ExtractionEventRow::cursor).toList(),
                "同轮次同游标重放=同一窗口，不跳不重");
        List<ExtractionEventRow> tail =
                rig.extractionService.extractionEvents(id, replay.get(9).cursor(), 10, oldToken);
        assertEquals(2, tail.size(), "跟随窗口从游标之后继续，只剩 2 条");
    }

    @Test
    void 缺省runToken与显式最新轮次窗口字节一致() {
        String token = UUID.randomUUID().toString();
        long id = armedDocWithEvents(token, 5);
        List<ExtractionEventRow> implicitWindow =
                rig.extractionService.extractionEvents(id, 0L, 10);
        List<ExtractionEventRow> explicitWindow =
                rig.extractionService.extractionEvents(id, 0L, 10, token);
        assertEquals(5, implicitWindow.size());
        assertEquals(implicitWindow.stream().map(ExtractionEventRow::cursor).toList(),
                explicitWindow.stream().map(ExtractionEventRow::cursor).toList(),
                "缺省态=最新轮次语义零漂移：隐式与显式同 token 同窗口");
    }

    @Test
    void 换轮次后旧轮次过程事件不复活() {
        String oldToken = UUID.randomUUID().toString();
        long id = armedDocWithEvents(oldToken, 4);
        String liveToken = UUID.randomUUID().toString();
        ExtractionPreviewFixtures.armExtracting(rig.jdbcTemplate, id, liveToken);

        List<ExtractionEventRow> freshGen =
                rig.extractionService.extractionEvents(id, 0L, 10);
        assertTrue(freshGen.isEmpty(),
                "实扫=新一轮次未落任何过程事件，如实空窗；旧轮次步骤不得复活");
    }
}
