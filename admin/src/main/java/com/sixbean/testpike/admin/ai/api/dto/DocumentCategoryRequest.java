package com.sixbean.testpike.admin.ai.api.dto;

/**
 * Document category edit request (admin side, mirrors core
 * DocumentCategoryUpdate; PATCH /api/v1/ai/documents/{id}/category).
 * category = target value (required); revision = optimistic-lock token
 * read from the list/detail (missing=40001, stale=40901 naming the
 * current version).
 */
public record DocumentCategoryRequest(String category, Integer revision) {
}
