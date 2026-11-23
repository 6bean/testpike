package com.sixbean.testpike.core.aichat.domain.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shared test-ledger cleanup: deletes the rows a draft-behind-the-door suite
 * mints (chat session chain, then the case write-cascade). Both the case-write
 * flow suite and the queue-renewal suite tear the same shape down, so the
 * statement order lives in one place — R40 single source, no per-file copies.
 */
@Component
public class DraftLedgerCleanup {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Remove drafts/frames/messages attached to the session, then the session. */
    public void sessionChain(long sessionId) {
        jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_frame WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_message WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", sessionId);
    }

    /** Remove cases created by the suite with their steps/versions/scripts. */
    public void caseCascade(List<Long> caseIds) {
        for (long caseId : caseIds) {
            jdbcTemplate.update("DELETE FROM tp_case_step WHERE case_id = ?", caseId);
            jdbcTemplate.update("DELETE FROM tp_case_version WHERE case_id = ?", caseId);
            jdbcTemplate.update("DELETE FROM tp_script_version WHERE case_id = ?", caseId);
            jdbcTemplate.update("DELETE FROM tp_script WHERE case_id = ?", caseId);
            jdbcTemplate.update("DELETE FROM tp_test_case WHERE id = ?", caseId);
        }
    }
}
