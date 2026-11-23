package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockRuleSnapshotView;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock rule management-plane service tests (AG batch pack B: the single
 * rule source behind UI, snapshot loader and executor alike).
 * <p>Verified behaviour, all against the real dev database (SpringBootTest,
 * zero mocks — same posture as MockShellSchemaTest):
 * (1) space creation persists the routing handle with shell defaults and the
 * space_key is globally unique (duplicate rejected with a plain-word conflict);
 * (2) the production-environment ban lives in the save chain (a CHECK cannot
 * see tp_project_env), so binding env_type=4 or an env from another project is
 * refused before the row lands;
 * (3) stub save-chain validation single point rejects illegal methods, unknown
 * path match types, uncompilable regex patterns and jsonb payloads of the wrong
 * JSON shape;
 * (4) the snapshot view carries a content-pinned version so the mock-server
 * loader can detect drift, and disabled stubs never leak into it.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockRuleServiceTest {

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t357b" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newEnv(long projectId, int envType) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, config_json)"
                        + " VALUES (?, ?, ?, '{}'::jsonb) RETURNING id",
                Long.class, projectId, "env-" + envType + "-" + UUID.randomUUID().toString().substring(0, 8),
                envType);
    }

    private MockSpaceInput spaceInput(String key, Long envId) {
        return new MockSpaceInput("Space " + key, key, null, envId,
                "prefix", "none", null, null, 100, null);
    }

    private MockStubInput stubInput(String name, String method, String matchType, String pattern,
                                    String matchersJson, String responseJson, boolean enabled) {
        return new MockStubInput(name, 100, method, matchType, pattern,
                matchersJson, responseJson, null, null, null, "manual", null, enabled);
    }

    @Test
    void createSpacePersistsRoutingHandleWithShellDefaults() {
        long projectId = newProject();
        String key = "sk-" + UUID.randomUUID().toString().substring(0, 10);
        long id = spaceService.create(projectId, spaceInput(key, null));

        MockSpaceInfo info = spaceService.get(id);
        assertEquals(projectId, info.projectId().longValue());
        assertEquals(key, info.spaceKey());
        assertEquals(0, info.status().intValue(), "new space starts as draft");
        assertEquals("prefix", info.basePathMode());
        assertEquals("none", info.authMode());
        assertEquals(0, info.version().intValue(), "optimistic lock starts at 0");
    }

    @Test
    void duplicateSpaceKeyIsRejectedInPlainWords() {
        long projectId = newProject();
        String key = "dup-" + UUID.randomUUID().toString().substring(0, 10);
        spaceService.create(projectId, spaceInput(key, null));

        long otherProject = newProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> spaceService.create(otherProject, spaceInput(key, null)));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
    }

    @Test
    void illegalSpaceKeyShapeIsRejected() {
        long projectId = newProject();
        // Routing handle enters the URL path — uppercase/slashes/spaces must not land.
        assertThrows(ServiceException.class,
                () -> spaceService.create(projectId, spaceInput("Bad Key/Slash", null)));
    }

    @Test
    void productionEnvBindingIsRefusedAtSaveChain() {
        long projectId = newProject();
        long prodEnv = newEnv(projectId, 4);
        String key = "prod-" + UUID.randomUUID().toString().substring(0, 10);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> spaceService.create(projectId, spaceInput(key, prodEnv)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
    }

    @Test
    void envFromAnotherProjectIsRefusedWithoutLeakingExistence() {
        long projectId = newProject();
        long foreignEnv = newEnv(newProject(), 2);
        String key = "fx-" + UUID.randomUUID().toString().substring(0, 10);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> spaceService.create(projectId, spaceInput(key, foreignEnv)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
    }

    @Test
    void stubSaveChainRejectsIllegalMethodMatcherAndPayloadShape() {
        long projectId = newProject();
        String key = "vt-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(projectId, spaceInput(key, null));

        ServiceException badMethod = assertThrows(ServiceException.class, () -> stubService.create(spaceId,
                stubInput("m", "FETCH", "exact", "/a", "[]", "{}", true)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badMethod.getCode());

        assertThrows(ServiceException.class, () -> stubService.create(spaceId,
                stubInput("m", "GET", "glob", "/a", "[]", "{}", true)));

        // regex match type must actually compile at save time, not at traffic time
        assertThrows(ServiceException.class, () -> stubService.create(spaceId,
                stubInput("m", "GET", "regex", "/a([z-9", "[]", "{}", true)));

        // matchers must be a JSON array, response must be a JSON object (db CHECK twin,
        // but the service gate speaks plain words instead of a 500)
        assertThrows(ServiceException.class, () -> stubService.create(spaceId,
                stubInput("m", "GET", "exact", "/a", "{}", "{}", true)));
        assertThrows(ServiceException.class, () -> stubService.create(spaceId,
                stubInput("m", "GET", "exact", "/a", "[]", "[]", true)));
    }

    @Test
    void matcherRowsOutsideTheFrozenKeySetAreRejectedAtSaveTime() {
        // froze the matchers_json key set — the save chain delegates to the
        // matching kernel's legality so UI, snapshot and traffic plane speak one
        // spectrum (no second validator downstream can be dodged).
        long projectId = newProject();
        String key = "mrx-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(projectId, spaceInput(key, null));

        // unknown matcher kind
        assertThrows(ServiceException.class, () -> stubService.create(spaceId,
                stubInput("m", "GET", "exact", "/a", "[{\"kind\":\"xpath\",\"key\":\"a\"}]", "{}", true)));
        // JSONPath outside the restricted subset (filter expression)
        assertThrows(ServiceException.class, () -> stubService.create(spaceId,
                stubInput("m", "GET", "exact", "/a",
                        "[{\"kind\":\"body\",\"bodyType\":\"jsonpath\",\"path\":\"$..a\"}]", "{}", true)));
        // compare type outside the allowed set
        assertThrows(ServiceException.class, () -> stubService.create(spaceId,
                stubInput("m", "GET", "exact", "/a",
                        "[{\"kind\":\"query\",\"key\":\"a\",\"matchType\":\"script\",\"value\":\"1\"}]",
                        "{}", true)));
        // legal row lands untouched
        long stubId = stubService.create(spaceId, stubInput("m", "GET", "exact", "/a",
                "[{\"kind\":\"query\",\"key\":\"a\",\"matchType\":\"regex\",\"value\":\"^x\"}]", "{}", true));
        assertTrue(stubId > 0);
    }

    @Test
    void validStubPersistsSingleRuleSourceRow() {
        long projectId = newProject();
        String key = "ok-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(projectId, spaceInput(key, null));

        long stubId = stubService.create(spaceId, stubInput("list users", "GET", "template",
                "/users/{id}", "[{\"kind\":\"query\",\"key\":\"page\",\"matchType\":\"exact\",\"value\":\"1\"}]",
                "{\"status\":200,\"body\":\"{\\\"id\\\":\\\"{{path.id}}\\\"}\"}", true));

        MockStubInfo info = stubService.get(stubId);
        assertEquals(spaceId, info.spaceId().longValue());
        assertEquals("GET", info.method());
        assertEquals("template", info.pathMatchType());
        assertTrue(info.enabled());
        assertEquals("manual", info.source());
        assertTrue(info.matchersJson().contains("\"query\""));
    }

    @Test
    void stubOmittingOptionalJsonPayloadsPersistsWithShellDefaults() {
        // A rule editor may leave matchers/response empty — the save chain must
        // land the table shell defaults ([], {}) instead of tripping the NOT NULL
        // columns (the L3 bridge call proved the explicit-NULL insert was a 500).
        long projectId = newProject();
        String key = "dflt-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(projectId, spaceInput(key, null));

        long stubId = stubService.create(spaceId, stubInput("bare", "GET", "exact",
                "/bare", null, null, true));

        MockStubInfo info = stubService.get(stubId);
        assertEquals("[]", info.matchersJson());
        assertEquals("{}", info.responseJson());
    }

    @Test
    void snapshotCarriesPinnedVersionAndExcludesDisabledStubs() {
        long projectId = newProject();
        String key = "snap-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(projectId, spaceInput(key, null));
        long liveStub = stubService.create(spaceId,
                stubInput("live", "GET", "exact", "/live", "[]", "{}", true));
        stubService.create(spaceId, stubInput("off", "POST", "exact", "/off", "[]", "{}", false));

        // only a published space serves — publish before snapshotting.
        spaceService.transition(spaceId, 1, 0);
        MockRuleSnapshotView snapshot = spaceService.snapshot(key);
        assertNotNull(snapshot.version(), "content-pinned version is the loader drift signal");
        assertEquals(key, snapshot.space().spaceKey());
        List<Long> ids = snapshot.stubs().stream().map(MockStubInfo::id).toList();
        assertTrue(ids.contains(liveStub));
        assertEquals(1, ids.size(), "disabled stubs must never reach the traffic plane");

        // any rule change must move the pin (loader re-pulls on drift only)
        jdbcTemplate.update("UPDATE tp_mock_stub SET updated_at = now() + interval '1 second' WHERE id = ?", liveStub);
        MockRuleSnapshotView after = spaceService.snapshot(key);
        assertNotEquals(snapshot.version(), after.version(),
                "snapshot version is content-pinned: a rule edit must move it");
    }

    @Test
    void unknownSpaceKeySnapshotFailsHonest() {
        assertThrows(ServiceException.class,
                () -> spaceService.snapshot("no-such-" + UUID.randomUUID().toString().substring(0, 8)));
        assertFalse(spaceService.listByProject(newProject()).stream().findAny().isPresent(),
                "a fresh project has no spaces yet");
    }
}
