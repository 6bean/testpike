package com.sixbean.testpike.core.cases;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.cases.domain.component.CaseEvent;
import com.sixbean.testpike.core.cases.domain.component.CaseStatus;
import com.sixbean.testpike.core.cases.domain.component.CaseStatusMachine;


import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class CaseStatusMachineTest {


    @Test
    void s1_待评审_editBegin_评审中_pass_已通过() {
        assertEquals(CaseStatus.IN_REVIEW.code(),
                CaseStatusMachine.transition(CaseStatus.PENDING_REVIEW.code(), CaseEvent.EDIT_BEGIN));
        assertEquals(CaseStatus.APPROVED.code(),
                CaseStatusMachine.transition(CaseStatus.IN_REVIEW.code(), CaseEvent.PASS));
    }


    @Test
    void rvw_待评审_pass_已通过() {
        assertEquals(CaseStatus.APPROVED.code(),
                CaseStatusMachine.transition(CaseStatus.PENDING_REVIEW.code(), CaseEvent.PASS));
    }

    @Test
    void rvw_待评审_needChange_需修改() {
        assertEquals(CaseStatus.NEED_CHANGE.code(),
                CaseStatusMachine.transition(CaseStatus.PENDING_REVIEW.code(), CaseEvent.NEED_CHANGE));
    }

    @Test
    void rvw_待评审_reject_已拒绝() {
        assertEquals(CaseStatus.REJECTED.code(),
                CaseStatusMachine.transition(CaseStatus.PENDING_REVIEW.code(), CaseEvent.REJECT));
    }


    @Test
    void rvw_评审中_pass_已通过() {
        assertEquals(CaseStatus.APPROVED.code(),
                CaseStatusMachine.transition(CaseStatus.IN_REVIEW.code(), CaseEvent.PASS));
    }

    @Test
    void rvw_评审中_needChange_需修改() {
        assertEquals(CaseStatus.NEED_CHANGE.code(),
                CaseStatusMachine.transition(CaseStatus.IN_REVIEW.code(), CaseEvent.NEED_CHANGE));
    }

    @Test
    void rvw_评审中_reject_已拒绝() {
        assertEquals(CaseStatus.REJECTED.code(),
                CaseStatusMachine.transition(CaseStatus.IN_REVIEW.code(), CaseEvent.REJECT));
    }


    @Test
    void s6_已通过_再pass_40902终态拦截() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                CaseStatusMachine.transition(CaseStatus.APPROVED.code(), CaseEvent.PASS));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
    }

    @Test
    void s6_已通过_再reject_40902终态拦截() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                CaseStatusMachine.transition(CaseStatus.APPROVED.code(), CaseEvent.REJECT));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
        assertEquals(true, ex.getMessage().contains("终态"), "终态消息应明确：%s".formatted(ex.getMessage()));
    }

    @Test
    void s6_已拒绝_再needChange_40902终态拦截() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                CaseStatusMachine.transition(CaseStatus.REJECTED.code(), CaseEvent.NEED_CHANGE));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
    }


    @Test
    void s5_执行中_评审_40902() {
        for (CaseEvent event : new CaseEvent[]{CaseEvent.PASS, CaseEvent.NEED_CHANGE, CaseEvent.REJECT}) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    CaseStatusMachine.transition(CaseStatus.EXECUTING.code(), event));
            assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode(), "事件 " + event.code());
        }
    }

    @Test
    void rvw_需修改_评审_40902() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                CaseStatusMachine.transition(CaseStatus.NEED_CHANGE.code(), CaseEvent.PASS));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
    }


    @Test
    void engine_需修改_editSave_回待评审() {
        assertEquals(CaseStatus.PENDING_REVIEW.code(),
                CaseStatusMachine.transition(CaseStatus.NEED_CHANGE.code(), CaseEvent.EDIT_SAVE));
    }

    @Test
    void engine_已拒绝_restore_回待评审() {
        assertEquals(CaseStatus.PENDING_REVIEW.code(),
                CaseStatusMachine.transition(CaseStatus.REJECTED.code(), CaseEvent.RESTORE));
    }


    @Test
    void engine_非法状态码_40902() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                CaseStatusMachine.transition(999, CaseEvent.PASS));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
    }
}
