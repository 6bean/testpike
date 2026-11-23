package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecStepEvidenceView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ExecStepEvidenceTest {

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"app.tap\",\"target\":{\"selector\":\"#btn\"}}]}";

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectEnvService projectEnvService;

    // Fixture-scoped teardown : the name-family sweep walks the
    // FK graph from this suite's fixed-name project (tp_exec_step_snapshot
    // hangs under tp_exec_task, so the FK walk covers it; tp_exec_task is in
    // the helper's default project-keyed set). The executor rows drop by
    // their registered name family. A blanket TRUNCATE would wipe concurrent
    // suites' rows on the dev database.
    private static final java.util.List<String> FIXTURE_PROJECT_NAMES =
            java.util.List.of("三联图项目");

    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, java.util.List.of(), FIXTURE_PROJECT_NAMES);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-evid-%'");
    }

    private long createTask() {
        long pid = projectService.create(1L, new ProjectCreateInput("三联图项目",
                "EVID" + System.nanoTime() % 100000, "zh-CN", "P1", null));
        long envId = projectEnvService.create(pid, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1.0.0"));
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, "三联图用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        return execTaskService.create(new ExecTaskCreateInput(pid, info.id(), "appauto-v1",
                ACTION_JSON, envId));
    }

    private ClaimTaskView claim(long taskId, String name) {
        long exId = executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", 2, "1.0.0", 0));
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "必须领取到任务: " + taskId);
        assertEquals(taskId, view.taskId());
        return view;
    }

    
    private void submitWithTimeline(long taskId, ClaimTaskView view, String stepsTimeline) {
        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "fail", 1, "第 2 步 app.tap 失败",
                stepsTimeline, null, 3200, null, null, null, null, null));
    }

    @Test
    void 失败步证据行投影三槽位_缺键槽位诚实null() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-evid-1");
        submitWithTimeline(taskId, view, """
                [{"stepNo":1,"action":"app.launch","result":"pass"},
                 {"stepNo":2,"action":"app.tap","result":"fail",
                  "evidence":{"screenshot":"evid/shot.png","view_tree":"evid/tree.json"}},
                 {"stepNo":3,"action":"app.back","result":"pass"}]
                """);

        List<ExecStepEvidenceView> rows = execTaskService.listStepEvidence(taskId);
        assertEquals(1, rows.size(), "仅含 evidence 键的行进聚合列表（无证据步不占位）");
        ExecStepEvidenceView row = rows.get(0);
        assertEquals(taskId, row.taskId());
        assertEquals(2, row.stepNo(), "按步序回显（页签失败步定位口径）");
        assertEquals("app.tap", row.action());
        assertEquals("evid/shot.png", row.screenshotKey());
        assertNotNull(row.screenshotUrl(), "截图 objectKey → 预签名 GET URL");
        assertTrue(row.screenshotUrl().contains("evid/shot.png"), "URL 指向本对象（真实 S3 签发）");
        assertEquals("evid/tree.json", row.viewTreeKey());
        assertNotNull(row.viewTreeUrl(), "视图树=文本产物同径签发");
        assertNull(row.logTailKey(), "log_tail 缺键=null 诚实占位（禁假造）");
        assertNull(row.logTailUrl());
        assertNull(row.recordKey(), "record 缺键=null 诚实占位（T327 第四联录屏切片缺位禁假造）");
        assertNull(row.recordUrl());
    }

    @Test
    void 失败步证据行投影第四联录屏切片() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-evid-rec");
        submitWithTimeline(taskId, view, """
                [{"stepNo":2,"action":"app.tap","result":"fail",
                  "evidence":{"screenshot":"evid/shot.png","record":"evid/clip.mp4"}}]
                """);

        List<ExecStepEvidenceView> rows = execTaskService.listStepEvidence(taskId);
        assertEquals(1, rows.size());
        ExecStepEvidenceView row = rows.get(0);
        assertEquals("evid/clip.mp4", row.recordKey(), "第四联 record 槽位=录屏切片 objectKey");
        assertNotNull(row.recordUrl(), "录屏切片与三联图同径 grantViewUrl 预签名签发");
        assertTrue(row.recordUrl().contains("evid/clip.mp4"), "URL 指向本对象（真实 S3 签发）");
        assertEquals("evid/shot.png", row.screenshotKey(), "同元素其余槽位照常");
    }

    @Test
    void 失败步证据行投影第五联弱网观测时序() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-evid-net");
        submitWithTimeline(taskId, view, """
                [{"stepNo":2,"action":"app.tap","result":"fail",
                  "evidence":{"screenshot":"evid/shot.png",
                              "net_timeline":"evid/tp-net-timeline.jsonl"}}]
                """);

        List<ExecStepEvidenceView> rows = execTaskService.listStepEvidence(taskId);
        assertEquals(1, rows.size());
        ExecStepEvidenceView row = rows.get(0);
        assertEquals("evid/tp-net-timeline.jsonl", row.netTimelineKey(),
                "第五联 net_timeline 槽位=弱网代理观测行 objectKey（T328 包③a）");
        assertNotNull(row.netTimelineUrl(), "观测行=文本产物与三联图同径 grantViewUrl 签发");
        assertTrue(row.netTimelineUrl().contains("tp-net-timeline.jsonl"),
                "URL 指向本对象（真实 S3 签发）");
        assertNull(row.recordKey(), "record 缺位=null 诚实占位（同元素其余槽位照常）");
        assertEquals("evid/shot.png", row.screenshotKey());
    }

    @Test
    void 弱网观测时序缺位诚实null() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-evid-net-absent");
        submitWithTimeline(taskId, view, """
                [{"stepNo":1,"action":"app.tap","result":"fail",
                  "evidence":{"screenshot":"evid/shot.png"}}]
                """);

        List<ExecStepEvidenceView> rows = execTaskService.listStepEvidence(taskId);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).netTimelineKey(),
                "无弱网会话=net_timeline 缺键 null 诚实占位（禁假造）");
        assertNull(rows.get(0).netTimelineUrl());
    }

    @Test
    void 失败步证据行投影第六联性能采样曲线() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-evid-perf");
        submitWithTimeline(taskId, view, """
                [{"stepNo":2,"action":"app.tap","result":"fail",
                  "evidence":{"screenshot":"evid/shot.png",
                              "perf_timeline":"evid/tp-perf-samples.jsonl"}}]
                """);

        List<ExecStepEvidenceView> rows = execTaskService.listStepEvidence(taskId);
        assertEquals(1, rows.size());
        ExecStepEvidenceView row = rows.get(0);
        assertEquals("evid/tp-perf-samples.jsonl", row.perfTimelineKey(),
                "第六联 perf_timeline 槽位=性能采样原始样行 objectKey（T329 包④）");
        assertNotNull(row.perfTimelineUrl(), "样行=文本产物与三联图同径 grantViewUrl 签发");
        assertTrue(row.perfTimelineUrl().contains("tp-perf-samples.jsonl"),
                "URL 指向本对象（真实 S3 签发）");
        assertNull(row.netTimelineKey(), "net_timeline 缺位=null 诚实占位（同元素其余槽位照常）");
        assertEquals("evid/shot.png", row.screenshotKey());
    }

    @Test
    void 性能采样曲线缺位诚实null() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-evid-perf-absent");
        submitWithTimeline(taskId, view, """
                [{"stepNo":1,"action":"app.tap","result":"fail",
                  "evidence":{"screenshot":"evid/shot.png"}}]
                """);

        List<ExecStepEvidenceView> rows = execTaskService.listStepEvidence(taskId);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).perfTimelineKey(),
                "adb 缺位/零会话=perf_timeline 缺键 null 诚实占位（禁假造）");
        assertNull(rows.get(0).perfTimelineUrl());
    }

    @Test
    void 无证据行或空evidence对象_返回空列表不报错() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-evid-2");
        submitWithTimeline(taskId, view, """
                [{"stepNo":1,"action":"app.tap","result":"pass"},
                 {"stepNo":2,"action":"app.back","result":"fail","evidence":{}}]
                """);
        assertEquals(0, execTaskService.listStepEvidence(taskId).size(),
                "空 evidence 对象=零证据件，不进列表（非异常）");
    }

    @Test
    void 槽位值非文本_该槽null其余照常() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-evid-3");
        submitWithTimeline(taskId, view, """
                [{"stepNo":1,"action":"app.tap","result":"fail",
                  "evidence":{"screenshot":{"nested":"脏值"},"view_tree":"evid/tree.json"}}]
                """);
        List<ExecStepEvidenceView> rows = execTaskService.listStepEvidence(taskId);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).screenshotKey(), "非文本值=不可签发，槽位 null（禁炸整包回传面）");
        assertEquals("evid/tree.json", rows.get(0).viewTreeKey(), "同元素其余槽位照常");
    }

    @Test
    void 任务不存在_抛人话异常与screenshots同口径() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.listStepEvidence(987654321L));
        assertTrue(ex.getMessage().contains("不存在"), "40401 人话（admin 桥判权短路依赖）");
    }
}
