package com.sixbean.testpike.core.program.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import com.sixbean.testpike.core.program.domain.service.ProgramService;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProgramLensScope — the single point that narrows a list query to the projects one
 * caller may see through a program lens (lens membership ∩ caller visibility).
 *
 * <p>Rules locked here (consumed by every list endpoint that gained a programId filter):
 * no lens (id &lt;= 0) means no narrowing and returns null so callers skip the filter;
 * an archived lens exposes nothing; a caller whose data scope is "all projects" sees
 * every project inside the lens; everyone else sees only lens projects they reach
 * through a direct grant or a group grant (ProjectRoleExpansion, never a second copy).
 *
 * <p>Every operator here is a real tp_user row: the production path always carries a
 * session user, and RoleService.dataScopeOfUser legitimately refuses accounts that do
 * not exist — synthetic ids without a row would test that legacy contract, not the lens.
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class ProgramLensScopeTest {

    @Autowired
    private ProgramLensScope scope;

    @Autowired
    private ProgramService programService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeCreatedUsers() {
        for (Long id : createdUserIds) {
            jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id = ?", id);
            jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", id);
        }
        createdUserIds.clear();
    }

    @Test
    void noLens_returnsNullSoCallersSkipTheFilter() {
        assertNull(scope.visibleProjectIds(0, ProgramLensFixture.OPERATOR));
        assertNull(scope.visibleProjectIds(-7, ProgramLensFixture.OPERATOR));
    }

    @Test
    void archivedLens_exposesNothingToAnybody(ProgramLensFixture fixture) {
        long program = fixture.newProgram();
        long project = fixture.newProject();
        programService.attachProject(program, project, ProgramLensFixture.OPERATOR);
        programService.setState(program, 0, ProgramLensFixture.OPERATOR);

        assertEquals(Set.of(), scope.visibleProjectIds(program, managerUser()));
    }

    @Test
    void systemManager_seesEveryProjectInsideTheLens(ProgramLensFixture fixture) {
        long program = fixture.newProgram();
        long a = fixture.newProject();
        long b = fixture.newProject();
        long outside = fixture.newProject();
        programService.attachProject(program, a, ProgramLensFixture.OPERATOR);
        programService.attachProject(program, b, ProgramLensFixture.OPERATOR);

        Set<Long> got = scope.visibleProjectIds(program, managerUser());

        assertTrue(got.containsAll(List.of(a, b)));
        assertEquals(2, got.size(), "only the two lens projects, never the outside one");
    }

    @Test
    void memberUser_seesOnlyLensProjectsHeCanReach(ProgramLensFixture fixture) {
        long program = fixture.newProgram();
        long inLens = fixture.newProject();
        long reachableButOutside = fixture.newProject();
        programService.attachProject(program, inLens, ProgramLensFixture.OPERATOR);
        long user = realUser();
        fixture.direct(inLens, user, 1);
        fixture.direct(reachableButOutside, user, 2);

        assertEquals(Set.of(inLens), scope.visibleProjectIds(program, user));
    }

    @Test
    void groupGrantReachesLensProjectsThroughExpansion(ProgramLensFixture fixture) {
        long program = fixture.newProgram();
        long project = fixture.newProject();
        long user = realUser();
        fixture.grant(program, project, user, 1);

        assertEquals(Set.of(project), scope.visibleProjectIds(program, user));
    }

    @Test
    void userOutsideTheLens_getsAnEmptySetInsteadOfAnError(ProgramLensFixture fixture) {
        long program = fixture.newProgram();
        programService.attachProject(program, fixture.newProject(), ProgramLensFixture.OPERATOR);

        assertEquals(Set.of(), scope.visibleProjectIds(program, realUser()));
    }

    @Test
    void lensWithoutProjects_returnsEmptyEvenForTheManager(ProgramLensFixture fixture) {
        assertEquals(Set.of(), scope.visibleProjectIds(fixture.newProgram(), managerUser()));
    }

    /** Plain account with a real tp_user row and no role links (data scope defaults to 2). */
    private long realUser() {
        long id = insertUser("lens-u-");
        return id;
    }

    /** Real account whose only role has data scope 1 (all projects) — the manager branch. */
    private long managerUser() {
        long id = insertUser("lens-mgr-");
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_role WHERE code = 'admin'", Long.class);
        assertNotNull(roleId, "built-in admin role must be seeded (shipped baseline)");
        roleService.assignUserRoles(id, List.of(roleId));
        return id;
    }

    private long insertUser(String prefix) {
        String name = prefix + UUID.randomUUID().toString().substring(0, 8);
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status) "
                        + "VALUES (?, 'x', ?, 1, 1) RETURNING id",
                Long.class, name, name);
        assertNotNull(id);
        createdUserIds.add(id);
        return id;
    }
}
