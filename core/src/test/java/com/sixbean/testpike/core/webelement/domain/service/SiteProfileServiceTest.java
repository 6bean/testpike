package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileInput;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Site profile CRUD doors : the scope prefix is derived on the
 * server from the start URL (the client value is never trusted), a duplicate
 * name inside one project refuses with the name called out, an unchanged save
 * writes nothing at all (revision frozen, updated_at frozen - the R28 empty-
 * save posture), a stale revision refuses the write, and deleting a profile
 * keeps its run ledger rows and collected elements (adjudication D7).
 * <p>Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class SiteProfileServiceTest {

    @Autowired
    private SiteProfileService siteProfileService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L,
                fixtureProjectIds, "站点档案项目" + code, code);
    }

    @AfterEach
    void cleanUp() {
        for (Long projectId : fixtureProjectIds) {
            jdbcTemplate.update("DELETE FROM tp_site_survey_run WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_site_profile WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
        fixtureProjectIds.clear();
    }

    private SiteProfileInput input(long projectId, String name, String startUrl) {
        return new SiteProfileInput(projectId, name, startUrl,
                null, null, null, null, null, null, null);
    }

    @Test
    void createDerivesScopeAndAppliesStoredDefaults() {
        long projectId = createProject();
        SiteProfileView view = siteProfileService.create(input(projectId, "商城前台", "https://xx.com/pr"));

        assertEquals("/pr/", view.scopePrefix(), "scope must be server-derived and segment aligned");
        assertNotNull(view.id());
        assertEquals(0, view.revision());
        assertTrue(view.ignoreParams().contains("pageNum"),
                "ignored params fall back to the stored preset list, got " + view.ignoreParams());
        assertTrue(view.verbsGuardEnabled());
        assertTrue(view.enabled());
        assertEquals(Map.of(), view.budgetOverrides());
        // A client-supplied scope value never reaches storage; the column only
        // ever carries what the derivation produced for this start URL.
        String storedScope = jdbcTemplate.queryForObject(
                "SELECT scope_prefix FROM tp_site_profile WHERE id = ?", String.class, view.id());
        assertEquals("/pr/", storedScope);
    }

    @Test
    void duplicateNameInsideOneProjectRefusesCallingOutTheName() {
        long projectId = createProject();
        siteProfileService.create(input(projectId, "唯一名", "https://xx.com/pr/"));

        ServiceException dup = assertThrows(ServiceException.class,
                () -> siteProfileService.create(input(projectId, "唯一名", "https://xx.com/md/")));
        assertTrue(dup.getMessage().contains("唯一名"),
                "refusal must name the colliding profile, got: " + dup.getMessage());

        // The same name in another project is a separate site and must pass.
        long other = createProject();
        SiteProfileView twin = siteProfileService.create(input(other, "唯一名", "https://xx.com/md/"));
        assertEquals("/md/", twin.scopePrefix());
    }

    @Test
    void unchangedSaveWritesNothing() {
        long projectId = createProject();
        SiteProfileView created = siteProfileService.create(input(projectId, "不变档案", "https://xx.com/pr/"));

        Timestamp before = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM tp_site_profile WHERE id = ?", Timestamp.class, created.id());

        SiteProfileInput same = new SiteProfileInput(projectId, "不变档案", "https://xx.com/pr/",
                created.excludeGlobs(), created.ignoreParams(), created.verbsGuardEnabled(),
                created.budgetOverrides(), created.loginStateName(), created.enabled(),
                created.revision());
        SiteProfileView after = siteProfileService.update(created.id(), same);

        assertEquals(created.revision(), after.revision(), "revision must stay frozen on a no-change save");
        Timestamp storedAfter = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM tp_site_profile WHERE id = ?", Timestamp.class, created.id());
        assertEquals(before, storedAfter, "updated_at must not move when nothing changed");
    }

    @Test
    void staleRevisionRefusesTheWrite() {
        long projectId = createProject();
        SiteProfileView created = siteProfileService.create(input(projectId, "并发档案", "https://xx.com/pr/"));

        SiteProfileInput edited = new SiteProfileInput(projectId, "并发档案", "https://xx.com/pr/",
                List.of("/pr/logout*"), null, false, Map.of("max-pages", 20), null, true,
                created.revision() + 5);
        assertThrows(ServiceException.class, () -> siteProfileService.update(created.id(), edited));

        // The row is untouched by the refused write.
        assertEquals(0, siteProfileService.get(created.id()).revision());
    }

    @Test
    void realUpdateBumpsRevisionAndReturnsNewValues() {
        long projectId = createProject();
        SiteProfileView created = siteProfileService.create(input(projectId, "改档", "https://xx.com/pr/"));

        SiteProfileInput edited = new SiteProfileInput(projectId, "改档改名", "https://xx.com/md/",
                List.of("/md/export*"), List.of("page"), false, Map.of("max-depth", 3),
                "默认登录态", false, created.revision());
        SiteProfileView updated = siteProfileService.update(created.id(), edited);

        assertEquals("改档改名", updated.name());
        assertEquals("/md/", updated.scopePrefix());
        assertEquals(List.of("/md/export*"), updated.excludeGlobs());
        assertEquals(List.of("page"), updated.ignoreParams());
        assertEquals(false, updated.verbsGuardEnabled());
        assertEquals(Map.of("max-depth", 3), updated.budgetOverrides());
        assertEquals("默认登录态", updated.loginStateName());
        assertEquals(false, updated.enabled());
        assertEquals(created.revision() + 1, updated.revision());
    }

    @Test
    void deleteKeepsRunLedgerRows() {
        long projectId = createProject();
        SiteProfileView created = siteProfileService.create(input(projectId, "被删档案", "https://xx.com/pr/"));
        jdbcTemplate.update("INSERT INTO tp_site_survey_run (profile_id, project_id, status) VALUES (?, ?, 'completed')",
                created.id(), projectId);

        siteProfileService.delete(created.id());

        Integer profiles = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_site_profile WHERE id = ?", Integer.class, created.id());
        assertEquals(0, profiles);
        Integer runs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_site_survey_run WHERE profile_id = ?", Integer.class, created.id());
        assertEquals(1, runs, "run history must outlive the profile row (adjudication D7)");
    }

    @Test
    void listReturnsProjectProfilesNewestFirst() {
        long projectId = createProject();
        SiteProfileView first = siteProfileService.create(input(projectId, "先建", "https://xx.com/pr/"));
        SiteProfileView second = siteProfileService.create(input(projectId, "后建", "https://xx.com/md/"));

        List<SiteProfileView> views = siteProfileService.list(projectId);
        assertEquals(2, views.size());
        assertEquals(second.id(), views.get(0).id());
        assertEquals(first.id(), views.get(1).id());
    }
}
