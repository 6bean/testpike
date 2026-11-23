package com.sixbean.testpike.core.asset.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.asset.api.dto.AssetPublishRequest;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventDict;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Update / withdrawal notices of the shared asset pool against the real
 * dev database. A re-publish of a chain that consumers pinned sends one ASSET_UPDATED
 * in-site notice per consumer-project owner/creator, deduped by
 * {@code ASSET_UPDATED:{newRowId}:{userId}}; the first publish of a chain sends nothing.
 * Withdrawing a chain that still has active consumer pins is blocked fail-closed and the
 * rejection names the consumer projects; the forced path withdraws, notifies every
 * consumer with ASSET_WITHDRAWN and marks pinned scripts pending-update through the
 * single-sourced invalidation component (status 2 on the real tp_script row).
 * <p>tp_asset / tp_asset_reference carry no factory rows; teardown is the
 * watermark pair (R37) — each test deletes only rows it created above the
 * captured id floors. Projects are created under unique markers and purged
 * afterwards, cases/scripts are inserted with unique case numbers and deleted
 * by id, asset-domain notifications are swept inside the run window.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class SharedAssetChangeNoticeTest {

    private static final String ASSET_EVENTS =
            "'ASSET_UPDATED','ASSET_WITHDRAWN'";

    @Autowired
    private SharedAssetService assetService;
    @Autowired
    private SharedAssetReferenceService referenceService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdProjectIds = new ArrayList<>();
    private final List<Long> createdCaseIds = new ArrayList<>();
    private final Map<Long, String> projectNames = new java.util.HashMap<>();

    // Fixture-scoped teardown : the shared AssetFixtureFloors
    // watermark pair marks the asset/pin/notification id floors before each
    // test and deletes only rows this run created above them.
    private AssetFixtureFloors floors;

    @BeforeEach
    void markWatermarks() {
        // Stale synthetic pins from a crashed run would collide with this
        // class's fixed host ids on the uq_asset_reference_host key.
        SharedDbFixtureSweep.sweepSyntheticPins(jdbcTemplate, 987_000_000_000L);
        floors = AssetFixtureFloors.mark(jdbcTemplate);
    }

    @AfterEach
    void sweepRunRows() {
        floors.sweep(jdbcTemplate);
        for (Long caseId : createdCaseIds) {
            jdbcTemplate.update("DELETE FROM tp_script WHERE case_id = ?", caseId);
            jdbcTemplate.update("DELETE FROM tp_test_case WHERE id = ?", caseId);
        }
        createdCaseIds.clear();
        ProjectFixtureRig.purgeViaService(projectService, 1L, createdProjectIds);
        projectNames.clear();
    }

    private long createProject(String tag) {
        String code = "cn" + tag + UUID.randomUUID().toString().substring(0, 8);
        String name = "AssetNotice " + tag + " " + code;
        long id = projectService.create(1L,
                new ProjectCreateInput(name, code, "zh-CN", "P1", null));
        createdProjectIds.add(id);
        projectNames.put(id, name);
        return id;
    }

    private SharedAssetInfo publish(long ownerProjectId, String kind, String assetName,
                                    String payload) {
        return assetService.publish(1L, ownerProjectId,
                new AssetPublishRequest(ownerProjectId, kind, assetName, payload));
    }

    private long insertCaseWithScript(long projectId) {
        Long caseId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_test_case (case_no, project_id, title) VALUES (?, ?, ?)"
                        + " RETURNING id",
                Long.class, "Y" + UUID.randomUUID().toString().substring(0, 16),
                projectId, "pinned case");
        jdbcTemplate.update("INSERT INTO tp_script (case_id) VALUES (?)", caseId);
        createdCaseIds.add(caseId);
        return caseId;
    }

    private List<Map<String, Object>> assetNotices() {
        return jdbcTemplate.queryForList(
                "SELECT user_id, project_id, event_type, dedup_key FROM tp_notification"
                        + " WHERE event_type IN (" + ASSET_EVENTS + ")"
                        + " AND id > " + floors.notificationFloor());
    }

    @Test
    void republishNotifiesEachConsumerProjectOnceAndStaysSilentForTheOwner() {
        long owner = createProject("own");
        long consumer = createProject("csm");
        SharedAssetInfo v1 = publish(owner, "gen_function", "order-seq", "{\"type\":1}");
        referenceService.pin(consumer, v1.id(), "case_step", 987_000_000_001L);

        SharedAssetInfo v2 = publish(owner, "gen_function", "order-seq", "{\"type\":2}");

        List<Map<String, Object>> updated = assetNotices().stream()
                .filter(r -> "ASSET_UPDATED".equals(r.get("event_type")))
                .toList();
        assertEquals(1, updated.size(),
                "one ASSET_UPDATED per recipient of the consumer project, none for the owner");
        assertEquals(consumer, ((Number) updated.get(0).get("project_id")).longValue());
        assertEquals("ASSET_UPDATED:" + v2.id() + ":" + updated.get(0).get("user_id"),
                updated.get(0).get("dedup_key"));
    }

    @Test
    void firstPublishOfAChainSendsNoNotice() {
        long owner = createProject("own");
        publish(owner, "login_recipe", "crm-cookie", "{\"label\":\"CRM\"}");
        assertTrue(assetNotices().isEmpty(),
                "a brand-new chain has no consumers, so nothing may be sent");
    }

    @Test
    void withdrawIsBlockedFailClosedAndNamesTheActiveConsumerProjects() {
        long owner = createProject("own");
        long consumer = createProject("csm");
        SharedAssetInfo v1 = publish(owner, "action_component", "login-action", "[]");
        referenceService.pin(consumer, v1.id(), "case_step", 987_000_000_002L);

        ServiceException blocked = assertThrows(ServiceException.class,
                () -> assetService.withdraw(1L, owner, v1.id(), false));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, blocked.getCode());
        assertTrue(blocked.getMessage().contains(projectNames.get(consumer)),
                "the rejection must name every active consumer project, got: "
                        + blocked.getMessage());

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_asset WHERE status = 2 AND id > " + floors.assetFloor(),
                Integer.class).intValue(),
                "a blocked withdrawal changes nothing");
        assertTrue(assetNotices().isEmpty());
    }

    @Test
    void forceWithdrawNotifiesConsumersAndMarksPinnedScriptsPending() {
        long owner = createProject("own");
        long consumer = createProject("csm");
        long caseId = insertCaseWithScript(consumer);
        SharedAssetInfo v1 = publish(owner, "case_fragment", "checkout-steps", "[]");
        referenceService.pin(consumer, v1.id(), "case", caseId);

        assetService.withdraw(1L, owner, v1.id(), true);

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_asset WHERE status = 1 AND id > " + floors.assetFloor(),
                Integer.class).intValue(),
                "the whole chain leaves the discoverable pool");
        Integer scriptStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_script WHERE case_id = ?", Integer.class, caseId);
        assertEquals(2, scriptStatus.intValue(),
                "the pinned script is marked pending-update through the single-sourced component");
        List<Map<String, Object>> withdrawn = assetNotices().stream()
                .filter(r -> "ASSET_WITHDRAWN".equals(r.get("event_type")))
                .toList();
        assertEquals(1, withdrawn.size());
        assertEquals(consumer, ((Number) withdrawn.get(0).get("project_id")).longValue());
        assertEquals("ASSET_WITHDRAWN:" + v1.id() + ":" + withdrawn.get(0).get("user_id"),
                withdrawn.get(0).get("dedup_key"));
    }

    @Test
    void bothAssetEventsAreRegisteredInTheEventDictionary() {
        assertTrue(NotificationEventDict.isKnown("ASSET_UPDATED"));
        assertTrue(NotificationEventDict.isKnown("ASSET_WITHDRAWN"));
        assertFalse(NotificationEventDict.isForced("ASSET_UPDATED"));
        assertFalse(NotificationEventDict.isForced("ASSET_WITHDRAWN"));
        assertTrue(NotificationEventDict.isDefaultEnabled("ASSET_UPDATED"));
        assertTrue(NotificationEventDict.isDefaultEnabled("ASSET_WITHDRAWN"));
    }
}
