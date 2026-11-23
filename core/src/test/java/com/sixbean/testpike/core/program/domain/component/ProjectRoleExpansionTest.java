package com.sixbean.testpike.core.program.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import com.sixbean.testpike.core.program.domain.service.ProgramMemberService;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project-set role expansion test (lens foundation): effective project role =
 * max(direct grant, 1 if the user sits in any active program that includes the project).
 *
 * <p>Fixed semantics decided during reconnaissance:
 * <ul>
 *   <li>program membership expands to project role 1 (member) at most;</li>
 *   <li>program owner (role 2) never upgrades to project owner — write paths inside a
 *       project stay governed by direct grants, H1 isolation untouched;</li>
 *   <li>expanded project ids = direct union group-granted (member switcher source).</li>
 * </ul>
 *
 * <p>Real dev DB, no mocks (red line: services and request chains must not be mocked).
 * Fixture rows come from the shared ProgramLensFixture (extension-supplied and
 * auto-torn-down); shipped tables are never truncated (baseline duty).
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class ProjectRoleExpansionTest {

    @Autowired
    private ProjectRoleExpansion expansion;

    @Autowired
    private ProgramMemberService programMemberService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCheck() {
        // tables must exist before any case runs (migration applied to the dev DB)
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'tp_program'",
                Integer.class);
        assertEquals(1, n, "tp_program must exist: apply db/migration/V340__program_lens.sql to the dev DB");
    }

    @Test
    void effectiveRole_withoutAnyGrant_isZero(ProgramLensFixture fixture) {
        long project = fixture.newProject();
        assertEquals(0, expansion.effectiveRole(project, fixture.freshUser()));
    }

    @Test
    void effectiveRole_mirrorsDirectGrantLevels(ProgramLensFixture fixture) {
        long project = fixture.newProject();
        long member = fixture.freshUser();
        // creator got the implicit owner row through ProjectService.create
        assertEquals(2, expansion.effectiveRole(project, ProgramLensFixture.OPERATOR),
                "direct owner grant must be mirrored unchanged");
        memberService.add(project, member, 1);
        assertEquals(1, expansion.effectiveRole(project, member));
    }

    @Test
    void effectiveRole_programMembershipExpandsToProjectMember(ProgramLensFixture fixture) {
        long project = fixture.newProject();
        long user = fixture.freshUser();
        fixture.grant(fixture.newProgram(), project, user, 1);

        assertEquals(1, expansion.effectiveRole(project, user),
                "group grant must expand to project role 1 with no direct row");
    }

    @Test
    void effectiveRole_programOwnerNeverUpgradesToProjectOwner(ProgramLensFixture fixture) {
        long project = fixture.newProject();
        long user = fixture.freshUser();
        fixture.grant(fixture.newProgram(), project, user, 2);

        assertEquals(1, expansion.effectiveRole(project, user),
                "program owner expands to member level only — project owner stays a direct-grant path");
    }

    @Test
    void effectiveRole_takesTheHigherOfDirectAndExpanded(ProgramLensFixture fixture) {
        long project = fixture.newProject();
        long user = fixture.freshUser();
        fixture.grant(fixture.newProgram(), project, user, 2);

        memberService.add(project, user, 1);
        assertEquals(1, expansion.effectiveRole(project, user), "direct 1 vs expanded 1 -> 1");

        memberService.updateRole(project, user, 2);
        assertEquals(2, expansion.effectiveRole(project, user), "direct 2 vs expanded 1 -> 2");
    }

    @Test
    void expandedProjectIds_unionsDirectAndGroupGrants(ProgramLensFixture fixture) {
        long directProject = fixture.newProject();
        long groupProject = fixture.newProject();
        long foreignProject = fixture.newProject();
        long user = fixture.freshUser();

        fixture.direct(directProject, user, 1);
        fixture.grant(fixture.newProgram(), groupProject, user, 1);

        Set<Long> ids = expansion.expandedProjectIds(user);
        assertTrue(ids.contains(directProject), "direct grant must be in the expanded set");
        assertTrue(ids.contains(groupProject), "group grant must be expanded into the set");
        assertEquals(false, ids.contains(foreignProject), "ungranted project must stay out (H1 holds)");
    }

    @Test
    void groupGrantAudit_reusesTheCentralAuditChannel(ProgramLensFixture fixture) {
        long program = fixture.newProgram();
        long user = fixture.freshUser();
        programMemberService.add(program, user, 1, ProgramLensFixture.OPERATOR);

        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'program_grant' AND object_id = ?",
                Integer.class, String.valueOf(program));
        assertEquals(1, n, "grant must land one audit row on the existing channel (no side table)");
    }
}
