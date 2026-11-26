package com.sixbean.testpike.core.cases.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cases.api.dto.FlowGuideAction;
import com.sixbean.testpike.core.cases.api.dto.FlowGuideInfo;
import com.sixbean.testpike.core.cases.api.dto.FlowGuideStateNode;
import com.sixbean.testpike.core.cases.domain.component.CaseEvent;
import com.sixbean.testpike.core.cases.domain.component.CaseFlowScreens;
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
                throw new ServiceException("用例不存在或已被删除（id=" + caseId + "），无法给出流转指引"
                        + "——请在「用例管理」确认该用例是否在当前顶栏项目下；若列表中已无该用例，可在「用例管理」的回收站恢复后重试",
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
                        labelOf(target), CaseFlowScreens.routeOf(event), "caseId"));
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
