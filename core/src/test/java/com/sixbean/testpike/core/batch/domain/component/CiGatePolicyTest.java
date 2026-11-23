package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.CiVerdictView;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CiGatePolicyTest {

    @Autowired
    private BatchService batchService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long caseSeq;
    private String originalPassRate;

    /** Per-run token: only this run's projects (and their FK trees) are swept (R37). */
    private final String token = SharedDbFixtureSweep.newToken();
    private final List<Long> projectIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
    }

    private long createProject(String code) {
        long id = projectService.create(1L, new ProjectCreateInput(token + "-V5项目-" + code,
                token + "-" + code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private long createEnv(long pid, String name) {
        return projectEnvService.create(pid, new ProjectEnvInput(name, 2,
                "https://" + name + ".example.com", null, "web", "chromium", "v1.0.0"));
    }

    
    private long readyCase(long pid, String title, int priority) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                priority, 2, "web", 3, 2, 1, null, List.of()));
        scriptService.save(new ScriptSaveInput(info.id(), "v2",
                "{\"caseId\":" + info.id() + ",\"steps\":[]}", 2));
        return info.id();
    }

    
    private BatchInfo finishedBatch(long pid, long env, List<Integer> priorities, int[] statuses) {
        List<Long> cases = priorities.stream()
                .map(p -> readyCase(pid, "V5用例-" + (++caseSeq), p)).toList();
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "V5批-" + caseSeq, env,
                cases, null));
        batchService.start(1L, batch.id());
        List<Long> taskIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_exec_task WHERE batch_id = ? ORDER BY id", Long.class, batch.id());
        for (int i = 0; i < statuses.length; i++) {
            jdbcTemplate.update("UPDATE tp_exec_task SET status = ?, error_detail = ?"
                            + " WHERE id = ?", statuses[i],
                    statuses[i] == 3 ? null : "断言失败：登录按钮未出现", taskIds.get(i));
        }
        boolean allPass = statuses.length > 0 && statuses[0] == 3;
        for (int s : statuses) {
            if (s != 3) {
                allPass = false;
            }
        }
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = ? WHERE id = ?",
                allPass ? 6 : 7, batch.id());
        return batchService.get(batch.id());
    }


    
    @Test
    void v5_verdict_pass_when_threshold_met() {
        long pid = createProject("V5P1");
        long env = createEnv(pid, "测试");
        BatchInfo batch = finishedBatch(pid, env, List.of(0, 1), new int[]{3, 3});

        CiVerdictView v = batchService.verdict(batch.id());
        assertEquals("pass", v.verdict());
        assertEquals(batch.id(), v.batchId());
        assertEquals(0, v.threshold().compareTo(new BigDecimal("0.95")),
                "默认阈值 0.95（V79 种子）");
        assertEquals(0, v.passRate().compareTo(BigDecimal.ONE));
        assertTrue(v.unmetItems().isEmpty(), "达阈未达项必空：" + v.unmetItems());
    }

    
    @Test
    void v5_verdict_fail_lists_p0_details() {
        long pid = createProject("V5F1");
        long env = createEnv(pid, "测试");
        BatchInfo batch = finishedBatch(pid, env, List.of(0, 0, 1), new int[]{4, 3, 3});

        CiVerdictView v = batchService.verdict(batch.id());
        assertEquals("fail", v.verdict());
        Map<String, Object> rateItem = v.unmetItems().stream()
                .filter(i -> "passRate".equals(i.get("item"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal(String.valueOf(rateItem.get("required")))
                .compareTo(new BigDecimal("0.95")));
        List<Map<String, Object>> p0 = v.unmetItems().stream()
                .filter(i -> "p0Fail".equals(i.get("item"))).toList();
        assertEquals(1, p0.size(), "仅 1 条 P0 失败（status=4）：" + v.unmetItems());
        assertTrue(String.valueOf(p0.get(0).get("title")).contains("V5用例"), p0.get(0).toString());
        assertTrue(String.valueOf(p0.get(0).get("errorDetail")).contains("断言失败"), p0.get(0).toString());
    }

    
    @Test
    void v5_verdict_pending_when_batch_not_finished() {
        long pid = createProject("V5W1");
        long env = createEnv(pid, "测试");
        long c = readyCase(pid, "V5用例-" + (++caseSeq), 1);
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "V5在跑批", env,
                List.of(c), null));
        batchService.start(1L, batch.id());

        CiVerdictView v = batchService.verdict(batch.id());
        assertEquals("pending", v.verdict());
        assertTrue(v.verdictPhrase().contains("执行中"), "人话必含状态：/" + v.verdictPhrase());
        assertNull(v.passRate(), "未终态不硬判通过率");
    }

    
    @Test
    void v5_verdict_pending_when_no_terminal_task() {
        long pid = createProject("V5W2");
        long env = createEnv(pid, "测试");
        BatchInfo batch = finishedBatch(pid, env, List.of(1), new int[]{3});
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 6 WHERE batch_id = ?", batch.id());

        CiVerdictView v = batchService.verdict(batch.id());
        assertEquals("pending", v.verdict());
        assertTrue(v.verdictPhrase().contains("暂无终态任务"), v.verdictPhrase());
    }

    
    @Test
    void v5_verdict_follows_hot_config() {
        long pid = createProject("V5H1");
        long env = createEnv(pid, "测试");
        BatchInfo batch = finishedBatch(pid, env, List.of(1, 1, 1), new int[]{3, 3, 4});
        assertEquals("fail", batchService.verdict(batch.id()).verdict(), "默认 0.95 下 2/3=fail");

        Long keyId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_sys_config WHERE config_key = 'ci.verdict.pass-rate'", Long.class);
        configService.update(1L, keyId, new ConfigInput(
                "ci.verdict.pass-rate", "0.6", "系统", "string", 0, "CI verdict 通过率阈值（热更验证临时值）"));
        try {
            CiVerdictView v = batchService.verdict(batch.id());
            assertEquals("pass", v.verdict(), "热更 0.6 后 2/3=0.667 应 pass");
            assertEquals(0, v.threshold().compareTo(new BigDecimal("0.6")));
        } finally {
            configService.update(1L, keyId, new ConfigInput(
                    "ci.verdict.pass-rate", "0.95", "系统", "string", 0, "CI verdict 通过率阈值"));
        }
    }

    
    @Test
    void v5_verdict_threshold_overridden_by_plan() {
        long pid = createProject("V5PL");
        long env = createEnv(pid, "测试");
        BatchInfo batch = finishedBatch(pid, env, List.of(1, 1, 1), new int[]{3, 3, 4});
        String planName = token + "-准出阈值覆盖计划";
        jdbcTemplate.update("""
                INSERT INTO tp_exec_plan (project_id, name, status, exit_criteria_json)
                VALUES (?, ?, 3, '{"minPassRate":0.99,"noOpenDefects":false}')""", pid, planName);
        long planId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_plan WHERE name = ?", Long.class, planName);
        jdbcTemplate.update("UPDATE tp_exec_batch SET plan_id = ? WHERE id = ?", planId, batch.id());

        CiVerdictView v = batchService.verdict(batch.id());
        assertEquals(0, v.threshold().compareTo(new BigDecimal("0.99")),
                "计划准出覆盖 K8 默认");
        assertEquals("plan", v.thresholdSource(), v.thresholdSource());
    }

    
    @Test
    void v5_verdict_rate_same_source_as_badge() {
        long pid = createProject("V5S1");
        long env = createEnv(pid, "测试");
        BatchInfo batch = finishedBatch(pid, env, List.of(0, 1), new int[]{3, 5});
        CiVerdictView v = batchService.verdict(batch.id());
        BigDecimal badgeRate = batchService.badgeByBatchId(batch.id()).passRate();
        assertEquals(0, v.passRate().compareTo(badgeRate), "PassRateSql 单点同值");
    }

    
    @Test
    void v5_verdict_unknown_batch_404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.verdict(999999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("批次不存在"), ex.getMessage());
    }
}
