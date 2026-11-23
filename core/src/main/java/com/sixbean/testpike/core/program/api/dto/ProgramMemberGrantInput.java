package com.sixbean.testpike.core.program.api.dto;

/**
 * Group-level grant payload.
 *
 * @param userId SSO user id (logical key, no FK)
 * @param role   1 = program member, 2 = program owner
 */
public record ProgramMemberGrantInput(long userId, int role) {
}
