package com.sixbean.testpike.core.member.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.member.api.dto.UserProjectInfo;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Project switcher visibility must include group grants (users/me snapshot source).
 *
 * <p>Without this union a program-only member disappears from the project switcher
 * even though the access layer admits them into the project — same visibility family
 * as the system-asset default-visibility rule: grants must be reachable, not hidden.
 *
 * <p>Real dev DB, no mocks; fixture via the shared extension; no shipped table is
 * truncated.
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class UserProjectVisibilityGroupGrantTest {

    @Autowired
    private UserProjectQueryService queryService;

    @Test
    void findByUserId_includesGroupGrantedProjectAtMemberRole(ProgramLensFixture fixture) {
        long directProject = fixture.newProject();
        long groupProject = fixture.newProject();
        long foreignProject = fixture.newProject();
        long user = fixture.freshUser();

        fixture.direct(directProject, user, 1);
        fixture.grant(fixture.newProgram(), groupProject, user, 1);

        List<UserProjectInfo> rows = queryService.findByUserId(user);
        UserProjectInfo groupRow = rowOf(rows, groupProject);
        assertEquals(1, groupRow.role(), "group-only project must read as member level");
        assertEquals("成员", groupRow.roleLabel());
        assertNotNull(rowOf(rows, directProject), "direct grant row must stay present");
        assertEquals(0, rows.stream().filter(r -> r.projectId().equals(foreignProject)).count(),
                "ungranted project must stay out of the visible set (H1 holds)");
    }

    @Test
    void findByUserId_keepsSingleRowWithDirectOwnerRole(ProgramLensFixture fixture) {
        long project = fixture.newProject();
        long user = fixture.freshUser();
        fixture.direct(project, user, 2);
        fixture.grant(fixture.newProgram(), project, user, 1);

        List<UserProjectInfo> rows = queryService.findByUserId(user);
        List<UserProjectInfo> forProject =
                rows.stream().filter(r -> r.projectId().equals(project)).toList();
        assertEquals(1, forProject.size(), "union must not duplicate the project row");
        assertEquals(2, forProject.get(0).role(), "direct owner role wins over group membership");
    }

    private static UserProjectInfo rowOf(List<UserProjectInfo> rows, long projectId) {
        return rows.stream()
                .filter(r -> r.projectId().equals(projectId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "project " + projectId + " missing from visible list " + rows));
    }
}
