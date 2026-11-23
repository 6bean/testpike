package com.sixbean.testpike.core.skill.api.dto;

import java.time.LocalDateTime;

/**
 * Project-memory ledger row .
 * <p>One distilled or manually pinned project convention as the assembly sees it:
 * content plus traceability anchors, the injection state and hit accounting.
 * The ledger page renders these rows; hit_count is read-only observability.
 *
 * @param id entry id (tp_ai_ctx_memory.id)
 * @param projectId owning project (entries never cross projects)
 * @param content the convention text injected into assembly
 * @param sourceRef traceability anchors JSON ([{type,id}]; manual entries carry type=manual)
 * @param injectStatus 1 = active (injected), 0 = operator-disabled, 2 = distilled and awaiting confirmation, 3 = rejected through the ledger
 * @param hitCount times this entry took part in assembly (read-only)
 * @param createdAt creation timestamp
 * @param updatedAt last governance-action timestamp
 * @param draftId newest still-open proposal card anchored to this row
 * (tp_ai_chat_draft.id, status pending), or null when no
 * confirmation door route exists for it
 */
public record ProjectMemoryInfo(Long id, Long projectId, String content, String sourceRef,
                                int injectStatus, long hitCount,
                                LocalDateTime createdAt, LocalDateTime updatedAt,
                                Long draftId) {
}
