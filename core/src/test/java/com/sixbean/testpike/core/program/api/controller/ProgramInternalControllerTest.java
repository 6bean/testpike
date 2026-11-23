package com.sixbean.testpike.core.program.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.program.api.dto.ProgramCreateInput;
import com.sixbean.testpike.core.program.api.dto.ProgramInfo;
import com.sixbean.testpike.core.program.api.dto.ProgramMemberGrantInput;
import com.sixbean.testpike.core.program.api.dto.ProgramMemberInfo;
import com.sixbean.testpike.core.program.api.dto.ProgramMemberRoleInput;
import com.sixbean.testpike.core.program.api.dto.ProgramUpdateInput;
import com.sixbean.testpike.core.program.domain.service.ProgramMemberService;
import com.sixbean.testpike.core.program.domain.service.ProgramService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Program internal contract test (same posture as UpgradePlanInternalControllerTest):
 * the controller is a thin wrapper — create/update/state/delete/attach/detach and the
 * group-grant mutations are forwarded verbatim to the services with the operator taken
 * from X-Operator-Id, every validation and audit write stays inside the service (red
 * line: zero second judgement at the api layer).
 */
class ProgramInternalControllerTest {

    private final ProgramService programService = mock(ProgramService.class);
    private final ProgramMemberService memberService = mock(ProgramMemberService.class);
    private final ProgramInternalController controller =
            new ProgramInternalController(programService, memberService);

    @BeforeEach
    void loginOperator() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(OperatorContext.OPERATOR_ID_HEADER, "7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void create_forwards_name_description_and_resolved_operator() {
        when(programService.create("中台集成", "订单+库存中心", 7L)).thenReturn(42L);

        Response<Long> got = controller.create(new ProgramCreateInput("中台集成", "订单+库存中心"));

        assertEquals(42L, got.getData().longValue());
        verify(programService).create("中台集成", "订单+库存中心", 7L);
    }

    @Test
    void update_state_and_delete_are_pure_forwarding() {
        controller.update(3L, new ProgramUpdateInput("改名", null));
        verify(programService).update(3L, "改名", null, 7L);

        controller.setState(3L, 0);
        verify(programService).setState(3L, 0, 7L);

        controller.delete(3L);
        verify(programService).delete(3L, 7L);
    }

    @Test
    void get_list_and_project_ids_pass_through_service_rows_untouched() {
        ProgramInfo info = new ProgramInfo(3L, "中台集成", "d", 1, "2026-10-31 21:00:00");
        when(programService.get(3L)).thenReturn(info);
        when(programService.listAll()).thenReturn(List.of(info));
        when(programService.listProjectIds(3L)).thenReturn(List.of(11L, 12L));

        assertEquals(info, controller.get(3L).getData());
        assertEquals(List.of(info), controller.list().getData());
        assertEquals(List.of(11L, 12L), controller.listProjectIds(3L).getData());
    }

    @Test
    void attach_and_detach_forward_pair_and_operator() {
        controller.attachProject(3L, 11L);
        verify(programService).attachProject(3L, 11L, 7L);

        controller.detachProject(3L, 12L);
        verify(programService).detachProject(3L, 12L, 7L);
    }

    @Test
    void grant_mutation_forward_user_role_and_operator() {
        controller.addMember(3L, new ProgramMemberGrantInput(9L, 1));
        verify(memberService).add(3L, 9L, 1, 7L);

        controller.updateMemberRole(3L, 9L, new ProgramMemberRoleInput(2));
        verify(memberService).updateRole(3L, 9L, 2, 7L);

        controller.removeMember(3L, 9L);
        verify(memberService).remove(3L, 9L, 7L);
    }

    @Test
    void member_listing_returns_grant_rows_as_stored() {
        List<ProgramMemberInfo> rows = List.of(new ProgramMemberInfo(9L, 1),
                new ProgramMemberInfo(10L, 2));
        when(memberService.list(anyLong())).thenReturn(rows);

        assertEquals(rows, controller.listMembers(3L).getData());
        verify(memberService).list(3L);
    }
}
