package com.sixbean.testpike.core.explore.domain.service;

import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.explore.api.dto.ExploreCancelResult;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ExploreToCasesTest {

    @Autowired
    private ExploreService exploreService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long sessionId;

    @BeforeEach
    void cleanAndSeed() {
        // Fixture-scoped teardown (R37): only the EXP- project subtree
        // (sessions/steps/cases hang off it) — no blanket table deletes.
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, "EXP-T210"));
        projectId = projectService.create(1L,
                new ProjectCreateInput("探索项目", "EXP-T210", "zh-CN", "P1", null));
        sessionId = exploreService.create(1L, projectId,
                new ExploreCreateInput("摸清登录流程", "http://localhost:5173"));
    }

    
    private void step(int seq, boolean ok, String action, String observation) {
        exploreService.recordStep(sessionId, new ExploreStepInput(seq,
                "{\"name\":\"" + action + "\",\"arguments\":{\"url\":\"http://localhost:5173\"}}",
                "{\"ok\":" + ok + ",\"observation\":\"" + observation + "\"}",
                null, null, null, null));
    }

    
    @Test
    void toCases_success_landsPendingReview_source5_exploreRef() {
        step(1, true, "page-snapshot", "首页有登录入口");
        step(2, false, "click-path", "点击失败");
        step(3, true, "assert", "登录页标题断言通过");

        List<Long> caseIds = exploreService.toCases(1L, sessionId, List.of(1, 3));
        assertEquals(2, caseIds.size());

        CaseInfo first = caseService.get(caseIds.get(0));
        assertEquals(5, first.source(), "转用例来源=5 探索转用例（TC-B9-04）");
        assertEquals("explore:" + sessionId, first.sourceRef(), "session 级引用（坑55 裁定）");
        assertEquals(1, first.status(), "必落待评审——绕质量门=缺陷级红线（TC-B9-04）");
        assertEquals(projectId, first.projectId(), "归属探索会话所在项目");
        assertTrue(first.title().contains("摸清登录流程"), "标题携带探索目标：" + first.title());
        assertTrue(first.expectedResult() != null && first.expectedResult().contains("首页有登录入口"),
                "预期结果携带该步观测：" + first.expectedResult());
        CaseInfo second = caseService.get(caseIds.get(1));
        assertTrue(second.steps().get(0).description().contains("断言通过"),
                "步骤描述携带观测：" + second.steps().get(0).description());
        assertEquals(1, second.steps().get(0).isAssert(), "assert 动作步=is_assert 1");
    }

    
    @Test
    void toCases_rejections_empty_notPassed_overLimit_missingStep() {
        step(1, true, "page-snapshot", "ok");
        step(2, false, "click-path", "元素未找到");
        step(5, false, "assert", "断言失败");

        ServiceException empty = assertThrows(ServiceException.class,
                () -> exploreService.toCases(1L, sessionId, List.of()));
        assertEquals(40001, empty.getCode(), "空勾选=40001 人话，实际=" + empty.getMessage());

        ServiceException notPassed = assertThrows(ServiceException.class,
                () -> exploreService.toCases(1L, sessionId, List.of(1, 2, 5)));
        assertEquals(40001, notPassed.getCode());
        assertTrue(notPassed.getMessage().contains("2") && notPassed.getMessage().contains("5"),
                "拒转必须列出步号 2/5：" + notPassed.getMessage());
        assertEquals(0, countExploreCases(), "整体拒绝=零写入，不得部分落库");

        ServiceException overLimit = assertThrows(ServiceException.class,
                () -> exploreService.toCases(1L, sessionId, IntStream.rangeClosed(1, 51).boxed().toList()));
        assertEquals(40001, overLimit.getCode());
        assertTrue(overLimit.getMessage().contains("50"), "超 50 分批提示：实际=" + overLimit.getMessage());

        ServiceException missing = assertThrows(ServiceException.class,
                () -> exploreService.toCases(1L, sessionId, List.of(9)));
        assertEquals(40001, missing.getCode());
        assertTrue(missing.getMessage().contains("9"), "步缺失点名步号 9：" + missing.getMessage());

        ServiceException noSession = assertThrows(ServiceException.class,
                () -> exploreService.toCases(1L, 999999999L, List.of(1)));
        assertEquals(40401, noSession.getCode(), "会话不存在 40401");
    }

    
    @Test
    void toCases_malformedOrSuccesslessResult_rejected() {
        exploreService.recordStep(sessionId, new ExploreStepInput(1, "{}", "{\"ok\":false}",
                null, null, null, null));
        ServiceException r = assertThrows(ServiceException.class,
                () -> exploreService.toCases(1L, sessionId, List.of(1)));
        assertTrue(r.getMessage().contains("1"), "列出步号：" + r.getMessage());
    }

    
    @Test
    void cancel_runningSoftTerminates_terminalCleansRows() {
        step(1, true, "page-snapshot", "ok");
        ExploreCancelResult cancelled = exploreService.cancel(sessionId);
        assertEquals(ExploreCancelResult.ACTION_CANCELLED, cancelled.action());
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_explore_session WHERE id = ?", Integer.class, sessionId);
        assertEquals(5, status, "running 软终止→5=cancelled（停止派发判据）");
        assertEquals(1, stepCount(), "软终止只翻态，步骤留痕行保留");
        ServiceException gated = assertThrows(ServiceException.class,
                () -> exploreService.recordStep(sessionId,
                        new ExploreStepInput(2, "{}", "{\"ok\":true}", null, null, null, null)));
        assertEquals(40902, gated.getCode(), "已取消会话步进拒 40902");

        ExploreCancelResult cleaned = exploreService.cancel(sessionId);
        assertEquals(ExploreCancelResult.ACTION_DELETED, cleaned.action());
        assertEquals(0, sessionCount(), "终态清理=会话行删除");
        assertEquals(0, stepCount(), "步骤行随删（证据对象归 S3 随 Q6 保留期，不触碰）");

        ServiceException missing = assertThrows(ServiceException.class,
                () -> exploreService.cancel(sessionId));
        assertEquals(40401, missing.getCode(), "已删/不存在会话 40401");
    }

    
    @Test
    void cancel_terminalSession_deletesDirectly() {
        exploreService.updateStatus(sessionId, 2); // done
        ExploreCancelResult r = exploreService.cancel(sessionId);
        assertEquals(ExploreCancelResult.ACTION_DELETED, r.action());
        assertEquals(0, sessionCount());
    }

    /**
 * R62 segment a terminal-cleanup pairing: deleting the owning survey session
 * must purge its process-event face (kind=web-element-survey, ref=session
 * id) in the same door, while a colliding element-ledger audit row under
 * the same numeric ref survives — value ranges of the two streams stay
 * separate down to the reclaim path (resource-lifetime rule: every
 * creation point has a paired reclaim).
 */
    @Test
    void cancel_terminalSession_purgesSurveyEvents_andKeepsOtherStreamIntact() {
        String surveyKind = com.sixbean.testpike.core.events.domain.component
                .AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY;
        String ref = String.valueOf(sessionId);
        jdbcTemplate.update("INSERT INTO tp_ai_task_event (job_kind, job_ref, run_token, level, summary)"
                + " VALUES (?, ?, ?, 'step', '测绘发起回执帧')", surveyKind, ref, "run-purge-" + ref);
        jdbcTemplate.update("INSERT INTO tp_ai_task_event (job_kind, job_ref, run_token, level, summary)"
                + " VALUES ('element-ledger', ?, 'run-purge-x', 'step', '同号元素行审计帧')", ref);
        exploreService.updateStatus(sessionId, 2);

        ExploreCancelResult r = exploreService.cancel(sessionId);

        assertEquals(ExploreCancelResult.ACTION_DELETED, r.action());
        assertEquals(0, countEvents(surveyKind, ref),
                "survey process stream is reclaimed with its owning session row");
        assertEquals(1, countEvents("element-ledger", ref),
                "the other registered stream under the same numeric ref is never collateral");
        jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                + " AND job_ref = ? AND run_token = 'run-purge-x'", ref);
    }

    private int countEvents(String kind, String ref) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = ? AND job_ref = ?",
                Integer.class, kind, ref);
        return n == null ? 0 : n;
    }

    private int sessionCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_explore_session WHERE id = ?", Integer.class, sessionId);
        return n == null ? 0 : n;
    }

    private int stepCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_explore_step WHERE session_id = ?", Integer.class, sessionId);
        return n == null ? 0 : n;
    }

    private int countExploreCases() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE source = 5 AND project_id = ?",
                Integer.class, projectId);
        return n == null ? 0 : n;
    }
}
