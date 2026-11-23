package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.apitest.api.dto.StepAliasInfo;
import com.sixbean.testpike.core.apitest.api.dto.StepAliasInput;
import com.sixbean.testpike.core.apitest.domain.service.StepAliasService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class StepAliasClaimViewTest {

    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private StepAliasService stepAliasService;

    // Fixture-scoped teardown : projects carry the fixed name
    // FK-tree walk reaches it, tasks/cases/steps/envs/links fall with the
    // project. The single executor drops by exact name.
    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectList(jdbcTemplate,
                jdbcTemplate.queryForList(
                        "SELECT id FROM tp_project WHERE name LIKE '别名信封项目%'", Long.class));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name = 'ex-alias'");
    }

    private long newTask() {
        long pid = projectService.create(1L, new ProjectCreateInput("别名信封项目",
                "AL" + System.nanoTime() % 100000, "zh-CN", "P1", null));
        long envId = projectEnvService.create(pid, new ProjectEnvInput("测试环境", 2,
                "http://demo.local", null, "web", "chromium", "v1"));
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, "别名链用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        execTaskService.create(new ExecTaskCreateInput(pid, info.id(), "v2",
                "{\"steps\":[]}", envId));
        return pid;
    }

    private ClaimTaskView claimOne() {
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-alias", null, null, "web", "chromium", 2, "1.0.0", 0));
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "必须领取到任务");
        return view;
    }

    @Test
    void claim_信封带项目别名表_别名名到规范寻址键() {
        long pid = newTask();
        StepAliasInfo stepAlias = stepAliasService.create(1L,
                new StepAliasInput(pid, "创建订单", "step", "3.orderNo", null));
        StepAliasInfo pathAlias = stepAliasService.create(1L,
                new StepAliasInput(pid, "订单号", "path", "$.data.orderNo", null));

        ClaimTaskView view = claimOne();
        Map<String, String> aliases = view.stepAliases();
        assertNotNull(aliases, "stepAliases 键必须存在（尾追兼容先例=sessionArtifacts）");
        assertEquals(Map.of("创建订单", "3.orderNo", "订单号", "$.data.orderNo"), aliases,
                "别名值=纯寻址线索（规范寻址键原样下发，非敏感实值，零 ENC 纪律适用）");
        assertNotNull(stepAlias);
        assertNotNull(pathAlias);
    }

    @Test
    void claim_项目无别名登记_空map旧形状零漂移() {
        newTask();
        ClaimTaskView view = claimOne();
        assertNotNull(view.stepAliases(), "缺省=空 map 不是 null（执行器 dict 消费口径）");
        assertEquals(Map.of(), view.stepAliases(), "无登记项目=空表恒等（零迁移护栏）");
    }

    @Test
    void claim_他项目别名不串() {
        long mine = newTask();
        long other = projectService.create(1L, new ProjectCreateInput("别的项目",
                "OT" + System.nanoTime() % 100000, "zh-CN", "P1", null));
        stepAliasService.create(1L, new StepAliasInput(other, "别家步骤", "step", "9.x", null));
        ClaimTaskView view = claimOne();
        assertEquals(Map.of(), view.stepAliases(), "别名表按项目隔离，他项目行禁串下发");
        assertNotNull(mine);
    }
}
