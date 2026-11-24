package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateResult;
import com.sixbean.testpike.core.ai.api.dto.AiBatchInfo;
import com.sixbean.testpike.core.ai.api.dto.AiRequirementLiveInfo;
import com.sixbean.testpike.common.aibatch.AiBatchItem;
import com.sixbean.testpike.core.ai.api.dto.AiTaskCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiTaskInfo;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recovery query surface of the AI generation chain : the doors
 * that let a page which was left mid-run come back and re-attach —
 * latest single-line task, latest batch aggregation, and the live generation
 * markers of a page of requirement points. Real dev database through the
 * services (no mocking of the persistence chain); the MQ publisher is mocked
 * only to keep test envelopes off the broker. All fixture rows are
 * self-built and reclaimed by id (R37: factory tables are never touched).
 */
@SpringBootTest(classes = CoreApplication.class)
class AiGenerationRecoveryQueryTest {

    @Autowired
    private AiTaskService aiTaskService;

    @Autowired
    private AiBatchService aiBatchService;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiGenPublisher aiGenPublisher;

    private final List<Long> createdRequirementIds = new ArrayList<>();
    private final List<Long> createdBatchIds = new ArrayList<>();
    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long projectA;
    private long projectB;

    @BeforeEach
    void createFixtureProjects() {
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectA = newFixtureProject("AIRECOV-A-" + uniq);
        projectB = newFixtureProject("AIRECOV-B-" + uniq);
    }

    private long newFixtureProject(String code) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
        fixtureProjectIds.add(id);
        return id;
    }

    @AfterEach
    void cleanOwnRows() {
        for (Long batchId : createdBatchIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_task WHERE batch_id = ?", batchId);
            jdbcTemplate.update("DELETE FROM tp_ai_batch WHERE id = ?", batchId);
        }
        createdBatchIds.clear();
        for (Long requirementId : createdRequirementIds) {
            jdbcTemplate.update("DELETE FROM tp_requirement WHERE id = ?", requirementId);
        }
        createdRequirementIds.clear();
        // Single-line tasks carry no batch id, so the project sweep catches them.
        SharedDbFixtureSweep.byProject(jdbcTemplate, List.of("tp_ai_batch", "tp_ai_task"),
                fixtureProjectIds.stream().mapToLong(Long::longValue).toArray());
        fixtureProjectIds.clear();
    }

    private long newRequirement(long projectId, String title) {
        RequirementInfo info = requirementService.create(5L,
                new RequirementCreateInput(projectId, 3, null, title, null, null));
        createdRequirementIds.add(info.id());
        return info.id();
    }

    private long newSingleTask(long projectId, String text, Long requirementId) {
        return aiTaskService.create(new AiTaskCreateInput(projectId, 5L, 1, text, requirementId, null, null));
    }

    @Test
    void findLatestSingle_returnsNewestNonBatchCaseTaskAndKeepsBatchChildrenOut() {
        long first = newSingleTask(projectA, "找回第一条单任务", null);
        AiBatchCreateResult batchOnB = aiBatchService.createBatch(
                AiBatchCreateInput.fromTexts(projectB, 5L, List.of("批量甲", "批量乙")));
        createdBatchIds.add(batchOnB.batchId());
        long second = newSingleTask(projectA, "找回第二条单任务", null);

        AiTaskInfo latest = aiTaskService.findLatestSingle(projectA);
        assertEquals(second, latest.id(), "the newest single-line row wins over older ones");
        assertTrue(latest.id() > first, "monotonic handle for the recovery door");

        // Project B owns only batch children: the single-task door must stay
        // null there — batch rows never leak into the single channel, so the
        // two panels can never show the same run twice (no forked book).
        assertNull(aiTaskService.findLatestSingle(projectB), "batch children are excluded by design");
    }

    @Test
    void liveByRequirements_answersOnlyQueuedOrRunningInsideTheProjectSlice() {
        long reqQueued = newRequirement(projectA, "排队注解");
        long reqRunning = newRequirement(projectA, "生成中注解");
        long reqDone = newRequirement(projectA, "已完成注解");
        long reqForeign = newRequirement(projectB, "他项目注解");

        newSingleTask(projectA, "排队中的生成", reqQueued);
        long running = newSingleTask(projectA, "生成中的生成", reqRunning);
        aiTaskService.markRunning(running);
        long done = newSingleTask(projectA, "已完成的生成", reqDone);
        aiTaskService.markRunning(done);
        aiTaskService.markDone(done, null);
        newSingleTask(projectB, "他项目排队", reqForeign);

        List<AiRequirementLiveInfo> live = aiTaskService.liveByRequirements(
                projectA, List.of(reqQueued, reqRunning, reqDone, reqForeign));
        assertEquals(2, live.size(), "settled rows and cross-project rows never annotate");
        AiRequirementLiveInfo queued = live.stream().filter(r -> r.requirementId() == reqQueued).findFirst().orElseThrow();
        AiRequirementLiveInfo runningRow = live.stream().filter(r -> r.requirementId() == reqRunning).findFirst().orElseThrow();
        assertEquals(1, queued.status(), "queued bucket carries status 1");
        assertEquals(2, runningRow.status(), "running bucket carries status 2");
        assertNull(queued.batchId(), "a single-line task carries no batch handle");

        assertTrue(aiTaskService.liveByRequirements(projectA, List.of()).isEmpty(),
                "an empty id list answers empty without dialing the database");
    }

    @Test
    void findLatestByProject_returnsNewestBatchThroughTheSameAggregation() {
        assertNull(aiBatchService.findLatestByProject(projectA), "never-submitted slice answers null");
        AiBatchCreateResult first = aiBatchService.createBatch(
                AiBatchCreateInput.fromTexts(projectA, 5L, List.of("找回批次一甲", "找回批次一乙")));
        createdBatchIds.add(first.batchId());
        AiBatchCreateResult second = aiBatchService.createBatch(
                AiBatchCreateInput.fromTexts(projectA, 5L, List.of("找回批次二甲")));
        createdBatchIds.add(second.batchId());

        // A human stop on the second batch's child settles the cancelled
        // bucket through the same aggregation path (no forked counting).
        long child = second.taskIds().get(0);
        aiTaskService.markRunning(child);
        aiTaskService.cancelTask(child);

        AiBatchInfo latest = aiBatchService.findLatestByProject(projectA);
        assertEquals(second.batchId(), latest.id(), "the newest batch wins");
        assertEquals(1, latest.cancelled(), "the stopped child counts as settled cancelled");
        assertEquals(1, latest.items().size(), "queue items ride along for the panel");
        assertEquals(5, latest.items().get(0).status(), "item row carries the cancelled status");
        assertNull(aiBatchService.findLatestByProject(projectB), "the other slice stays null");
    }
}
