package com.sixbean.testpike.admin.ai.api.dto;

/**
 * Per-document usage switches edit request (admin side, mirrors core
 * DocumentUsageSwitchesUpdate; PATCH
 * /api/v1/ai/documents/{id}/usage-switches). All three switches carry
 * their intended values (a missing field is refused with a readable
 * 40001 naming it); revision = optimistic-lock token read first.
 */
public record DocumentUsageSwitchesRequest(Boolean useForExtraction, Boolean useInCasePrompt,
                                           Boolean useInGrounding, Integer revision) {
}
