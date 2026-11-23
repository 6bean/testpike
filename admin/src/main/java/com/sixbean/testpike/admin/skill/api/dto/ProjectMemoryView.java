package com.sixbean.testpike.admin.skill.api.dto;

import java.time.LocalDateTime;

/**
 * Ledger row shown by the project-memory tab (02 §2.16; mirrors the core
 * ProjectMemoryInfo field names one-to-one so cross-service deserialization
 * matches by name — a silently dropped field equals an empty ledger column).
 *
 * @param id           entry id (tp_ai_ctx_memory.id)
 * @param projectId    owning project (entries never cross projects)
 * @param content      the convention text injected into assembly
 * @param sourceRef    traceability anchors JSON ([{type,id}]; manual entries carry type=manual)
 * @param injectStatus 1 = active (injected), 0 = operator-disabled, 2 = distilled and awaiting confirmation, 3 = rejected through the ledger
 * @param hitCount     times this entry took part in assembly (read-only)
 * @param createdAt    creation timestamp
 * @param updatedAt    last governance-action timestamp
 * @param draftId      newest still-open proposal card anchored to this row
 *                     (null = no confirmation-door route shown for it)
 */
public record ProjectMemoryView(Long id, Long projectId, String content, String sourceRef,
                                int injectStatus, long hitCount,
                                LocalDateTime createdAt, LocalDateTime updatedAt,
                                Long draftId) {
}
