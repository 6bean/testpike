package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncLandingResult;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncRoundResult;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncSaveRequest;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncScanResult;

/**
 * MCP knowledge sync subscription registration service .
 *
 * <p>Owns the tp_mcp_knowledge_sync ledger write face: guards run in the
 * service layer (server exists, tool present in the connection-test snapshot
 * and annotated read-only, interval inside the guard range, target bucket
 * visible, name unique per project). The scheduled pull loop itself is the
 * next segment and consumes {@code enabled=true} due rows from here.
 */
public interface McpKnowledgeSyncService {

    /** Register a subscription; auto-activate falls back to the factory default (off). */
    McpKnowledgeSyncInfo save(long operatorId, McpKnowledgeSyncSaveRequest input);

    /** Replace the editable columns of one subscription under the same guards. */
    McpKnowledgeSyncInfo update(long operatorId, long id, McpKnowledgeSyncSaveRequest input);

    /** Pause: excluded from the due scan until resumed. */
    void pause(long operatorId, long id);

    /** Resume a paused subscription. */
    void resume(long operatorId, long id);

    /** Remove the subscription row (history rounds are ledger-side, kept). */
    void delete(long operatorId, long id);

    /** One subscription by id (40001 human message when absent). */
    McpKnowledgeSyncInfo detail(long id);

    /**
 * Paged project segment (projectId null = all rows; id DESC baseline).
 * serverId narrows to one MCP server's subscriptions (the drawer inside
 * a server detail); null keeps the whole project segment visible.
 */
    PageResult<McpKnowledgeSyncInfo> list(Long projectId, Long serverId, int page, int size);

    /**
     * Scheduler entry: SQL-side due scan (enabled rows whose sync_interval_minutes
     * elapsed since last_sync_at, never-synced rows are due), one independent
     * {@link #runOnce(long, long)} per due row. The loop never dies on a single
     * subscription failure: the round is typed and written to the ledger.
     */
    McpKnowledgeSyncScanResult scanDue();

    /**
     * One subscription round: DOWN gate through the existing connectivity
     * probe (no second transport stack), then a read-only tools/call through
     * McpHandshakeClient and the payload into the knowledge ledger.
     */
    McpKnowledgeSyncRoundResult runOnce(long operatorId, long id);

    /**
     * Land one tool-output payload into the knowledge ledger through the
     * lineage table: fingerprint of an identical latest payload = replay =
     * zero new rows; a changed payload lands a new ledger version. Entries
     * wait for human batch activation (INACTIVE) unless the subscription
     * explicitly opted into auto_activate.
     */
    McpKnowledgeSyncLandingResult ingestPayload(long subscriptionId, String payload, String sourceUrl);
}
