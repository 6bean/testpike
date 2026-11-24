package com.sixbean.testpike.core.explore.domain.service;

import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.webelement.domain.component.WebElementSurveyCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R62 segment b: the survey process stream (kind=web-element-survey,
 * job_ref=run_token=session id) must carry one step-summary frame per
 * executor write-back and one terminal frame per session end state, so the
 * tail drawer shows live progress without polling the ledger. Plain explore
 * sessions never write into this stream (they own the kind=explore stream).
 */
@SpringBootTest(classes = CoreApplication.class)
class ExploreSurveyProcessFramesTest {

    @Autowired
    private ExploreService exploreService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long fixtureProjectId;

    @BeforeEach
    void seedNothingButIsolate() {
        fixtureProjectIdsClear();
    }

    @AfterEach
    void cleanUp() {
        if (fixtureProjectId != null) {
            long pid = fixtureProjectId;
            jdbcTemplate.update(
                    "DELETE FROM tp_ai_task_event WHERE job_kind = 'web-element-survey'"
                            + " AND job_ref IN (SELECT id::text FROM tp_explore_session WHERE project_id = ?)",
                    pid);
            jdbcTemplate.update(
                    "DELETE FROM tp_explore_step WHERE session_id IN"
                            + " (SELECT id FROM tp_explore_session WHERE project_id = ?)", pid);
            jdbcTemplate.update("DELETE FROM tp_explore_session WHERE project_id = ?", pid);
            jdbcTemplate.update("DELETE FROM tp_web_element WHERE project_id = ?", pid);
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", pid);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", pid);
            fixtureProjectId = null;
        }
    }

    private void fixtureProjectIdsClear() {
        // fresh fixture per test; nothing else to reset
    }

    private long newProject() {
        String code = "R62B-" + UUID.randomUUID().toString().substring(0, 8);
        fixtureProjectId = projectService.create(1L, new ProjectCreateInput(
                "测绘过程帧项目" + code, code, "zh-CN", "P1", null));
        return fixtureProjectId;
    }

    private long surveySession(long projectId, String pageUrl) {
        return exploreService.create(1L, projectId, new ExploreCreateInput(
                WebElementSurveyCollector.SURVEY_GOAL_PREFIX + pageUrl, pageUrl));
    }

    private List<Map<String, Object>> streamOf(long sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT level, run_token, summary FROM tp_ai_task_event"
                        + " WHERE job_kind = 'web-element-survey' AND job_ref = ? ORDER BY id",
                String.valueOf(sessionId));
    }

    private String poolJson() {
        return "{\"truncated\":false,\"candidates\":["
                + "{\"tag\":\"button\",\"text\":\"提交\",\"target\":{\"css\":\"[data-test=r62b]\"}}]}";
    }

    private String candidatesAction(String pageUrl) {
        return "{\"name\":\"element-candidates\",\"arguments\":{\"url\":\"" + pageUrl + "\"}}";
    }

    @Test
    void surveyStepWriteBackAppendsOneSummaryFramePerStep() {
        long projectId = newProject();
        String pageUrl = "http://localhost:5173/r62b-" + UUID.randomUUID() + "/form";
        long sessionId = surveySession(projectId, pageUrl);

        exploreService.recordStep(sessionId, new ExploreStepInput(1,
                candidatesAction(pageUrl), "{\"ok\":true}", null, null, "候选池回写", poolJson()));
        exploreService.recordStep(sessionId, new ExploreStepInput(2,
                "{\"name\":\"goto\",\"arguments\":{\"url\":\"" + pageUrl + "/next\"}}",
                "{\"ok\":true}", null, null, "无候选池步", null));

        List<Map<String, Object>> frames = streamOf(sessionId);
        // The stream may also carry a notice frame (e.g. the naming-model
        // degradation note), so the step fact is counted by its frame head.
        List<String> stepFrames = frames.stream()
                .map(f -> String.valueOf(f.get("summary")))
                .filter(s -> s.startsWith("步骤 "))
                .toList();
        assertEquals(2, stepFrames.size(), "每探索步骤一条汇总帧，实际流=" + frames);
        for (Map<String, Object> frame : frames) {
            assertEquals(String.valueOf(sessionId), frame.get("run_token"),
                    "一步会话一条尾流：run_token 恒为会话号");
        }
        for (String summary : stepFrames) {
            assertTrue(summary.contains("候选"), "帧含候选数，实际=" + summary);
        }
        assertTrue(stepFrames.get(0).contains("新增元素 1"),
                "带池步应说明本步新增了多少元素，实际=" + stepFrames.get(0));
        assertFalse(stepFrames.get(0).contains("入账") || stepFrames.get(0).contains("CREATED="),
                "步骤帧仍带内部记账说法或枚举常量名，实际=" + stepFrames.get(0));
        assertTrue(stepFrames.get(1).contains("本步未采集到元素"),
                "无候选池的步骤应说明这一步没采到元素，实际=" + stepFrames.get(1));
    }

    @Test
    void plainExploreSessionNeverWritesSurveyStreamFrames() {
        long projectId = newProject();
        String pageUrl = "http://localhost:5173/r62b-" + UUID.randomUUID() + "/plain";
        long sessionId = exploreService.create(1L, projectId, new ExploreCreateInput("自由探索", pageUrl));

        exploreService.recordStep(sessionId, new ExploreStepInput(1,
                candidatesAction(pageUrl), "{\"ok\":true}", null, null, "普通探索步", poolJson()));

        assertEquals(0, streamOf(sessionId).size(), "非测绘会话禁写测绘过程流");
    }

    @Test
    void terminalStatusFrameCarriesHumanReasonAndLedgerCounts() {
        long projectId = newProject();
        String pageUrl = "http://localhost:5173/r62b-" + UUID.randomUUID() + "/end";
        long sessionId = surveySession(projectId, pageUrl);
        exploreService.recordStep(sessionId, new ExploreStepInput(1,
                candidatesAction(pageUrl), "{\"ok\":true}", null, null, "入账一条候选", poolJson()));

        exploreService.updateStatus(sessionId, ExploreSessionView.STATUS_BUDGET_STOP);

        List<Map<String, Object>> frames = streamOf(sessionId);
        String last = String.valueOf(frames.get(frames.size() - 1).get("summary"));
        assertTrue(last.contains("达到采集步数上限"), "终态帧应点名是什么上限到了，实际=" + last);
        assertTrue(last.contains("累计候选"), "终态帧应给出候选计数，实际=" + last);
        assertTrue(last.contains("1"), "候选计数出现在帧内，实际=" + last);
        assertFalse(last.contains("入账"), "终态帧仍带内部记账说法，实际=" + last);
    }

    @Test
    void softCancelAppendsStoppedFrameButDeleteReclaimsStream() {
        long projectId = newProject();
        String pageUrl = "http://localhost:5173/r62b-" + UUID.randomUUID() + "/cancel";
        long sessionId = surveySession(projectId, pageUrl);

        exploreService.cancel(sessionId);
        List<Map<String, Object>> stopped = streamOf(sessionId);
        assertTrue(stopped.stream().anyMatch(f -> String.valueOf(f.get("summary")).contains("已停止")),
                "软终止帧=操作人停止");

        // second cancel = terminal cleanup path: rows purged with the session
        exploreService.cancel(sessionId);
        assertEquals(0, streamOf(sessionId).size(), "删除清理路径帧随会话回收（R57 配对）");
    }
}
