package com.sixbean.testpike.core.asset.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.asset.api.dto.AssetPublishRequest;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Login-recipe payload contract at the publish gate (W5 app-automation phase 2, segment B1):
 * a recipe asset is the reusable "how to get signed in" description for a system under
 * test, and the app variant carries a step sequence that the executor will run verbatim.
 * Two facts must therefore hold before a row is ever stored — the steps must be engine
 * independent (every action is an {@code app.*} verb of the abstract action IR, the same
 * table the record/nl step chains gate on) and the payload must never carry a secret
 * literal (only a vault reference, so publishing a recipe into the shared pool can not
 * leak a password into every project that discovers it).
 * <p>Untagged payloads are pre-B1 snapshots: they keep publishing unchanged, because the
 * web-side recipe shape belongs to the monitor login chain and is not re-shaped here.
 * <p>Runs against the real dev database like the rest of the asset family: the rejection
 * must happen before the first SQL write, which is only observable on a real connection.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class SharedAssetLoginRecipeVariantTest {

    @Autowired
    private SharedAssetService assetService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdProjectIds = new ArrayList<>();
    private final List<Long> createdAssetIds = new ArrayList<>();
    private long projectId;

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        // tp_asset.project_id carries no FK into tp_project, so the asset rows of this
        // fixture are tracked by primary key and removed through the FK graph child-first
        // (tp_asset_reference points at them); projects are purged by id afterwards.
        // R37: no blanket TRUNCATE of a shared dev table — concurrent chains own rows there.
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_asset", createdAssetIds);
        createdAssetIds.clear();
        ProjectFixtureRig.purgeViaService(projectService, 1L, createdProjectIds);
    }

    private long createProject(String tag) {
        String code = "recipe" + tag + UUID.randomUUID().toString().substring(0, 8);
        long id = projectService.create(1L,
                new ProjectCreateInput("LoginRecipe " + tag + " " + code, code, "zh-CN", "P1", null));
        createdProjectIds.add(id);
        return id;
    }

    private SharedAssetInfo publishRecipe(String name, String payload) {
        SharedAssetInfo published = assetService.publish(1L, projectId,
                new AssetPublishRequest(projectId, "login_recipe", name, payload));
        createdAssetIds.add(published.id());
        return published;
    }

    /** Steps of a well-formed app recipe: launch the app, then fill the two login fields. */
    private static String appSteps(String... actionNames) {
        StringBuilder steps = new StringBuilder();
        for (String action : actionNames) {
            if (steps.length() > 0) {
                steps.append(',');
            }
            steps.append("{\"action\":\"").append(action).append("\"}");
        }
        return "{\"target\":\"app\",\"steps\":[" + steps + "]}";
    }

    @Test
    void appVariantRecipeWithIrStepsPublishesAsHead() {
        projectId = createProject("owner");
        SharedAssetInfo published = publishRecipe("mall-app-login",
                appSteps("app.launch", "app.tap", "app.input", "app.assertVisible"));

        assertTrue(published.id() > 0);
        assertEquals(1, published.assetVersion());
        assertTrue(published.head());
        assertEquals(1L, assetRowsOfFixtureProject());
    }

    @Test
    void unknownVariantTargetIsRejectedNamingTheAllowedValues() {
        projectId = createProject("owner");
        ServiceException rejected = assertThrows(ServiceException.class, () -> publishRecipe(
                "mall-app-login", "{\"target\":\"desktop\",\"steps\":[]}"));

        assertEquals(BizErrorCode.VALIDATION_ERROR, rejected.getCode());
        assertTrue(rejected.getMessage().contains("web"),
                "rejection names the allowed variants, was: " + rejected.getMessage());
        assertTrue(rejected.getMessage().contains("app"),
                "rejection names the allowed variants, was: " + rejected.getMessage());
        assertNoAssetWritten();
    }

    @Test
    void emptyStepsOrNonArrayStepsAreRejectedForAppVariant() {
        projectId = createProject("owner");
        ServiceException empty = assertThrows(ServiceException.class,
                () -> publishRecipe("empty-steps", "{\"target\":\"app\",\"steps\":[]}"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, empty.getCode());

        ServiceException missing = assertThrows(ServiceException.class,
                () -> publishRecipe("missing-steps", "{\"target\":\"app\"}"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, missing.getCode());

        ServiceException notArray = assertThrows(ServiceException.class,
                () -> publishRecipe("steps-as-object", "{\"target\":\"app\",\"steps\":{}}"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, notArray.getCode());
        assertNoAssetWritten();
    }

    @Test
    void appVariantStepOutsideTheIrBaselineNamesTheOffendingStepAndItsPosition() {
        projectId = createProject("owner");
        ServiceException rejected = assertThrows(ServiceException.class, () -> publishRecipe(
                "bad-verb", appSteps("app.launch", "app.dragDrop")));

        assertEquals(BizErrorCode.VALIDATION_ERROR, rejected.getCode());
        assertTrue(rejected.getMessage().contains("app.dragDrop"),
                "rejection names the offending verb, was: " + rejected.getMessage());
        assertTrue(rejected.getMessage().contains("2"),
                "rejection names the step position, was: " + rejected.getMessage());
        assertNoAssetWritten();
    }

    @Test
    void appVariantStepWithoutAppPrefixIsRejected() {
        projectId = createProject("owner");
        ServiceException rejected = assertThrows(ServiceException.class, () -> publishRecipe(
                "web-verb-in-app-recipe", appSteps("click")));

        assertEquals(BizErrorCode.VALIDATION_ERROR, rejected.getCode());
        assertTrue(rejected.getMessage().contains("app."),
                "rejection points at the app.* IR prefix, was: " + rejected.getMessage());
        assertNoAssetWritten();
    }

    @Test
    void secretLiteralPasswordIsRejectedAndPointsAtTheVaultReference() {
        projectId = createProject("owner");
        ServiceException rejected = assertThrows(ServiceException.class, () -> publishRecipe(
                "plaintext-secret", "{\"target\":\"app\",\"steps\":["
                        + "{\"action\":\"app.input\",\"params\":{\"password\":\"Hunter2!\"}}]}"));

        assertEquals(BizErrorCode.VALIDATION_ERROR, rejected.getCode());
        assertTrue(rejected.getMessage().contains("password"),
                "rejection names the offending field, was: " + rejected.getMessage());
        assertTrue(rejected.getMessage().contains("CRED"),
                "rejection names the reference syntax, was: " + rejected.getMessage());
        assertNoAssetWritten();
    }

    @Test
    void bothVaultReferenceDialectsAreAcceptedInAppSteps() {
        projectId = createProject("owner");
        SharedAssetInfo templateDialect = publishRecipe("mall-app-login",
                "{\"target\":\"app\",\"steps\":[{\"action\":\"app.input\",\"params\":"
                        + "{\"password\":\"{{CRED:mall_password}}\"}}]}");
        SharedAssetInfo wholeStringDialect = publishRecipe("mall-app-login",
                "{\"target\":\"app\",\"steps\":[{\"action\":\"app.input\",\"params\":"
                        + "{\"password\":\"@cred/mall_password\"}}]}");

        assertEquals(1, templateDialect.assetVersion());
        assertEquals(2, wholeStringDialect.assetVersion());
    }

    @Test
    void malformedCredentialReferenceIsRejectedNamingTheKey() {
        projectId = createProject("owner");
        // A dotted key is the alias namespace form, never a vault key (the injector
        // resolves identifier-shaped keys only) — an unresolvable reference would blow
        // up at dispatch, so the save gate has to name it here.
        ServiceException rejected = assertThrows(ServiceException.class, () -> publishRecipe(
                "bad-reference", "{\"target\":\"app\",\"steps\":[{\"action\":\"app.input\","
                        + "\"params\":{\"password\":\"{{CRED:mall.password}}\"}}]}"));

        assertEquals(BizErrorCode.VALIDATION_ERROR, rejected.getCode());
        assertTrue(rejected.getMessage().contains("mall.password"),
                "rejection names the malformed key, was: " + rejected.getMessage());
        assertNoAssetWritten();
    }

    @Test
    void untaggedSnapshotPayloadStillPublishes() {
        projectId = createProject("owner");
        SharedAssetInfo legacy = publishRecipe("tms-cookie-recipe",
                "{\"label\":\"TMS\",\"assemblies\":[]}");

        assertTrue(legacy.id() > 0);
        assertEquals("{\"label\":\"TMS\",\"assemblies\":[]}", legacy.payloadJson());
    }

    @Test
    void malformedJsonPayloadIsRejectedBeforeAnyRowExists() {
        projectId = createProject("owner");
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> publishRecipe("broken", "{target:app"));

        assertEquals(BizErrorCode.VALIDATION_ERROR, rejected.getCode());
        assertNoAssetWritten();
    }

    private void assertNoAssetWritten() {
        assertEquals(0L, assetRowsOfFixtureProject(),
                "the payload contract must reject before the first SQL write");
    }

    /** Counted per fixture project: the dev database is shared with concurrent chains. */
    private long assetRowsOfFixtureProject() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_asset WHERE project_id = ?",
                Long.class, projectId);
    }
}
