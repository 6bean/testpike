package com.sixbean.testpike.core.document.api.dto;

/**
 * Field-level category edit request ( package 2, PATCH
 * /internal/documents/{id}/category body). category = target document
 * category (required; off-list values are refused with a readable 400
 * naming the value set); revision = the optimistic-lock token the caller
 * read (null = 40001, stale = 40901 naming the current version).
 */
public record DocumentCategoryUpdate(String category, Integer revision) {
}
