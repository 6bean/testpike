package com.sixbean.testpike.core.program.api.dto;

/**
 * Program member api view (group-level grant row).
 *
 * @param userId SSO user id (logical key)
 * @param role   1 = program member, 2 = program owner
 */
public record ProgramMemberInfo(
        Long userId,
        int role) {
}
