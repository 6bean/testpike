package com.sixbean.testpike.admin.program.api.dto;

/**
 * Role-change request for an existing group grant. Mirrors core
 * {@code ProgramMemberRoleInput}.
 *
 * @param role 1 = program member, 2 = program owner
 */
public record ProgramMemberRoleRequest(int role) {
}
