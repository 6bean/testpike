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
        assertEquals(2, frames.size(), "每探索步骤一条汇总帧");
        for (Map<String, Object> frame : frames) {
            assertEquals(String.valueOf(sessionId), frame.get("run_token"),
                    "一步会话一条尾流：run_token 恒为会话号");
            String summary = String.valueOf(frame.get("summary"));
            assertTrue(summary.startsWith("步骤 "), "帧头=步序，实际=" + summary);
            assertTrue(summary.contains("候选"), "帧含候选数，实际=" + summary);
        }
        assertTrue(String.valueOf(frames.get(0).get("summary")).contains("入账"),
                "带池步含入账增量，实际=" + frames.get(0).get("summary"));
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
        assertTrue(last.contains("预算耗尽"), "终态帧=人话原因，实际=" + last);
        assertTrue(last.contains("入账候选"), "终态帧=入账计数，实际=" + last);
        assertTrue(last.contains("1"), "候选入账计数出现在帧内，实际=" + last);
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
