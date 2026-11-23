package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionInfo;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionInput;
import com.sixbean.testpike.core.session.domain.component.CaptureSessionStore;
import com.sixbean.testpike.core.session.domain.component.SessionGovernanceKeys;
import com.sixbean.testpike.core.session.domain.service.CaptureSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real dev DB + real Redis (SpringBootTest, no mocks — red line 3).
 * Verification faces:
 * ① open stores the payload on Redis only, TTL bound to the hot
 * session.capture-timeout-minutes window (never hardcoded);
 * ② the debug endpoint string has zero hits in PG persistent surfaces
 * (tp_cluster_command payload/result_detail, tp_login_state text columns —
 * red line 4) and never echoes through the read shape;
 * ③ host-window mode is refused BEFORE any session exists while the
 * headless shell switch is off, wording naming both the settings path and
 * the parameter (R32 guidance), and passes once the switch is on;
 * ④ every terminal exit removes the keys: explicit abandon and TTL expiry
 * (the commit-path delete joins with the wiring package);
 * ⑤ fail-closed scope gate: empty origin whitelist and malformed anchor are
 * refused (red line 2 — empty scope never means "all").
 * Config flips are cache-holder only, restored in finally (no DB writes).
 */
@SpringBootTest(classes = CoreApplication.class)
class CaptureSessionLifecycleContractTest {

    private static final long FIXTURE_PROJECT_ID = 900_000_000_001L;

    @Autowired
    private CaptureSessionService captureSessionService;
    @Autowired
    private CaptureSessionStore captureSessionStore;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigCacheHolder configCacheHolder;

    /** Captured after injection (field initializers run before @Autowired wiring). */
    private String savedShellSwitch;

    @org.junit.jupiter.api.BeforeEach
    void captureSavedSwitch() {
        savedShellSwitch = configCacheHolder.get(SessionGovernanceKeys.KEY_HEADLESS_HOST_CAPTURE_ENABLED);
    }

    @AfterEach
    void restoreSwitch() {
        if (savedShellSwitch == null) {
            configCacheHolder.remove(SessionGovernanceKeys.KEY_HEADLESS_HOST_CAPTURE_ENABLED);
        } else {
            configCacheHolder.put(SessionGovernanceKeys.KEY_HEADLESS_HOST_CAPTURE_ENABLED, savedShellSwitch);
        }
    }

    private static CaptureSessionInput input(String mode, String endpoint) {
        return new CaptureSessionInput("契约夹具登录态", List.of("https://capture-contract.example"),
                "url-prefix", "https://capture-contract.example/home", endpoint, mode);
    }

    @Test
    void openStoresTransientSessionWithHotTtlAndNoPersistentTrace() {
        String endpoint = "http://127.0.0.1:59" + (System.nanoTime() % 100000)
                + "/devtools/browser/" + UUID.randomUUID();
        String sessionId = captureSessionService.open(FIXTURE_PROJECT_ID, input(null, endpoint));
        assertNotNull(sessionId);
        try {
            long ttl = captureSessionStore.ttlSeconds(sessionId);
            long windowSeconds = SessionGovernanceKeys.DEFAULT_CAPTURE_TIMEOUT_MINUTES * 60L + 60L;
            assertTrue(ttl > 0 && ttl <= windowSeconds,
                    "会话 TTL 必须落在热更窗口内, ttl=" + ttl);

            CaptureSessionInfo info = captureSessionService.find(sessionId);
            assertEquals(FIXTURE_PROJECT_ID, info.projectId());
            assertEquals("local-direct", info.mode());
            assertEquals("collecting", info.status());
            assertEquals(0L, info.candidateCount());
            assertFalse(info.toString().contains(endpoint),
                    "读取形态禁止回显调试端点串（红线4）");

            String like = "%" + endpoint + "%";
            Long cmd = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_cluster_command"
                            + " WHERE payload::text LIKE ? OR COALESCE(result_detail, '') LIKE ?",
                    Long.class, like, like);
            Long ledger = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_login_state"
                            + " WHERE COALESCE(state_name, '') LIKE ?"
                            + " OR COALESCE(remark, '') LIKE ?"
                            + " OR COALESCE(state_object_key, '') LIKE ?",
                    Long.class, like, like, like);
            assertEquals(0L, cmd, "端点串禁止进入命令队列持久面（红线4）");
            assertEquals(0L, ledger, "端点串禁止进入登录态账本持久面（红线4）");
        } finally {
            captureSessionService.abandon(sessionId);
        }
    }

    @Test
    void hostWindowModeRefusedBeforeCreationWhileShellSwitchOff() {
        String endpoint = "http://127.0.0.1:61" + (System.nanoTime() % 100000)
                + "/devtools/browser/" + UUID.randomUUID();
        configCacheHolder.put(SessionGovernanceKeys.KEY_HEADLESS_HOST_CAPTURE_ENABLED, "0");
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> captureSessionService.open(FIXTURE_PROJECT_ID, input("host-window", endpoint)));
        String msg = refusal.getMessage();
        // Kernel refusal frame is the single source: it names the settings
        // navigation path, the literal config key and the caller's alternative.
        assertTrue(msg.contains("系统管理 → 系统配置"), "guidance must name the settings page (kernel frame), actual: " + msg);
        assertTrue(msg.contains("session.headless-host-capture.enabled"),
                "guidance must name the config key, actual: " + msg);
        assertTrue(msg.contains("本机直连"), "guidance must offer a usable alternative, actual: " + msg);
    }

    @Test
    void hostWindowModeAllowedWhenShellSwitchOn() {
        String endpoint = "http://127.0.0.1:62" + (System.nanoTime() % 100000)
                + "/devtools/browser/" + UUID.randomUUID();
        configCacheHolder.put(SessionGovernanceKeys.KEY_HEADLESS_HOST_CAPTURE_ENABLED, "1");
        String sessionId = captureSessionService.open(FIXTURE_PROJECT_ID, input("host-window", endpoint));
        try {
            assertEquals("host-window", captureSessionService.find(sessionId).mode());
        } finally {
            captureSessionService.abandon(sessionId);
        }
    }

    @Test
    void explicitAbandonRemovesBothSessionKeys() {
        String endpoint = "http://127.0.0.1:63" + (System.nanoTime() % 100000)
                + "/devtools/browser/" + UUID.randomUUID();
        String sessionId = captureSessionService.open(FIXTURE_PROJECT_ID, input("local-direct", endpoint));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(CaptureSessionStore.sessionKey(sessionId))));
        captureSessionService.abandon(sessionId);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(CaptureSessionStore.sessionKey(sessionId))),
                "显式放弃后会话键必须消失（终态删除，含取消分支）");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(CaptureSessionStore.candidatesKey(sessionId))),
                "显式放弃后候选键必须一并消失");
        assertThrows(ServiceException.class, () -> captureSessionService.find(sessionId));
    }

    @Test
    void ttlExpiryCollapsesSessionHonestly() throws InterruptedException {
        String endpoint = "http://127.0.0.1:64" + (System.nanoTime() % 100000)
                + "/devtools/browser/" + UUID.randomUUID();
        String sessionId = captureSessionService.open(FIXTURE_PROJECT_ID, input("local-direct", endpoint));
        redis.expire(CaptureSessionStore.sessionKey(sessionId), Duration.ofSeconds(1));
        Thread.sleep(1500);
        ServiceException expired = assertThrows(ServiceException.class,
                () -> captureSessionService.find(sessionId));
        assertTrue(expired.getMessage().contains(SessionGovernanceKeys.KEY_CAPTURE_TIMEOUT_MINUTES),
                "超时作废文案必须点名采集窗口参数, 实际: " + expired.getMessage());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(CaptureSessionStore.sessionKey(sessionId))),
                "TTL 到点会话键必须由 Redis 自动清除");
    }

    @Test
    void zeroWindowSessionIsVoidOnEveryFaceEvenWhileTheKeySits() {
        // -B: the capture window can be flipped to 0 at runtime (L3 state B
        // premise). A zero-TTL write may keep the key present without expiry,
        // so liveness must be gated by the window, not by key presence:
        // claim skips it, and both read faces (detail + candidates) refuse
        // with the SAME wording naming the hot parameter (R32 guidance).
        String saved = configCacheHolder.get(SessionGovernanceKeys.KEY_CAPTURE_TIMEOUT_MINUTES);
        configCacheHolder.put(SessionGovernanceKeys.KEY_CAPTURE_TIMEOUT_MINUTES, "0");
        String endpoint = "http://127.0.0.1:67" + (System.nanoTime() % 100000)
                + "/devtools/browser/" + UUID.randomUUID();
        String sessionId = captureSessionService.open(FIXTURE_PROJECT_ID, input("local-direct", endpoint));
        try {
            assertTrue(Boolean.TRUE.equals(redis.hasKey(CaptureSessionStore.sessionKey(sessionId))),
                    "用例前提：零窗口写入后会话键可能仍在位（键在位不等于会话存活）");
            assertTrue(captureSessionService.claim(990_007L) == null,
                    "0 窗口的会话不得被领取（与 L3 状态 B 同一判据）");
            ServiceException onDetail = assertThrows(ServiceException.class,
                    () -> captureSessionService.find(sessionId),
                    "窗口已过而键在位时详情面必须诚实作废");
            assertTrue(onDetail.getMessage().contains(SessionGovernanceKeys.KEY_CAPTURE_TIMEOUT_MINUTES),
                    "详情面作废文案必须点名窗口参数, 实际: " + onDetail.getMessage());
            ServiceException onCandidates = assertThrows(ServiceException.class,
                    () -> captureSessionService.candidates(sessionId),
                    "候选面与详情面必须同一拒止（单一存活闸）");
            assertTrue(onCandidates.getMessage().contains(SessionGovernanceKeys.KEY_CAPTURE_TIMEOUT_MINUTES),
                    "候选面拒止文案必须点名窗口参数, 实际: " + onCandidates.getMessage());
            assertFalse(Boolean.TRUE.equals(redis.hasKey(CaptureSessionStore.sessionKey(sessionId))),
                    "过期读取即终态：读面按属主清扫会话键（R57 出口清扫）");
        } finally {
            if (saved == null) {
                configCacheHolder.remove(SessionGovernanceKeys.KEY_CAPTURE_TIMEOUT_MINUTES);
            } else {
                configCacheHolder.put(SessionGovernanceKeys.KEY_CAPTURE_TIMEOUT_MINUTES, saved);
            }
            captureSessionService.abandon(sessionId);
        }
    }

    @Test
    void failClosedScopeAndAnchorGateRefusesBeforeStoring() {
        String endpoint = "http://127.0.0.1:65" + (System.nanoTime() % 100000) + "/devtools/browser/x";
        ServiceException emptyScope = assertThrows(ServiceException.class,
                () -> captureSessionService.open(FIXTURE_PROJECT_ID,
                        new CaptureSessionInput("x", List.of(), "url-prefix", "v", endpoint, null)));
        assertTrue(emptyScope.getMessage().contains("白名单"), "空范围必须 fail-closed 拒绝, 实际: " + emptyScope.getMessage());

        assertThrows(ServiceException.class,
                () -> captureSessionService.open(FIXTURE_PROJECT_ID,
                        new CaptureSessionInput("x", List.of("https://a.example"), "cookie-name", "v", endpoint, null)));

        assertThrows(ServiceException.class,
                () -> captureSessionService.open(FIXTURE_PROJECT_ID,
                        new CaptureSessionInput("x", List.of("https://a.example"), "url-prefix", "v", endpoint, "carrier-pigeon")));
    }
}
