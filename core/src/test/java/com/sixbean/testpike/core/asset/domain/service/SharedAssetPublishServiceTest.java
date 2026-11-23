package com.sixbean.testpike.core.asset.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.asset.api.dto.AssetPublishRequest;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.AssetFixtureFloors;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publish chain of the shared asset pool against the real dev database:
 * first publish lands version 1 with no predecessor; a same-name re-publish appends
 * version 2 linked through prev_asset_id and moves the head; the four-kind CHECK is
 * enforced as a readable 400 before the DB is touched (a CHECK violation would poison
 * the surrounding transaction); withdrawal flips the whole chain and removes it from
 * global discovery while every version row stays readable for pinned references;
 * cross-project writes fail with 403 naming the owner project.
 * <p>tp_asset is introduced by V358 and carries no factory rows, so the chain table
 * itself is truncated around each test; projects are created under a unique marker and
 * purged by id afterwards (R37 — factory tables like tp_project are never truncated).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class SharedAssetPublishServiceTest {

    @Autowired
    private SharedAssetService assetService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdProjectIds = new ArrayList<>();
    private long ownerProjectId;
    private String ownerProjectName;
    private long readerProjectId;

    // Fixture-scoped teardown : the shared AssetFixtureFloors
    // watermark pair marks the asset/pin/notification id floors before each
    // test and deletes only rows this run created above them (child pins
    // first, so the V359 pin FK stays satisfied).
    private AssetFixtureFloors floors;

    @BeforeEach
    void markPublishRunFloors() {
        floors = AssetFixtureFloors.mark(jdbcTemplate);
    }

    @AfterEach
    void sweepPublishRunRows() {
        floors.sweep(jdbcTemplate);
        ProjectFixtureRig.purgeViaService(projectService, 1L, createdProjectIds);
    }

    private long createProject(String tag) {
        String code = "asset" + tag + UUID.randomUUID().toString().substring(0, 8);
        String name = "SharedAsset " + tag + " " + code;
        long id = projectService.create(1L,
                new ProjectCreateInput(name, code, "zh-CN", "P1", null));
        createdProjectIds.add(id);
        if ("owner".equals(tag)) {
            ownerProjectName = name;
        }
        return id;
    }

    private SharedAssetInfo publish(long callerProjectId, long ownerOf, String kind,
                                    String assetName, String payload) {
        return assetService.publish(1L, callerProjectId,
                new AssetPublishRequest(ownerOf, kind, assetName, payload));
    }

    @Test
    void firstPublishLandsVersionOneAsChainHead() {
        ownerProjectId = createProject("owner");
        SharedAssetInfo info = publish(ownerProjectId, ownerProjectId, "case_fragment",
                "login-flow-fragment", "[{\"action\":\"openUrl\"}]");

        assertTrue(info.id() > 0);
        assertEquals(1, info.assetVersion());
        assertNull(info.prevAssetId());
        assertEquals(1, info.status());
        assertTrue(info.head());
        // resolveHeadId returns the head ROW id of the chain (interface contract), so the
        // freshly published row must be the head; comparing it to the project id was a
        // mis-assertion caught at GREEN time (see the republish test for the same pattern).
        assertEquals(info.id(), assetService.resolveHeadId(ownerProjectId,
                "case_fragment", "login-flow-fragment"));
        assertEquals(ownerProjectName, info.ownerProjectName());
    }

    @Test
    void republishSameNameAppendsVersionAndMovesHead() {
        ownerProjectId = createProject("owner");
        SharedAssetInfo v1 = publish(ownerProjectId, ownerProjectId, "gen_function",
                "order-id-factory", "{\"type\":3}");
        SharedAssetInfo v2 = publish(ownerProjectId, ownerProjectId, "gen_function",
                "order-id-factory", "{\"type\":2}");

        assertEquals(2, v2.assetVersion());
        assertEquals(v1.id(), v2.prevAssetId());
        assertEquals(v2.id(), assetService.resolveHeadId(ownerProjectId,
                "gen_function", "order-id-factory"));
        assertFalseHead(v1.id());
        assertEquals(1, assetService.get(v1.id()).assetVersion());
    }

    private void assertFalseHead(long assetId) {
        assertTrue(!assetService.get(assetId).head(),
                "superseded version row must not be flagged as head");
    }

    @Test
    void invalidKindBlankNameOrBlankPayloadIsReadableValidationError() {
        ownerProjectId = createProject("owner");

        ServiceException badKind = assertThrows(ServiceException.class, () -> publish(
                ownerProjectId, ownerProjectId, "dashboard_widget", "n", "{}"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badKind.getCode());

        ServiceException blankName = assertThrows(ServiceException.class, () -> publish(
                ownerProjectId, ownerProjectId, "login_recipe", "  ", "{}"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, blankName.getCode());

        ServiceException blankPayload = assertThrows(ServiceException.class, () -> publish(
                ownerProjectId, ownerProjectId, "login_recipe", "n", "  "));
        assertEquals(BizErrorCode.VALIDATION_ERROR, blankPayload.getCode());

        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_asset WHERE id > " + floors.assetFloor(), Long.class),
                "validation must reject before any row is written");
    }

    @Test
    void withdrawFlipsWholeChainAndKeepsRowsReadable() {
        ownerProjectId = createProject("owner");
        SharedAssetInfo v1 = publish(ownerProjectId, ownerProjectId, "action_component",
                "captcha-solver", "{\"action\":\"solveCaptcha\"}");
        SharedAssetInfo v2 = publish(ownerProjectId, ownerProjectId, "action_component",
                "captcha-solver", "{\"action\":\"solveCaptcha\",\"v\":2}");

        assetService.withdraw(1L, ownerProjectId, v2.id(), false);

        assertEquals(2, assetService.get(v1.id()).status());
        assertEquals(2, assetService.get(v2.id()).status());
        PageResult<SharedAssetInfo> visible = assetService.listDiscoverable(null, 1, 50);
        assertTrue(visible.list().stream().noneMatch(a -> a.name().equals("captcha-solver")),
                "withdrawn chain must leave global discovery");
    }

    @Test
    void publishedHeadIsDiscoverableAcrossProjectsWithOwnerAttribution() {
        ownerProjectId = createProject("owner");
        readerProjectId = createProject("reader");
        SharedAssetInfo published = publish(ownerProjectId, ownerProjectId, "login_recipe",
                "tms-cookie-recipe", "{\"label\":\"TMS\",\"assemblies\":[]}");

        PageResult<SharedAssetInfo> visible = assetService.listDiscoverable("login_recipe", 1, 50);
        SharedAssetInfo row = visible.list().stream()
                .filter(a -> a.name().equals("tms-cookie-recipe")).findFirst().orElseThrow();
        assertEquals(published.id(), row.id());
        assertEquals(ownerProjectName, row.ownerProjectName());
        assertEquals(1L, visible.total());
    }

    @Test
    void crossProjectPublishAndWithdrawAreForbiddenNamingOwner() {
        ownerProjectId = createProject("owner");
        readerProjectId = createProject("reader");
        SharedAssetInfo mine = publish(ownerProjectId, ownerProjectId, "case_fragment",
                "owner-only-fragment", "[]");

        ServiceException republishFromOutsider = assertThrows(ServiceException.class,
                () -> publish(readerProjectId, ownerProjectId, "case_fragment",
                        "owner-only-fragment", "[]"));
        assertEquals(BizErrorCode.FORBIDDEN, republishFromOutsider.getCode());
        assertTrue(republishFromOutsider.getMessage().contains(ownerProjectName),
                "rejection names the owner project, was: " + republishFromOutsider.getMessage());

        ServiceException withdrawFromOutsider = assertThrows(ServiceException.class,
                () -> assetService.withdraw(1L, readerProjectId, mine.id(), false));
        assertEquals(BizErrorCode.FORBIDDEN, withdrawFromOutsider.getCode());
        assertEquals(1, assetService.get(mine.id()).status());
    }

    @Test
    void headResolutionOfUnknownChainAndReadOfUnknownRowAreNotFound() {
        ownerProjectId = createProject("owner");
        ServiceException missingChain = assertThrows(ServiceException.class,
                () -> assetService.resolveHeadId(ownerProjectId, "gen_function", "ghost"));
        assertEquals(BizErrorCode.NOT_FOUND, missingChain.getCode());

        ServiceException missingRow = assertThrows(ServiceException.class,
                () -> assetService.get(999999999L));
        assertEquals(BizErrorCode.NOT_FOUND, missingRow.getCode());
    }
}
