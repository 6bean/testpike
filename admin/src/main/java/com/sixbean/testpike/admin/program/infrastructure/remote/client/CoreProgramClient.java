package com.sixbean.testpike.admin.program.infrastructure.remote.client;

import com.sixbean.testpike.admin.program.api.dto.CreateProgramRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberAddRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberRoleRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberView;
import com.sixbean.testpike.admin.program.api.dto.ProgramView;
import com.sixbean.testpike.admin.program.api.dto.UpdateProgramRequest;
import com.sixbean.testpike.admin.program.api.dto.UserProgramView;

import java.util.List;

/**
 * core program service client (service layering: admin → HTTP → core
 * /internal/programs/**, same topology as {@code CoreProjectClient}).
 * <p>Whole-object pass-through rule: the request DTOs the controller receives are
 * forwarded verbatim — no per-field copying at the bridge (field-loss incident class).
 */
public interface CoreProgramClient {

    /** Create the lens; returns the new program id. */
    long createProgram(CreateProgramRequest request);

    /** Rename or re-describe a program. */
    void updateProgram(long id, UpdateProgramRequest request);

    /** Archive (state 0) or restore (state 1) a program. */
    void setProgramState(long id, int state);

    /** Delete the lens; grouping/grant rows vanish, projects untouched. */
    void deleteProgram(long id);

    /** Single program view. */
    ProgramView getProgram(long id);

    /** Every program (both states); the UI filters per user intent. */
    List<ProgramView> listPrograms();

    /**
     * Active programs visible to one user: member scope normally, everything for a
     * system manager (same flag as the project lens — single source, never re-derived).
     * Feeds the /users/me snapshot for the header switcher.
     */
    List<UserProgramView> listProgramsForUser(long userId, boolean systemManager);

    /** Attach an existing project to the lens. */
    void attachProject(long id, long projectId);

    /** Detach a project from the lens. */
    void detachProject(long id, long projectId);

    /** Project ids currently inside the lens. */
    List<Long> listProgramProjectIds(long id);

    /** Grant group membership (role 1 = member, 2 = owner). */
    void addMember(long id, ProgramMemberAddRequest request);

    /** Change the granted role of an existing grant. */
    void updateMemberRole(long id, long userId, ProgramMemberRoleRequest request);

    /** Revoke a group grant. */
    void removeMember(long id, long userId);

    /** Group grant rows of one program. */
    List<ProgramMemberView> listMembers(long id);
}
