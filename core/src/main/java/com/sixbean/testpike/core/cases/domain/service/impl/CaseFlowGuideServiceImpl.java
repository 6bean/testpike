package com.sixbean.testpike.core.cases.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cases.api.dto.FlowGuideAction;
import com.sixbean.testpike.core.cases.api.dto.FlowGuideInfo;
import com.sixbean.testpike.core.cases.api.dto.FlowGuideStateNode;
import com.sixbean.testpike.core.cases.domain.component.CaseEvent;
import com.sixbean.testpike.core.cases.domain.component.CaseStatus;
import com.sixbean.testpike.core.cases.domain.component.CaseStatusMachine;
import com.sixbean.testpike.core.cases.domain.service.CaseFlowGuideService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flow guide implementation. Edges and labels are probed live from
 * CaseStatusMachine/CaseStatus/CaseEvent — no second transition table, no second
 * status vocabulary (AO-9 acceptance: answers must reconcile with the machine).
 * <p>Route field = front-end pages that actually perform the action; the dialog
 * whitelist-validates them against menuStructure, so values must be route paths.
 */
@Service
public class CaseFlowGuideServiceImpl implements CaseFlowGuideService {

    /** Review-workbench route (pass / need_change / reject / edit_begin happen there). */
    private static final String PAGE_REVIEW = "/review";
    /** Case list route (edit-save back, restore, archive family are list/detail actions). */
    private static final String PAGE_CASES = "/cases";
    /** Execution center route (exec-side events land there). */
    private static final String PAGE_EXECUTION = "/execution";

    /** Where each machine event is performed by a human (structured route, never guessed client-side). */
    private static final Map<String, String> EVENT_PAGE = Map.ofEntries(
            Map.entry("pass", PAGE_REVIEW),
            Map.entry("need_change", PAGE_REVIEW),
            Map.entry("reject", PAGE_REVIEW),
            Map.entry("edit_begin", PAGE_REVIEW),
            Map.entry("edit_save", PAGE_CASES),
            Map.entry("restore", PAGE_CASES),
            Map.entry("archive", PAGE_CASES),
            Map.entry("unarchive", PAGE_CASES),
            Map.entry("exec_start", PAGE_EXECUTION),
            Map.entry("exec_success", PAGE_EXECUTION),
            Map.entry("exec_fail", PAGE_EXECUTION),
            Map.entry("retry_env_ok", PAGE_EXECUTION),
            Map.entry("defect_fixed", PAGE_CASES));

    private final JdbcTemplate jdbcTemplate;

    public CaseFlowGuideServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public FlowGuideInfo guide(Long caseId) {
        Integer currentStatus = null;
        String title = null;
        if (caseId != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT title, status FROM tp_test_case WHERE id = ? AND deleted = 0", caseId);
            if (rows.isEmpty()) {
                throw new ServiceException("用例不存在或已被删除（id=" + caseId + "），无法给出流转指引",
                        BizErrorCode.VALIDATION_ERROR);
            }
            Map<String, Object> row = rows.get(0);
            title = (String) row.get("title");
            currentStatus = ((Number) row.get("status")).intValue();
        }
        return new FlowGuideInfo(caseId, title, currentStatus,
                currentStatus == null ? null : labelOf(currentStatus), buildStates());
    }

    /** Whole graph derived from the machine probe for every registered status. */
    private List<FlowGuideStateNode> buildStates() {
        List<FlowGuideStateNode> states = new ArrayList<>();
        for (CaseStatus status : CaseStatus.values()) {
            List<FlowGuideAction> actions = new ArrayList<>();
            for (CaseEvent event : CaseStatusMachine.legalEventsFrom(status.code())) {
                int target = CaseStatusMachine.targetOf(status.code(), event);
                actions.add(new FlowGuideAction(event.code(), event.label(), target,
                        labelOf(target), EVENT_PAGE.getOrDefault(event.code(), PAGE_CASES), "caseId"));
            }
            states.add(new FlowGuideStateNode(status.code(), status.label(),
                    CaseStatusMachine.isTerminal(status.code()), List.copyOf(actions)));
        }
        return List.copyOf(states);
    }

    private static String labelOf(int code) {
        CaseStatus status = CaseStatus.of(code);
        return status == null ? String.valueOf(code) : status.label();
    }
}
