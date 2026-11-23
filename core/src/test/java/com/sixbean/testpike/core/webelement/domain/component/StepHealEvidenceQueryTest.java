package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.webelement.api.dto.HealEvidencePage;
import com.sixbean.testpike.core.webelement.api.dto.StepHealEvidenceView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read side of the self-heal evidence ledger . The
 * report step rows and the Page Element Library detail "recent heals" list
 * both consume these doors; the execution record is the single source, so
 * the queries here never recompute or copy numbers.
 * <p>Contracts nailed here:
 * ① task-scope query returns every row of the task ordered by step index
 * (nulls last) then id, and carries the owning project id for the
 * admin-side membership guard;
 * ② element-scope query is bounded paging with a server-side clamped page
 * size (max 100) and an exact total for the detail drawer;
 * ③ a task with no evidence returns an empty page, not an error — legacy
 * runs simply have nothing to badge;
 * ④ an unknown element returns an empty page with total zero.
 * <p>Real chain: evidence rows land through the proven writeback hook, the
 * project is created through the live service against the chain-slot DB,
 * fixtures purged by task cascade in teardown (R57).
 */
@SpringBootTest(classes = CoreApplication.class)
class StepHealEvidenceQueryTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private StepHealEvidenceWriteback writeback;
    @Autowired
    private StepHealEvidenceQuery query;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;
    private final List<Long> taskIds = new ArrayList<>();

    @BeforeEach
    void seedProject() {
        long tag = SEQ.incrementAndGet();
        String code = "SQ" + System.currentTimeMillis() % 100000000L + tag;
        projectId = projectService.create(1L, new ProjectCreateInput(
                "自愈证据读侧项目" + code, code, "zh-CN", "P1", null));
    }

    @AfterEach
    void removeFixtures() {
        for (Long id : taskIds) {
            jdbcTemplate.update("DELETE FROM tp_exec_task WHERE id = ?", id);
        }
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        taskIds.clear();
    }

    private long newTask() {
        long tag = SEQ.incrementAndGet();
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_task (project_id, case_id, status, task_type) "
                        + "VALUES (?, ?, 3, 1) RETURNING id",
                Long.class, projectId, 900000L + tag);
        taskIds.add(id);
        return id;
    }

    private void relayRows(long taskId, List<Map<String, Object>> evidence) {
        Map<String, Object> taskShape = new LinkedHashMap<>();
        taskShape.put("id", taskId);
        taskShape.put("projectId", projectId);
        taskShape.put("taskType", 1);
        ExecTaskInfo task = objectMapper.convertValue(taskShape, ExecTaskInfo.class);
        Map<String, Object> inputShape = new LinkedHashMap<>();
        inputShape.put("taskId", taskId);
        inputShape.put("result", "pass");
        inputShape.put("stepHealEvidence", evidence);
        writeback.onResultSubmitted(task, objectMapper.convertValue(inputShape, ExecResultInput.class));
    }

    private static Map<String, Object> evidenceRow(Long elementId, int stepNo, String usedValue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("healed", true);
        row.put("firstAnchorFailed", true);
        row.put("elementId", elementId);
        row.put("stepNo", stepNo);
        row.put("fromAnchor", Map.of("type", "css", "value", "#old-" + stepNo));
        row.put("usedAnchor", Map.of("type", "testid", "value", usedValue));
        row.put("viewport", "1440x900");
        row.put("ledgerRevision", 7);
        row.put("similarityScore", 0.9);
        return row;
    }

    @Test
    void taskQueryReturnsRowsOrderedByStepWithProjectId() {
        long taskId = newTask();
        relayRows(taskId, List.of(
                evidenceRow(41L, 3, "third"),
                evidenceRow(41L, 1, "first"),
                evidenceRow(42L, 2, "second")));
        HealEvidencePage page = query.byTask(taskId);
        assertEquals(projectId, page.projectId());
        assertEquals(3, page.items().size());
        List<StepHealEvidenceView> items = page.items();
        assertEquals(1, items.get(0).stepNo());
        assertEquals("first", items.get(0).usedAnchorValue());
        assertEquals(2, items.get(1).stepNo());
        assertEquals(42L, items.get(1).elementId());
        assertEquals(3, items.get(2).stepNo());
        assertNotNull(items.get(0).createdAt());
        assertEquals(3, page.total());
    }

    @Test
    void elementQueryPagesWithClampedSizeAndExactTotal() {
        long first = newTask();
        long second = newTask();
        relayRows(first, List.of(
                evidenceRow(51L, 1, "a"),
                evidenceRow(51L, 2, "b")));
        relayRows(second, List.of(
                evidenceRow(51L, 1, "c"),
                evidenceRow(52L, 1, "d")));
        HealEvidencePage pageOne = query.byElement(51L, 1, 2);
        assertEquals(3, pageOne.total());
        assertEquals(2, pageOne.items().size());
        assertEquals("c", pageOne.items().get(0).usedAnchorValue());
        HealEvidencePage pageTwo = query.byElement(51L, 2, 2);
        assertEquals(1, pageTwo.items().size());
        assertEquals("a", pageTwo.items().get(0).usedAnchorValue());
        assertEquals(51L, pageTwo.items().get(0).elementId());
        // oversized request must clamp server-side, never dump the whole table
        HealEvidencePage clamped = query.byElement(51L, 1, 5000);
        assertEquals(100, clamped.pageSize());
        // illegal page numbers degrade to the first page
        HealEvidencePage reset = query.byElement(51L, 0, -1);
        assertEquals(20, reset.pageSize());
        assertEquals(3, reset.items().size());
    }

    @Test
    void emptyAndUnknownScopesReturnEmptyPages() {
        long taskId = newTask();
        HealEvidencePage page = query.byTask(taskId);
        assertTrue(page.items().isEmpty());
        assertEquals(0, page.total());
        assertEquals(projectId, page.projectId());
        HealEvidencePage unknown = query.byElement(999999999L, 1, 10);
        assertTrue(unknown.items().isEmpty());
        assertEquals(0, unknown.total());
        assertNull(unknown.projectId());
    }
}
