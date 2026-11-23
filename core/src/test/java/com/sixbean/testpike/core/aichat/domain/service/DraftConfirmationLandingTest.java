package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic draft landing : every dialog draft frame
 * projects into ONE ledger table (tp_ai_chat_draft) discriminated by
 * object_type, so the platform never grows a second confirmation chain per
 * proposal kind (red line 6). The repair kind is the first registered
 * strategy; its anchor columns arrive here as target_id/payload_json, the
 * kind-specific display anchor (executionId) rides in context_json.
 * <p>What must hold:
 * <ul>
 * <li>a DRAFT frame whose meta carries the repair anchors lands one pending
 * row with object_type=SCRIPT_REPAIR (meta without an explicit
 * objectType defaults to that kind — upstream emitters predate the
 * discriminator and must keep landing unchanged);</li>
 * <li>re-appending the same draft message stays idempotent (one row, one
 * draftId pinned onto every replayed frame meta);</li>
 * <li>an unknown object_type = honest skip: no strategy registered means
 * nothing confirmable may exist, the ledger keeps streaming;</li>
 * <li>partial anchors on a known kind skip the landing exactly like the
 * repair-era contract (half-trusted snapshot = no confirmable row).</li>
 * </ul>
 * Real dev database, zero mocks (same posture as RepairDraftLandingTest).
 */
@SpringBootTest(classes = CoreApplication.class)
class DraftConfirmationLandingTest {

    /** Repair-kind meta as the ai-service emitter writes it: no objectType field yet. */
    private static final String REPAIR_META =
            "{\"executionId\":5151,\"caseId\":9,\"baseVersion\":4,"
                    + "\"schemaVersion\":\"v2\",\"model\":\"generic-test-model\","
                    + "\"actionJson\":\"{\\\"version\\\":\\\"v2\\\",\\\"steps\\\":[]}\"}";

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newUserId(String tag) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
    }

    private long newProjectId(String tag) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
    }

    private ChatSessionInfo freshSession() {
        String tag = "t430a" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return sessionService.create(newProjectId(tag), newUserId(tag), "Generic draft " + tag,
                1, "{\"route\":\"/cases\"}");
    }

    @Test
    void repairAnchorsWithoutObjectTypeLandAsScriptRepairInTheGenericLedger() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.DRAFT,
                "泛化落账草案", REPAIR_META);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM tp_ai_chat_draft WHERE message_id = ?", message);
        assertEquals("SCRIPT_REPAIR", row.get("object_type"),
                "meta without an explicit kind defaults to the only registered repair kind");
        assertEquals(1, ((Number) row.get("status")).intValue(),
                "a landed draft must wait for confirmation (status=1)");
        assertEquals(9L, ((Number) row.get("target_id")).longValue());
        assertEquals(4, ((Number) row.get("base_version")).intValue());
        assertEquals("v2", row.get("schema_version"));
        assertEquals("generic-test-model", row.get("model_name"));
        assertTrue(String.valueOf(row.get("payload_json")).contains("\"steps\""),
                "the confirmable snapshot carries the proposal body");
        assertEquals("5151", String.valueOf(jdbcTemplate.queryForObject(
                "SELECT context_json->>'executionId' FROM tp_ai_chat_draft WHERE message_id = ?",
                String.class, message)),
                "kind-specific display anchors ride in context_json, never in dedicated columns");
        assertEquals(session.id(), ((Number) row.get("session_id")).longValue());
    }

    @Test
    void explicitSupportedObjectTypeLandsUnderThatKind() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.DRAFT,
                "显式类型草案", REPAIR_META.replace("\"executionId\"",
                        "\"objectType\":\"SCRIPT_REPAIR\",\"executionId\""));

        String objectType = jdbcTemplate.queryForObject(
                "SELECT object_type FROM tp_ai_chat_draft WHERE message_id = ?",
                String.class, message);
        assertEquals("SCRIPT_REPAIR", objectType);
    }

    @Test
    void unknownObjectTypeIsSkippedHonestlyAndTheLedgerKeepsStreaming() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        // Unregistered kind: even with a full anchor set nothing may become
        // confirmable — a strategy-less proposal has no honest landing path.
        long seq = frameService.appendFrame(session.id(), message, ChatFrameType.DRAFT,
                "未知类型草案", REPAIR_META.replace("\"executionId\"",
                        "\"objectType\":\"WIDGET_TELEPORT\",\"executionId\""));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_ai_chat_draft WHERE message_id = ?",
                Integer.class, message);
        assertNotNull(count);
        assertEquals(0, count.intValue(), "no registered strategy = no confirmable row");
        String metaDraftId = jdbcTemplate.queryForObject(
                "SELECT meta_json->>'draftId' FROM tp_ai_chat_frame "
                        + "WHERE session_id = ? AND seq = ?",
                String.class, session.id(), seq);
        assertNull(metaDraftId, "a skipped landing must not pin a dead confirm door on the card");
    }

    @Test
    void partialAnchorsSkipTheLandingExactlyLikeTheRepairEraContract() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.DRAFT,
                "半截锚点", "{\"executionId\":1}");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_ai_chat_draft WHERE message_id = ?",
                Integer.class, message);
        assertNotNull(count);
        assertEquals(0, count.intValue(),
                "a half-trusted snapshot must never become a confirmable draft row");
    }

    @Test
    void reAppendedDraftFramesShareOneGenericRowAndOneDraftId() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        long first = frameService.appendFrame(session.id(), message, ChatFrameType.DRAFT,
                "草案一", REPAIR_META);
        long second = frameService.appendFrame(session.id(), message, ChatFrameType.DRAFT,
                "草案一", REPAIR_META);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_ai_chat_draft WHERE message_id = ?",
                Integer.class, message);
        assertNotNull(rows);
        assertEquals(1, rows.intValue(), "one message mints at most one confirmable draft");
        Long draftId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_ai_chat_draft WHERE message_id = ?", Long.class, message);
        assertNotNull(draftId);
        for (long seq : new long[] {first, second}) {
            Long metaDraftId = jdbcTemplate.queryForObject(
                    "SELECT (meta_json->>'draftId')::bigint FROM tp_ai_chat_frame "
                            + "WHERE session_id = ? AND seq = ?", Long.class, session.id(), seq);
            assertEquals(draftId, metaDraftId,
                    "a replayed draft frame points at the same draft, never a second ticket");
        }
    }
}
