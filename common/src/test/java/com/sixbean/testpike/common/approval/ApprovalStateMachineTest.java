package com.sixbean.testpike.common.approval;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ApprovalStateMachineTest {


    @Test
    void fullApprovalChainDraftToPublished() {
 // draft→submit→approve→published
        int pending = ApprovalStateMachine.transition(
                ApprovalStatus.DRAFT.code(), ApprovalEvent.SUBMIT, "SKILL");
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), pending);
        int published = ApprovalStateMachine.transition(
                pending, ApprovalEvent.APPROVE, "SKILL");
        assertEquals(ApprovalStatus.PUBLISHED.code(), published);
    }

    @Test
    void submitAllowedFromDraftPendingAndRejectedAndPublished() {
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), ApprovalStateMachine.transition(
                ApprovalStatus.DRAFT.code(), ApprovalEvent.SUBMIT, "SKILL"));
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), ApprovalStateMachine.transition(
                ApprovalStatus.REJECTED.code(), ApprovalEvent.SUBMIT, "SKILL"));
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), ApprovalStateMachine.transition(
                ApprovalStatus.PUBLISHED.code(), ApprovalEvent.SUBMIT, "SKILL"));
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), ApprovalStateMachine.transition(
                ApprovalStatus.PENDING_APPROVAL.code(), ApprovalEvent.SUBMIT, "SKILL"));
    }

    @Test
    void rejectPendingRejected() {
        assertEquals(ApprovalStatus.REJECTED.code(), ApprovalStateMachine.transition(
                ApprovalStatus.PENDING_APPROVAL.code(), ApprovalEvent.REJECT, "SKILL"));
    }

    @Test
    void retireFromPublished() {
        assertEquals(ApprovalStatus.RETIRED.code(), ApprovalStateMachine.transition(
                ApprovalStatus.PUBLISHED.code(), ApprovalEvent.RETIRE, "SKILL"));
    }


    @Test
    void directJumpDraftToApproveRejectedWith40001() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                ApprovalStateMachine.transition(
                        ApprovalStatus.DRAFT.code(), ApprovalEvent.APPROVE, "SKILL"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode().intValue());
        assertTrue(ex.getMessage().contains("草稿"), "消息须含当前状态中文人话：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("审批通过"), "消息须含动作中文人话：" + ex.getMessage());
    }

    @Test
    void approveFromRejectedIllegal() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                ApprovalStateMachine.transition(
                        ApprovalStatus.REJECTED.code(), ApprovalEvent.APPROVE, "SKILL"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode().intValue());
    }

    @Test
    void submitFromRetiredIllegalWithTerminalWording() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                ApprovalStateMachine.transition(
                        ApprovalStatus.RETIRED.code(), ApprovalEvent.SUBMIT, "SKILL"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode().intValue());
        assertTrue(ex.getMessage().contains("已终止"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SKILL"), "entityLabel 须进消息：" + ex.getMessage());
    }

    @Test
    void illegalMatrixAllEventSourceCombinations() {
        int[][] legal = {
                {ApprovalStatus.DRAFT.code(), ApprovalEvent.SUBMIT.ordinal()},
                {ApprovalStatus.PENDING_APPROVAL.code(), ApprovalEvent.SUBMIT.ordinal()},
                {ApprovalStatus.REJECTED.code(), ApprovalEvent.SUBMIT.ordinal()},
                {ApprovalStatus.PUBLISHED.code(), ApprovalEvent.SUBMIT.ordinal()},
                {ApprovalStatus.PENDING_APPROVAL.code(), ApprovalEvent.APPROVE.ordinal()},
                {ApprovalStatus.PENDING_APPROVAL.code(), ApprovalEvent.REJECT.ordinal()},
                {ApprovalStatus.PUBLISHED.code(), ApprovalEvent.RETIRE.ordinal()},
        };
        for (int status = 1; status <= 5; status++) {
            int currentStatus = status;
            for (ApprovalEvent event : ApprovalEvent.values()) {
                boolean allowed = false;
                for (int[] pair : legal) {
                    if (pair[0] == status && pair[1] == event.ordinal()) {
                        allowed = true;
                        break;
                    }
                }
                if (allowed) {
                    continue;
                }
                ServiceException ex = assertThrows(ServiceException.class, () ->
                        ApprovalStateMachine.transition(currentStatus, event, "SKILL"),
                        "非法组合静默放行：status=" + status + " event=" + event);
                assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode().intValue());
            }
        }
    }

    @Test
    void unknownStatusCodeRejectedNotSilent() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                ApprovalStateMachine.transition(99, ApprovalEvent.APPROVE, "SKILL"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode().intValue());
        assertTrue(ex.getMessage().contains("99"), ex.getMessage());
    }


    @Test
    void isPublishedDrivesIdempotentShortcut() {
        assertTrue(ApprovalStateMachine.isPublished(ApprovalStatus.PUBLISHED.code()));
        assertFalse(ApprovalStateMachine.isPublished(ApprovalStatus.DRAFT.code()));
        assertFalse(ApprovalStateMachine.isPublished(ApprovalStatus.PENDING_APPROVAL.code()));
        assertFalse(ApprovalStateMachine.isPublished(ApprovalStatus.RETIRED.code()));
        assertFalse(ApprovalStateMachine.isPublished(99));
    }

    @Test
    void onlyPublishedIsReferencableSemanticsExposedViaLabel() {
        assertEquals("草稿", ApprovalStatus.of(ApprovalStatus.DRAFT.code()).label());
        assertEquals("已发布", ApprovalStatus.PUBLISHED.label());
        assertEquals("已下架", ApprovalStatus.RETIRED.label());
    }

    @Test
    void enumOfUnknownReturnsNullForCallerGuard() {
        assertNull(ApprovalStatus.of(0));
        assertNull(ApprovalStatus.of(6));
        assertNotNull(ApprovalStatus.of(4));
        assertEquals("待审批", ApprovalStatus.of(2).label());
    }
}
