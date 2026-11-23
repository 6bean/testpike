package com.sixbean.testpike.admin.program.api.dto;

/**
 * Program member view (one group-level grant row).
 * Mirrors the core {@code ProgramMemberInfo} contract.
 *
 * @param userId SSO user id (logical key)
 * @param role   1 = program member, 2 = program owner
 */
public record ProgramMemberView(
        Long userId,
        int role) {
}
