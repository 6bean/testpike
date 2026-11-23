package com.sixbean.testpike.admin.program.api.dto;

/**
 * Group-grant request body. Mirrors core {@code ProgramMemberGrantInput}.
 *
 * @param userId SSO user id (logical key, no FK)
 * @param role   1 = program member, 2 = program owner
 */
public record ProgramMemberAddRequest(long userId, int role) {
}
