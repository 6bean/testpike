package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import com.sixbean.testpike.core.project.domain.service.ProjectAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Project access checks consumed through the group-grant expansion single point.
 *
 * <p>Contract locked here (cluster Y lens decision): a program membership grants
 * project-member level access (role 1) via ProjectAccessService; program ownership
 * never upgrades to project ownership — write paths inside a project stay governed
 * by direct grants (H1 isolation untouched).
 *
 * <p>Real dev DB, no mocks; fixtures built through the shared ProgramLensFixture
 * (extension-supplied, auto-torn-down). Shipped tables are never truncated.
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class ProjectAccessGroupGrantTest {

    @Autowired
    private ProjectAccessService accessService;

    @Test
    void getMemberRole_expandsGroupGrantToMember(ProgramLensFixture fixture) {
        long project = fixture.newProject();
        long user = fixture.freshUser();
        fixture.grant(fixture.newProgram(), project, user, 1);

        assertEquals(1, accessService.getMemberRole(project, user),
                "group grant must surface as role 1 through the access single point");
    }

    @Test
    void requireProjectMember_allowsGroupGrantedUser(ProgramLensFixture fixture) {
        long project = fixture.newProject();
        long user = fixture.freshUser();
        fixture.grant(fixture.newProgram(), project, user, 1);

        accessService.requireProjectMember(project, user); // must not throw 403
    }

    @Test
    void requireProjectOwner_deniesProgramOwner(ProgramLensFixture fixture) {
        long project = fixture.newProject();
        long user = fixture.freshUser();
        fixture.grant(fixture.newProgram(), project, user, 2);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> accessService.requireProjectOwner(project, user));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(),
                "program owner must never reach project ownership (no silent write-path widening)");
    }

    @Test
    void getMemberRole_keepsDirectOwnerUnchanged(ProgramLensFixture fixture) {
        long project = fixture.newProject(); // creator gets the implicit owner row

        assertEquals(2, accessService.getMemberRole(project, ProgramLensFixture.OPERATOR),
                "direct owner grant must be mirrored unchanged through the expansion point");
    }
}
