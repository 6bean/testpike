package com.sixbean.testpike.core.asset.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.asset.api.dto.AssetPublishRequest;
import com.sixbean.testpike.core.asset.api.dto.AssetReferenceInfo;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.AssetFixtureFloors;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reference pins of the shared asset pool against the real dev database:
 * a consumer project pins one host entity to a concrete asset version row; both query
 * directions ("who uses me" by asset chain, "what I use" by consumer project) resolve in
 * a single SQL join; an upstream re-publish marks the pin stale without moving it and an
 * explicit re-pin upserts the same host row instead of duplicating it; a withdrawn chain
 * stays queryable so the withdrawal flow can name every active consumer from this single
 * source; unknown asset rows and blank host locators are rejected before any write.
 * <p>tp_asset (V358) and tp_asset_reference (V359) carry no factory rows, so both tables
 * are truncated around each test; projects are created under unique markers and purged by
 * id afterwards (R37 — factory tables like tp_project are never truncated).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class SharedAssetReferenceServiceTest {

    /** Synthetic host row ids: far above any real tp_case_step id range, freed per test. */
    private static final long HOST_ID_BASE = 900_000_000_000L;

    @Autowired
    private SharedAssetService assetService;
    @Autowired
    private SharedAssetReferenceService referenceService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdProjectIds = new ArrayList<>();
    private final Map<Long, String> projectNames = new HashMap<>();
    private long hostSeq;

    // Fixture-scoped teardown : the shared AssetFixtureFloors
    // watermark pair marks the asset/pin/notification id floors before each
    // test and deletes only rows this run created above them.
    private AssetFixtureFloors floors;

    @BeforeEach
    void markRefRunFloors() {
        // Crash residue carries this class's synthetic host ids and pins reuse
        // the same (referrer_kind, referrer_id) key across runs.
        SharedDbFixtureSweep.sweepSyntheticPins(jdbcTemplate, HOST_ID_BASE);
        floors = AssetFixtureFloors.mark(jdbcTemplate);
    }

    @AfterEach
    void sweepRefRunRows() {
        floors.sweep(jdbcTemplate);
        ProjectFixtureRig.purgeViaService(projectService, 1L, createdProjectIds);
        projectNames.clear();
    }

    private long createProject(String tag) {
        // Project code is capped at 20 characters by ProjectService validation.
        String code = "ar" + tag + UUID.randomUUID().toString().substring(0, 8);
        String name = "SharedAssetRef " + tag + " " + code;
        long id = projectService.create(1L,
                new ProjectCreateInput(name, code, "zh-CN", "P1", null));
        createdProjectIds.add(id);
        projectNames.put(id, name);
        return id;
    }

    private long nextHostId() {
        return HOST_ID_BASE + (++hostSeq);
    }

    private SharedAssetInfo publish(long ownerProjectId, String kind, String assetName,
                                    String payload) {
        return assetService.publish(1L, ownerProjectId,
                new AssetPublishRequest(ownerProjectId, kind, assetName, payload));
    }

    private long countPinsForHost(long hostId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_asset_reference WHERE referrer_id = " + hostId,
                Long.class);
        return n == null ? 0L : n;
    }

    @Test
    void pinLandsOneRowAndBothQueryDirectionsSeeIt() {
        long owner = createProject("owner");
        long consumer = createProject("consumer");
        SharedAssetInfo head = publish(owner, "case_fragment", "checkout-fragment", "[]");
        long hostId = nextHostId();

        AssetReferenceInfo pin = referenceService.pin(consumer, head.id(), "case_step", hostId);

        assertEquals(head.id(), pin.assetRowId());
        assertEquals("checkout-fragment", pin.name());
        assertEquals("case_fragment", pin.kind());
        assertEquals(1, pin.pinnedVersion());
        assertEquals(1, pin.headVersion());
        assertFalse(pin.stale());
        assertEquals(1, pin.assetStatus());
        assertEquals(projectNames.get(owner), pin.ownerProjectName());
        assertEquals(projectNames.get(consumer), pin.referrerProjectName());

        PageResult<AssetReferenceInfo> usedBy = referenceService.listReferencesToChain(
                owner, "case_fragment", "checkout-fragment", 1, 50);
        assertEquals(1L, usedBy.total());
        assertEquals(hostId, usedBy.list().get(0).referrerId());
        assertEquals("case_step", usedBy.list().get(0).referrerKind());
        assertEquals(consumer, usedBy.list().get(0).referrerProjectId());

        PageResult<AssetReferenceInfo> myPins =
                referenceService.listReferencesByConsumer(consumer, 1, 50);
        assertEquals(1L, myPins.total());
        assertEquals(head.id(), myPins.list().get(0).assetRowId());
    }

    @Test
    void upstreamRepublishMarksPinStaleUntilExplicitRepinUpsertsSameHostRow() {
        long owner = createProject("owner");
        long consumer = createProject("consumer");
        SharedAssetInfo v1 = publish(owner, "gen_function", "order-id-factory", "{\"type\":3}");
        long hostId = nextHostId();
        referenceService.pin(consumer, v1.id(), "case_step", hostId);

        SharedAssetInfo v2 = publish(owner, "gen_function", "order-id-factory", "{\"type\":2}");

        AssetReferenceInfo staleView =
                referenceService.listReferencesByConsumer(consumer, 1, 50).list().get(0);
        assertTrue(staleView.stale(), "pin must survive re-publish untouched but read stale");
        assertEquals(1, staleView.pinnedVersion());
        assertEquals(2, staleView.headVersion());
        assertEquals(v1.id(), staleView.assetRowId());

        referenceService.pin(consumer, v2.id(), "case_step", hostId);

        assertEquals(1L, countPinsForHost(hostId),
                "a same-chain re-pin replaces the pinned version, never stacks two rows");
        AssetReferenceInfo moved =
                referenceService.listReferencesByConsumer(consumer, 1, 50).list().get(0);
        assertFalse(moved.stale());
        assertEquals(v2.id(), moved.assetRowId());
        assertEquals(2, moved.pinnedVersion());
    }

    @Test
    void oneHostPinsSeveralAssetsAndChainSwitchRetiresTheOldVersionPin() {
        long owner = createProject("owner");
        long otherOwner = createProject("other");
        long consumer = createProject("consumer");
        SharedAssetInfo fragV1 = publish(owner, "case_fragment", "multi-frag", "[]");
        SharedAssetInfo recipe = publish(otherOwner, "login_recipe", "sso-recipe", "{}");
        SharedAssetInfo fragV2 = publish(owner, "case_fragment", "multi-frag", "[1]");
        long hostId = nextHostId();

        referenceService.pin(consumer, fragV1.id(), "case_step", hostId);
        referenceService.pin(consumer, recipe.id(), "case_step", hostId);

        PageResult<AssetReferenceInfo> mine =
                referenceService.listReferencesByConsumer(consumer, 1, 50);
        assertEquals(2L, mine.total(), "a host may pin several distinct asset chains at once");

        // Re-pin the fragment chain to its newer version: the old version row's pin must
        // be retired in the same call, otherwise version timelines overstate pin counts.
        referenceService.pin(consumer, fragV2.id(), "case_step", hostId);

        assertEquals(2L, countPinsForHost(hostId), "chain switch keeps exactly one pin per chain");
        PageResult<AssetReferenceInfo> usedBy = referenceService.listReferencesToChain(
                owner, "case_fragment", "multi-frag", 1, 50);
        assertEquals(1L, usedBy.total(), "only the newest pinned version row is held");
        assertEquals(fragV2.id(), usedBy.list().get(0).assetRowId());

        // Re-pinning the very same row is an idempotent replace, never a duplicate row.
        referenceService.pin(consumer, recipe.id(), "case_step", hostId);
        assertEquals(2L, countPinsForHost(hostId), "same-row re-pin must not duplicate");
    }

    @Test
    void unknownAssetRowOrBlankHostFailsBeforeAnyWrite() {
        long consumer = createProject("consumer");

        ServiceException missingRow = assertThrows(ServiceException.class,
                () -> referenceService.pin(consumer, 999_999_999L, "case_step", nextHostId()));
        assertEquals(BizErrorCode.NOT_FOUND, missingRow.getCode());

        ServiceException blankKind = assertThrows(ServiceException.class,
                () -> referenceService.pin(consumer, 1L, "  ", nextHostId()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, blankKind.getCode());

        ServiceException zeroHost = assertThrows(ServiceException.class,
                () -> referenceService.pin(consumer, 1L, "case_step", 0L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, zeroHost.getCode());

        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_asset_reference WHERE id > " + floors.referenceFloor(),
                Long.class);
        assertEquals(0L, rows == null ? 0L : rows,
                "validation must reject before any row is written");
    }

    @Test
    void withdrawnChainStaysQueryableForActivePins() {
        long owner = createProject("owner");
        long consumer = createProject("consumer");
        SharedAssetInfo v1 = publish(owner, "login_recipe", "tms-cookie-recipe",
                "{\"label\":\"TMS\"}");
        referenceService.pin(consumer, v1.id(), "case_step", nextHostId());

        assetService.withdraw(1L, owner, v1.id(), true);

        PageResult<AssetReferenceInfo> usedBy = referenceService.listReferencesToChain(
                owner, "login_recipe", "tms-cookie-recipe", 1, 50);
        assertEquals(1L, usedBy.total());
        assertEquals(2, usedBy.list().get(0).assetStatus());
        assertFalse(usedBy.list().get(0).stale());
        assertEquals(projectNames.get(consumer), usedBy.list().get(0).referrerProjectName());
    }
}
