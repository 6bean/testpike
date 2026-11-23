package com.sixbean.testpike.core.aichat.domain.component;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Removes the conversation ledger a project owns (AN batch residue settlement).
 * <p>Why a dedicated piece exists: the ledger tables carry the project anchor
 * as a plain column — {@code tp_ai_chat_session} and {@code tp_ai_chat_draft}
 * hold no foreign key onto {@code tp_project} (the draft anchor was relaxed
 * with the system-origin card family), so nothing in the database removes
 * those rows when the project disappears. The live slot database showed 47
 * draft rows hung under 45 deleted projects, script-repair cards included:
 * rows created without a paired release path.
 * <p>Terminal-state deletion, single source for every caller (project purge
 * and the test fixtures that drop seeded projects directly). Delete order is
 * child before parent, which is also the failure-branch posture: a later
 * statement never leaves an earlier one's orphans behind.
 */
@Component
public class ChatLedgerPurgeSupport {

    private final JdbcTemplate jdbcTemplate;

    public ChatLedgerPurgeSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Drop the whole conversation ledger of one project: replay frames, then
     * turn messages, then every draft card (session-anchored, system-origin
     * and never-applied alike), then the sessions themselves.
     *
     * @return number of removed rows across the four tables (audit account)
     */
    public int purgeByProject(long projectId) {
        return purge(jdbcTemplate, projectId);
    }

    /**
     * Same cascade against an explicit template — the shared implementation
     * behind {@link #purgeByProject(long)} and the test fixtures, so the
     * statement list exists exactly once.
     */
    public static int purge(JdbcTemplate jdbc, long projectId) {
        int removed = 0;
        removed += jdbc.update("DELETE FROM tp_ai_chat_frame WHERE message_id IN "
                + "(SELECT m.id FROM tp_ai_chat_message m JOIN tp_ai_chat_session s ON s.id = m.session_id"
                + " WHERE s.project_id = ?)", projectId);
        removed += jdbc.update("DELETE FROM tp_ai_chat_message WHERE session_id IN "
                + "(SELECT id FROM tp_ai_chat_session WHERE project_id = ?)", projectId);
        removed += jdbc.update("DELETE FROM tp_ai_chat_draft WHERE project_id = ? "
                + "OR session_id IN (SELECT id FROM tp_ai_chat_session WHERE project_id = ?)",
                projectId, projectId);
        removed += jdbc.update("DELETE FROM tp_ai_chat_session WHERE project_id = ?", projectId);
        return removed;
    }
}
