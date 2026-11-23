package com.sixbean.testpike.core.asset.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.asset.api.dto.AssetPublishRequest;
import com.sixbean.testpike.core.asset.api.dto.AssetVersionTimelineInfo;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.asset.domain.component.AssetChainQuery;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version timeline of one asset chain : the
 * detail-side "who references me" panel needs, for every version row of the chain,
 * how many pins are attached to exactly that version — the chain-level counter on
 * the list column cannot distinguish which version the pool is pinning. Pins stay
 * on the concrete row after upstream re-publish, so each version row must carry its
 * own pinned count, resolved inside a single grouped SELECT (no per-row probe).
 * Rows come back newest first with the head flag marking the newest version.
 * <p>tp_asset and tp_asset_reference carry no factory rows; teardown is the
 * watermark pair (R37): each test deletes only the rows it created above the
 * captured id floors instead of truncating the shared tables. Projects are
 * created under unique markers and purged afterwards (factory tables are
 * never truncated).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class SharedAssetChainVersionTimelineTest {

    /** Synthetic host row ids: far above any real host table range, freed per test. */
    private static final long HOST_ID_BASE = 920_000_000_000L;

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
    void markTimelineRunFloors() {
        // Crash residue carries this class's synthetic host ids and pins reuse
        // the same (referrer_kind, referrer_id) key across runs, so stale rows
        // inside the synthetic range are removed before the floors are marked.
        SharedDbFixtureSweep.sweepSyntheticPins(jdbcTemplate, HOST_ID_BASE);
        floors = AssetFixtureFloors.mark(jdbcTemplate);
    }

    @AfterEach
    void sweepTimelineRunRows() {
        floors.sweep(jdbcTemplate);
        ProjectFixtureRig.purgeViaService(projectService, 1L, createdProjectIds);
    }

    private long createProject(String tag) {
        String code = "tl" + tag + UUID.randomUUID().toString().substring(0, 8);
        long id = projectService.create(1L,
                new ProjectCreateInput("ChainTimeline " + tag + " " + code, code,
                        "zh-CN", "P1", null));
        createdProjectIds.add(id);
        return id;
    }

    private SharedAssetInfo publish(long ownerProjectId, String kind, String assetName,
                                    String payload) {
        return assetService.publish(1L, ownerProjectId,
                new AssetPublishRequest(ownerProjectId, kind, assetName, payload));
    }

    private List<AssetVersionTimelineInfo> timeline(long owner, String kind, String name) {
        return referenceService.listChainVersionTimeline(owner, kind, name);
    }

    @Test
    void everyVersionRowCarriesItsOwnPinnedCountNewestFirst() {
        long owner = createProject("own");
        long consumer = createProject("csm");
        SharedAssetInfo v1 = publish(owner, "case_fragment", "cart-fragment", "[]");
        referenceService.pin(consumer, v1.id(), "case_step", HOST_ID_BASE + (++hostSeq));

        SharedAssetInfo v2 = publish(owner, "case_fragment", "cart-fragment", "[{}]");
        referenceService.pin(consumer, v2.id(), "case_step", HOST_ID_BASE + (++hostSeq));
        long otherHost = HOST_ID_BASE + (++hostSeq);
        referenceService.pin(createProject("csm2"), v2.id(), "case_step", otherHost);

        SharedAssetInfo v3 = publish(owner, "case_fragment", "cart-fragment", "[{},{},{}]");

        List<AssetVersionTimelineInfo> rows = timeline(owner, "case_fragment", "cart-fragment");
        assertEquals(3, rows.size(), "one timeline row per version row of the chain");
        assertEquals(List.of(3, 2, 1), rows.stream().map(AssetVersionTimelineInfo::assetVersion).toList());
        assertEquals(0, rows.get(0).pinnedCount(), "fresh head without pins counts zero");
        assertEquals(2, rows.get(1).pinnedCount(), "pins on THIS row, not the chain");
        assertEquals(1, rows.get(2).pinnedCount());
        assertTrue(rows.get(0).head(), "newest version carries the head flag");
        assertFalse(rows.get(1).head() || rows.get(2).head(),
                "superseded rows are never head");
        assertEquals(v2.id(), rows.get(1).assetRowId(),
                "timeline rows expose the concrete row id for future drill-in");
    }

    @Test
    void unpinnedChainStillListsEveryVersionWithZeroCounts() {
        long owner = createProject("own");
        publish(owner, "login_recipe", "buyer-login", "{}");
        publish(owner, "login_recipe", "buyer-login", "{\"k\":1}");

        List<AssetVersionTimelineInfo> rows = timeline(owner, "login_recipe", "buyer-login");
        assertEquals(2, rows.size());
        assertEquals(List.of(0L, 0L), rows.stream().map(AssetVersionTimelineInfo::pinnedCount).toList(),
                "unpinned versions read zero, never null or missing");
        assertTrue(rows.get(0).head());
    }

    @Test
    void unknownChainReadsEmptyTimeline() {
        long owner = createProject("own");
        assertTrue(timeline(owner, "gen_function", "never-published").isEmpty(),
                "a chain that was never published has no version rows to list");
    }

    @Test
    void pinnedCountResolvesInsideTheSingleTimelineStatement() {
        String sql = AssetChainQuery.SELECT_VERSION_TIMELINE_OF_CHAIN;
        assertTrue(sql.contains("tp_asset_reference") && sql.contains("GROUP BY"),
                "per-version pin counts must join a grouped aggregate inside the"
                        + " timeline SELECT, never be resolved per version row (N+1 contract)");
    }
}
