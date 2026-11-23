package com.sixbean.testpike.common.approval;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.Map;
import java.util.Set;

/**
 * <ul>
 * </ul>
 *
 */
public final class ApprovalStateMachine {

    
    private static final Map<ApprovalEvent, Map<Integer, Integer>> TRANSITIONS = Map.of(
            ApprovalEvent.SUBMIT, Map.of(
                    ApprovalStatus.DRAFT.code(), ApprovalStatus.PENDING_APPROVAL.code(),
                    ApprovalStatus.PENDING_APPROVAL.code(), ApprovalStatus.PENDING_APPROVAL.code(),
                    ApprovalStatus.REJECTED.code(), ApprovalStatus.PENDING_APPROVAL.code(),
                    ApprovalStatus.PUBLISHED.code(), ApprovalStatus.PENDING_APPROVAL.code()),
            ApprovalEvent.APPROVE, Map.of(
                    ApprovalStatus.PENDING_APPROVAL.code(), ApprovalStatus.PUBLISHED.code()),
            ApprovalEvent.REJECT, Map.of(
                    ApprovalStatus.PENDING_APPROVAL.code(), ApprovalStatus.REJECTED.code()),
            ApprovalEvent.RETIRE, Map.of(
                    ApprovalStatus.PUBLISHED.code(), ApprovalStatus.RETIRED.code()));

 
    private static final Set<Integer> TERMINAL_STATES = Set.of(ApprovalStatus.RETIRED.code());

    private ApprovalStateMachine() {
    }

    
    public static int transition(int currentStatus, ApprovalEvent event, String entityLabel) {
        Map<Integer, Integer> bySource = TRANSITIONS.get(event);
        Integer target = bySource == null ? null : bySource.get(currentStatus);
        if (target != null) {
            return target;
        }
        String statusLabel = ApprovalStatus.labelOf(currentStatus);
        if (TERMINAL_STATES.contains(currentStatus)) {
            throw new ServiceException(
                    entityLabel + "已终止（" + statusLabel + "），不能再执行「" + event.label()
                            + "」操作",
                    BizErrorCode.VALIDATION_ERROR);
        }
        throw new ServiceException(
                entityLabel + "当前状态（" + statusLabel + "）不允许「" + event.label()
                        + "」，请检查状态机流程（仅待审批可审批通过/拒绝）",
                BizErrorCode.VALIDATION_ERROR);
    }

 
    public static boolean isPublished(int status) {
        return status == ApprovalStatus.PUBLISHED.code();
    }
}
