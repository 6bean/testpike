package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Shared test seed for the chat ledger's owning scope: one throwaway user,
 * one throwaway project and one session under a unique tag. Draft-behind-the-
 * door suites all open the same three-row scope before minting frames, so the
 * statement order lives in one place (R40 single source) instead of being
 * copied into every suite — a copy here would drift the moment the scope shape
 * changes.
 * <p>Only seeds what it creates; the caller deletes through
 * {@link DraftLedgerCleanup} (session chain) plus the returned ids, so no suite
 * ever guesses which rows it owns.
 */
@Component
public class LedgerSessionSeed {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatSessionService sessionService;

    /** The three-row scope a dialog suite owns, with the unique tag it was minted under. */
    public record Scope(long userId, long projectId, long sessionId, String tag) {
    }

    /**
     * Seed user + project + chat session under one unique tag prefix.
     *
     * @param tagPrefix    short suite-specific prefix so rows are traceable to their owner
     * @param sessionLabel human label for the session row
     * @param routeJson    the session's page route context, as the stored jsonb string
     */
    public Scope open(String tagPrefix, String sessionLabel, String routeJson) {
        String tag = tagPrefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        long userId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
        long projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
        ChatSessionInfo session = sessionService.create(projectId, userId,
                sessionLabel + " " + tag, 1, routeJson);
        return new Scope(userId, projectId, session.id(), tag);
    }
}
