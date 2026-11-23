package com.sixbean.testpike.admin.apitest.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetCreateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetDetailView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetPreviewDepView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetPreviewStepView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetStepView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetView;
import com.sixbean.testpike.admin.apitest.app.component.ApiTestAccessResolver;
import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreApiRunSetClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Collection-run admin bridge contract test .
 * <p>Assertion surface (PromotionBridgeTest precedent):
 * ① launch write-guard runs BEFORE the core call — ownership resolved from the
 * collection via accessResolver (client-supplied projectId never trusted);
 * non-member refused with core seeing zero traffic;
 * ② detail read-guard uses the projectId carried by the core ledger row itself
 * (one hop saved, no re-derivation from the client);
 * ③ DTO field names mirror the core wire shape exactly — drift would silently
 * drop values during deserialization;
 * ④ core 400 (bad interruption mode / foreign env) passes through verbatim —
 * admin must not re-implement validation (red line: single source).
 * <p>Client/guard stubs = bridge-contract L2 isolation; the real admin→core→PG
 * chain is covered by L3 in .
 */
class RunSetBridgeTest {

    private final CoreApiRunSetClient client = mock(CoreApiRunSetClient.class);
    private final ApiTestAccessResolver accessResolver = mock(ApiTestAccessResolver.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ApiRunSetController controller =
            new ApiRunSetController(client, accessResolver, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TESTER"), 0L, null));
    }

    private RunSetView ledger(long id, long projectId) {
        return new RunSetView(id, 3L, projectId, null, "冒烟一轮", "STOP_ON_ASSERT_FAIL",
                1, "[]", "{}", 2, 0, 0, 0);
    }

    @Test
    void 发起集合运行_写闸按集合归属项目_先于core下发() {
        login();
        when(accessResolver.projectIdOfCollection(3L)).thenReturn(11L);
        when(client.createRunSet(any())).thenReturn(ledger(5L, 11L));

        Response<RunSetView> resp = controller.create(
                new RunSetCreateRequest(3L, null, "冒烟一轮", "STOP_ON_ASSERT_FAIL"));

        verify(guard).assertCanWriteCase(any(CurrentUser.class), eq(11L));
        assertEquals(5L, resp.getData().id());
        assertEquals("STOP_ON_ASSERT_FAIL", resp.getData().interruptionMode());
    }

    @Test
    void 发起集合运行_非成员403拒_core零下发() {
        login();
        when(accessResolver.projectIdOfCollection(3L)).thenReturn(11L);
        org.mockito.Mockito.doThrow(new ServiceException("无权限", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanWriteCase(any(CurrentUser.class), anyLong());

        assertThrows(ServiceException.class, () -> controller.create(
                new RunSetCreateRequest(3L, null, null, "STOP_ON_ASSERT_FAIL")));
        verify(client, never()).createRunSet(any());
    }

    @Test
    void 详情读闸消费账本自带项目_不回查客户端入参() {
        login();
        when(client.getRunSetDetail(5L)).thenReturn(new RunSetDetailView(
                ledger(5L, 11L),
                List.of(new RunSetStepView(9L, 1L, 1, "登录", "POST", 1, 120L, "", "http", 2),
                        new RunSetStepView(10L, 2L, 2, "下单", "POST", 0, null, "", "http", 0))));

        Response<RunSetDetailView> resp = controller.detail(5L);

        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
        assertEquals(2, resp.getData().steps().size());
        assertEquals(1, resp.getData().steps().get(0).status());
        assertEquals("下单", resp.getData().steps().get(1).name());
    }

    @Test
    void core校验400人话原文透传_admin不重实现() {
        login();
        when(accessResolver.projectIdOfCollection(3L)).thenReturn(11L);
        when(client.createRunSet(any())).thenThrow(new ServiceException(
                "中断模式仅支持 STOP_ON_ASSERT_FAIL / CONTINUE_AND_SUMMARY / CHAIN_DEP_ONLY，当前值「X」",
                BizErrorCode.VALIDATION_ERROR));

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.create(
                new RunSetCreateRequest(3L, null, null, "X")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode().intValue());
        assertEquals("中断模式仅支持 STOP_ON_ASSERT_FAIL / CONTINUE_AND_SUMMARY / CHAIN_DEP_ONLY，当前值「X」",
                ex.getMessage(), "core validation text must survive the bridge verbatim");
    }

    @Test
    void 预览读闸按集合归属项目_非成员拒_core零下发() {
        login();
        when(accessResolver.projectIdOfCollection(3L)).thenReturn(11L);
        when(client.previewRunSet(3L)).thenReturn(List.of(
                new RunSetPreviewStepView(1L, 1, "登录", "POST", "/auth/login",
                        List.of("token"), List.of(), List.of()),
                new RunSetPreviewStepView(2L, 2, "下单", "POST", "/orders",
                        List.of(), List.of("token"),
                        List.of(new RunSetPreviewDepView("token", 1L, true)))));

        Response<List<RunSetPreviewStepView>> resp = controller.preview(3L);

        verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
        assertEquals(2, resp.getData().size());
        assertEquals("token", resp.getData().get(1).deps().get(0).key());
        assertTrue(resp.getData().get(1).deps().get(0).producerEarlier());
    }

    @Test
    void 预览非成员403拒_core零下发() {
        login();
        when(accessResolver.projectIdOfCollection(3L)).thenReturn(11L);
        org.mockito.Mockito.doThrow(new ServiceException("无权限", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(CurrentUser.class), anyLong());

        assertThrows(ServiceException.class, () -> controller.preview(3L));
        verify(client, never()).previewRunSet(anyLong());
    }

    @Test
    void DTO镜像字段名与core线格式逐字段对齐() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String ledgerWire = """
                {"id":5,"collectionId":3,"projectId":11,"envId":null,"name":"冒烟",
                 "interruptionMode":"STOP_ON_ASSERT_FAIL","status":1,
                 "pinnedJson":"[]","initialVarsJson":"{}","totalSteps":2,
                 "passedCount":1,"failedCount":0,"skippedCount":0}
                """;
        RunSetView view = mapper.readValue(ledgerWire, RunSetView.class);
        assertEquals(5L, view.id());
        assertEquals(11L, view.projectId());
        assertEquals(1, view.passedCount());

        String stepWire = """
                {"runId":9,"requestId":1,"seqNo":1,"name":"登录","method":"POST",
                 "status":1,"durationMs":120,"errorMsg":""}
                """;
        RunSetStepView step = mapper.readValue(stepWire, RunSetStepView.class);
        assertEquals(9L, step.runId());
        assertEquals(120L, step.durationMs().longValue());

        // record shape lock: drift on either mirror = silent field loss
        List<String> ledgerNames = java.util.Arrays.stream(
                RunSetView.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertEquals(List.of("id", "collectionId", "projectId", "envId", "name",
                "interruptionMode", "status", "pinnedJson", "initialVarsJson",
                "totalSteps", "passedCount", "failedCount", "skippedCount"), ledgerNames,
                "RunSetView shape drift vs core ApiRunSetInfo");
        List<String> stepNames = java.util.Arrays.stream(
                RunSetStepView.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertEquals(List.of("runId", "requestId", "seqNo", "name", "method",
                "status", "durationMs", "errorMsg", "protocol", "assertCount"), stepNames,
                "RunSetStepView shape drift vs core ApiRunSetStep");

        // Preview card rows ride the same wire-lock rule .
        String previewWire = """
                {"requestId":1,"seqNo":1,"name":"登录","method":"POST","urlPath":"/auth/login",
                 "extracts":["token"],"references":["token"],
                 "deps":[{"key":"token","producerRequestId":9,"producerEarlier":false}]}
                """;
        RunSetPreviewStepView preview = mapper.readValue(previewWire, RunSetPreviewStepView.class);
        assertEquals(List.of("token"), preview.extracts());
        assertEquals(9L, preview.deps().get(0).producerRequestId());
        assertEquals(false, preview.deps().get(0).producerEarlier());
    }
}
