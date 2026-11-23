package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.events.api.dto.TaskEventRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 segment B: the cursor-window door shared by every job kind of the
 * process event ledger. Pinned behaviours (same contract the extraction tail
 * already rides, now in one place): strictly ascending window after the
 * cursor, replaying the same cursor returns exactly the same window, the
 * window size clamps into 10~1000 with the hot key default, and a blank run
 * anchor honestly answers an empty window. Real slot database, real door —
 * fixture rows are self-created and cleaned by key (no TRUNCATE of shipped
 * tables, R37 baseline duty).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskEventWindowReaderTest {

    private static final String KIND = "it-window-reader";

    @Autowired
    private TaskEventWindowReader reader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String jobRef;

    @AfterEach
    void cleanUpFixtures() {
        if (jobRef != null) {
            jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind=? AND job_ref=?",
                    KIND, jobRef);
            jobRef = null;
        }
    }

    /** Land {@code rows} events for a fresh run anchor; returns the token. */
    private String landRows(int rows) {
        jobRef = "ref-" + UUID.randomUUID().toString().substring(0, 8);
        String token = UUID.randomUUID().toString();
        for (int i = 1; i <= rows; i++) {
            jdbcTemplate.update("INSERT INTO tp_ai_task_event"
                            + " (job_kind, job_ref, run_token, level, summary, detail)"
                            + " VALUES (?,?,?,?,?,?)",
                    KIND, jobRef, token,
                    i % 3 == 0 ? "error" : "step",
                    "过程事件 " + i, "{\"seq\":" + i + "}");
        }
        return token;
    }

    @Test
    void 窗读严格升序且重放同游标返回同一窗口() {
        String token = landRows(5);

        List<TaskEventRow> first = reader.since(KIND, jobRef, token, 0L, 10);
        assertEquals(5, first.size());
        for (int i = 1; i < first.size(); i++) {
            assertTrue(first.get(i).cursor() > first.get(i - 1).cursor(),
                    "行 id 游标必须严格升序");
        }
        List<TaskEventRow> replay = reader.since(KIND, jobRef, token, 0L, 10);
        assertEquals(first.stream().map(TaskEventRow::cursor).toList(),
                replay.stream().map(TaskEventRow::cursor).toList(),
                "重放同游标=同一窗口，不跳不重");
        assertEquals(0, reader.since(KIND, jobRef, token,
                first.get(first.size() - 1).cursor(), 10).size(),
                "跟随窗口从游标之后继续，已无新行");
        assertEquals("过程事件 1", first.get(0).summary());
        assertEquals("{\"seq\":1}", first.get(0).detail());
        assertTrue(first.get(0).landedAt() != null && first.get(0).landedAt().length() >= 19,
                "落账时刻 ISO 串可达");
    }

    @Test
    void 窗口尺寸钳制在十到一千且缺省走热更键() {
        assertEquals(TaskEventWindowReader.WINDOW_MIN, reader.windowSize(1),
                "limit<10 抬到 10（窗口下界不截内容）");
        assertEquals(TaskEventWindowReader.WINDOW_MAX, reader.windowSize(999_999),
                "limit>1000 钳到 1000（窗口有界）");
        int fallback = reader.windowSize(null);
        assertTrue(fallback >= TaskEventWindowReader.WINDOW_MIN
                        && fallback <= TaskEventWindowReader.WINDOW_MAX,
                "缺省窗走 ai.stream.chunk-size，仍在同一区间内");
    }

    @Test
    void 空运行锚如实回答空窗而不是报错() {
        String token = landRows(2);
        assertTrue(reader.since(KIND, jobRef, "", 0L, 10).isEmpty(),
                "空运行锚=无可重放，如实空窗");
        assertTrue(reader.since(KIND, jobRef, null, 0L, 10).isEmpty());
        assertTrue(reader.since(null, jobRef, token, 0L, 10).isEmpty());
        assertTrue(reader.since(KIND, "ref-none", token, 0L, 10).isEmpty(),
                "陌生业务锚同样空窗");
    }

    /** Land rows under an explicit token for the current jobRef; returns it. */
    private String landRunUnderToken(String token, int rows, String label) {
        for (int i = 1; i <= rows; i++) {
            jdbcTemplate.update("INSERT INTO tp_ai_task_event"
                            + " (job_kind, job_ref, run_token, level, summary, detail)"
                            + " VALUES (?,?,?,?,?,?)",
                    KIND, jobRef, token, "step", label + " " + i, "{\"seq\":" + i + "}");
        }
        return token;
    }

    /**
 * segment C1 (channel-B spec extraction tail): the latest-run door.
 * This job kind persists its run token nowhere outside the ledger, so
 * the door resolves the token of the newest landed row for the anchor
 * and replays only that run — a retried attempt never mixes frames with
 * the previous one, and an attempt that continues later keeps seeing
 * its own earlier frames (token-keyed replay, not time-keyed).
 */
    @Test
    void latestRunReplaysOnlyTheAttemptThatLandedTheNewestRow() {
        jobRef = "ref-" + UUID.randomUUID().toString().substring(0, 8);
        String tokenA = landRunUnderToken(UUID.randomUUID().toString(), 3, "首轮");
        landRunUnderToken(UUID.randomUUID().toString(), 2, "次轮");

        List<TaskEventRow> window = reader.sinceLatestRun(KIND, jobRef, 0L, 10);
        assertEquals(2, window.size(), "最新运行=次轮两行，首轮行不出窗");
        assertEquals("次轮 1", window.get(0).summary());
        assertTrue(window.stream().noneMatch(row -> row.summary().startsWith("首轮")),
                "游标窗内不得混入他轮帧");
        List<TaskEventRow> replay = reader.sinceLatestRun(KIND, jobRef, 0L, 10);
        assertEquals(window.stream().map(TaskEventRow::cursor).toList(),
                replay.stream().map(TaskEventRow::cursor).toList(),
                "重放同游标=同一窗口，不跳不重");
        assertEquals(0, reader.sinceLatestRun(KIND, jobRef,
                window.get(1).cursor(), 10).size(), "窗尾之后无新帧=跟随窗收口");

        // The first attempt landing again flips the anchor back to its token
        // and its own earlier frames stay visible (three before + one new).
        landRunUnderToken(tokenA, 1, "首轮");
        List<TaskEventRow> resumed = reader.sinceLatestRun(KIND, jobRef, 0L, 10);
        assertEquals(4, resumed.size(), "锚随最新落账行翻转，该 token 全部四行出窗");
        assertTrue(resumed.stream().allMatch(row -> row.summary().startsWith("首轮")),
                "翻转后窗内只剩首轮帧");
    }

    /** Blank anchors and unknown business refs answer an honest empty window
     *  through the latest-run door too (same fail-soft contract as since). */
    @Test
    void latestRunBlankOrUnknownAnchorHonestlyAnswersEmptyWindow() {
        assertTrue(reader.sinceLatestRun(KIND,
                "ref-none-" + UUID.randomUUID().toString().substring(0, 8), 0L, 10).isEmpty(),
                "陌生业务锚=空窗非报错");
        assertTrue(reader.sinceLatestRun(null, "x", 0L, 10).isEmpty());
        assertTrue(reader.sinceLatestRun("", "x", 0L, 10).isEmpty());
        assertTrue(reader.sinceLatestRun(KIND, " ", 0L, 10).isEmpty());
    }

    /** Run-history door: one aggregated row per run token, newest run first,
     *  the error flag raised when any frame of that run carried the error
     *  level, and rows landed without a token never show as a run. */
    @Test
    void runsAggregatePerTokenNewestFirstAndSkipTokenlessRows() {
        jobRef = "ref-" + UUID.randomUUID().toString().substring(0, 8);
        String olderToken = landRunUnderToken("run-older-" + jobRef, 4, "older");
        jdbcTemplate.update("INSERT INTO tp_ai_task_event"
                        + " (job_kind, job_ref, run_token, level, summary, detail)"
                        + " VALUES (?,?,?,?,?,?)",
                KIND, jobRef, olderToken, "error", "older failure frame", "{}");
        // Frames without a run token belong to no run and must stay invisible.
        for (int i = 1; i <= 2; i++) {
            jdbcTemplate.update("INSERT INTO tp_ai_task_event"
                            + " (job_kind, job_ref, run_token, level, summary, detail)"
                            + " VALUES (?,?,?,?,?,?)",
                    KIND, jobRef, "", "step", "tokenless " + i, "{}");
        }
        String newerToken = landRunUnderToken("run-newer-" + jobRef, 3, "newer");

        var runs = reader.runs(KIND, jobRef, null);
        assertEquals(2, runs.size(), "one aggregated row per token, tokenless rows skipped");
        assertEquals(newerToken, runs.get(0).runToken(), "newest run first");
        assertEquals(olderToken, runs.get(1).runToken());
        assertEquals(3, runs.get(0).eventCount());
        assertEquals(5, runs.get(1).eventCount());
        org.junit.jupiter.api.Assertions.assertFalse(runs.get(0).hasError());
        org.junit.jupiter.api.Assertions.assertTrue(runs.get(1).hasError());
        assertTrue(runs.get(0).firstAt() != null && runs.get(0).lastAt() != null,
                "first/last timestamps bound the run window");
        assertTrue(runs.get(0).lastAt().compareTo(runs.get(0).firstAt()) >= 0);
    }

    /** Blank job kind, blank business anchor or an unknown anchor answer an
     *  honest empty list through the run-history door too (fail-soft). */
    @Test
    void runsBlankOrUnknownAnchorHonestlyAnswersEmptyList() {
        assertTrue(reader.runs(null, "x", null).isEmpty());
        assertTrue(reader.runs("", "x", 10).isEmpty());
        assertTrue(reader.runs(KIND, " ", 10).isEmpty());
        assertTrue(reader.runs(KIND,
                "ref-none-" + UUID.randomUUID().toString().substring(0, 8), null).isEmpty(),
                "unknown business anchor = empty list, not an error");
    }
}
