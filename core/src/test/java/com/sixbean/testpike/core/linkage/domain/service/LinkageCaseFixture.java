package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.aichat.domain.component.ChatLedgerPurgeSupport;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared seed/purge fixture for the linkage test family (single source, R40).
 * <p>Every linkage scenario test needs the same upstream object (a case with a
 * script under its own project) and the same bottom-up cleanup once the ledger
 * rows it minted are checked. The duplication gate flagged the copy family the
 * moment a third test joined it, so the seed/purge reads now live here once;
 * tests that need an intermediate row (an operator user, extra impacts) call
 * the finer-grained steps and interleave their own inserts.
 */
public final class LinkageCaseFixture {

    private LinkageCaseFixture() {
    }

    /** Create a project row and return its id. */
    public static long insertProject(JdbcTemplate jdbc, String name, String code) {
        return jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, name, code);
    }

    /** Create one case under the project and return its id. */
    public static long seedCaseUnderProject(JdbcTemplate jdbc, CaseService caseService,
            long projectId, long operatorId, String caseTitle) {
        CaseInfo created = caseService.create(operatorId, new CaseCreateInput(
                projectId, caseTitle, "前置", "预期", 1, 3, "web", 1, null, 1, null, null));
        return created.id();
    }

    /** Attach a version-4 empty-steps script to the case and return its id. */
    public static long seedScriptForCase(JdbcTemplate jdbc, long caseId) {
        return jdbc.queryForObject(
                "INSERT INTO tp_script (case_id, status, action_json, version)"
                        + " VALUES (?, 1, '{\"steps\":[]}'::jsonb, 4) RETURNING id",
                Long.class, caseId);
    }

    /** One call for the common shape: project -> case -> script. */
    public static LinkageCaseChain seedProjectCaseWithScript(
            JdbcTemplate jdbc, CaseService caseService,
            String projectName, String projectCode, long operatorId, String caseTitle) {
        long projectId = insertProject(jdbc, projectName, projectCode);
        long caseId = seedCaseUnderProject(jdbc, caseService, projectId, operatorId, caseTitle);
        long scriptId = seedScriptForCase(jdbc, caseId);
        return new LinkageCaseChain(projectId, caseId, scriptId);
    }

    /** Remove draft cards, proposals and impacts hung under one upstream object. */
    public static void purgeUpstreamChain(
            JdbcTemplate jdbc, String upstreamType, long upstreamId) {
        jdbc.update("DELETE FROM tp_ai_chat_draft WHERE context_json->>'linkageImpactId' IN"
                + " (SELECT CAST(id AS TEXT) FROM tp_linkage_impact"
                + "  WHERE upstream_object_type = ? AND upstream_object_id = ?)",
                upstreamType, upstreamId);
        jdbc.update("DELETE FROM tp_linkage_proposal WHERE impact_id IN"
                + " (SELECT id FROM tp_linkage_impact"
                + "  WHERE upstream_object_type = ? AND upstream_object_id = ?)",
                upstreamType, upstreamId);
        jdbc.update("DELETE FROM tp_linkage_impact WHERE upstream_object_type = ?"
                + " AND upstream_object_id = ?", upstreamType, upstreamId);
    }

    /** Remove the seeded case chain bottom-up (script, steps, case, chat ledger, project). */
    public static void purgeCaseChain(JdbcTemplate jdbc, LinkageCaseChain chain) {
        jdbc.update("DELETE FROM tp_script WHERE id = ?", chain.scriptId());
        jdbc.update("DELETE FROM tp_case_step WHERE case_id = ?", chain.caseId());
        jdbc.update("DELETE FROM tp_test_case WHERE id = ?", chain.caseId());
        // The seeded project also owns any card and session the scenario minted
        // (linkage proposals land as project-anchored draft rows). Dropping the
        // project row without them is exactly how the orphan ledger grew, so
        // the same single-sourced cascade the product purge uses runs here too.
        ChatLedgerPurgeSupport.purge(jdbc, chain.projectId());
        jdbc.update("DELETE FROM tp_project WHERE id = ?", chain.projectId());
    }
}
