package com.sixbean.testpike.core.program.domain.service.impl;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import com.sixbean.testpike.core.program.api.dto.UserProgramInfo;
import com.sixbean.testpike.core.program.domain.service.ProgramService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-user program lens query feeding the /users/me snapshot (header two-level switcher).
 * Visibility rule: a normal user sees active programs they are a member of;
 * a system manager sees every active program. Archived programs never appear.
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class ProgramUserLensQueryTest {

    @Autowired
    private ProgramService programService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCheck() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'tp_program'",
                Integer.class);
        assertEquals(1, n, "tp_program must exist: apply V340__program_lens.sql to the dev DB");
    }

    @Test
    void listForUser_returnsOnlyJoinedActiveProgramsWithTheirProjects(ProgramLensFixture fixture) {
        long user = fixture.freshUser();
        long joined = fixture.newProgram();
        long other = fixture.newProgram();
        long projectA = fixture.newProject();
        long projectB = fixture.newProject();
        fixture.grant(joined, projectA, user, 1);
        // second project joins the same lens without re-granting the same member
        programService.attachProject(joined, projectB, ProgramLensFixture.OPERATOR);

        List<UserProgramInfo> got = programService.listForUser(user, false);

        assertEquals(1, got.size(), "only the program the user belongs to is visible");
        UserProgramInfo info = got.get(0);
        assertEquals(joined, info.programId());
        List<Long> projectIds = info.projectIds();
        assertEquals(2, projectIds.size(), "both attached projects of the lens are returned");
        assertTrue(projectIds.contains(projectA) && projectIds.contains(projectB));
        assertTrue(other > 0, "unused program id kept for readability");
    }

    @Test
    void listForUser_hidesArchivedPrograms(ProgramLensFixture fixture) {
        long user = fixture.freshUser();
        long program = fixture.newProgram();
        fixture.grant(program, fixture.newProject(), user, 1);
        programService.setState(program, 0, ProgramLensFixture.OPERATOR);

        assertTrue(programService.listForUser(user, false).isEmpty(),
                "archived lens must not reach the header switcher");
    }

    @Test
    void listForUser_systemManagerSeesEveryActiveProgram(ProgramLensFixture fixture) {
        long manager = fixture.freshUser();
        long active = fixture.newProgram();
        long archived = fixture.newProgram();
        programService.setState(archived, 0, ProgramLensFixture.OPERATOR);

        List<UserProgramInfo> got = programService.listForUser(manager, true);

        assertTrue(got.stream().anyMatch(p -> p.programId() == active),
                "a system manager sees active programs without membership");
        assertTrue(got.stream().noneMatch(p -> p.programId() == archived),
                "even managers never see archived programs");
    }

    @Test
    void listForUser_programWithoutProjectsReturnsEmptyProjectList(ProgramLensFixture fixture) {
        long user = fixture.freshUser();
        long emptyProgram = fixture.newProgram();
        long attached = fixture.newProject();
        fixture.grant(emptyProgram, attached, user, 1);
        programService.detachProject(emptyProgram, attached, ProgramLensFixture.OPERATOR);

        List<UserProgramInfo> got = programService.listForUser(user, false);

        assertEquals(1, got.size());
        assertTrue(got.get(0).projectIds().isEmpty(), "no projects -> empty list, never null");
    }
}
