package com.sixbean.testpike.core.cases.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.FlowGuideInfo;
import com.sixbean.testpike.core.cases.api.dto.FlowGuideAction;
import com.sixbean.testpike.core.cases.api.dto.FlowGuideStateNode;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.component.CaseEvent;
import com.sixbean.testpike.core.cases.domain.component.CaseStatus;
import com.sixbean.testpike.core.cases.domain.component.CaseStatusMachine;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flow-guide query service tests (AO-9 E8: "what can I do next from this state").
 * Real dev DB, no mocks. Four criteria:
 * 1) the emitted edge set reconciles with CaseStatusMachine through its public
 *    transition probe only (never by reading the private table — anti self-proof);
 * 2) zero second vocabulary source (labels must equal CaseStatus.label(), same for
 *    target status labels);
 * 3) live shape = current status + actionable events + structured route field
 *    (server-side page value, never a client-guessed path);
 * 4) unknown/deleted case fails honestly with a human message, not an empty graph.
 */
@SpringBootTest(classes = CoreApplication.class)
class CaseFlowGuideServiceTest {

    @Autowired
    private CaseFlowGuideService caseFlowGuideService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> guideTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Shared dev database (R37): no blanket table wipe — the tracked-project
        // FK sweep purges this suite's cases, steps, scripts, version snapshots,
        // exec tasks, defects and audit rows.
        ProjectFixtureRig.sweep(jdbcTemplate, guideTrackedProjects);
        guideTrackedProjects.clear();
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                guideTrackedProjects, "项目" + code, code);
    }

    private long createCase(long projectId, String title) {
        return caseService.create(1L, new CaseCreateInput(projectId, title, null, null, 1, 2, "web",
                1, null, 1, null, List.of(new CaseStepInput(1, "打开应用", 0)),
                null, null, null, null, null)).id();
    }

    /** Derive the legal edge set purely through the public transition probe (no private-table read). */
    private Set<String> edgesByProbingMachine() {
        Set<String> edges = new HashSet<>();
        for (CaseStatus status : CaseStatus.values()) {
            for (CaseEvent event : CaseEvent.values()) {
                try {
                    int target = CaseStatusMachine.transition(status.code(), event);
                    edges.add(status.code() + "|" + event.code() + "|" + target);
                } catch (RuntimeException expected) {
                    // Illegal transition per the machine itself — not an edge.
                }
            }
        }
        return edges;
    }

    @Test
    void graphEdgesReconcileWithMachineTransitionTable() {
        FlowGuideInfo graph = caseFlowGuideService.guide(null);

        Set<String> serviceEdges = new HashSet<>();
        for (FlowGuideStateNode node : graph.states()) {
            for (FlowGuideAction action : node.actions()) {
                serviceEdges.add(node.code() + "|" + action.event() + "|" + action.toStatus());
            }
        }
        assertEquals(edgesByProbingMachine(), serviceEdges,
                "流转图谱必须与状态机转换表逐事件对账一致（禁第二套转换账）");

        // Terminal flags reconcile with the machine's own terminal set (single source).
        Set<Integer> terminalCodes = new HashSet<>();
        for (FlowGuideStateNode node : graph.states()) {
            assertEquals(CaseStatusMachine.isTerminal(node.code()), node.terminal(),
                    "终态标记必须与状态机终态集对账");
            if (node.terminal()) {
                terminalCodes.add(node.code());
            }
        }
        assertTrue(terminalCodes.contains(CaseStatus.APPROVED.code())
                        && terminalCodes.contains(CaseStatus.REJECTED.code()),
                "已通过/已拒绝必须标记为终态");
    }

    @Test
    void vocabularyComesFromStatusEnumWithZeroSecondSource() {
        FlowGuideInfo graph = caseFlowGuideService.guide(null);
        assertEquals(CaseStatus.values().length, graph.states().size(), "图谱应覆盖 9 态全集");

        for (FlowGuideStateNode node : graph.states()) {
            CaseStatus status = CaseStatus.of(node.code());
            assertNotNull(status);
            assertEquals(status.label(), node.label(), "状态名必须等于枚举 label（词表单源禁二套）");
            for (FlowGuideAction action : node.actions()) {
                assertEquals(CaseStatus.of(action.toStatus()).label(), action.toStatusLabel(),
                        "目标状态名同样取自枚举 label");
                // Action route suggestion is a structured server field, never a client-side guess.
                assertNotNull(action.page(), "可走动作必须带服务端结构化直达页字段");
            }
        }
    }

    @Test
    void liveCaseGuideReturnsCurrentStateWithActionableEvents() throws Exception {
        long projectId = createProject("FG1");
        long caseId = createCase(projectId, "指引用例");

        FlowGuideInfo guide = caseFlowGuideService.guide(caseId);
        assertEquals(CaseStatus.PENDING_REVIEW.code(), guide.currentStatus(),
                "新建用例实况当前态=待评审");
        assertNotNull(guide.currentStatusLabel());
        assertFalse(guide.actionsForCurrent().isEmpty(), "待评审态必有可走事件");
        Set<String> events = new HashSet<>();
        for (FlowGuideAction action : guide.actionsForCurrent()) {
            events.add(action.event());
        }
        assertTrue(events.contains("pass") && events.contains("need_change") && events.contains("reject"),
                "待评审可走事件=评审三动作");

        // JSON envelope shape is what the dialog tool consumes — serialize once to pin the contract.
        String json = objectMapper.writeValueAsString(guide);
        assertTrue(json.contains("\"caseId\":" + caseId));
        assertTrue(json.contains(CaseStatus.PENDING_REVIEW.label()));
    }

    @Test
    void fullGraphWithoutCaseIdAnswersVocabularyQuestion() {
        FlowGuideInfo graph = caseFlowGuideService.guide(null);
        assertTrue(graph.states().size() > 0);
        // No live case asked for → no current state, but the whole map is answerable.
        assertTrue(graph.caseId() == null || graph.caseId() == 0L);
    }

    @Test
    void unknownCaseIdFailsHonestly() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> caseFlowGuideService.guide(999999999L));
        assertTrue(ex.getMessage().contains("用例") && ex.getMessage().contains("不存在"),
                "不存在用例必须人话点名，禁静默返回空图谱诱导新建");
    }
}
