package com.sixbean.testpike.core.program.api.dto;

/**
 * Role-change payload for an existing group grant (1 = member, 2 = owner).
 */
public record ProgramMemberRoleInput(int role) {
}
