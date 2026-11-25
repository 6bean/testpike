package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.session.api.dto.CaptureCommittedEntry;
import com.sixbean.testpike.core.session.api.dto.CaptureSlotStageInput;
import com.sixbean.testpike.core.session.domain.component.CaptureSessionStore;
import com.sixbean.testpike.core.session.domain.service.CaptureSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screenshot staging channel : the ai-service
 * attachment parsing chain hands freshly OCR-ed rows with their values to this
 * internal door, and the door parks them in the same TTL-bound transient store
 * the CDP wizard commit round already owns — one key family, one window, no
 * second spelling (R40). The staging token is all the chat side ever sees;
 * values only ever live inside the Redis slot until the draft strategy
 * re-reads and deletes them at landing time.
 *
 * <p>Real dev DB + real Redis (SpringBootTest, no mocks — red line 3).
 * Verification faces:
 * ① stage stores {channel, sessionId, entries-with-values} under a fresh
 * token with the hot capture window TTL;
 * ② the staged values have zero trace on persistent surfaces (tp_login_state,
 * tp_ai_chat_draft) — red line 4 / piece E;
 * ③ malformed inputs (blank channel, non-positive session, empty rows, blank
 * or duplicated names, over-cap batch) refuse before any key is written.
 */
@SpringBootTest(classes = CoreApplication.class)
@IsolatedCaptureRedis
class CaptureScreenshotStageContractTest {

    @Autowired
    private CaptureSessionService captureSessionService;
    @Autowired
    private CaptureSessionStore captureSessionStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static CaptureCommittedEntry cookie(String name, String value) {
        return new CaptureCommittedEntry("cookie", name, ".stage-contract.example",
                value, null);
    }

    @Test
    void stageParksValuesInTransientSlotUnderFreshToken() {
        String secret = "stageval-" + UUID.randomUUID();
        long chatSessionId = 900_000_777L;
        String token = captureSessionService.stageScreenshotEntries(
                new CaptureSlotStageInput(CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT,
                        chatSessionId,
                        List.of(cookie("sbtoken", secret),
                                new CaptureCommittedEntry("local_storage", "jwt",
                                        "https://stage-contract.example", secret + "-ls", 123L))));
        assertNotNull(token);
        try {
            Map<String, Object> slot = captureSessionStore.load(token);
            assertNotNull(slot, "槽必须落在同一瞬态存储（load 可读形态）");
            assertEquals(CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT, slot.get("channel"));
            assertEquals(chatSessionId, ((Number) slot.get("sessionId")).longValue());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) slot.get("entries");
            assertEquals(2, entries.size());
            assertEquals(secret, entries.get(0).get("value"));
            assertEquals("sbtoken", entries.get(0).get("name"));
            assertEquals(secret + "-ls", entries.get(1).get("value"));
            assertEquals(123L, ((Number) entries.get(1).get("expiresAtEpochSecond")).longValue());
            long ttl = captureSessionStore.ttlSeconds(token);
            assertTrue(ttl > 0 && ttl <= 24 * 3600,
                    "槽 TTL 必须落在热更窗口内, ttl=" + ttl);
        } finally {
            captureSessionStore.remove(token);
        }
    }

    @Test
    void stagedValuesLeaveNoPersistentTrace() {
        String secret = "trace-" + UUID.randomUUID();
        String token = captureSessionService.stageScreenshotEntries(
                new CaptureSlotStageInput(CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT,
                        900_000_778L, List.of(cookie("traceKey", secret))));
        try {
            String like = "%" + secret + "%";
            Long ledger = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_login_state"
                            + " WHERE COALESCE(state_object_key, '') LIKE ? OR COALESCE(remark, '') LIKE ?",
                    Long.class, like, like);
            Long draft = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_ai_chat_draft WHERE payload_json::text LIKE ?",
                    Long.class, like);
            assertEquals(0L, ledger, "截图解析值禁止进入登录态持久面（红线4）");
            assertEquals(0L, draft, "截图解析值禁止进入草案载荷持久面（红线4）");
        } finally {
            captureSessionStore.remove(token);
        }
    }

    @Test
    void malformedStageInputsRefuseBeforeAnyKeyIsWritten() {
        List<CaptureCommittedEntry> rows = List.of(cookie("ok", "v1"));
        assertThrows(ServiceException.class, () -> captureSessionService
                .stageScreenshotEntries(new CaptureSlotStageInput(" ", 1L, rows)),
                "空白渠道必须拒");
        assertThrows(ServiceException.class, () -> captureSessionService
                .stageScreenshotEntries(new CaptureSlotStageInput(
                        CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT, 0L, rows)),
                "非正会话号必须拒");
        assertThrows(ServiceException.class, () -> captureSessionService
                .stageScreenshotEntries(new CaptureSlotStageInput(
                        CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT, 1L, List.of())),
                "空行集必须拒（空即无事可登记）");
        assertThrows(ServiceException.class, () -> captureSessionService
                .stageScreenshotEntries(new CaptureSlotStageInput(
                        CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT, 1L,
                        List.of(cookie(" ", "v")))),
                "空白名称行必须拒");
        assertThrows(ServiceException.class, () -> captureSessionService
                .stageScreenshotEntries(new CaptureSlotStageInput(
                        CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT, 1L,
                        List.of(cookie("dup", "a"), cookie("dup", "b")))),
                "同名双行必须拒（勾选按名称授权，重名即歧义）");
        List<CaptureCommittedEntry> overCap = new ArrayList<>();
        for (int i = 0; i <= CaptureSlotStageInput.MAX_ENTRIES; i++) {
            overCap.add(cookie("row-" + i, "v" + i));
        }
        assertThrows(ServiceException.class, () -> captureSessionService
                .stageScreenshotEntries(new CaptureSlotStageInput(
                        CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT, 1L, overCap)),
                "超上限批量必须拒（R57：瞬态面同样有界）");
    }

    @Test
    void unknownTokenIsHonestAbsence() {
        assertNull(captureSessionStore.load("no-such-token-" + UUID.randomUUID()));
    }
}
