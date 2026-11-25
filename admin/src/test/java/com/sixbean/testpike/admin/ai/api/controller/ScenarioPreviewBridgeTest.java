package com.sixbean.testpike.admin.ai.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.AiBatchEstimateView;
import com.sixbean.testpike.admin.ai.api.dto.AiBatchGenerateRequest;
import com.sixbean.testpike.admin.ai.api.dto.ScenarioPreviewRequest;
import com.sixbean.testpike.admin.ai.api.dto.ScenarioPreviewItemView;
import com.sixbean.testpike.admin.ai.api.dto.ScenarioPreviewView;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreAiBatchClient;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreAiScenarioPreviewClient;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreAiTaskClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.aibatch.AiBatchItem;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage0 scenario preview admin-bridge contract test , same
 * rule family as JourneyBridgeTest:
 * (1) the controller forwards the whole request object toward core — never
 * rebuilds it field by field (field-loss incident class);
 * (2) the preview batch id on the estimate request reaches the three-argument
 * client overload so the confirmation dialog shows the multiplied cost;
 * (3) the read-back lane guards by the row's OWN projectId returned from core
 * (never trust a client-supplied owner, getBatch rule), an empty list has
 * no ownership surface to guard, and the write door shares the generation
 * write path gate (assertCanWriteCase).
 * Clients and the guard are the isolated seam at L2; the live
 * admin→core→ai-service hop rides the L3 window.
 */
class ScenarioPreviewBridgeTest {

    private final CoreAiTaskClient taskClient = mock(CoreAiTaskClient.class);
    private final CoreAiBatchClient batchClient = mock(CoreAiBatchClient.class);
    private final CoreAiScenarioPreviewClient previewClient = mock(CoreAiScenarioPreviewClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final IdempotentGuard idempotentGuard = mock(IdempotentGuard.class);
    private final com.sixbean.testpike.admin.ai.app.service.StepsGenEventStreamBridge
            stepsBridge = mock(
                    com.sixbean.testpike.admin.ai.app.service.StepsGenEventStreamBridge.class);
    private final AiController controller = StreamDoorTestAssembly.aiController(
            taskClient, batchClient, previewClient, guard, idempotentGuard, stepsBridge);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginAdmin() {
        UserContext.set(new CurrentUser(5L, "admin", "A", "系统管理员", List.of("ADMIN"),
                System.currentTimeMillis() + 3600_000, null));
    }

    private ScenarioPreviewView row(long projectId) {
        return new ScenarioPreviewView(9L, 3L, 0, projectId, "用户登录功能", 0, true,
                List.of(new ScenarioPreviewItemView("main-flow", "正常登录", "账号密码登录", null)),
                List.of(), 2, 3, true);
    }

    @Test
    void estimatePassesPreviewBatchIdToCoreClient() {
        loginAdmin();
        when(batchClient.estimateBatch(eq(7L), anyList(), eq(88L)))
                .thenReturn(new AiBatchEstimateView(2, 4800, 0.05, 500, 6, 2));
        AiBatchGenerateRequest request = new AiBatchGenerateRequest(7L,
                List.of("用户登录功能", "订单支付功能"), null, 88L);

        AiBatchEstimateView view = controller.batchEstimate(request).getData();

        verify(batchClient).estimateBatch(eq(7L),
                eq(List.of(new AiBatchItem("用户登录功能", null, null),
                        new AiBatchItem("订单支付功能", null, null))), eq(88L));
        assertEquals(6, view.scenarioCount(), "multiplier columns must survive the bridge");
        assertEquals(2, view.deliberationRounds());
    }

    @Test
    void previewRunGuardsWriteDoorAndForwardsWholeRequest() {
        loginAdmin();
        when(previewClient.create(org.mockito.ArgumentMatchers.any(), anyLong())).thenReturn(9L);
        ScenarioPreviewRequest request = new ScenarioPreviewRequest(3L, 0, 7L, "用户登录功能", 0);

        assertEquals(9L, controller.runScenarioPreview(request).getData());

        verify(guard).assertCanWriteCase(UserContext.current(), 7L);
        verify(previewClient).create(request, 5L);
    }

    @Test
    void previewRunReturnsZeroHonestlyWhenSwitchOff() {
        loginAdmin();
        when(previewClient.create(org.mockito.ArgumentMatchers.any(), anyLong())).thenReturn(0L);
        assertEquals(0L, controller.runScenarioPreview(
                new ScenarioPreviewRequest(null, 0, 7L, "单行预览", 1)).getData(),
                "K8 switch off = honest 0 (legacy shape kept upstream)");
    }

    @Test
    void previewListGuardsByRowOwnedProjectFromCore() {
        loginAdmin();
        when(previewClient.listByBatch(eq(3L), anyLong())).thenReturn(List.of(row(7L)));

        List<ScenarioPreviewView> rows = controller.listScenarioPreviews(3L).getData();

        assertEquals(1, rows.size());
        assertEquals("main-flow", rows.get(0).scenarios().get(0).kind(),
                "hyphen-code kind string passes through untouched");
        verify(guard).assertCanView(UserContext.current(), 7L);
    }

    @Test
    void emptyPreviewListSkipsOwnershipGuardAndStaysEmpty() {
        loginAdmin();
        when(previewClient.listByBatch(eq(404L), anyLong())).thenReturn(List.of());

        List<ScenarioPreviewView> rows = controller.listScenarioPreviews(404L).getData();

        assertTrue(rows.isEmpty());
        verify(guard, never()).assertCanView(org.mockito.ArgumentMatchers.any(), anyLong());
    }
}
