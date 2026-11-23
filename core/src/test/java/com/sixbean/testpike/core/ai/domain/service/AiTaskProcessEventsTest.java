package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.ai.api.dto.AiTaskCreateInput;
import com.sixbean.testpike.core.events.api.dto.TaskEventRow;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 segment B: the read door of the script-generation job's process
 * events. Pinned behaviours: the steps-gen face is keyed by the task itself
 * (job_kind = the registered V460 token "steps-gen", job_ref and run_token =
 * the task id, so a re-triggered generation opens a fresh event face exactly
 * like the extraction ledger defines), the window rides the shared cursor
 * contract (ascending, replay-stable, clamp 10~1000), and an unknown task
 * honestly answers an empty window. Real slot database, real doors — fixture
 * rows self-created and swept by id (R37: shipped tables are never TRUNCATEd).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiTaskProcessEventsTest {

    /** Registered job kind of the script-generation pipeline (V460 column
     *  comment closes the set; the read door must use this exact token). */
    private static final String KIND_STEPS_GEN = "steps-gen";

    @Autowired
    private AiTaskService aiTaskService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long projectId;
    private Long taskId;
    /** Extra fixture tasks built by the two-pair tests, swept by id as well. */
    private final java.util.List<Long> extraTaskIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanUpFixtures() {
        for (Long extra : extraTaskIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind=? AND job_ref=?",
                    KIND_STEPS_GEN, String.valueOf(extra));
            jdbcTemplate.update("DELETE FROM tp_ai_task WHERE id = ?", extra);
        }
        extraTaskIds.clear();
        if (taskId != null) {
            jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind=? AND job_ref=?",
                    KIND_STEPS_GEN, String.valueOf(taskId));
            jdbcTemplate.update("DELETE FROM tp_ai_task WHERE id = ?", taskId);
            taskId = null;
        }
        if (projectId != null) {
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
            projectId = null;
        }
    }

    /** Create a real task through the service door and land N events on its
     *  steps-gen face. Returns the task id. */
    private long taskWithEvents(int rows) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        projectId = projectService.create(1L,
                new ProjectCreateInput("过程尾项目" + tag, tag, "zh-CN", "P1", null));
        taskId = aiTaskService.create(new AiTaskCreateInput(projectId, 1L, 1,
                "生成过程事件夹具需求 " + tag, null));
        for (int i = 1; i <= rows; i++) {
            jdbcTemplate.update("INSERT INTO tp_ai_task_event"
                            + " (job_kind, job_ref, run_token, level, summary, detail)"
                            + " VALUES (?,?,?,?,?,?)",
                    KIND_STEPS_GEN, String.valueOf(taskId), String.valueOf(taskId),
                    i % 3 == 0 ? "error" : "step",
                    "生成过程 " + i, "{\"seq\":" + i + "}");
        }
        return taskId;
    }

    @Test
    void 生成任务过程事件按游标升序且重放同游标同窗() {
        long id = taskWithEvents(4);

        List<TaskEventRow> first = aiTaskService.processEvents(id, 0L, 10);
        assertEquals(4, first.size());
        for (int i = 1; i < first.size(); i++) {
            assertTrue(first.get(i).cursor() > first.get(i - 1).cursor(),
                    "行 id 游标必须严格升序");
        }
        List<TaskEventRow> replay = aiTaskService.processEvents(id, 0L, 10);
        assertEquals(first.stream().map(TaskEventRow::cursor).toList(),
                replay.stream().map(TaskEventRow::cursor).toList(),
                "重放同游标=同一窗口，不跳不重");
        assertEquals("生成过程 1", first.get(0).summary());
        assertEquals(0, aiTaskService.processEvents(id,
                first.get(first.size() - 1).cursor(), 10).size(),
                "跟随窗口从游标之后继续，已无新行");
    }

    @Test
    void 生成任务读窗同样受十到一千钳制() {
        long id = taskWithEvents(3);
        assertEquals(3, aiTaskService.processEvents(id, 0L, 1).size(),
                "limit<10 抬到 10：3 条全部可见");
        assertEquals(3, aiTaskService.processEvents(id, 0L, 999_999).size(),
                "limit>1000 钳到 1000：窗口有界");
        assertEquals(3, aiTaskService.processEvents(id, 0L, null).size(),
                "缺省窗走 ai.stream.chunk-size（出厂 200 ≥ 3）");
    }

    @Test
    void 陌生任务与空账如实回答空窗() {
        long id = taskWithEvents(2);
        assertTrue(aiTaskService.processEvents(id + 999_999L, 0L, 10).isEmpty(),
                "陌生任务号=空窗，不报错也不造数据");
        long emptyLedger = taskWithEventsZero();
        assertTrue(aiTaskService.processEvents(emptyLedger, 0L, 10).isEmpty(),
                "已建任务但台账无行=如实空窗");
    }

    private long taskWithEventsZero() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long projectId = projectService.create(1L,
                new ProjectCreateInput("过程尾空账项目" + tag, tag, "zh-CN", "P1", null));
        long id = aiTaskService.create(new AiTaskCreateInput(projectId, 1L, 1,
                "生成过程空账夹具 " + tag, null));
        extraTaskIds.add(id);
        // Sweep this second project now: the single-slot cleanup only tracks the
        // first fixture pair (cleanup-by-id discipline, nothing left behind).
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        return id;
    }
}
