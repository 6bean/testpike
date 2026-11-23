package com.sixbean.testpike.admin.apitest.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.apitest.api.dto.PromotionResultDto;
import com.sixbean.testpike.admin.apitest.app.component.ApiTestAccessResolver;
import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreApiRequestClient;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sandbox-promotion admin bridge contract test .
 * <p>Assertion surface:
 * ① write guard runs BEFORE the core call — the guard resolves project ownership
 * via {@code accessResolver.projectIdOfRequest(id)} (never trusts a client-supplied
 * project), non-member is refused and core sees zero traffic;
 * ② happy path returns the promotion coordinates (caseId/caseNo/requestId/projectId)
 * unwrapped through the admin DTO mirror;
 * ③ core 409 (already promoted, message names the existing case number) is passed
 * through verbatim — admin must not re-implement the check (red line: single source);
 * ④ DTO field names mirror the core wire shape exactly — a drift would silently drop
 * values during deserialization (RequestViewContractStaleTest precedent).
 * <p>CoreApiRequestClient/Guard stubs = bridge-contract L2 isolation (StepAliasBridgeTest
 * precedent); the real admin→core→PG chain is covered by L3 in .
 */
class PromotionBridgeTest {

    private final CoreApiRequestClient client = mock(CoreApiRequestClient.class);
    private final ApiTestAccessResolver accessResolver = mock(ApiTestAccessResolver.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ApiRequestController controller =
            new ApiRequestController(client, accessResolver, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TESTER"), 0L, null));
    }

    private PromotionResultDto result(long caseId, String caseNo) {
        return new PromotionResultDto(caseId, caseNo, 9L, 11L);
    }

    @Test
    void 升格_写闸按请求归属项目_命中后回显坐标() {
        login();
        when(accessResolver.projectIdOfRequest(9L)).thenReturn(11L);
        when(client.promoteRequest(9L)).thenReturn(result(101L, "CASE-0101"));

        Response<PromotionResultDto> resp = controller.promote(9L);

        verify(guard).assertCanWriteCase(any(CurrentUser.class), eq(11L));
        assertEquals(101L, resp.getData().caseId());
        assertEquals("CASE-0101", resp.getData().caseNo());
        assertEquals(9L, resp.getData().requestId());
        assertEquals(11L, resp.getData().projectId());
    }

    @Test
    void 升格_非成员403拒_core零下发() {
        login();
        when(accessResolver.projectIdOfRequest(9L)).thenReturn(11L);
        org.mockito.Mockito.doThrow(new ServiceException("无权限", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanWriteCase(any(CurrentUser.class), anyLong());

        assertThrows(ServiceException.class, () -> controller.promote(9L));
        verify(client, never()).promoteRequest(anyLong());
    }

    @Test
    void 升格_重复升格409_人话原文透传() {
        login();
        when(accessResolver.projectIdOfRequest(9L)).thenReturn(11L);
        when(client.promoteRequest(9L)).thenThrow(
                new ServiceException("该请求已升格为用例 CASE-0101，请勿重复升格", BizErrorCode.RESOURCE_CONFLICT));

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.promote(9L));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode().intValue(),
                "40904 semantics must survive the bridge");
        assertEquals("该请求已升格为用例 CASE-0101，请勿重复升格", ex.getMessage(),
                "existing-case echo must not be rewritten by admin (single source)");
    }

    @Test
    void DTO镜像字段名与core线格式逐字段对齐() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String coreWire = """
                {"caseId":101,"caseNo":"CASE-0101","requestId":9,"projectId":11}
                """;
        PromotionResultDto dto = mapper.readValue(coreWire, PromotionResultDto.class);
        assertEquals(101L, dto.caseId());
        assertEquals("CASE-0101", dto.caseNo());
        assertEquals(9L, dto.requestId());
        assertEquals(11L, dto.projectId());

        // record shape lock: admin mirror must carry exactly the four core components
        assertEquals(4, PromotionResultDto.class.getRecordComponents().length,
                "PromotionResultDto shape drift (added/removed field)");
        for (RecordComponent rc : PromotionResultDto.class.getRecordComponents()) {
            Class<?> expected = "caseNo".equals(rc.getName()) ? String.class : long.class;
            assertEquals(expected, rc.getType(),
                    "field " + rc.getName() + " type drift vs core wire shape");
        }
    }
}
