package com.sixbean.testpike.core.cases.api.dto;

/**
 * Automatic allocation per-person dispatch rows (list rows in the preview confirmation dialog showing N items -> M users x X items per user).
 *
 * @param userId ID of the assigned member
 * @param name Display name (display_name fallback username)
 * @param count Number of items this member will or has accepted in this operation
 */
public record AutoAssignAssigneeRow(long userId, String name, int count) {
}
