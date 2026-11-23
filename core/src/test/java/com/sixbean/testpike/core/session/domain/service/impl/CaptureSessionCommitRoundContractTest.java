package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.session.api.dto.CaptureCandidateMetadata;
import com.sixbean.testpike.core.session.api.dto.CaptureCommitValues;
import com.sixbean.testpike.core.session.api.dto.CaptureCommittedEntry;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionClaim;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionInput;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionReport;
import com.sixbean.testpike.core.session.domain.component.CaptureSessionStore;
import com.sixbean.testpike.core.session.domain.service.CaptureSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the wizard checks a subset, the signal rides the same hand-off queue to the
 * executor, and the freshly read values are registered into the vault through
 * the existing LoginStateService while the session itself dies on every
 * terminal exit). Real dev DB + real Redis (SpringBootTest, no mocks — red
 * line 3).
 *
 * <p>Verification faces:
 * ① commit only accepts names present in the stored candidate list and the
 * next claim carries exactly that selection (unchecked names are never
 * asked for — the authorization is the checked subset);
 * ② registered values land encrypted behind the vault reference: neither the
 * ledger row nor the credential row carries the plaintext (red line 3),
 * the row is stamped with the capture lineage (source_kind=3) and re-capture
 * of the same name renews instead of appending (D9);
 * ③ every terminal path (registered / browser gone / out-of-window) deletes
 * both session keys and the queue entry (R57, wizard then sees the honest
 * re-open wording);
 * ④ values without a commit signal are refused (no blind write door).
 */
@SpringBootTest(classes = CoreApplication.class)
class CaptureSessionCommitRoundContractTest {

    private static final long FIXTURE_EXECUTOR_ID = 900_000_000_903L;
    private static final String PROJECT_CODE = "capture-commit-fx";
    private static final String ORIGIN = "https://capture-commit.example";
    private static final String STATE_NAME = "采集入册夹具登录态";

    @Autowired
    private CaptureSessionService captureSessionService;
    @Autowired
    private CaptureSessionStore captureSessionStore;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Owned fixture project (tp_credential carries a real project FK). */
    private long projectId;

    @BeforeEach
    void resetTransientAndProject() {
        while (captureSessionStore.claimPending() != null) {
            // drain the hand-off queue (shared state inside this slot's Redis)
        }
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id IN"
                + " (SELECT id FROM tp_project WHERE code = ?)", PROJECT_CODE);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id IN"
                + " (SELECT id FROM tp_project WHERE code = ?)", PROJECT_CODE);
        jdbcTemplate.update("DELETE FROM tp_project WHERE code = ?", PROJECT_CODE);
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "采集入册夹具项目", PROJECT_CODE);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, PROJECT_CODE);
    }

    @AfterEach
    void dropFixtureData() {
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        while (captureSessionStore.claimPending() != null) {
            // fixtures never leave queue entries behind (R57)
        }
    }

    private static CaptureSessionInput input() {
        return new CaptureSessionInput(STATE_NAME, List.of(ORIGIN),
                "url-prefix", ORIGIN + "/home", freshEndpoint(), null);
    }

    private static String freshEndpoint() {
        return "http://127.0.0.1:57" + (System.nanoTime() % 100000)
                + "/devtools/browser/" + UUID.randomUUID();
    }

    /** Open a session, hand it over and store two candidates (cookie + storage). */
    private String openWithCandidates() {
        String sessionId = captureSessionService.open(projectId, input());
        assertNotNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID));
        long stored = captureSessionService.report(sessionId, new CaptureSessionReport(
                FIXTURE_EXECUTOR_ID, null,
                List.of(new CaptureCandidateMetadata("cookie", "SESSION-ID", ORIGIN, 1_893_456_000L),
                        new CaptureCandidateMetadata("local_storage", "auth_token", ORIGIN, null))));
        assertEquals(2L, stored);
        return sessionId;
    }

    @Test
    void commitKeepsOnlyCheckedNamesAndTheNextClaimCarriesTheSignal() {
        String sessionId = openWithCandidates();
        try {
            assertEquals(1, captureSessionService.commit(sessionId, List.of("SESSION-ID")));
            CaptureSessionClaim claim = captureSessionService.claim(FIXTURE_EXECUTOR_ID);
            assertNotNull(claim, "入册信号必须通过同一交接队列送达执行侧");
            assertEquals(List.of("SESSION-ID"), claim.commitSelection(),
                    "只交接勾选子集，未勾选键名零读取");
            assertEquals("commit-requested", captureSessionService.find(sessionId).status());
        } finally {
            captureSessionService.abandon(sessionId);
        }
    }

    @Test
    void commitRefusesNamesOutsideTheCandidateList() {
        String sessionId = openWithCandidates();
        try {
            assertThrows(RuntimeException.class,
                    () -> captureSessionService.commit(sessionId, List.of("NO-SUCH-KEY")),
                    "候选清单外的勾选必须整体拒止");
            assertEquals("candidates-ready", captureSessionService.find(sessionId).status(),
                    "拒止不得毁会话");
            captureSessionStore.dequeue(sessionId);
            assertEquals(0L, captureSessionStore.candidateCount(sessionId) - 2L,
                    "会话候选保持原样");
        } finally {
            captureSessionService.abandon(sessionId);
        }
    }

    @Test
    void candidatesReadFaceReturnsMetadataOnlyShape() {
        String sessionId = openWithCandidates();
        try {
            List<CaptureCandidateMetadata> rows = captureSessionService.candidates(sessionId);
            assertEquals(2, rows.size());
            for (CaptureCandidateMetadata row : rows) {
                assertNotNull(row.name());
                assertNotNull(row.kind());
                assertEquals(ORIGIN, row.origin());
            }
            List<String> raw = captureSessionStore.candidateRows(sessionId);
            for (String row : raw) {
                assertFalse(row.contains("value"), "候选值字段禁止出现在回写面（红线3）");
            }
        } finally {
            captureSessionService.abandon(sessionId);
        }
    }

    @Test
    void commitValuesRegistersEncryptedRowAndTerminatesSession() {
        String sessionId = openWithCandidates();
        String plaintext = "SECRET-" + UUID.randomUUID();
        try {
            captureSessionService.commit(sessionId, List.of("SESSION-ID"));
            assertNotNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID));
            long registered = captureSessionService.commitValues(sessionId, new CaptureCommitValues(
                    List.of(new CaptureCommittedEntry("cookie", "SESSION-ID", ORIGIN, plaintext, null),
                            // Defense in depth: an unchecked name sent anyway is dropped
                            // server-side (red line 2 double gate).
                            new CaptureCommittedEntry("local_storage", "auth_token", ORIGIN,
                                    "SHOULD-NOT-BE-STORED", null)), null));

            assertEquals(1L, registered, "只有勾选子集会入册");
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT * FROM tp_login_state WHERE project_id = ? AND state_name = ?",
                    projectId, STATE_NAME);
            assertEquals("cookie_set", row.get("kind"));
            assertEquals(3, ((Number) row.get("source_kind")).intValue(),
                    "入册行必须盖采集谱系戳");
            assertNotNull(row.get("credential_id"), "值必须走保险库单源引用");
            assertNoPlaintext(row, plaintext, "账本行");
            Map<String, Object> cred = jdbcTemplate.queryForMap(
                    "SELECT * FROM tp_credential WHERE id = ?", row.get("credential_id"));
            assertNoPlaintext(cred, plaintext, "保险库行");
            assertTrue(cred.values().stream().map(String::valueOf)
                            .anyMatch(v -> v.startsWith("ENC:")),
                    "密文单源=ENC 前缀形态（E4 保险库，禁明文入库）");

            assertEquals(-2L, captureSessionStore.ttlSeconds(sessionId),
                    "入册成功即删会话键（终态删除，R57）");
            assertEquals(null, redis.opsForValue().get(CaptureSessionStore.candidatesKey(sessionId)),
                    "候选列表随终态一起清掉");
            assertEquals(null, captureSessionService.claim(FIXTURE_EXECUTOR_ID),
                    "队列项已随入册终态回收");
            assertThrows(RuntimeException.class, () -> captureSessionService.find(sessionId),
                    "终态后向导轮询得到诚实过期文案（重新发起指路）");
        } finally {
            captureSessionService.abandon(sessionId);
        }
    }

    @Test
    void sameNameRecommitRenewsTheRowInsteadOfAppending() {
        String first = openWithCandidates();
        captureSessionService.commit(first, List.of("SESSION-ID"));
        assertNotNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID));
        captureSessionService.commitValues(first, new CaptureCommitValues(
                List.of(new CaptureCommittedEntry("cookie", "SESSION-ID", ORIGIN, "V1", null)), null));
        Object credIdAfterFirst = jdbcTemplate.queryForObject(
                "SELECT credential_id FROM tp_login_state WHERE project_id = ? AND state_name = ?",
                Object.class, projectId, STATE_NAME);

        String second = openWithCandidates();
        captureSessionService.commit(second, List.of("SESSION-ID"));
        assertNotNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID));
        captureSessionService.commitValues(second, new CaptureCommitValues(
                List.of(new CaptureCommittedEntry("cookie", "SESSION-ID", ORIGIN, "V2", null)), null));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM tp_login_state WHERE project_id = ? AND state_name = ?",
                projectId, STATE_NAME);
        assertEquals(1, rows.size(), "同名再采=覆盖更新非追加（D9）");
        // The renewal re-points the SAME vault row (uk project+derived cred key).
        assertEquals(credIdAfterFirst, rows.get(0).get("credential_id"),
                "同名再采必须更新同一密文行而非新建");
        Long vaultRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_credential WHERE project_id = ?"
                        + " AND cred_key LIKE 'capture\\_%' ESCAPE '\\'",
                Long.class, projectId);
        assertEquals(1L, vaultRows, "采集谱系密文行项目内同名只有一行");
    }

    @Test
    void browserGoneAnswerTerminallyInvalidatesWithoutLedgerWrite() {
        String sessionId = openWithCandidates();
        captureSessionService.commit(sessionId, List.of("SESSION-ID"));
        assertNotNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID));
        long registered = captureSessionService.commitValues(sessionId, new CaptureCommitValues(
                List.of(), "无法连接本机浏览器调试端点"));

        assertEquals(-1L, registered, "浏览器会话不在=诚实作废");
        assertEquals(-2L, captureSessionStore.ttlSeconds(sessionId),
                "作废必须连队列项一起终态删除");
        assertEquals(null, captureSessionService.claim(FIXTURE_EXECUTOR_ID));
        Long ledger = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_login_state WHERE project_id = ?", Long.class,
                projectId);
        assertEquals(0L, ledger, "作废回合禁止产生账本行");
        assertThrows(RuntimeException.class, () -> captureSessionService.find(sessionId),
                "向导看到重新发起指路而非静默");
    }

    @Test
    void valuesWithoutCommitSignalAreRefused() {
        String sessionId = openWithCandidates();
        long registered = captureSessionService.commitValues(sessionId, new CaptureCommitValues(
                List.of(new CaptureCommittedEntry("cookie", "SESSION-ID", ORIGIN, "BLIND", null)), null));
        try {
            assertEquals(-1L, registered, "未确认勾选的取值回写必须拒收（勾选即授权）");
            Long ledger = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_login_state WHERE project_id = ?", Long.class,
                    projectId);
            assertEquals(0L, ledger);
            assertTrue(captureSessionStore.ttlSeconds(sessionId) > 0, "拒收不毁在途会话");
        } finally {
            captureSessionService.abandon(sessionId);
        }
    }

    private static void assertNoPlaintext(Map<String, Object> row, String plaintext, String face) {
        for (Map.Entry<String, Object> column : row.entrySet()) {
            if (column.getValue() != null) {
                assertFalse(String.valueOf(column.getValue()).contains(plaintext),
                        face + "列 " + column.getKey() + " 禁止出现采集明文（红线3）");
            }
        }
    }
}
