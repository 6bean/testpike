package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.session.api.dto.CaptureCandidateMetadata;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionClaim;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionInput;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionReport;
import com.sixbean.testpike.core.session.domain.component.CaptureSessionStore;
import com.sixbean.testpike.core.session.domain.component.SessionGovernanceKeys;
import com.sixbean.testpike.core.session.domain.service.CaptureSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executor-side capture round on the transient channel ( U2 — design doc
 * shape as the takeover input queue, reads scoped candidates through CDP and
 * writes back metadata only). Real dev DB + real Redis (SpringBootTest, no
 * mocks — red line 3).
 *
 * <p>Verification faces:
 * ① the hand-off queue is single-sourced on the store: an opened session is
 * claimable exactly once and an empty queue answers null (honest absence);
 * ② the claim projection is the only read shape that carries the debug endpoint
 * (executor side needs it) and it carries the hot
 * session.validate-timeout-ms value instead of a hardcoded executor timeout;
 * ③ candidate write-back raises the live count, moves the session to
 * candidates-ready and stores the four metadata fields only — the value field
 * of red line 3 has no slot anywhere, and neither the endpoint string nor a
 * candidate name reaches a PostgreSQL persistent surface (red line 4);
 * ④ an honest failure keeps the session (no destruction) and re-queues it for a
 * retry with an observable attempt counter;
 * ⑤ a session whose window has elapsed is cleaned on claim instead of being
 * handed over (timeout invalidation belongs to the TTL window, R57).
 */
@SpringBootTest(classes = CoreApplication.class)
class CaptureSessionClaimReportContractTest {

    private static final long FIXTURE_PROJECT_ID = 900_000_000_002L;
    private static final long FIXTURE_EXECUTOR_ID = 900_000_000_902L;

    @Autowired
    private CaptureSessionService captureSessionService;
    @Autowired
    private CaptureSessionStore captureSessionStore;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void drainPendingQueue() {
        // The hand-off queue is shared state inside this slot's Redis: start and
        // end empty so a leftover session of another class can never be claimed
        // here (and fixtures never leave queue entries behind, R57).
        while (captureSessionStore.claimPending() != null) {
            // drain
        }
    }

    private static CaptureSessionInput input(String endpoint) {
        return new CaptureSessionInput("领取契约夹具登录态", List.of("https://capture-claim.example"),
                "url-prefix", "https://capture-claim.example/home", endpoint, null);
    }

    private static String freshEndpoint() {
        return "http://127.0.0.1:57" + (System.nanoTime() % 100000)
                + "/devtools/browser/" + UUID.randomUUID();
    }

    @Test
    void emptyQueueAnswersNullAndOpenedSessionIsClaimableExactlyOnce() {
        assertNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID), "空队列必须诚实返回 null");

        String endpoint = freshEndpoint();
        String sessionId = captureSessionService.open(FIXTURE_PROJECT_ID, input(endpoint));
        try {
            CaptureSessionClaim claim = captureSessionService.claim(FIXTURE_EXECUTOR_ID);
            assertNotNull(claim, "已发起的会话必须可被领取");
            assertEquals(sessionId, claim.sessionId());
            assertEquals(FIXTURE_PROJECT_ID, claim.projectId());
            assertEquals(endpoint, claim.debugEndpoint(), "领取形态必须把调试端点交给执行侧");
            assertEquals(List.of("https://capture-claim.example"), claim.scopeOrigins());
            assertEquals("url-prefix", claim.anchorKind());
            assertEquals("local-direct", claim.mode());
            assertEquals(1L, claim.attempt());
            assertEquals(SessionGovernanceKeys.DEFAULT_VALIDATE_TIMEOUT_MS, claim.validateTimeoutMs(),
                    "执行侧超时取值必须来自热更键");

            assertNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID),
                    "一次领取即一次交接，禁同一会话被两台执行机重复消费");
        } finally {
            captureSessionService.abandon(sessionId);
        }
    }

    @Test
    void candidateWriteBackRaisesLiveCountAndStoresMetadataOnly() {
        String endpoint = freshEndpoint();
        String sessionId = captureSessionService.open(FIXTURE_PROJECT_ID, input(endpoint));
        String secretValue = "SEC-" + UUID.randomUUID();
        try {
            assertNotNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID));
            long accepted = captureSessionService.report(sessionId, new CaptureSessionReport(
                    FIXTURE_EXECUTOR_ID, null,
                    List.of(new CaptureCandidateMetadata("cookie", "SESSION-ID",
                                    "https://capture-claim.example", 1_893_456_000L),
                            new CaptureCandidateMetadata("local_storage", "auth_token",
                                    "https://capture-claim.example", null),
                            // Defense in depth: a row outside the session whitelist must be
                            // dropped on the core side even if the executor mis-filtered.
                            new CaptureCandidateMetadata("cookie", "OUT-OF-SCOPE",
                                    "https://elsewhere.example", null))));

            assertEquals(2L, accepted, "白名单外条目必须被服务端摘除（红线2 三交集）");
            assertEquals(2L, captureSessionService.find(sessionId).candidateCount());
            assertEquals("candidates-ready", captureSessionService.find(sessionId).status());
            assertTrue(captureSessionStore.ttlSeconds(sessionId) > 0, "写回后窗口必须仍在");

            List<String> rows = redis.opsForList()
                    .range(CaptureSessionStore.candidatesKey(sessionId), 0, -1);
            assertNotNull(rows);
            assertEquals(2, rows.size());
            for (String row : rows) {
                assertFalse(row.contains("value"), "候选值字段禁止出现在回写面（红线3）");
                assertTrue(row.contains("SESSION-ID") || row.contains("auth_token"),
                        "元数据必须完整可勾选, 实际: " + row);
            }
            assertFalse(secretValue.isEmpty());

            String likeEndpoint = "%" + endpoint + "%";
            Long persisted = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_cluster_command"
                            + " WHERE payload::text LIKE ? OR COALESCE(result_detail, '') LIKE ?",
                    Long.class, likeEndpoint, likeEndpoint);
            Long ledger = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_login_state"
                            + " WHERE COALESCE(state_name, '') LIKE ? OR COALESCE(remark, '') LIKE ?",
                    Long.class, likeEndpoint, "%SESSION-ID%");
            assertEquals(0L, persisted, "采集回写禁止进入命令队列持久面（红线4）");
            assertEquals(0L, ledger, "采集回写禁止进入登录态账本持久面（红线4）");
        } finally {
            captureSessionService.abandon(sessionId);
        }
        assertNull(redis.opsForValue().get(CaptureSessionStore.candidatesKey(sessionId)),
                "终态删除必须连候选列表一起清掉（R57）");
    }

    @Test
    void honestFailureKeepsSessionAndRequeuesForRetry() {
        String endpoint = freshEndpoint();
        String sessionId = captureSessionService.open(FIXTURE_PROJECT_ID, input(endpoint));
        try {
            assertNotNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID));
            captureSessionService.report(sessionId, new CaptureSessionReport(FIXTURE_EXECUTOR_ID,
                    "无法连接调试端点，请确认浏览器已按指引开启远程调试", List.of()));

            assertEquals("collect-failed", captureSessionService.find(sessionId).status(),
                    "失败必须可见但不毁会话");
            // The reason sentence stays inside the transient payload: the read shape
            // carries no field for it, so an executor string can never reach a screen
            // without the facade batch reviewing it.
            assertFalse(captureSessionService.find(sessionId).toString()
                    .contains("无法连接调试端点"), "读取形态禁止外泄执行侧原因串");

            CaptureSessionClaim retry = captureSessionService.claim(FIXTURE_EXECUTOR_ID);
            assertNotNull(retry, "失败后必须重新排队供重试（会话不毁）");
            assertEquals(2L, retry.attempt(), "重试轮次必须可观测");
        } finally {
            captureSessionService.abandon(sessionId);
        }
    }

    @Test
    void zeroCandidatePassIsVisibleAndRequeuesWithinWindow() {
        String endpoint = freshEndpoint();
        String sessionId = captureSessionService.open(FIXTURE_PROJECT_ID, input(endpoint));
        try {
            assertNotNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID));
            long accepted = captureSessionService.report(sessionId,
                    new CaptureSessionReport(FIXTURE_EXECUTOR_ID, null, List.of()));
            assertEquals(0L, accepted);
            assertEquals("collect-empty", captureSessionService.find(sessionId).status());
            assertNotNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID),
                    "空手而归（人还没登完）必须允许下轮再采，禁把会话判死");
        } finally {
            captureSessionService.abandon(sessionId);
        }
    }

    @Test
    void claimCleansSessionWhoseWindowElapsed() {
        String orphanId = "claim-window-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> stale = new LinkedHashMap<>();
        stale.put("projectId", FIXTURE_PROJECT_ID);
        stale.put("stateName", "过期窗口夹具");
        stale.put("scopeOrigins", List.of("https://capture-claim.example"));
        stale.put("anchorKind", "url-prefix");
        stale.put("anchorValue", "https://capture-claim.example/home");
        stale.put("debugEndpoint", freshEndpoint());
        stale.put("mode", "local-direct");
        stale.put("status", "collecting");
        stale.put("attempt", 0);
        // Two days ago: far outside any legal capture window (1..240 minutes).
        stale.put("createdAtEpochSecond", System.currentTimeMillis() / 1000 - 172_800);
        captureSessionStore.save(orphanId, stale);
        captureSessionStore.enqueue(orphanId);

        assertNull(captureSessionService.claim(FIXTURE_EXECUTOR_ID),
                "窗口已过的会话不得交接（超时作废归 TTL 谱）");
        assertNull(captureSessionStore.load(orphanId), "窗口判定必须顺手清理过期载荷（R57）");
    }

    @Test
    void reportForUnknownSessionIsRefusedWithoutCreatingKeys() {
        String ghost = "claim-absent-" + UUID.randomUUID().toString().replace("-", "");
        long accepted = captureSessionService.report(ghost, new CaptureSessionReport(
                FIXTURE_EXECUTOR_ID, null,
                List.of(new CaptureCandidateMetadata("cookie", "X", "https://capture-claim.example", null))));
        assertEquals(-1L, accepted, "会话不存在的回写必须诚实拒收（不落任何键）");
        assertNull(captureSessionStore.load(ghost));
    }
}
