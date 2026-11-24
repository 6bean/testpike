package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.ai.api.controller.AiBatchInternalController;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateRequest;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateResult;
import com.sixbean.testpike.common.aibatch.AiBatchItem;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Batch generation with structured requirement linkage .
 * <p>Real dev database, no mocking of the persistence chain; only the MQ publisher is mocked
 * to keep test messages off the broker. Covers: item-form createBatch persists the requirement
 * link, cross-project and unknown ids reject the whole request with a human-readable message
 * naming the offending entry, the legacy plain-text form still works with no link, and the
 * internal controller publishes one envelope per item carrying the requirement id.
 * Fixtures are self-built (requirements created through the service, rows cleaned by id;
 * factory tables are never truncated here).
 */
@SpringBootTest(classes = CoreApplication.class)
class AiBatchRequirementLinkageTest {

    @Autowired
    private AiBatchService aiBatchService;

    @Autowired
    private AiBatchInternalController controller;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private AiGenPublisher aiGenPublisher;

    private final List<Long> createdRequirementIds = new ArrayList<>();
    private final List<Long> createdBatchIds = new ArrayList<>();

    /** Fixture projects created by this class (R37: self-built rows, swept by id — no factory-row trust). */
    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long projectA;
    private long projectB;

    @BeforeEach
    void createFixtureProjects() {
        // A freshly provisioned chain slot carries no factory tp_project rows, and
        // ambient rows of other suites must never leak into these assertions, so
        // each test gets two self-built projects reclaimed in @AfterEach.
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectA = newFixtureProject("AIBATCH-A-" + uniq);
        projectB = newFixtureProject("AIBATCH-B-" + uniq);
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
        // Projects ride last: the tracked deletes above already released their FK refs;
        // the sweep also clears project-keyed log rows (audit/ai batch/task) idempotently.
        SharedDbFixtureSweep.byProject(jdbcTemplate, List.of("tp_ai_batch", "tp_ai_task"),
                fixtureProjectIds.stream().mapToLong(Long::longValue).toArray());
        fixtureProjectIds.clear();
    }

    /** Requirement point created through the single-source service so the link id is real. */
    private long newRequirement(long projectId, String title) {
        RequirementInfo info = requirementService.create(5L,
                new RequirementCreateInput(projectId, 3, null, title, null, null));
        createdRequirementIds.add(info.id());
        return info.id();
    }

    @Test
    void createBatch_structuredItem_persistsRequirementId() {
        long requirementId = newRequirement(projectA, "登录失败提示剩余次数");
        AiBatchCreateResult result = aiBatchService.createBatch(new AiBatchCreateInput(
                projectA, 5L, List.of(new AiBatchItem("登录失败提示剩余重试次数", requirementId, null))));
        createdBatchIds.add(result.batchId());
        assertEquals(1, result.taskIds().size());
        Long stored = jdbcTemplate.queryForObject(
                "SELECT requirement_id FROM tp_ai_task WHERE id = ?", Long.class, result.taskIds().get(0));
        assertEquals(requirementId, stored, "structured item links the generated task to its requirement");
    }

    @Test
    void createBatch_crossProjectRequirement_rejectsWholeRequestNamingEntry() {
        long foreignRequirement = newRequirement(projectB, "他项目需求点");
        long ownRequirement = newRequirement(projectA, "本项目需求点");
        AiBatchCreateInput input = new AiBatchCreateInput(projectA, 5L, List.of(
                new AiBatchItem("本项目需求文本", ownRequirement, null),
                new AiBatchItem("越权需求文本", foreignRequirement, null)));
        ServiceException error = assertThrows(ServiceException.class,
                () -> aiBatchService.createBatch(input));
        assertTrue(error.getMessage().contains("第 2 条"),
                "message names the offending entry: " + error.getMessage());
        assertTrue(error.getMessage().contains(String.valueOf(foreignRequirement)),
                "message names the requirement id: " + error.getMessage());
        assertTrue(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_ai_batch WHERE project_id = ? AND total = 2", Long.class,
                        projectA) == 0, "cross-project rejection leaves no batch row");
        verify(aiGenPublisher, never()).publish(any());
    }

    @Test
    void createBatch_unknownRequirementId_rejected() {
        AiBatchCreateInput input = new AiBatchCreateInput(projectA, 5L, List.of(
                new AiBatchItem("任意需求文本", 999_999_999L, null)));
        ServiceException error = assertThrows(ServiceException.class,
                () -> aiBatchService.createBatch(input));
        assertTrue(error.getMessage().contains("第 1 条"),
                "unknown id rejected with the entry position: " + error.getMessage());
    }

    @Test
    void legacyPlainTextInput_keepsWorkingWithoutLink() {
        AiBatchCreateResult result = aiBatchService.createBatch(
                AiBatchCreateInput.fromTexts(projectA, 5L, List.of("纯文本需求一", "纯文本需求二")));
        createdBatchIds.add(result.batchId());
        List<Long> links = jdbcTemplate.query(
                "SELECT requirement_id FROM tp_ai_task WHERE batch_id = ? ORDER BY id",
                (rs, n) -> (Long) rs.getObject(1), result.batchId());
        assertEquals(2, links.size());
        assertNull(links.get(0), "legacy plain-text item carries no requirement link");
        assertNull(links.get(1), "legacy plain-text item carries no requirement link");
    }

    @Test
    void controllerCreate_itemForm_publishesEnvelopeWithRequirementId() {
        long requirementId = newRequirement(projectA, "控制器信封携带需求点");
        Response<?> response = controller.create(
                new AiBatchCreateRequest(projectA, null,
                        List.of(new AiBatchItem("控制器需求文本", requirementId, "doc:3.2#p1"))), 5L);
        createdBatchIds.add((Long) ((java.util.Map<?, ?>) response.getData()).get("batchId"));
        ArgumentCaptor<AiGenMessage> captor = ArgumentCaptor.forClass(AiGenMessage.class);
        // The controller publishes through the two-argument overload that also carries
        // the window-feed flag; this test asserts the envelope, not the feed routing.
        verify(aiGenPublisher, times(1)).publish(captor.capture(), anyBoolean());
        assertEquals(requirementId, captor.getValue().requirementId(),
                "MQ envelope carries the requirement link");
        assertEquals("控制器需求文本", captor.getValue().requirementText());
    }

    @Test
    void controllerCreate_legacyBody_stillPublishesWithoutLink() {
        Response<?> response = controller.create(
                new AiBatchCreateRequest(projectA, List.of("旧形态文本"), null), 5L);
        Long batchId = (Long) ((java.util.Map<?, ?>) response.getData()).get("batchId");
        // Two-argument overload: the second value is the window-feed flag, not asserted here.
        createdBatchIds.add(batchId);
        ArgumentCaptor<AiGenMessage> captor = ArgumentCaptor.forClass(AiGenMessage.class);
        verify(aiGenPublisher, times(1)).publish(captor.capture(), anyBoolean());
        assertNull(captor.getValue().requirementId(), "legacy form keeps the envelope unlinked");
        assertEquals("旧形态文本", captor.getValue().requirementText());
    }
}
