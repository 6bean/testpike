package com.sixbean.testpike.admin.asset.api.controller;

import com.sixbean.testpike.admin.asset.api.dto.AssetPinForm;
import com.sixbean.testpike.admin.asset.api.dto.AssetPublishForm;
import com.sixbean.testpike.admin.asset.api.dto.AssetReferenceView;
import com.sixbean.testpike.admin.asset.api.dto.AssetVersionTimelineView;
import com.sixbean.testpike.admin.asset.api.dto.SharedAssetView;
import com.sixbean.testpike.admin.asset.infrastructure.remote.client.CoreSharedAssetClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared-asset admin bridge contract test .
 * <p>Spectra: write doors run the project fence BEFORE the core call
 * (publish/withdraw = project lead, pin = case-write surface guard, so a
 * developer cannot host a pin); callerProjectId travels positionally and the
 * bridge never recomputes core judgments (head/stale math and the owner fence
 * stay single-sourced in core, R40). Read doors are pool-global: the asset
 * pool is discoverable across projects by design, the functional key is the
 * door, no H1 segment guard is invented here. Catalog ruling: every door key is
 * registered exactly once and every door carries its annotation.
 * <p>CoreSharedAssetClient is the integration boundary double for the L2
 * bridge contract; the real admin to core chain is covered by the L3 live
 * script at segment close (verification levels may not be skipped).
 */
class SharedAssetBridgeTest {

    private final CoreSharedAssetClient client = mock(CoreSharedAssetClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final SharedAssetController controller = new SharedAssetController(client, guard);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static void login(long userId) {
        UserContext.set(new CurrentUser(userId, "u" + userId, "U", "Tester",
                List.of("TEST_LEAD"), System.currentTimeMillis() + 3600_000, null));
    }

    private static SharedAssetView assetRow(long id, int version, boolean head) {
        return new SharedAssetView(id, 7L, "Order Center", "case_fragment",
                "login-then-search", version, version > 1 ? id - 1 : null, 1,
                "{\"steps\":[]}", head, 2);
    }

    private static AssetReferenceView refRow(long id, boolean stale) {
        return new AssetReferenceView(id, 100L, 7L, "Order Center", "login_recipe",
                "tmsso", 2, 1, 3, stale, 9L, "Checkout", "case_step", 4200L,
                Instant.parse("2026-11-02T01:00:00Z"));
    }

    @Test
    void publish_door_passesManageGuardFirst_andForwardsActingProjectWithBodyVerbatim() {
        login(7L);
        AssetPublishForm form = new AssetPublishForm(7L, "case_fragment", "login-then-search",
                "{\"steps\":[]}");
        when(client.publish(eq(7L), any(AssetPublishForm.class)))
                .thenReturn(assetRow(101L, 1, true));
        Response<SharedAssetView> resp = controller.publish(form);
        assertEquals("0", resp.getCode());
        assertEquals(101L, resp.getData().id());
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanManage(user.capture(), eq(7L));
        assertEquals(7L, user.getValue().userId(), "guard must use the logged-in user");
        verify(client).publish(7L, form);
    }

    @Test
    void withdraw_door_passesManageGuardBeforeCore_andNamesCallerProject() {
        login(7L);
        Response<Void> resp = controller.withdraw(7L, 101L, true);
        assertEquals("0", resp.getCode());
        verify(guard).assertCanManage(any(CurrentUser.class), eq(7L));
        verify(client).withdraw(7L, 101L, true);
    }

    @Test
    void withdraw_door_guardRejection_shortCircuitsBeforeCore() {
        login(7L);
        org.mockito.Mockito.doThrow(new IllegalStateException("fence rejected the caller"))
                .when(guard).assertCanManage(any(CurrentUser.class), eq(8L));
        assertThrows(IllegalStateException.class, () -> controller.withdraw(8L, 101L, false));
        verify(client, never()).withdraw(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void pin_door_usesCaseWriteGuard_forwardsConsumerProjectAndPinBody() {
        login(7L);
        AssetPinForm form = new AssetPinForm(9L, 102L, "case_step", 4200L);
        when(client.pin(eq(9L), any(AssetPinForm.class))).thenReturn(refRow(55L, true));
        Response<AssetReferenceView> resp = controller.pin(form);
        assertEquals("0", resp.getCode());
        assertEquals(55L, resp.getData().id());
        verify(guard).assertCanWriteCase(any(CurrentUser.class), eq(9L));
        verify(client).pin(9L, form);
    }

    @Test
    void getDoor_and_listDoor_forwardParamsVerbatim_noBridgeSideFiltering() {
        login(7L);
        when(client.get(101L)).thenReturn(assetRow(101L, 2, false));
        Response<SharedAssetView> one = controller.get(101L);
        assertEquals(2, one.getData().assetVersion());
        assertFalseStyle(one, "get");

        when(client.list("case_fragment", 2, 20, "asset_version", "desc"))
                .thenReturn(new PageView<>(List.of(assetRow(101L, 2, true)), 57L));
        Response<PageView<SharedAssetView>> page =
                controller.list("case_fragment", 2, 20, "asset_version", "desc");
        assertEquals(57L, page.getData().total());
        verify(client).list("case_fragment", 2, 20, "asset_version", "desc");
    }

    /** Cheap readability helper: fails the test when the envelope code is not success. */
    private static void assertFalseStyle(Response<?> resp, String door) {
        assertEquals("0", resp.getCode(), door + " door must wrap the core envelope as-is");
    }

    @Test
    void toChainDoor_and_byConsumerDoor_forwardPagingAndAnchorParamsVerbatim() {
        login(7L);
        when(client.referencesToChain(7L, "login_recipe", "tmsso", 1, 20))
                .thenReturn(new PageView<>(List.of(refRow(55L, true)), 1L));
        Response<PageView<AssetReferenceView>> who =
                controller.referencesToChain(7L, "login_recipe", "tmsso", 1, 20);
        assertEquals(1L, who.getData().total());
        assertEquals(true, who.getData().list().get(0).stale());
        verify(client).referencesToChain(7L, "login_recipe", "tmsso", 1, 20);

        when(client.referencesByConsumer(9L, 1, 20))
                .thenReturn(new PageView<>(List.of(refRow(55L, false)), 3L));
        Response<PageView<AssetReferenceView>> mine = controller.referencesByConsumer(9L, 1, 20);
        assertEquals(3L, mine.getData().total());
        verify(client).referencesByConsumer(9L, 1, 20);
    }

    @Test
    void chainVersionsDoor_forwardsChainAnchorVerbatim_andMirrowsPerVersionPinRows() {
        login(7L);
        when(client.chainVersions(7L, "case_fragment", "login-then-search"))
                .thenReturn(List.of(
                        new AssetVersionTimelineView(12L, 2, 1, true, 0L,
                                Instant.parse("2026-11-02T02:00:00Z")),
                        new AssetVersionTimelineView(11L, 1, 1, false, 1L,
                                Instant.parse("2026-11-02T01:00:00Z"))));
        Response<List<AssetVersionTimelineView>> timeline =
                controller.chainVersions(7L, "case_fragment", "login-then-search");
        assertEquals(2, timeline.getData().size());
        assertEquals(true, timeline.getData().get(0).head());
        assertEquals(1L, timeline.getData().get(1).pinnedCount());
        verify(client).chainVersions(7L, "case_fragment", "login-then-search");
    }

    @Test
    void everyDoorKey_isCatalogedExactlyOnce() {
        for (String key : List.of("perm:asset:pool:read", "perm:asset:publish",
                "perm:asset:withdraw", "perm:asset:pin")) {
            assertEquals(true, PermissionCatalog.exists(key), key + " must be in the catalog");
            long sameKey = java.util.Arrays.stream(PermissionCatalog.values())
                    .filter(p -> p.key().equals(key)).count();
            assertEquals(1L, sameKey, key + " must be registered once (zero extra)");
        }
    }
}
