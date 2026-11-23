package com.sixbean.testpike.core.asset.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.asset.api.dto.AssetPublishRequest;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.asset.domain.component.AssetChainQuery;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chain-level reference counts on the discoverable asset list , first
 * landing "referenced N" column): every head row of the list carries the number of
 * reference pins attached to ANY version row of its (project, kind, name) chain, so
 * the consumer-facing list can render a clickable counter without per-row probes.
 * The count must resolve inside the single SELECT_WITH_HEAD statement (a grouped
 * sub-join, never a per-row query — the list endpoint is N+1-free by contract), a
 * pin left on a superseded version still counts toward the chain, and an unpinned
 * chain reads zero instead of null.
 * <p>tp_asset and tp_asset_reference carry no factory rows, so both are truncated
 * around each test; projects are created under unique markers and purged by id
 * afterwards (R37 — factory tables are never truncated).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class SharedAssetListRefCountTest {

    /** Synthetic host row ids: far above any real host table range, freed per test. */
    private static final long HOST_ID_BASE = 910_000_000_000L;

    @Autowired
    private SharedAssetService assetService;
    @Autowired
    private SharedAssetReferenceService referenceService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdProjectIds = new ArrayList<>();
    private long hostSeq;

    // Fixture-scoped teardown : the shared AssetFixtureFloors
    // watermark pair marks the asset/pin/notification id floors before each
    // test and deletes only rows this run created above them.
    private AssetFixtureFloors floors;

    @BeforeEach
    void markCountRunFloors() {
        SharedDbFixtureSweep.sweepSyntheticPins(jdbcTemplate, HOST_ID_BASE);
        floors = AssetFixtureFloors.mark(jdbcTemplate);
    }

    @AfterEach
    void sweepCountRunRows() {
        floors.sweep(jdbcTemplate);
        ProjectFixtureRig.purgeViaService(projectService, 1L, createdProjectIds);
    }

    private long createProject(String tag) {
        String code = "rc" + tag + UUID.randomUUID().toString().substring(0, 8);
        long id = projectService.create(1L,
                new ProjectCreateInput("SharedAssetRefCount " + tag + " " + code, code,
                        "zh-CN", "P1", null));
        createdProjectIds.add(id);
        return id;
    }

    private SharedAssetInfo publish(long ownerProjectId, String kind, String assetName,
                                    String payload) {
        return assetService.publish(1L, ownerProjectId,
                new AssetPublishRequest(ownerProjectId, kind, assetName, payload));
    }

    private SharedAssetInfo headRowOf(long ownerProjectId, String kind, String assetName) {
        PageResult<SharedAssetInfo> page = assetService.listDiscoverable(null, 1, 50);
        return page.list().stream()
                .filter(row -> row.projectId() == ownerProjectId
                        && row.kind().equals(kind) && row.name().equals(assetName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "head row missing from discoverable list: " + assetName));
    }

    @Test
    void pinsAcrossChainVersionsCountOnceOnHeadRow() {
        long owner = createProject("own");
        long consumer = createProject("csm");
        SharedAssetInfo v1 = publish(owner, "case_fragment", "cart-fragment", "[]");
        long hostA = HOST_ID_BASE + (++hostSeq);
        referenceService.pin(consumer, v1.id(), "case_step", hostA);

        SharedAssetInfo v2 = publish(owner, "case_fragment", "cart-fragment", "[{}]");
        long hostB = HOST_ID_BASE + (++hostSeq);
        referenceService.pin(consumer, v2.id(), "case_step", hostB);

        SharedAssetInfo head = headRowOf(owner, "case_fragment", "cart-fragment");
        assertEquals(2, head.refCount(),
                "chain counter must add the pin on the superseded version to the head row");
    }

    @Test
    void unpinnedChainHeadRowReportsZero() {
        long owner = createProject("own");
        publish(owner, "login_recipe", "buyer-login", "{}");

        SharedAssetInfo head = headRowOf(owner, "login_recipe", "buyer-login");
        assertEquals(0, head.refCount(), "unpinned chains read zero, never null or missing");
    }

    @Test
    void pinOnlyOnSupersededVersionStillCountsOnHeadRow() {
        long owner = createProject("own");
        long consumer = createProject("csm");
        SharedAssetInfo v1 = publish(owner, "gen_function", "order-no", "{\"type\":1}");
        referenceService.pin(consumer, v1.id(), "case_step", HOST_ID_BASE + (++hostSeq));

        publish(owner, "gen_function", "order-no", "{\"type\":2}");

        SharedAssetInfo head = headRowOf(owner, "gen_function", "order-no");
        assertEquals(2, head.assetVersion());
        assertEquals(1, head.refCount(),
                "stale pins are invisible nowhere: they still count toward the chain");
    }

    @Test
    void chainCounterResolvesInsideTheSingleListStatement() {
        String sql = AssetChainQuery.SELECT_WITH_HEAD;
        assertTrue(sql.contains("tp_asset_reference") && sql.contains("GROUP BY"),
                "reference counter must join a grouped aggregate inside SELECT_WITH_HEAD,"
                        + " never be resolved per row outside it (N+1 contract)");
    }
}
