package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.TraceChain;
import com.sixbean.testpike.core.requirement.api.dto.TraceNode;
import com.sixbean.testpike.core.requirement.api.dto.TraceNodeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class TraceChainServiceTest {

    @Autowired
    private TraceChainService traceChainService;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37): only this suite's own project
        // subtrees — blanket TRUNCATE wiped concurrent chains' rows (r13).
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjectIds);
        fixtureProjectIds.clear();
    }

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject(String code) {
        long id = projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        fixtureProjectIds.add(id);
        return id;
    }

    private long createCase(long projectId, String title) {
        CaseCreateInput input = new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "步骤一", 0)));
        return caseService.create(1L, input).id();
    }

    private long createRequirement(long projectId, String title) {
        return requirementService.create(1L,
                new RequirementCreateInput(projectId, 3, "R-" + title, title, null, null)).id();
    }

    
    private long insertBatchExec(long projectId, long caseId, String batchName, int taskStatus) {
        Long batchId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type) "
                        + "VALUES (?, ?, ?, 3, 1) RETURNING id",
                Long.class, projectId, "B-" + batchName, batchName);
        assert batchId != null;
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, finished_at) "
                + "VALUES (?, ?, ?, ?, now())", projectId, caseId, batchId, taskStatus);
        return batchId;
    }


    @Test
    void traceChainOfRequirement_四段链装配含执行与缺陷双源() {
        long pid = createProject("trace1");
        long reqId = createRequirement(pid, "登录功能");
        long caseA = createCase(pid, "正向登录");
        long caseB = createCase(pid, "逆向登录");
        requirementService.linkCases(1L, reqId, List.of(caseA, caseB));
        long batchId = insertBatchExec(pid, caseA, "冒烟批次", 3);
        Long defectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_defect (project_id, case_id, title, severity, status, reporter_id, external_key) "
                        + "VALUES (?, ?, '登录崩溃', 1, 2, 1, 'BUG-100') RETURNING id",
                Long.class, pid, caseA);
        jdbcTemplate.update("INSERT INTO tp_case_defect (case_id, defect_key, defect_status) "
                + "VALUES (?, 'EXT-9', 'open')", caseA);
        long otherPid = createProject("trace1o");
        long otherCase = createCase(otherPid, "无关用例");
        insertBatchExec(otherPid, otherCase, "无关批次", 3);
        jdbcTemplate.update("INSERT INTO tp_defect (project_id, case_id, title, severity, status, reporter_id) "
                + "VALUES (?, ?, '无关缺陷', 2, 1, 1)", otherPid, otherCase);

        TraceChain chain = traceChainService.traceChainOfRequirement(reqId);

        assertEquals(TraceNodeType.REQUIREMENT, chain.root().type());
        assertEquals(reqId, chain.root().id());
        assertEquals(pid, chain.root().projectId());
        assertEquals("登录功能", chain.root().label());
        assertEquals(1, chain.root().status());
        assertEquals("活跃", chain.root().statusText());
        assertTrue(chain.root().routePath().startsWith("/requirements?projectId=" + pid));

        assertEquals(4, chain.count());
        assertFalse(chain.truncated());
        List<TraceNode> caseNodes = chain.nodes().stream()
                .filter(n -> n.type() == TraceNodeType.CASE).toList();
        assertEquals(2, caseNodes.size());
        TraceNode nodeA = caseNodes.stream().filter(n -> n.id() == caseA).findFirst().orElseThrow();
        assertEquals(batchId, nodeA.lastBatchId());
        assertEquals("冒烟批次", nodeA.lastBatchName());
        assertEquals(3, nodeA.lastExecStatus());
        assertNotNull(nodeA.lastExecTime());
        assertTrue(nodeA.routePath().equals("/cases/" + caseA));
        TraceNode nodeB = caseNodes.stream().filter(n -> n.id() == caseB).findFirst().orElseThrow();
        assertNull(nodeB.lastBatchId(), "无批次执行的用例节点执行字段为 null");
        assertNull(nodeB.lastExecStatus());

        List<TraceNode> defectNodes = chain.nodes().stream()
                .filter(n -> n.type() == TraceNodeType.DEFECT).toList();
        assertEquals(2, defectNodes.size());
        TraceNode lib = defectNodes.stream().filter(n -> "library".equals(n.source())).findFirst().orElseThrow();
        assertEquals(defectId, lib.id());
        assertEquals(2, lib.status());
        assertEquals("处理中", lib.statusText());
        assertTrue(lib.routePath().startsWith("/defects?projectId=" + pid),
                "内置缺陷直达=缺陷库列表 keyword 定位（命中口径=标题，与列表搜索同源）");
        assertTrue(java.net.URLDecoder.decode(lib.routePath(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("keyword=登录崩溃"), "直达 keyword=缺陷标题（列表可命中）");
        TraceNode ext = defectNodes.stream().filter(n -> "external".equals(n.source())).findFirst().orElseThrow();
        assertEquals("EXT-9", ext.label());
        assertEquals("未处理", ext.statusText(), "Jira 态中文映射（人话铁律）");
        assertEquals("/cases/" + caseA, ext.routePath(), "外部关联无缺陷库行，直达=用例详情关联缺陷区块");
    }


    @Test
    void traceChainOfRequirement_用例取最近批次执行() {
        long pid = createProject("trace2");
        long reqId = createRequirement(pid, "需求二");
        long caseId = createCase(pid, "用例二");
        requirementService.linkCases(1L, reqId, List.of(caseId));
        insertBatchExec(pid, caseId, "旧批次", 4);
        long newBatch = insertBatchExec(pid, caseId, "新批次", 3);

        TraceChain chain = traceChainService.traceChainOfRequirement(reqId);
        TraceNode caseNode = chain.nodes().stream()
                .filter(n -> n.type() == TraceNodeType.CASE).findFirst().orElseThrow();
        assertEquals(newBatch, caseNode.lastBatchId(), "按 id 最大=最近一条批次执行（J5 历史同源 ORDER BY id DESC）");
        assertEquals("新批次", caseNode.lastBatchName());
        assertEquals(3, caseNode.lastExecStatus());
    }


    @Test
    void traceChainOfRequirement_超200节点截断带count() {
        long pid = createProject("trace3");
        long reqId = createRequirement(pid, "大需求");
        for (int i = 0; i < 201; i++) {
            long caseId = createCase(pid, "批量用例" + i);
            requirementService.linkCases(1L, reqId, List.of(caseId));
        }

        TraceChain chain = traceChainService.traceChainOfRequirement(reqId);

        assertEquals(TraceChain.MAX_NODES, chain.nodes().size(), "截断到 200");
        assertTrue(chain.truncated());
        assertEquals(201, chain.count(), "count=截断前完整总数");
    }


    @Test
    void traceChain_空链返回空结构不报错() {
        long pid = createProject("trace4");
        long reqId = createRequirement(pid, "孤儿需求");
        long caseId = createCase(pid, "孤儿用例");

        TraceChain emptyReq = traceChainService.traceChainOfRequirement(reqId);
        assertEquals(List.of(), emptyReq.nodes());
        assertEquals(0, emptyReq.count());
        assertFalse(emptyReq.truncated());

        TraceChain emptyCase = traceChainService.traceChainOfCase(caseId);
        assertEquals(TraceNodeType.CASE, emptyCase.root().type());
        assertEquals(List.of(), emptyCase.nodes());
        assertEquals(0, emptyCase.count());
    }


    @Test
    void traceChainOfCase_反向链含所属需求与缺陷() {
        long pid = createProject("trace5");
        long reqActive = createRequirement(pid, "在架需求");
        long reqOff = createRequirement(pid, "下线需求");
        long caseId = createCase(pid, "被链用例");
        requirementService.linkCases(1L, reqActive, List.of(caseId));
        requirementService.linkCases(1L, reqOff, List.of(caseId));
        requirementService.update(1L, reqOff,
                new com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput(
                        null, null, null, null, null, 3, null));
        insertBatchExec(pid, caseId, "回归批次", 5);
        jdbcTemplate.update("INSERT INTO tp_defect (project_id, case_id, title, severity, status, reporter_id) "
                + "VALUES (?, ?, '本用例缺陷', 2, 1, 1)", pid, caseId);

        TraceChain chain = traceChainService.traceChainOfCase(caseId);

        assertEquals(TraceNodeType.CASE, chain.root().type());
        assertEquals(caseId, chain.root().id());
        assertEquals("回归批次", chain.root().lastBatchName());
        assertEquals(5, chain.root().lastExecStatus());

        List<TraceNode> reqNodes = chain.nodes().stream()
                .filter(n -> n.type() == TraceNodeType.REQUIREMENT).toList();
        assertEquals(2, reqNodes.size(), "已下线需求仍在链上（可发现性，T127 口径联动）");
        TraceNode offNode = reqNodes.stream().filter(n -> n.id() == reqOff).findFirst().orElseThrow();
        assertEquals(3, offNode.status());
        assertEquals("已下线", offNode.statusText());
        List<TraceNode> defectNodes = chain.nodes().stream()
                .filter(n -> n.type() == TraceNodeType.DEFECT).toList();
        assertEquals(1, defectNodes.size());
        assertEquals("本用例缺陷", defectNodes.get(0).label(), "无外部 KEY 的内置缺陷直达=标题");
    }


    @Test
    void traceChainOfRequirement_软删用例不进链() {
        long pid = createProject("trace6");
        long reqId = createRequirement(pid, "含软删需求");
        long caseAlive = createCase(pid, "存活用例");
        long caseDeleted = createCase(pid, "被删用例");
        requirementService.linkCases(1L, reqId, List.of(caseAlive, caseDeleted));
        jdbcTemplate.update("UPDATE tp_test_case SET deleted = 1 WHERE id = ?", caseDeleted);

        TraceChain chain = traceChainService.traceChainOfRequirement(reqId);
        assertEquals(1, chain.count(), "软删用例剔除，仅剩存活用例节点");
        assertEquals(caseAlive, chain.nodes().get(0).id());
    }


    @Test
    void traceChain_不存在实体40401() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> traceChainService.traceChainOfRequirement(999_999_999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> traceChainService.traceChainOfCase(999_999_999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex2.getCode());
    }


    @Test
    void traceChain_节点状态中文可读() {
        long pid = createProject("trace7");
        long reqId = createRequirement(pid, "状态需求");
        long caseId = createCase(pid, "状态用例");
        requirementService.linkCases(1L, reqId, List.of(caseId));

        TraceChain chain = traceChainService.traceChainOfRequirement(reqId);
        TraceNode caseNode = chain.nodes().get(0);
        assertEquals(1, caseNode.status(), "create 后 status=1");
        assertNotNull(caseNode.statusText(), "9 态中文不落空");
        assertEquals("待评审", caseNode.statusText());
    }
}
