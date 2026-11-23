package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Single-source fixture-project boilerplate for suites converted away from
 * blanket TRUNCATE . The tracked-id teardown, the service
 * path create and the same-code self-heal were being copy-pasted per suite
 * and tripped the duplication gate; the mechanics live here only (R40).
 * Suites keep their own wiring (owner account id, name literals, method
 * names) and delegate the shapes below.
 */
public final class ProjectFixtureRig {

    private ProjectFixtureRig() {
    }

    /**
     * Create a fixture project through the service path and track its id
     * for the teardown sweep.
     */
    public static long create(JdbcTemplate jdbc, ProjectService projectService, long accountId,
            List<Long> tracked, String name, String code) {
        long id = projectService.create(accountId,
                new ProjectCreateInput(name, code, "zh-CN", "P1", null));
        tracked.add(id);
        return id;
    }

    /**
     * Same-code self-heal variant: drop a crashed run's leftover carrying
     * this fixed code first, so the unique key never collides with a stale
     * row, then create and track the fresh fixture project.
     */
    public static long createSelfHeal(JdbcTemplate jdbc, ProjectService projectService,
            long accountId, List<Long> tracked, String name, String code) {
        SharedDbFixtureSweep.byProject(jdbc, SharedDbFixtureSweep.projectIdByCode(jdbc, code));
        return create(jdbc, projectService, accountId, tracked, name, code);
    }

    /**
     * Self-heal create with the conventional display name derived from the
     * code (the converted suites all spell it the same way, so the wrapper
     * stays a single delegation line, R40).
     */
    public static long createSelfHealByCode(JdbcTemplate jdbc, ProjectService projectService,
            long accountId, List<Long> tracked, String code) {
        return createSelfHeal(jdbc, projectService, accountId, tracked, "项目" + code, code);
    }

    /**
     * Create a tracked project with a random unique code, for suites that
     * need no fixed-code self-heal — the tag-suffixed name/code pair was
     * being repeated per suite (R40 single source).
     */
    public static long createTracked(JdbcTemplate jdbc, ProjectService projectService,
            List<Long> tracked, String namePrefix) {
        String tag = java.util.UUID.randomUUID().toString().substring(0, 8);
        return create(jdbc, projectService, 1L, tracked, namePrefix + tag, tag);
    }

    /** Teardown: sweep only the rows reachable from tracked fixture projects. */
    public static void sweep(JdbcTemplate jdbc, List<Long> tracked) {
        SharedDbFixtureSweep.byProjectList(jdbc, tracked);
    }

    /**
     * Raw-row teardown for ledger-backed suites: delete the named ledger
     * tables' rows of every tracked project first (their domain cascades
     * are not modelled by ProjectService.purge), then drop the membership
     * and project rows. Table names come from call-site literals, never
     * from user input. Suites that duplicated this three-delete shape now
     * delegate here (R40).
     */
    public static void drainLedgerTables(JdbcTemplate jdbc, List<Long> tracked,
            String... ledgerTables) {
        for (Long id : tracked) {
            for (String table : ledgerTables) {
                jdbc.update("DELETE FROM " + table + " WHERE project_id = ?", id);
            }
            jdbc.update("DELETE FROM tp_user_project WHERE project_id = ?", id);
            jdbc.update("DELETE FROM tp_project WHERE id = ?", id);
        }
        tracked.clear();
    }

    /**
     * Service-path teardown: force-purge every tracked project through
     * {@code ProjectService.purge} so business cascades (asset refs, change
     * notices) run, then drop the tracked ids — the raw sweep above cannot
     * express those cascades, suites that rely on them use this shape.
     */
    public static void purgeViaService(ProjectService projectService, long operatorId,
            List<Long> tracked) {
        for (Long id : tracked) {
            projectService.purge(operatorId, id, true);
        }
        tracked.clear();
    }
}
