package com.sixbean.testpike.core.common.component.dedup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.RequirementDedupFixture;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deterministic L1 business-key spectrum of the shared duplicate gate for
 * the three non-requirement create surfaces named by the design registry:
 * case = case_no (globally unique), API contract = normalized method + path
 * over the project's parsed endpoints, mock rule = space + method + match
 * (path pattern and matcher payload). Each family proves the normalization
 * pierces the raw-compare blindness (case fold, whitespace and full-width
 * folding, query/trim path handling, reordered matcher payload) and that
 * scope isolation (project / space) and the factory default-off switch
 * short-circuit hold.
 * <p>Runs against the real dev database - no mocks. Projects come from the
 * single-source {@link RequirementDedupFixture} (factory tables are never
 * TRUNCATED); the entity rows this suite seeds are cleaned here strictly
 * by project scope before the fixture drops its own projects.
 */
@SpringBootTest(classes = CoreApplication.class)
class DedupGateBusinessKeyL1Test {

    @Autowired
    private DedupGate gate;
    @Autowired
    private ApplicationContext context;

    private RequirementDedupFixture fixture;
    private JdbcTemplate jdbc;
    private long projectA;
    private long projectB;

    @BeforeEach
    void seedProjects() {
        fixture = RequirementDedupFixture.fromContext(context, "bkgate", "业务键谱项目");
        projectA = fixture.projectA();
        projectB = fixture.projectB();
        jdbc = fixture.jdbc();
    }

    @AfterEach
    void cleanupEntitiesAndProjects() {
        jdbc.update("DELETE FROM tp_case_step WHERE case_id IN (SELECT id FROM tp_test_case WHERE project_id IN (?, ?))",
                projectA, projectB);
        jdbc.update("DELETE FROM tp_case_version WHERE case_id IN (SELECT id FROM tp_test_case WHERE project_id IN (?, ?))",
                projectA, projectB);
        jdbc.update("DELETE FROM tp_test_case WHERE project_id IN (?, ?)", projectA, projectB);
        jdbc.update("DELETE FROM tp_mock_stub WHERE space_id IN (SELECT id FROM tp_mock_space WHERE project_id IN (?, ?))",
                projectA, projectB);
        jdbc.update("DELETE FROM tp_mock_space WHERE project_id IN (?, ?)", projectA, projectB);
        jdbc.update("DELETE FROM tp_api_spec WHERE project_id IN (?, ?)", projectA, projectB);
        fixture.cleanup();
    }

    private long insertCase(String caseNo) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_test_case (case_no, project_id, title, source, created_by)"
                        + " VALUES (?, ?, '业务键谱用例', 3, 1) RETURNING id",
                Long.class, caseNo, projectA);
        long created = id != null ? id : -1;
        assertTrue(created > 0, "fixture case row must be created");
        return created;
    }

    private long insertSpec(long projectId, String name, String endpointsJson) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_api_spec (project_id, name, spec_format, spec_title, spec_text,"
                        + " content_sha256, spec_version, endpoints)"
                        + " VALUES (?, ?, 'openapi3-json', ?, '{}', ?, 1, ?::jsonb) RETURNING id",
                Long.class, projectId, name, name,
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                endpointsJson);
        long created = id != null ? id : -1;
        assertTrue(created > 0, "fixture spec row must be created");
        return created;
    }

    private long insertSpace(long projectId) {
        String key = "bkspace-" + UUID.randomUUID().toString().substring(0, 8);
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_mock_space (project_id, name, space_key) VALUES (?, ?, ?) RETURNING id",
                Long.class, projectId, key, key);
        long created = id != null ? id : -1;
        assertTrue(created > 0, "fixture mock space must be created");
        return created;
    }

    private long insertStub(long spaceId, String method, String pathPattern, String matchersJson) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_mock_stub (space_id, name, method, path_match_type, path_pattern,"
                        + " matchers_json, response_json)"
                        + " VALUES (?, '业务键谱桩', ?, 'exact', ?, ?::jsonb, '{}'::jsonb) RETURNING id",
                Long.class, spaceId, method, pathPattern, matchersJson);
        long created = id != null ? id : -1;
        assertTrue(created > 0, "fixture mock stub row must be created");
        return created;
    }

    private static String endpoint(String method, String path) {
        return "[{\"method\":\"" + method + "\",\"path\":\"" + path + "\"}]";
    }

    @Test
    void caseNoKeyPiercesWhitespaceAndFullWidthVariants() {
        long stored = insertCase("TC-BK-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase() + "-001");
        DedupVerdict exact = gate.evaluateCase(projectA, caseNoTail(stored).toLowerCase());
        assertEquals(DedupLevel.EXACT, exact.level());
        assertNotNull(exact.matched());
        assertEquals(stored, exact.matched().id());
        assertTrue(exact.blocksCreate());

        DedupVerdict spaced = gate.evaluateCase(projectA, " " + caseNoTail(stored) + "  ");
        assertEquals(DedupLevel.EXACT, spaced.level(), "padded raw form must fold to the same key");

        DedupVerdict fullWidth = gate.evaluateCase(projectA, caseNoTail(stored).replace("-", "－"));
        assertEquals(DedupLevel.EXACT, fullWidth.level(), "full-width hyphen folds via NFKC to the same key");
    }

    @Test
    void unknownCaseNoPassesQuietly() {
        DedupVerdict verdict = gate.evaluateCase(projectA, "TC-NOSUCH-" + UUID.randomUUID() + "-999");
        assertEquals(DedupLevel.PASS, verdict.level());
        assertFalse(verdict.blocksCreate());
    }

    @Test
    void endpointKeyFoldsMethodCaseAndTrimsPathNoise() {
        String path = "/orders/" + UUID.randomUUID().toString().substring(0, 8);
        long stored = insertSpec(projectA, "bk-order-spec", endpoint("GET", path));
        DedupVerdict upperVsLower = gate.evaluateApiEndpoint(projectA, "get", path);
        assertEquals(DedupLevel.EXACT, upperVsLower.level());
        assertEquals(stored, upperVsLower.matched().id(), "method case fold must hit the stored endpoint");

        DedupVerdict trailingSlash = gate.evaluateApiEndpoint(projectA, "GET", path + "/");
        assertEquals(DedupLevel.EXACT, trailingSlash.level(), "trailing slash noise must not defeat the key");

        DedupVerdict queryNoise = gate.evaluateApiEndpoint(projectA, "GET", path + "?page=1");
        assertEquals(DedupLevel.EXACT, queryNoise.level(), "query string must be dropped before keying");

        DedupVerdict otherProject = gate.evaluateApiEndpoint(projectB, "GET", path);
        assertEquals(DedupLevel.PASS, otherProject.level(), "endpoint keys never cross projects");
    }

    @Test
    void endpointAlreadyOwnedByTheSameSpecNameIsNotADuplicate() {
        String path = "/cart/" + UUID.randomUUID().toString().substring(0, 8);
        insertSpec(projectA, "bk-cart-spec", endpoint("POST", path));
        // Version-chain posture: re-uploading the same contract name that owns
        // the endpoint is a new version, not a duplicate create.
        DedupVerdict sameName = gate.evaluateApiEndpoint(projectA, "post", path, "bk-cart-spec");
        assertEquals(DedupLevel.PASS, sameName.level());

        // The same endpoint under a different contract name is a duplicate definition.
        DedupVerdict otherName = gate.evaluateApiEndpoint(projectA, "POST", path, "bk-other-spec");
        assertEquals(DedupLevel.EXACT, otherName.level());
        assertTrue(otherName.blocksCreate());
    }

    @Test
    void stubKeyIsSpaceScopedAndPiercesMethodAndMatcherNoise() {
        long spaceA = insertSpace(projectA);
        long spaceB = insertSpace(projectA);
        String path = "/pay/" + UUID.randomUUID().toString().substring(0, 8);
        long stored = insertStub(spaceA, "POST", path, "[{\"field\":\"amount\",\"op\":\"eq\",\"value\":\"10\"}]");

        DedupVerdict methodCase = gate.evaluateMockStub(spaceA, "post", path,
                "[{\"field\":\"amount\",\"op\":\"eq\",\"value\":\"10\"}]");
        assertEquals(DedupLevel.EXACT, methodCase.level());
        assertEquals(stored, methodCase.matched().id(), "method case fold must hit the stored rule");

        DedupVerdict reorderedMatchers = gate.evaluateMockStub(spaceA, "POST", path,
                "[{\"value\":\"10\",\"op\":\"eq\",\"field\":\"amount\"}]");
        assertEquals(DedupLevel.EXACT, reorderedMatchers.level(), "matcher payload key order must not defeat L1");

        DedupVerdict otherSpace = gate.evaluateMockStub(spaceB, "POST", path,
                "[{\"field\":\"amount\",\"op\":\"eq\",\"value\":\"10\"}]");
        assertEquals(DedupLevel.PASS, otherSpace.level(), "stub keys never cross mock spaces");

        DedupVerdict differentMatcher = gate.evaluateMockStub(spaceA, "POST", path,
                "[{\"field\":\"amount\",\"op\":\"eq\",\"value\":\"20\"}]");
        assertEquals(DedupLevel.PASS, differentMatcher.level(), "a different matcher payload is a different rule");
    }

    @Test
    void disabledSwitchShortCircuitsAllThreeKeys() {
        insertCase("TC-OFF-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase() + "-001");
        String path = "/off/" + UUID.randomUUID().toString().substring(0, 8);
        insertSpec(projectA, "bk-off-spec", endpoint("GET", path));
        long space = insertSpace(projectA);
        insertStub(space, "GET", path, "[]");

        fixture.pinEnabled(false);
        assertEquals(DedupLevel.PASS, gate.evaluateCase(projectA, "TC-OFF-ANY").level());
        assertEquals(DedupLevel.PASS, gate.evaluateApiEndpoint(projectA, "GET", path).level());
        assertEquals(DedupLevel.PASS, gate.evaluateMockStub(space, "GET", path, "[]").level());
    }

    /** Tail segment (project code + serial) of a stored case_no like TC-{code}-{seq}. */
    private String caseNoTail(long caseId) {
        String caseNo = jdbc.queryForObject("SELECT case_no FROM tp_test_case WHERE id = ?", String.class, caseId);
        assertNotNull(caseNo);
        return caseNo;
    }
}
