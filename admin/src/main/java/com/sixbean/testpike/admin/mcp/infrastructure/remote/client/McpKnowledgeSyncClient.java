package com.sixbean.testpike.admin.mcp.infrastructure.remote.client;

import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncRoundView;
import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncUpsertRequest;
import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncView;
import com.sixbean.testpike.admin.project.api.dto.PageView;

/**
 * core knowledge sync ledger client (admin bridge to core
 * /internal/mcp-knowledge-syncs). The bridge forwards; every guard, the DOWN
 * probe gate and the failure typing stay single-sourced in the core service.
 */
public interface McpKnowledgeSyncClient {

    PageView<KnowledgeSyncView> list(long serverId, Long projectId, int page, int size);

    KnowledgeSyncView detail(long id);

    KnowledgeSyncView save(KnowledgeSyncUpsertRequest request);

    KnowledgeSyncView update(long id, KnowledgeSyncUpsertRequest request);

    void delete(long id);

    void pause(long id);

    void resume(long id);

    KnowledgeSyncRoundView runNow(long id);
}
