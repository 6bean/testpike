package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Chat ledger cascade on project purge (real dev database, nothing mocked).
 * <p>Why this exists: {@code tp_ai_chat_session} carries a project anchor and
 * {@code tp_ai_chat_draft} carries one too (system-origin cards, session and
 * message NULL since the anchor was relaxed), and neither table holds a
 * foreign key onto {@code tp_project}. A purged project therefore used to
 * leave its whole conversation ledger behind — the live slot database showed
 * 47 draft rows hung under 45 projects that no longer exist, script-repair
 * cards included. Terminal state must remove the rows created under it
 * (including the never-applied ones), which is what this test locks.
 * <p>The sibling-project rows are the negative control: the cascade is scoped
 * by project, never a blanket sweep of the ledger.
 */
@SpringBootTest(classes = CoreApplication.class)
class ProjectPurgeChatLedgerTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String code;
    private long projectId;
    private long siblingProjectId;
    private long sessionId;
    private long messageId;
    private long userId;

    @BeforeEach
    void seedChatLedger() {
        code = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_user ORDER BY id LIMIT 1", Long.class);
        projectId = insertProject("P", "会话账级联项目-" + code);
        siblingProjectId = insertProject("S", "会话账对照项目-" + code);

        sessionId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_ai_chat_session (project_id, user_id, title) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class, projectId, userId, "级联验证会话-" + code);
        messageId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_ai_chat_message (session_id, role, content, status) "
                        + "VALUES (?, 'user', '夹具提问', 'done') RETURNING id",
                Long.class, sessionId);
        jdbcTemplate.update(
                "INSERT INTO tp_ai_chat_frame (session_id, message_id, seq, event_type) "
                        + "VALUES (?, ?, 1, 'delta')", sessionId, messageId);

        // Session-anchored card: belongs to the project through its session.
        jdbcTemplate.update(
                "INSERT INTO tp_ai_chat_draft (session_id, message_id, object_type, payload_json,"
                        + " status, seq) VALUES (?, ?, 'CASE', '{}'::jsonb, 1, 1)",
                sessionId, messageId);
        // System-origin card (never applied): anchored straight on the project.
        jdbcTemplate.update(
                "INSERT INTO tp_ai_chat_draft (session_id, message_id, project_id, object_type,"
                        + " target_id, payload_json, status, seq) VALUES (NULL, NULL, ?,"
                        + " 'SCRIPT_REPAIR', 1, '{}'::jsonb, 1, 1)",
                projectId);
        // The untouched sibling keeps one card and one session each.
        jdbcTemplate.update(
                "INSERT INTO tp_ai_chat_draft (session_id, message_id, project_id, object_type,"
                        + " target_id, payload_json, status, seq) VALUES (NULL, NULL, ?,"
                        + " 'SCRIPT_REPAIR', 1, '{}'::jsonb, 1, 1)",
                siblingProjectId);
        jdbcTemplate.update(
                "INSERT INTO tp_ai_chat_session (project_id, user_id, title) VALUES (?, ?, ?)",
                siblingProjectId, userId, "对照会话-" + code);
    }

    private long insertProject(String role, String name) {
        // tp_project.code is varchar(20): tag + role letter + short suffix.
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, name, "CLED" + role + code);
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void purgeRemovesProjectChatLedgerAndKeepsSiblings() {
        assertDoesNotThrow(() -> projectService.purge(1L, projectId, true),
                "purge force must clear the chat ledger, no foreign key may block it");

        assertEquals(0, count("SELECT count(*) FROM tp_ai_chat_session WHERE project_id = ?",
                projectId), "purged project keeps no conversation session");
        assertEquals(0, count("SELECT count(*) FROM tp_ai_chat_message m "
                        + "JOIN tp_ai_chat_session s ON s.id = m.session_id "
                        + "WHERE s.project_id = ?",
                projectId), "messages leave with their session");
        assertEquals(0, count("SELECT count(*) FROM tp_ai_chat_frame f "
                        + "JOIN tp_ai_chat_message m ON m.id = f.message_id "
                        + "WHERE m.session_id = ?",
                sessionId), "frames leave with their message");
        assertEquals(0, count("SELECT count(*) FROM tp_ai_chat_draft d "
                        + "WHERE d.project_id = ?"
                        + " OR d.session_id IN (SELECT id FROM tp_ai_chat_session WHERE project_id = ?)",
                projectId, projectId),
                "cards hang on the project anchor or its session and must leave with the project");

        // Negative control: the cascade is project-scoped.
        assertEquals(1, count("SELECT count(*) FROM tp_ai_chat_draft WHERE project_id = ?",
                siblingProjectId), "sibling project cards stay untouched");
        assertEquals(1, count("SELECT count(*) FROM tp_ai_chat_session WHERE project_id = ?",
                siblingProjectId), "sibling project sessions stay untouched");
    }

    @AfterEach
    void dropFixtureRows() {
        // Failure-path recovery: the ledger rows are removed by anchor before
        // the project rows, same child-then-parent order as the purge path.
        jdbcTemplate.update("DELETE FROM tp_ai_chat_frame WHERE session_id IN "
                + "(SELECT id FROM tp_ai_chat_session WHERE project_id IN (?, ?))",
                projectId, siblingProjectId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_message WHERE session_id IN "
                + "(SELECT id FROM tp_ai_chat_session WHERE project_id IN (?, ?))",
                projectId, siblingProjectId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE project_id IN (?, ?) "
                        + "OR session_id IN (SELECT id FROM tp_ai_chat_session WHERE project_id IN (?, ?))",
                projectId, siblingProjectId, projectId, siblingProjectId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE project_id IN (?, ?)",
                projectId, siblingProjectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id IN (?, ?)",
                siblingProjectId, projectId);
    }
}
