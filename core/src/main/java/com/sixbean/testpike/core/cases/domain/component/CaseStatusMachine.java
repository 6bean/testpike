package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;

import com.sixbean.testpike.common.exception.ServiceException;

import java.util.Map;
import java.util.Set;

/**
 * The use-case state-machine engine implements the transition table as a pure, stateless function.
 * <p>The transition table maps events to source-state-to-target-state mappings and registers three review actions, with quick review moving pending review directly to a terminal state.
 * Formal review moves from reviewing to a terminal state, and the engine reserves edit_begin, edit_save, and restore for edit locking and recovery from misjudgment.
 * <p>Disallowed transitions throw 40902 (STATE_MACHINE_REJECT), and terminal states (approved or rejected) are intercepted.
 * Non-review states are intercepted; execution is read-only, and modifications require editing and saving back to pending review through the edit_save transition.
 *
 */
public final class CaseStatusMachine {

 /** Events map to source-state-to-target-state mappings, covering the implemented subset and reserved transitions. */
    private static final Map<CaseEvent, Map<Integer, Integer>> TRANSITIONS = Map.of(
 // The three review actions include a quick-review path that moves pending review directly to a terminal state when Enter approves, and formal review that moves reviewing to a terminal state.
            CaseEvent.PASS, Map.of(
                    CaseStatus.PENDING_REVIEW.code(), CaseStatus.APPROVED.code(),
                    CaseStatus.IN_REVIEW.code(), CaseStatus.APPROVED.code()),
            CaseEvent.NEED_CHANGE, Map.of(
                    CaseStatus.PENDING_REVIEW.code(), CaseStatus.NEED_CHANGE.code(),
                    CaseStatus.IN_REVIEW.code(), CaseStatus.NEED_CHANGE.code()),
            CaseEvent.REJECT, Map.of(
                    CaseStatus.PENDING_REVIEW.code(), CaseStatus.REJECTED.code(),
                    CaseStatus.IN_REVIEW.code(), CaseStatus.REJECTED.code()),
 // Engine-level reserved transitions support edit locking and recovery from misjudgment in the transition table.
            CaseEvent.EDIT_BEGIN, Map.of(CaseStatus.PENDING_REVIEW.code(), CaseStatus.IN_REVIEW.code()),
            CaseEvent.EDIT_SAVE, Map.of(CaseStatus.NEED_CHANGE.code(), CaseStatus.PENDING_REVIEW.code()),
            CaseEvent.RESTORE, Map.of(CaseStatus.REJECTED.code(), CaseStatus.PENDING_REVIEW.code()));

 /** The terminal-state set always intercepts subsequent operations, and the terminal states are approved and rejected. */
    private static final Set<Integer> FINAL_STATES = Set.of(
            CaseStatus.APPROVED.code(), CaseStatus.REJECTED.code());

    private CaseStatusMachine() {
    }

    /** Terminal check for read-only consumers (flow guide / tool answers) — single source, no copy. */
    public static boolean isTerminal(int currentStatus) {
        return FINAL_STATES.contains(currentStatus);
    }

    /**
     * Events legally fireable from a status, derived from TRANSITIONS itself
     * (read-only probe; the flow guide must not keep a second edge table).
     */
    public static java.util.List<CaseEvent> legalEventsFrom(int currentStatus) {
        java.util.List<CaseEvent> events = new java.util.ArrayList<>();
        for (java.util.Map.Entry<CaseEvent, java.util.Map<Integer, Integer>> entry
                : TRANSITIONS.entrySet()) {
            if (entry.getValue().containsKey(currentStatus)) {
                events.add(entry.getKey());
            }
        }
        return events;
    }

    /** Target status of a legal event (assumes legality — callers probe via legalEventsFrom first). */
    public static int targetOf(int currentStatus, CaseEvent event) {
        return TRANSITIONS.get(event).get(currentStatus);
    }

    /**
     * State transition returns the target state for legal transitions and throws 40902 for illegal transitions, with the message distinguishing terminal-state rejection from state-not-allowed rejection.
     *
     * @param currentStatus The current status code (1 to 9).
     * @param event The state-machine event.
     * @return The target status code.
     */
    public static int transition(int currentStatus, CaseEvent event) {
        Map<Integer, Integer> bySource = TRANSITIONS.get(event);
        Integer target = bySource == null ? null : bySource.get(currentStatus);
        if (target != null) {
            return target;
        }
        if (FINAL_STATES.contains(currentStatus)) {
            CaseStatus status = CaseStatus.of(currentStatus);
            String label = status == null ? String.valueOf(currentStatus) : status.label();
            if (status == CaseStatus.APPROVED) {
                throw new ServiceException(
                        "用例已是终态（" + label + "），动作 " + event.code() + " 被拦截——如需变更内容，"
                                + "请在「用例管理」打开该用例编辑并保存，改动标题/前置条件/步骤/预期结果后"
                                + "会自动回到「待评审」重新进入评审队列",
                        BizErrorCode.STATE_MACHINE_REJECT);
            }
            throw new ServiceException(
                    "用例已是终态（" + label + "），动作 " + event.code() + " 被拦截——评审结论已定且平台未提供"
                            + "重开评审的入口，请在「用例管理」按修订后的内容新建用例；如需退回修改，"
                            + "请在评审当时改用「需修改」",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        CaseStatus status = CaseStatus.of(currentStatus);
        String label = status == null ? String.valueOf(currentStatus) : status.label();
        String canDo = legalEventsFrom(currentStatus).stream()
                .map(e -> "「" + e.label() + "」（在" + CaseFlowScreens.screenOf(e) + "操作）")
                .collect(java.util.stream.Collectors.joining("、"));
        throw new ServiceException(
                "用例当前状态（" + label + "）不允许动作 " + event.code() + "；该状态下可执行："
                        + (canDo.isEmpty() ? "无" : canDo)
                        + "——请改到「评审工作台」处理「待评审」或「评审中」的用例，"
                        + "或先在「用例管理」编辑并保存使「需修改」的用例回到「待评审」",
                BizErrorCode.STATE_MACHINE_REJECT);
    }
}
