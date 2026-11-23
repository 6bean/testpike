package com.sixbean.testpike.core.program.domain.service;

import com.sixbean.testpike.core.program.api.dto.ProgramMemberInfo;

import java.util.List;

/**
 * Group-level grants: one membership row covers every project inside the program once
 * expanded by {@link com.sixbean.testpike.core.program.domain.component.ProjectRoleExpansion}.
 *
 * <p>Every mutation writes one audit row on the central channel (object_type
 * {@code program_grant}) — revocable grants must be accountable, same duty as direct
 * project membership.
 */
public interface ProgramMemberService {

    /** Grant membership: role 1 = member, 2 = program owner. */
    void add(long programId, long userId, int role, long operatorId);

    /** Change the granted role (1 or 2). */
    void updateRole(long programId, long userId, int role, long operatorId);

    /** Revoke the grant; expanded access disappears on the next evaluation (no cache). */
    void remove(long programId, long userId, long operatorId);

    List<ProgramMemberInfo> list(long programId);
}
