package com.sixbean.testpike.core.common.api.dto;

/**
 * Deletion protection reference details: plain-language count for a single reference type.
 *
 * @param type Reference type identifier (env/case/plan/member/batch/deviation/user/tag).
 * @param label Reference type display name (environment/case/execution plan/member/execution batch/deviation record/user/tag).
 * @param count Reference count.
 * @param message Plain-language single-item message (e.g., "2 environments").
 */
public record DeleteRefInfo(String type, String label, long count, String message) {
}
