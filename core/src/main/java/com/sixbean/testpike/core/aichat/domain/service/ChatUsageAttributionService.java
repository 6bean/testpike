package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.ChatSessionUsagePageView;

/**
 * Session-attribution read model for the cost dashboard (E9, AO batch
 * phase 3): which conversations consumed tokens inside the asked natural
 * month, with the window sum recomputed from the message rows so the
 * dashboard's reconciliation never depends on a single write path agreeing
 * with itself. Read-only — the ledger itself is written at the done-frame
 * point (see ChatSessionUsageAccumulationTest), no second write door exists.
 */
public interface ChatUsageAttributionService {

    /**
 * Paged attribution rows for one natural month.
 *
 * @param month yyyy-MM key (UTC+8 month cut, same wording as the cost doors);
 * any other shape is refused fail-closed with a named reason
 * @param projectId restrict to one project segment; null = across all projects
 * @param page 1-based page
 * @param pageSize 1..100 rows per page
 */
    ChatSessionUsagePageView usageAttribution(String month, Long projectId, int page, int pageSize);
}
