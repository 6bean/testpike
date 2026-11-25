package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.mock.api.dto.MockStubDraftAdviceView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubDraftView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubImportResultView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.CoreMockRuleClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecView;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreApiSpecClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract-direct import bridge contract test .
 * <p>Asserted surface: ①drafts preview forwards the spec id and passes the
 * nine-field draft rows through untouched (the deterministic mapping lives in
 * core StubDraftMapper — the bridge re-decides nothing, red line 6); ②import
 * forwards spaceId + the draft list verbatim and mirrors the created/skipped
 * result untouched (idempotence is core's single point); ③both doors guard on
 * the SPEC's owning project via ProjectAccessGuard.assertCanWriteRequirement —
 * permission source is the row's ownership, never a frontend-passed id (H1
 * isolation, same posture as ApiSpecPreviewController.generate); ④a guard
 * refusal (non-member 403) must stop the call before core is ever touched;
 * ⑤an empty draft list is refused at the bridge door with a human-worded 400.
 * <p>CoreMockRuleClient/CoreApiSpecClient/ProjectAccessGuard stand-ins = L2
 * bridge-contract isolation (precedent: MockRuleBridgeTest, MonitorBridgeTest);
 * the real admin→core /internal/mock chain is verified at L3 in this pack.
 */
class MockStubImportBridgeTest {

    private final CoreMockRuleClient ruleClient = mock(CoreMockRuleClient.class);
    private final CoreApiSpecClient specClient = mock(CoreApiSpecClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final MockStubImportController controller =
            new MockStubImportController(ruleClient, specClient, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void loginAdmin() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("ADMIN"), 0L, null));
    }

    private static MockStubDraftView draft(String key, String method, String path) {
        return new MockStubDraftView(key, "订单查询", "get", "exact", path,
                "[]", "{\"status\":200,\"bodyMode\":\"static\",\"body\":\"{}\"}",
                "openapi", "spec:42");
    }

    private static ApiSpecView spec(long id, long projectId) {
        return new ApiSpecView(id, projectId, null, "支付契约", "openapi", "Pay API",
                "sha", 1, List.of(), null, null, 7L, null);
    }

    @Test
    void previewDrafts_guardsOnSpecOwningProject_andPassesNineFieldDraftsUntouched() {
        loginAdmin();
        when(specClient.get(42L)).thenReturn(spec(42L, 9L));
        when(ruleClient.previewStubDrafts(42L)).thenReturn(List.of(
                draft("GET /orders/{id}", "get", "/orders/{id}")));

        List<MockStubDraftView> drafts = controller.previewDrafts(42L).getData();

        verify(guard).assertCanWriteRequirement(any(CurrentUser.class), eq(9L));
        assertEquals(1, drafts.size());
        MockStubDraftView head = drafts.get(0);
        assertEquals("GET /orders/{id}", head.endpointKey());
        assertEquals("openapi", head.source());
        assertEquals("spec:42", head.sourceRef(), "provenance anchor must survive the bridge intact");
        assertEquals("{\"status\":200,\"bodyMode\":\"static\",\"body\":\"{}\"}", head.responseJson(),
                "response payload is forwarded verbatim, never rewritten");
    }

    @Test
    void previewDrafts_guardRefusal_stopsBeforeCoreIsTouched() {
        loginAdmin();
        when(specClient.get(42L)).thenReturn(spec(42L, 9L));
        org.mockito.Mockito.doThrow(new ServiceException(
                        "您不是该项目成员，无权访问该项目资源", 403))
                .when(guard).assertCanWriteRequirement(any(CurrentUser.class), eq(9L));

        assertThrows(ServiceException.class, () -> controller.previewDrafts(42L));
        verify(ruleClient, never()).previewStubDrafts(anyLong());
    }

    @Test
    void importStubs_forwardsSpaceIdAndDraftListVerbatim_mirrorsResultUntouched() {
        loginAdmin();
        when(specClient.get(42L)).thenReturn(spec(42L, 9L));
        List<MockStubDraftView> posted = List.of(
                draft("POST /orders", "post", "/orders"),
                draft("GET /orders/{id}", "get", "/orders/{id}"));
        when(ruleClient.importStubs(eq(101L), any())).thenReturn(
                new MockStubImportResultView(List.of(501L), List.of("GET /orders/{id}")));

        MockStubImportResultView result =
                controller.importFromSpec(101L, 42L, posted).getData();

        verify(guard).assertCanWriteRequirement(any(CurrentUser.class), eq(9L));
        ArgumentCaptor<List<MockStubDraftView>> cap = ArgumentCaptor.forClass(List.class);
        verify(ruleClient).importStubs(eq(101L), cap.capture());
        assertEquals(posted, cap.getValue(), "drafts are the wizard's proposals — bridge must not edit them");
        assertEquals(List.of(501L), result.createdStubIds());
        assertEquals(List.of("GET /orders/{id}"), result.skippedEndpointKeys(),
                "skipped keys are honest not-errors, forwarded untouched");
    }

    @Test
    void importStubs_emptyDraftList_refusedAtBridgeDoor() {
        loginAdmin();
        when(specClient.get(42L)).thenReturn(spec(42L, 9L));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                controller.importFromSpec(101L, 42L, List.of()));
        assertEquals(Integer.valueOf(400), ex.getCode());
        verify(ruleClient, never()).importStubs(anyLong(), any());
    }

    @Test
    void importStubs_coreHumanError_propagatesUntouched() {
        loginAdmin();
        when(specClient.get(42L)).thenReturn(spec(42L, 9L));
        when(ruleClient.importStubs(eq(101L), any())).thenThrow(new ServiceException(
                "规则校验未通过：pathPattern 必须以 / 开头", 400));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                controller.importFromSpec(101L, 42L, List.of(draft("GET /a", "get", "/a"))));
        assertEquals(Integer.valueOf(400), ex.getCode(), "core save-chain 400 must not be rewritten to 500");
    }

    /** Advice rows mirror the core shape field-for-field; the bridge re-decides nothing. */
    private static MockStubDraftAdviceView advice(String key, String verdict,
                                                  String adviceState, boolean defaultChecked) {
        return new MockStubDraftAdviceView(key, "订单查询", "get", "exact", "/orders/{id}",
                "[]", "{\"status\":200}", "openapi", "spec:42",
                verdict, "供支付回调查询订单状态", null, null,
                "{\"hits7d\":12}", 70, 80, "rule", adviceState, defaultChecked);
    }

    @Test
    void previewDraftsAdvice_forwardsSpecAndSpaceIds_passesAdviceRowsUntouched() {
        loginAdmin();
        when(specClient.get(42L)).thenReturn(spec(42L, 9L));
        when(ruleClient.previewStubDraftsAdvice(42L, 101L)).thenReturn(List.of(
                advice("GET /orders/{id}", "recommend", "pending", true)));

        List<MockStubDraftAdviceView> rows = controller.previewDraftsAdvice(42L, 101L).getData();

        verify(guard).assertCanWriteRequirement(any(CurrentUser.class), eq(9L));
        verify(ruleClient).previewStubDraftsAdvice(42L, 101L);
        assertEquals(1, rows.size());
        MockStubDraftAdviceView head = rows.get(0);
        assertEquals("recommend", head.verdict());
        assertEquals("供支付回调查询订单状态", head.reason(),
                "advice reason is core wording — forwarded verbatim");
        assertEquals("pending", head.adviceState(),
                "the honest waiting label comes from core, never recomputed here");
        assertEquals(80, head.confidence());
        assertEquals("rule", head.origin());
        assertTrue(head.defaultChecked(),
                "default selection rides on core's advice set — the bridge adds no rule of its own");
    }

    @Test
    void previewDraftsAdvice_blankSpaceId_forwardsAdviceFreeRowsUntouched() {
        loginAdmin();
        when(specClient.get(42L)).thenReturn(spec(42L, 9L));
        when(ruleClient.previewStubDraftsAdvice(42L, null)).thenReturn(List.of(
                advice("GET /orders/{id}", null, null, false)));

        List<MockStubDraftAdviceView> rows = controller.previewDraftsAdvice(42L, null).getData();

        assertEquals(1, rows.size());
        assertNull(rows.get(0).verdict(),
                "no space context keeps the advice null — never a fabricated suggestion");
        assertFalse(rows.get(0).defaultChecked(),
                "advice-free rows arrive unchecked exactly as core shaped them");
    }

    @Test
    void previewDraftsAdvice_guardRefusal_stopsBeforeCoreIsTouched() {
        loginAdmin();
        when(specClient.get(42L)).thenReturn(spec(42L, 9L));
        org.mockito.Mockito.doThrow(new ServiceException(
                        "您不是该项目成员，无权访问该项目资源", 403))
                .when(guard).assertCanWriteRequirement(any(CurrentUser.class), eq(9L));

        assertThrows(ServiceException.class, () -> controller.previewDraftsAdvice(42L, 101L));
        verify(ruleClient, never()).previewStubDraftsAdvice(anyLong(), any());
    }
}
