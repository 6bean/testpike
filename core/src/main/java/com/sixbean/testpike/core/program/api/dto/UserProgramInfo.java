package com.sixbean.testpike.core.program.api.dto;

import java.util.List;

/**
 * One program as seen from a single user: the lens row itself plus the projects
 * currently inside it. Feeds the /users/me snapshot so the header can render the
 * program -> project switcher without any further remote calls.
 *
 * @param programId   the lens id
 * @param name        display name of the program
 * @param projectIds  active membership of the lens (empty list when nothing is attached)
 */
public record UserProgramInfo(
        long programId,
        String name,
        List<Long> projectIds) {
}
