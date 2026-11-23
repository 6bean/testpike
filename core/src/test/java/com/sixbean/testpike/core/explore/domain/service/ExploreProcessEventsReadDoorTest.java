package com.sixbean.testpike.core.explore.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.events.api.dto.TaskEventRow;
import com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.explore.api.controller.ExploreInternalController;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R62 A-face read door: a plain explore session already records its process
 * stream in the shared ledger (ai-service loop kernel writes start / thinking
 * / step / exit frames under job kind {@code explore}, job_ref = run_token =
 * the session id), but until now nothing on the core side could replay it, so
 * the frontend had no tail to attach to. These tests pin the door contract
 * against a real ledger: cursor replay is strictly increasing and idempotent,
 * an unknown session refuses before the ledger is read, and the reclaim path
 * paired with the writes is exercised by the terminal delete.
 */
@SpringBootTest(classes = CoreApplication.class)
class ExploreProcessEventsReadDoorTest {

    @Autowired
    private ExploreInternalController door;

    @Autowired
    private ExploreService exploreService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private TaskEventWriter taskEvents;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long fixtureProjectId;

    @AfterEach
    void cleanFixture() {
        if (fixtureProjectId == null) {
            return;
        }
        long pid = fixtureProjectId;
        jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_ref IN (SELECT id::text"
                + " FROM tp_explore_session WHERE project_id = ?)", pid);
        jdbcTemplate.update("DELETE FROM tp_explore_step WHERE session_id IN"
                + " (SELECT id FROM tp_explore_session WHERE project_id = ?)", pid);
        jdbcTemplate.update("DELETE FROM tp_explore_session WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", pid);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", pid);
        fixtureProjectId = null;
    }

    private long newProject() {
        String code = "R62A-" + UUID.randomUUID().toString().substring(0, 8);
        fixtureProjectId = projectService.create(1L, new ProjectCreateInput(
                "探索尾窗项目" + code, code, "zh-CN", "P1", null));
        return fixtureProjectId;
    }

    private long plainSession(long projectId) {
        return exploreService.create(1L, projectId, new ExploreCreateInput(
                "自由探索", "http://localhost:5173/r62a-" + UUID.randomUUID() + "/plain"));
    }

    private void writeLoopTail(long sessionId) {
        String anchor = String.valueOf(sessionId);
        taskEvents.step(AiTaskEventTrimSweeper.KIND_EXPLORE, anchor, anchor, "探索开始｜目标 自由探索", null);
        taskEvents.step(AiTaskEventTrimSweeper.KIND_EXPLORE, anchor, anchor,
                "步骤 1｜动作 goto｜已访问 1 页", "{\"ok\":true}");
        taskEvents.error(AiTaskEventTrimSweeper.KIND_EXPLORE, anchor, anchor,
                "步骤 2｜动作 click 失败｜目标不可达", "{\"code\":\"ACT_FAILED\"}");
    }

    @Test
    void tailReplaysEveryFrameWrittenForTheSession() {
        long sessionId = plainSession(newProject());
        writeLoopTail(sessionId);

        List<TaskEventRow> window = door.processEvents(sessionId, 0, null).getData();
        assertEquals(3, window.size(), "写几帧读几帧，过程流不丢帧");
        for (int i = 1; i < window.size(); i++) {
            assertTrue(window.get(i).cursor() > window.get(i - 1).cursor(),
                    "游标严格递增，前端按尾窗续读");
        }
        assertEquals("step", window.get(0).level());
        assertEquals("error", window.get(2).level());
        assertEquals("{\"code\":\"ACT_FAILED\"}", window.get(2).detail(),
                "明细原样回传，前端折叠块按原值展示");

        long lastCursor = window.get(window.size() - 1).cursor();
        assertTrue(door.processEvents(sessionId, lastCursor, null).getData().isEmpty(),
                "回放同一游标零新增帧（幂等续读）");
        long firstCursor = window.get(0).cursor();
        assertEquals(2, door.processEvents(sessionId, firstCursor, null).getData().size(),
                "任意游标续读=该游标之后的窗口，不重复不跳帧");
    }

    @Test
    void unknownSessionRefusesBeforeTheLedgerIsRead() {
        long missing = Long.MAX_VALUE - 4242L;
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> door.processEvents(missing, 0, null));
        assertTrue(refusal.getMessage() != null && !refusal.getMessage().isBlank(),
                "不存在的会话给人话拒因，禁空窗假死");
    }

    @Test
    void deletingTheSessionReclaimsItsProcessTail() {
        long sessionId = plainSession(newProject());
        writeLoopTail(sessionId);
        String anchor = String.valueOf(sessionId);
        assertEquals(3, countTail(anchor), "终态删除前尾流在账");

        exploreService.updateStatus(sessionId, ExploreSessionView.STATUS_DONE);
        exploreService.cancel(sessionId);

        assertEquals(0, countTail(anchor),
                "会话行删除时其过程流同批回收（R57 创建↔回收配对，禁孤儿流）");
    }

    private int countTail(String anchor) {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = ? AND job_ref = ?",
                Integer.class, AiTaskEventTrimSweeper.KIND_EXPLORE, anchor);
        return rows == null ? 0 : rows;
    }
}
