package com.sixbean.testpike.core.mcp.domain.service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Single-source teardown for knowledge-sync subscription fixture rows.
 *
 * <p>Three subscription-cleanup suites repeated the exact same child-first
 * delete block; per R40 the mechanics live here only. Delete order stays
 * FK child-first (precedent: McpKnowledgeSyncServiceTest cleanup ordering):
 * synced docs, then sync entries, then the subscription itself.
 */
final class McpSyncSubscriptionCleaner {

    private McpSyncSubscriptionCleaner() {
    }

    /** Purge every tracked subscription and the docs/entries it produced. */
    static void purge(JdbcTemplate jdbcTemplate, List<Long> subscriptionIds) {
        for (Long id : subscriptionIds) {
            jdbcTemplate.update("DELETE FROM tp_knowledge_doc WHERE id IN"
                    + " (SELECT doc_id FROM tp_mcp_knowledge_sync_entry WHERE subscription_id = ?)", id);
            jdbcTemplate.update("DELETE FROM tp_mcp_knowledge_sync_entry WHERE subscription_id = ?", id);
            jdbcTemplate.update("DELETE FROM tp_mcp_knowledge_sync WHERE id = ?", id);
        }
    }
}
