package com.sixbean.testpike.core.document.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stall sweep for zombie "extracting" documents .
 * <p>A dispatched extraction rides tp_document.status=3 until ai-service writes
 * back; if the consumer dies mid-flight (restart, MQ loss, LLM hang killed)
 * the row stays 3 forever — the UI keeps showing "extracting" and the extract button
 * stays blocked (state machine rejects status=3 re-dispatch). The sweeper
 * reclaims rows whose updated_at passed the K8 window ai.doc.extract-stall-minutes
 * (default 30 = consume lease 15min x 2) back to status=1 with a human-readable
 * extract_error, so the user can simply retry.
 * <p>Behaviour under test (real dev database, zero mocks — posture of
 * MockHitRetentionSweeperTest):
 * <ul>
 * <li>status=3 rows older than the window fall back to 1 with the human error
 * text; fresh status=3 rows (inside the window) are never touched;</li>
 * <li>other statuses (1/2/4) are untouched regardless of age;</li>
 * <li>the sweep is idempotent: a second round settles nothing new;</li>
 * <li>fail-closed window parsing: garbage/blank falls back to the seeded 30,
 * and a non-positive window refuses the sweep (never "reset everything");</li>
 * <li>the window rides the K8 key read fresh on every run (hot-update).</li>
 * </ul>
 * Seeded fixture rows carry a unique name tag so cleanup and assertions only
 * ever look at this test's own rows.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=true")
class DocumentExtractStallSweeperTest {

    /** K8 key shared with the sweeper (V293 seeded default 30). */
    static final String STALL_MINUTES_KEY = "ai.doc.extract-stall-minutes";

    @Autowired
    private DocumentExtractStallSweeper sweeper;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String tag = "t445sweep" + UUID.randomUUID().toString()
            .replace("-", "").substring(0, 10);
    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "t445" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "提取僵尸清扫测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, code);
        configCacheHolder.put(STALL_MINUTES_KEY, "30");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM tp_document WHERE name LIKE ?", tag + "%");
        configCacheHolder.remove(STALL_MINUTES_KEY);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    /** Insert a document row pinned to the given status, aged back by an interval. */
    private long seedDoc(int status, String ageInterval) {
        String name = tag + "-" + UUID.randomUUID().toString().substring(0, 6);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_document (project_id, name, file_type, file_hash, size_bytes, "
                        + "status, updated_at) VALUES (?, ?, 'markdown', ?, 10, ?, "
                        + "now() - interval '" + ageInterval + "') RETURNING id",
                Long.class, projectId, name, name + "-hash", status);
    }

    private Integer statusOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tp_document WHERE id = ?", Integer.class, id);
    }

    private String errorOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT extract_error FROM tp_document WHERE id = ?", String.class, id);
    }

    @Test
    void staleExtractingRow_fallsBackToParseOk_withHumanError() {
        long zombie = seedDoc(3, "45 minutes");

        int swept = sweeper.sweepStalled();

        assertTrue(swept >= 1, "超窗 status=3 行必须被收回（本轮 swept=" + swept + "）");
        assertEquals(1, statusOf(zombie), "僵尸提取中行应回退 1（可重新发起提取）");
        String err = errorOf(zombie);
        assertNotNull(err, "回退必须带人话 extract_error（禁静默改态）");
        assertTrue(err.contains("重试"), "错误文案应指引用户重试，实测=" + err);
        assertTrue(err.length() <= 500, "extract_error VARCHAR(500) 不越界");
    }

    @Test
    void freshExtractingRow_andOtherStatuses_areUntouched() {
        long freshExtracting = seedDoc(3, "5 minutes");
        long oldParseOk = seedDoc(1, "2 hours");
        long oldFailed = seedDoc(2, "2 hours");
        long oldExtracted = seedDoc(4, "2 hours");

        sweeper.sweepStalled();

        assertEquals(3, statusOf(freshExtracting), "窗口内的提取中行=正常在途，禁误杀");
        assertEquals(1, statusOf(oldParseOk), "status=1 与本清扫无关");
        assertEquals(2, statusOf(oldFailed), "status=2 与本清扫无关");
        assertEquals(4, statusOf(oldExtracted), "status=4 与本清扫无关");
        assertNull(errorOf(oldParseOk), "未被清扫的行禁写入 extract_error");
    }

    @Test
    void secondRound_settlesNothingNew() {
        seedDoc(3, "45 minutes");
        int first = sweeper.sweepStalled();
        int second = sweeper.sweepStalled();
        assertEquals(0, second, "清扫幂等：第二轮对同一无存量面应归零（第一轮=" + first + "）");
    }

    @Test
    void windowRidesK8Key_hotUpdateAndFailClosed() {
        long borderline = seedDoc(3, "45 minutes");
        configCacheHolder.put(STALL_MINUTES_KEY, "60"); // widen beyond fixture age
        sweeper.sweepStalled();
        assertEquals(3, statusOf(borderline), "窗口热更放宽到 60min 后，45min 行应留在途态");

        configCacheHolder.put(STALL_MINUTES_KEY, "garbage");
        sweeper.sweepStalled();
        assertEquals(1, statusOf(borderline), "非法值按缺省 30 回退，45min 行应被收回（fail-closed）");
    }

    @Test
    void nonPositiveWindow_refusesSweep_neverResetsEverything() {
        long fresh = seedDoc(3, "5 minutes");
        long stale = seedDoc(3, "2 hours");
        for (String bad : new String[]{"0", "-1"}) {
            configCacheHolder.put(STALL_MINUTES_KEY, bad);
            int swept = sweeper.sweepStalled();
            assertEquals(0, swept, "窗口 " + bad + " 应拒绝清扫而非全量回退");
            assertEquals(3, statusOf(fresh), "拒绝清扫=一切在途态不动");
            assertEquals(3, statusOf(stale), "拒绝清扫=超窗行也不动（等合法窗口）");
        }
    }
}
