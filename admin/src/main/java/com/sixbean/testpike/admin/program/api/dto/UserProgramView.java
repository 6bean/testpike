package com.sixbean.testpike.admin.program.api.dto;

import java.util.List;

/**
 * One program as seen from a single user (admin mirror of the core
 * /internal/programs/for-user row). Carried inside the /users/me snapshot so the
 * frontend header renders the program → project switcher without extra remote calls.
 *
 * @param programId  the program lens id
 * @param name       display name of the program
 * @param projectIds active projects currently inside the lens (empty when nothing attached)
 */
public record UserProgramView(long programId, String name, List<Long> projectIds) {
}
