package com.sixbean.testpike.admin.skill.api.dto;

/**
 * Manual ledger entry request (02 §2.16 POST /api/v1/project-memory; the same
 * shape as the core ProjectMemoryCreateInput). A manual entry is created
 * directly effective (fixed injection) — blank content and unknown projects
 * are refused single-source in core, the bridge only carries the payload.
 *
 * @param projectId target project segment (required, ledger is project-scoped)
 * @param content convention text to pin into assembly
 */
public record ProjectMemoryCreateRequest(Long projectId, String content) {
}
