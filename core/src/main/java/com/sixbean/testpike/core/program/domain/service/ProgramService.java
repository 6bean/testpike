package com.sixbean.testpike.core.program.domain.service;

import com.sixbean.testpike.core.program.api.dto.ProgramInfo;
import com.sixbean.testpike.core.program.api.dto.UserProgramInfo;

import java.util.List;

/**
 * Program (project set) lifecycle: the lens over existing projects.
 *
 * <p>A program never owns business data — attaching a project only records membership,
 * detaching or deleting a program never touches project rows (H1 isolation untouched).
 */
public interface ProgramService {

    /** Create a program and return its id. Duplicate names are rejected. */
    long create(String name, String description, long operatorId);

    /** Rename or re-describe a program. */
    void update(long programId, String name, String description, long operatorId);

    /** Archive (state 0) or restore (state 1). Archived programs stop expanding access. */
    void setState(long programId, int state, long operatorId);

    /** Delete the lens: removes the program plus its membership/grant rows only. */
    void delete(long programId, long operatorId);

    ProgramInfo get(long programId);

    List<ProgramInfo> listAll();

    /** Attach an existing project to the program (idempotent re-attach is rejected as duplicate). */
    void attachProject(long programId, long projectId, long operatorId);

    /** Detach a project from the program; the project itself is untouched. */
    void detachProject(long programId, long projectId, long operatorId);

    /** Project ids currently inside the lens. */
    List<Long> listProjectIds(long programId);

    /**
     * Programs visible to one user for the /users/me snapshot: a normal user sees the
     * active lenses they are a member of; a system manager sees every active lens.
     * Archived lenses never appear (same rule as access expansion).
     */
    List<UserProgramInfo> listForUser(long userId, boolean systemManager);
}
