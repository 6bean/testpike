package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.prod-blacklist=*.example.com")
class ExecTaskAiClassifyTest {

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local/login\"}}]}";

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectEnvService projectEnvService;

    /**
     * Fixture projects created by this class, swept child-first after each
     * test (R37: no blanket TRUNCATE on shared dev tables — concurrent L3
     * probes and other suites own rows in the same schema).
     */
    private final List<Long> fixtureProjectIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjectIds);
    }

    
    private long failedTask() {
        long pid = projectService.create(1L, new ProjectCreateInput("归因写回项目",
                "CW" + System.nanoTime() % 100000, "zh-CN", "P1", null));
        fixtureProjectIds.add(pid);
        projectEnvService.create(pid, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1"));
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, "归因写回用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        Long envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? ORDER BY id LIMIT 1",
                Long.class, pid);
        return execTaskService.create(new ExecTaskCreateInput(pid, info.id(), "v2", ACTION_JSON, envId));
    }

    private void markFailed(long taskId) {
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 4, error_type = 2, "
                + "error_detail = '元素未找到' WHERE id = ?", taskId);
    }

    
    @Test
    void 归因写回三列落库且不改状态机() {
        long taskId = failedTask();
        markFailed(taskId);

        execTaskService.updateAiClassify(taskId, 1, "登录按钮定位失效，疑似页面改版");

        var row = jdbcTemplate.queryForMap(
                "SELECT status, error_type, ai_failure_class, ai_failure_note, ai_classified_at "
                        + "FROM tp_exec_task WHERE id = ?", taskId);
        assertEquals(1, ((Number) row.get("ai_failure_class")).intValue(), "归类=产品缺陷落列");
        assertEquals("登录按钮定位失效，疑似页面改版", row.get("ai_failure_note"));
        assertNotNull(row.get("ai_classified_at"), "归类时间戳在位");
        assertEquals(4, ((Number) row.get("status")).intValue(), "任务状态零触碰");
        assertEquals(2, ((Number) row.get("error_type")).intValue(), "执行器四分类原零正交不覆盖");
    }

    
    @Test
    void 值域闸外拒绝零落列() {
        long taskId = failedTask();
        markFailed(taskId);

        for (Integer bad : new Integer[]{0, 4, null}) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> execTaskService.updateAiClassify(taskId, bad, "闸外值"));
            assertTrue(ex.getMessage().contains("归因"), "人话点名归因值域: " + ex.getMessage());
        }
        assertNull(jdbcTemplate.queryForObject(
                "SELECT ai_failure_class FROM tp_exec_task WHERE id = ?", Integer.class, taskId),
                "闸外写回必须零落列");
    }

    
    @Test
    void 超长说明截断落库不炸() {
        long taskId = failedTask();
        markFailed(taskId);
        String longNote = "断".repeat(600);

        execTaskService.updateAiClassify(taskId, 3, longNote);

        String stored = jdbcTemplate.queryForObject(
                "SELECT ai_failure_note FROM tp_exec_task WHERE id = ?", String.class, taskId);
        assertNotNull(stored);
        assertEquals(500, stored.length(), "超长说明截 500 列宽");
    }

    
    @Test
    void 幽灵任务404() {
        assertThrows(ServiceException.class,
                () -> execTaskService.updateAiClassify(999_999_999L, 1, "幽灵"));
    }

    
    @Test
    void 近期归类读路径_窗口内出清单窗口外零出() {
        long taskId = failedTask();
        markFailed(taskId);
        execTaskService.updateAiClassify(taskId, 2, "定位器失效");
        Long caseId = jdbcTemplate.queryForObject(
                "SELECT case_id FROM tp_exec_task WHERE id = ?", Long.class, taskId);

        var rows = execTaskService.listRecentClassify(caseId);
        assertEquals(1, rows.size(), "24h 窗口内已归类=一行");
        assertEquals("元素未找到", rows.get(0).get("error_detail"));
        assertEquals(2, ((Number) rows.get(0).get("ai_failure_class")).intValue());

        jdbcTemplate.update("UPDATE tp_exec_task SET ai_classified_at = now() - interval '25 hour'");
        assertEquals(0, execTaskService.listRecentClassify(caseId).size(), "窗口外零出（复用时效）");

        assertEquals(0, execTaskService.listRecentClassify(999_888_777L).size(), "未归类用例零出");
    }

    
    @Test
    void 读路径详情与历史暴露归因两列() {
        long taskId = failedTask();
        markFailed(taskId);
        Long caseId = jdbcTemplate.queryForObject(
                "SELECT case_id FROM tp_exec_task WHERE id = ?", Long.class, taskId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT project_id FROM tp_exec_task WHERE id = ?", Long.class, taskId);

        var pending = execTaskService.get(taskId);
        assertNull(pending.aiFailureClass(), "未归类=class 空");
        assertNull(pending.aiFailureNote(), "未归类=note 空");

        execTaskService.updateAiClassify(taskId, 2, "定位器失效");

        var info = execTaskService.get(taskId);
        assertEquals(2, info.aiFailureClass(), "详情读路径带出归类");
        assertEquals("定位器失效", info.aiFailureNote(), "详情读路径带出说明");

        var histRow = execTaskService.listHistoryByCase(caseId, projectId, 1, 10).list().get(0);
        assertEquals(2, histRow.aiFailureClass(), "历史读路径带出归类");
        assertEquals("定位器失效", histRow.aiFailureNote(), "历史读路径带出说明");
    }

    
    @Test
    void 截图签发链路_闸内出URL闸外拒任务缺失404() {
        long taskId = failedTask();
        jdbcTemplate.update("UPDATE tp_exec_task SET screenshots_json = '[\"exec/1/step-3.png\"]'::jsonb WHERE id = ?", taskId);

        String url = execTaskService.screenshotViewUrl(taskId, "exec/1/step-3.png");
        assertNotNull(url, "闸内 key 签发预签名 URL");
        assertTrue(url.contains("step-3"), "URL 指向该截图对象: " + url);

        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> execTaskService.screenshotViewUrl(taskId, "exec/1/other.png"));
        assertEquals(40301, forbidden.getCode(), "闸外 key 拒签 40301");
        assertTrue(forbidden.getMessage().contains("截图"), "人话点名截图: " + forbidden.getMessage());

        ServiceException notFound = assertThrows(ServiceException.class,
                () -> execTaskService.screenshotViewUrl(999_666_333L, "exec/1/step-3.png"));
        assertEquals(40401, notFound.getCode(), "任务缺失 40401");

        ServiceException noKey = assertThrows(ServiceException.class,
                () -> execTaskService.screenshotViewUrl(taskId, null));
        assertEquals(40301, noKey.getCode(), "空 key 闸外口径拒签");
    }

    
    @Test
    void 重复归类覆盖最新() {
        long taskId = failedTask();
        markFailed(taskId);

        execTaskService.updateAiClassify(taskId, 2, "首轮=定位错误");
        execTaskService.updateAiClassify(taskId, 3, "复判=环境问题");

        var row = jdbcTemplate.queryForMap(
                "SELECT ai_failure_class, ai_failure_note FROM tp_exec_task WHERE id = ?", taskId);
        assertEquals(3, ((Number) row.get("ai_failure_class")).intValue());
        assertEquals("复判=环境问题", row.get("ai_failure_note"));
    }
}
