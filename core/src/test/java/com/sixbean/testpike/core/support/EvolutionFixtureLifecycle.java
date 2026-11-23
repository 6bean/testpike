package com.sixbean.testpike.core.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Shared lifecycle for the evolution-fixture suites (proposal rollback, trend
 * report). Both suites build the same fixture shape — random-tagged users,
 * projects and chat sessions, plus landed prompt/tool rows — so the handle
 * reset and the cleanup order live here as a single source instead of two
 * hand-copied bodies (the R40 lesson: hand-written teardown chains converge
 * to byte-identical duplicated logic the moment they are touched).
 * <p>Sweep order matters: prompt templates and project-scoped tools first
 * (tp_tool.project_id is a RESTRICT FK), then the chat rows, then the
 * tracked users and projects themselves. Tool rows are swept through
 * {@link SharedDbFixtureSweep#ownedByInProjects} so a created_by collision
 * can never reach shipped factory rows.
 */
public final class EvolutionFixtureLifecycle {

    private EvolutionFixtureLifecycle() {
    }

    /** Drop the handles a previous test kept so each case starts from empty lists. */
    public static void begin(List<Long> sessionIds, List<Long> projectIds, List<Long> userIds) {
        sessionIds.clear();
        projectIds.clear();
        userIds.clear();
    }

    /** Delete every row created by the tracked fixture users/projects/sessions. */
    public static void sweep(JdbcTemplate jdbc, List<Long> sessionIds,
            List<Long> projectIds, List<Long> userIds) {
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM tp_prompt_template WHERE created_by = ?", userId);
        }
        SharedDbFixtureSweep.ownedByInProjects(jdbc, "tp_tool", userIds, projectIds);
        for (Long sessionId : sessionIds) {
            jdbc.update("DELETE FROM tp_ai_chat_draft WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM tp_ai_chat_frame WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM tp_ai_chat_message WHERE session_id = ?", sessionId);
        }
        for (Long sessionId : sessionIds) {
            jdbc.update("DELETE FROM tp_ai_chat_session WHERE id = ?", sessionId);
        }
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM tp_user WHERE id = ?", userId);
        }
        for (Long projectId : projectIds) {
            jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
    }
}
