package com.sixbean.testpike.admin.apitest.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.apitest.api.dto.ResponseCompareMode;
import com.sixbean.testpike.admin.apitest.api.dto.ResponseCompareView;
import com.sixbean.testpike.admin.apitest.api.dto.ResponseDiffEntry;
import com.sixbean.testpike.admin.apitest.api.dto.ResponseDiffKind;
import com.sixbean.testpike.admin.apitest.api.dto.RunView;
import com.sixbean.testpike.admin.apitest.app.component.ApiTestAccessResolver;
import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreApiRunClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin bridge contract for the run-history response compare endpoint (W-c5).
 * <p>Assertion surface (RunSetBridgeTest precedent):
 * ① the view guard for BOTH sides runs before the compare hop — a non-member
 * must never reach core's compare, even when only one side is foreign;
 * ② the projectId is derived from the run ledger rows themselves (same
 * single-source rule as {@code get(id)} — client-supplied ids only);
 * ③ the DTO mirror field names match core's wire shape exactly — drift would
 * silently drop diff entries during deserialization;
 * ④ core 400 (no snapshot to compare) passes through verbatim — admin must
 * not re-implement validation (red line: single source).
 * <p>Client/guard stubs = bridge-contract L2 isolation; the real admin→core→PG
 * chain is covered by the closing package.
 */
class ApiRunCompareBridgeTest {

    private final CoreApiRunClient client = mock(CoreApiRunClient.class);
    private final ApiTestAccessResolver accessResolver = mock(ApiTestAccessResolver.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ApiRunController controller = new ApiRunController(client, accessResolver, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TESTER"), 0L, null));
    }

    private RunView run(long id, long requestId) {
        return new RunView(id, requestId, 2L, 1, 120L, "[]", "", "");
    }

    @Test
    void compare_guards_both_sides_before_core_and_passes_view_through() {
        login();
        when(client.getRun(11L)).thenReturn(run(11L, 5L));
        when(client.getRun(12L)).thenReturn(run(12L, 6L));
        when(accessResolver.projectIdOfRequest(5L)).thenReturn(11L);
        when(accessResolver.projectIdOfRequest(6L)).thenReturn(11L);
        when(client.compareRuns(11L, 12L)).thenReturn(new ResponseCompareView(
                ResponseCompareMode.JSON, false, true, "aa", "bb",
                List.of(new ResponseDiffEntry("$.a", ResponseDiffKind.VALUE_CHANGED,
                        "number", "string", "1", "\"x\""))));

        Response<ResponseCompareView> resp = controller.compare(11L, 12L);

        // Both sides resolve to the same project here — the guard must run once
        // per side (2 hops), which is exactly the no-short-circuit evidence.
        verify(guard, times(2)).assertCanView(any(CurrentUser.class), eq(11L));
        assertEquals(ResponseCompareMode.JSON, resp.getData().mode());
        assertEquals(1, resp.getData().entries().size());
        assertEquals("$.a", resp.getData().entries().get(0).path());
        assertEquals(Boolean.TRUE, resp.getData().rightTruncated());
    }

    @Test
    void compare_nonmember_is_refused_and_core_compare_is_never_called() {
        login();
        when(client.getRun(11L)).thenReturn(run(11L, 5L));
        when(client.getRun(12L)).thenReturn(run(12L, 6L));
        when(accessResolver.projectIdOfRequest(5L)).thenReturn(11L);
        when(accessResolver.projectIdOfRequest(6L)).thenReturn(99L);
        org.mockito.Mockito.doThrow(new ServiceException("无权限", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(CurrentUser.class), eq(99L));

        assertThrows(ServiceException.class, () -> controller.compare(11L, 12L));
        verify(client, never()).compareRuns(anyLong(), anyLong());
    }

    @Test
    void compare_core_400_no_snapshot_text_passes_through_verbatim() {
        login();
        when(client.getRun(11L)).thenReturn(run(11L, 5L));
        when(client.getRun(12L)).thenReturn(run(12L, 5L));
        when(accessResolver.projectIdOfRequest(5L)).thenReturn(11L);
        when(client.compareRuns(11L, 12L)).thenThrow(new ServiceException(
                "该运行未派发执行或派发单已清理，无响应快照可比对", BizErrorCode.VALIDATION_ERROR));

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.compare(11L, 12L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode().intValue());
        assertEquals("该运行未派发执行或派发单已清理，无响应快照可比对", ex.getMessage(),
                "core validation text must survive the bridge verbatim");
    }

    @Test
    void compare_dto_mirror_field_names_lock_to_core_wire_shape() throws Exception {
        // Wire sample captured from core ResponseCompareView serialization.
        String wire = """
                {"mode":"JSON","leftTruncated":false,"rightTruncated":true,
                 "leftSha256":"aa","rightSha256":"bb",
                 "entries":[{"path":"$.a","kind":"VALUE_CHANGED","leftType":"number",
                             "rightType":"string","leftSummary":"1","rightSummary":"\\"x\\""}]}
                """;
        ResponseCompareView view = new ObjectMapper().readValue(wire, ResponseCompareView.class);
        assertEquals(ResponseCompareMode.JSON, view.mode());
        assertEquals("bb", view.rightSha256());
        assertEquals(ResponseDiffEntry.class, view.entries().get(0).getClass());

        // record shape lock: drift on either mirror = silent field loss
        List<String> viewNames = Arrays.stream(
                ResponseCompareView.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertEquals(List.of("mode", "leftTruncated", "rightTruncated",
                "leftSha256", "rightSha256", "entries"), viewNames,
                "ResponseCompareView shape drift vs core wire");
        List<String> entryNames = Arrays.stream(
                ResponseDiffEntry.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertEquals(List.of("path", "kind", "leftType", "rightType",
                "leftSummary", "rightSummary"), entryNames,
                "ResponseDiffEntry shape drift vs core wire");
    }
}
