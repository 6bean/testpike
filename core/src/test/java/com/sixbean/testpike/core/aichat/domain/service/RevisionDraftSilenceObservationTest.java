package com.sixbean.testpike.core.aichat.domain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.aichat.domain.service.impl.ChatFrameServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Silence observation behind the revision-draft pre-generation door
 * : when a pregen-anchored
 * assistant turn (its session context_json carries an analysisId) seals
 * without ever landing a draft card, the run is physically silent — the
 * model answered in plain text and nothing downstream can tell a reviewer
 * why zero cards appeared. The seam writes exactly ONE readable WARN line
 * at the terminal close door, keyed by the analysis id.
 * <p>Guardrails pinned here:
 * <ul>
 * <li>a plain dialog turn without an anchored session never gets the
 * noise — the WARN is scoped to pregen sessions only;</li>
 * <li>a turn that DID land a draft card stays quiet;</li>
 * <li>the line names the analysis id, session id and message id so the
 * ledger can be re-read without opening the database by hand.</li>
 * </ul>
 * Real dev database, zero mocks, rows inserted and cleared by id (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
class RevisionDraftSilenceObservationTest {

    @Autowired
    private ChatSessionService sessionService;
    @Autowired
    private ChatFrameService frameService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tag;
    private long userId;
    private long projectId;
    private final List<Long> sessionIds = new ArrayList<>();
    private final List<Long> requirementIds = new ArrayList<>();

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag + "-" + UUID.randomUUID().toString().substring(0, 6));

        LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger target = context.getLogger(ChatFrameServiceImpl.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        target.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        context.getLogger(ChatFrameServiceImpl.class).detachAppender(appender);
        for (long sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_frame WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_message WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", sessionId);
        }
        for (long requirementId : requirementIds) {
            jdbcTemplate.update("DELETE FROM tp_case_requirement WHERE requirement_id = ?", requirementId);
            jdbcTemplate.update("DELETE FROM tp_requirement_version WHERE requirement_id = ?", requirementId);
            jdbcTemplate.update("DELETE FROM tp_requirement WHERE id = ?", requirementId);
        }
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    private boolean silenceWarned(long analysisId) {
        return appender.list.stream().anyMatch(e ->
                e.getLevel().isGreaterOrEqual(Level.WARN)
                        && e.getFormattedMessage().contains("analysisId=" + analysisId));
    }

    private long seedRequirement(String title) {
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_requirement (project_id, source, source_id, title, status, chapter)"
                        + " VALUES (?, 2, ?, ?, 1, ?) RETURNING id",
                Long.class, projectId, "doc:" + tag, title, "订单中心 PRD > 下单流程");
        requirementIds.add(id);
        return id;
    }

    private void landDraftCard(long sessionId, long messageId, long requirementId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("objectType", "REQUIREMENT_WRITE");
        meta.put("targetId", requirementId);
        meta.put("baseVersion", 1);
        meta.put("model", "test-model");
        meta.put("actionJson", "{\"title\":\"草案\",\"acceptanceCriteria\":\"x\",\"chapter\":null}");
        try {
            frameService.appendFrame(sessionId, messageId, ChatFrameType.DRAFT,
                    "requirement write proposal", objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anchoredTurnThatSealsWithoutACardWritesOneReadableWarnLine() {
        long analysisId = 900000L + Math.abs(tag.hashCode() % 100000);
        ChatSessionInfo session = sessionService.create(projectId, userId, "", 3,
                "{\"analysisId\":" + analysisId + "}");
        sessionIds.add(session.id());
        long turn = frameService.openMessage(session.id(), "assistant", null);

        frameService.closeMessage(turn, "done");

        assertTrue(silenceWarned(analysisId),
                "a pregen-anchored turn that lands no draft must leave one WARN line naming the analysis id");
        long warnCount = appender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .filter(e -> e.getFormattedMessage().contains("analysisId=" + analysisId))
                .count();
        assertEquals(1, warnCount, "exactly one line per sealed turn — no spam on replays of the same seal");
    }

    @Test
    void plainDialogTurnStaysQuietWhenNoCardLands() {
        ChatSessionInfo session = sessionService.create(projectId, userId, "plain", 1, "{}");
        sessionIds.add(session.id());
        long turn = frameService.openMessage(session.id(), "assistant", null);

        frameService.closeMessage(turn, "done");

        assertTrue(appender.list.stream()
                        .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                        .noneMatch(e -> e.getFormattedMessage().contains("analysisId=")),
                "ordinary Q&A turns never carry the pregen silence noise");
    }

    @Test
    void anchoredTurnThatLandedACardStaysQuiet() {
        long analysisId = 800000L + Math.abs(tag.hashCode() % 100000);
        long requirementId = seedRequirement("下单需求");
        ChatSessionInfo session = sessionService.create(projectId, userId, "", 3,
                "{\"analysisId\":" + analysisId + "}");
        sessionIds.add(session.id());
        long turn = frameService.openMessage(session.id(), "assistant", null);
        landDraftCard(session.id(), turn, requirementId);

        frameService.closeMessage(turn, "done");

        assertTrue(!silenceWarned(analysisId),
                "a sealed turn that really landed its draft card is the healthy path — stay quiet");
    }
}
