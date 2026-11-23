package com.sixbean.testpike.core.skill.api.dto;

/**
 * Manual memory entry creation : promotes the dialog
 * "remember this fact" capability into a ledger-managed fixed injection.
 * The row lands on the SAME assembly read point as distilled entries
 * (SkillConsumptionService memory branch) — never a second injection chain.
 *
 * @param projectId owning project
 * @param content the convention text; blank is rejected with 40001
 */
public record ProjectMemoryCreateInput(Long projectId, String content) {
}
