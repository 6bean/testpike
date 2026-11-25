package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.document.api.dto.DocChangeAnalysisInfo;
import com.sixbean.testpike.core.events.api.dto.RequirementEventMessage;
import com.sixbean.testpike.core.events.api.dto.RequirementEventType;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Document-change disposition writeback tests: confirming a change analysis now
 * walks the manual requirement update chain (status 2 = changed, version bump,
 * STATUS snapshot) for every requirement referenced by the recorded diff items,
 * and emits the requirement.changed event per really-written row.
 * <p>Real dev database and real RabbitMQ — no mocks. Fixtures create their own
 * rows and clean up by collected ids; factory tables are never truncated here.
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementChangeWritebackTest {

    @Autowired
    private DocumentChangeAnalysisService analysisService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    private final List<Long> projectIds = new ArrayList<>();
    private final List<Long> requirementIds = new ArrayList<>();
    private final List<Long> analysisIds = new ArrayList<>();

    @AfterEach
    void cleanupCreatedRows() {
        for (long aid : analysisIds) {
            jdbcTemplate.update("DELETE FROM tp_doc_change_analysis WHERE id = ?", aid);
        }
        for (long rid : requirementIds) {
            jdbcTemplate.update("DELETE FROM tp_requirement_version WHERE requirement_id = ?", rid);
            jdbcTemplate.update("DELETE FROM tp_requirement WHERE id = ?", rid);
        }
        for (long pid : projectIds) {
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", pid);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", pid);
        }
    }

    private long createProject() {
        String code = "alb" + UUID.randomUUID().toString().substring(0, 6);
        long pid = projectService.create(1L,
                new ProjectCreateInput("Writeback " + code, code, "zh-CN", "P1", null));
        projectIds.add(pid);
        return pid;
    }

    /** Seed a live requirement row (manual-source shape; collected for cleanup). */
    private long seedRequirement(long projectId, String title, int status, int version) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_requirement (project_id, source, title, status, version)"
                        + " VALUES (?, 3, ?, ?, ?) RETURNING id",
                Long.class, projectId, title, status, version);
        requirementIds.add(id);
        return id;
    }

    /** Seed one pending analysis row whose diff items reference the given requirements. */
    private long seedPendingAnalysis(long projectId, List<List<Long>> requirementIdsPerItem) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < requirementIdsPerItem.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"chapter\":\"ch").append(i).append("\",\"changeType\":\"modify\",")
                    .append("\"requirementIds\":").append(requirementIdsPerItem.get(i))
                    .append(",\"caseIds\":[],\"chapterLocatable\":true}");
        }
        json.append(']');
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_doc_change_analysis (project_id, old_document_id, new_document_id,"
                        + " changes, chapter_locatable, status, created_by)"
                        + " VALUES (?, 0, 0, ?::jsonb, true, 0, 1) RETURNING id",
                Long.class, projectId, json.toString());
        analysisIds.add(id);
        return id;
    }

    private int statusOf(long requirementId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tp_requirement WHERE id = ?", Integer.class, requirementId);
    }

    private int versionOf(long requirementId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM tp_requirement WHERE id = ?", Integer.class, requirementId);
    }

    private int snapshotCount(long requirementId, int version, int changeType) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement_version WHERE requirement_id = ?"
                        + " AND version = ? AND change_type = ?",
                Integer.class, requirementId, version, changeType);
    }

    @Test
    void confirm_disposition_writes_back_status_changed_and_versioned_snapshot_per_item() {
        long projectId = createProject();
        long reqA = seedRequirement(projectId, "Order flow rule", 1, 1);
        long reqB = seedRequirement(projectId, "Stock flow rule", 1, 1);
        long analysisId = seedPendingAnalysis(projectId,
                List.of(List.of(reqA), List.of(reqB, reqA)));

        DocChangeAnalysisInfo handled = analysisService.handle(1L, analysisId, "reuploaded v2");

        assertEquals(1, handled.status(), "analysis row flips to handled");
        assertEquals(2, statusOf(reqA), "referenced requirement becomes 2=changed (first state-machine producer)");
        assertEquals(2, statusOf(reqB), "every requirement across all diff items is written back");
        assertEquals(2, versionOf(reqA), "writeback travels the manual update chain, so the row version bumps");
        assertEquals(1, snapshotCount(reqA, 2, 2),
                "status transition is snapshotted (change_type=2 STATUS) at the new version for audit");
        assertEquals(1, snapshotCount(reqB, 2, 2));
    }

    @Test
    void confirm_disposition_skips_soft_deleted_requirement_and_still_completes() {
        long projectId = createProject();
        long reqGone = seedRequirement(projectId, "Removed chapter rule", 1, 1);
        long reqAlive = seedRequirement(projectId, "Still valid rule", 1, 1);
        long analysisId = seedPendingAnalysis(projectId,
                List.of(List.of(reqGone, reqAlive)));
        jdbcTemplate.update("UPDATE tp_requirement SET deleted = 1 WHERE id = ?", reqGone);

        DocChangeAnalysisInfo handled = analysisService.handle(1L, analysisId, null);

        assertEquals(1, handled.status(), "disposition completes even with a vanished target");
        assertEquals(1, versionOf(reqGone), "soft-deleted row is skipped, never rewritten");
        assertEquals(2, statusOf(reqAlive));
    }

    @Test
    void confirm_disposition_burns_no_version_on_already_changed_requirement() {
        long projectId = createProject();
        long req = seedRequirement(projectId, "Marked changed earlier", 2, 3);
        long analysisId = seedPendingAnalysis(projectId, List.of(List.of(req)));

        analysisService.handle(1L, analysisId, null);

        assertEquals(2, statusOf(req));
        assertEquals(3, versionOf(req),
                "already-in-target-state row replays through the idempotent update chain with zero writes");
        assertEquals(0, snapshotCount(req, 4, 2), "no phantom snapshot for a no-op writeback");
    }

    @Test
    void confirm_disposition_emits_requirement_changed_only_for_really_written_rows() {
        // Non-auto-delete queue: an autoDelete queue vanishes as soon as the first
        // receive cancels its consumer, which would break the second absence probe.
        String queueName = "tp.test." + UUID.randomUUID();
        amqpAdmin.declareQueue(new Queue(queueName, false, false, false));
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, RequirementEventType.CHANGED.routingKey(), null));
        try {
            long projectId = createProject();
            long written = seedRequirement(projectId, "Impacted rule", 1, 1);
            long untouched = seedRequirement(projectId, "Already changed rule", 2, 3);
            long analysisId = seedPendingAnalysis(projectId, List.of(List.of(written, untouched)));

            analysisService.handle(1L, analysisId, null);

            Message first = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(first, "the written-back requirement must emit requirement.changed after commit");
            RequirementEventMessage evt =
                    (RequirementEventMessage) caseEventMessageConverter.fromMessage(first);
            assertEquals(RequirementEventType.CHANGED.name(), evt.eventType());
            assertEquals(written, evt.requirementId());
            assertEquals(projectId, evt.projectId());
            assertEquals(2, evt.requirementVersion(),
                    "payload carries the post-write version (consumer idempotency key source)");
            // The save chain deliberately emits two frames per really-written row
            // on this routing key: the typed event above plus the generic linkage
            // announcement (RequirementServiceImpl#publishLinkageAnnouncement).
            // The old bare receive-null probe conflated the second frame of the
            // written row with an event for the no-op row; classify each drained
            // frame instead and keep the no-op absence guarantee per frame type.
            boolean sawAnnouncementForWrittenRow = false;
            Message frame;
            while ((frame = rabbitTemplate.receive(queueName, 1500)) != null) {
                Object pojo = caseEventMessageConverter.fromMessage(frame);
                if (pojo instanceof ObjectChangedEventMessage announcement) {
                    assertEquals(Long.valueOf(written), announcement.objectId(),
                            "the generic linkage announcement must reference the written row");
                    sawAnnouncementForWrittenRow = true;
                } else if (pojo instanceof RequirementEventMessage typed) {
                    assertEquals(written, typed.requirementId(),
                            "the no-op row must not emit a typed requirement.changed event");
                } else {
                    throw new AssertionError("unexpected payload type on requirement.changed: "
                            + pojo.getClass().getName());
                }
            }
            assertTrue(sawAnnouncementForWrittenRow,
                    "the written row must also carry the generic linkage announcement "
                            + "(dual-frame save-chain contract)");
        } finally {
            amqpAdmin.deleteQueue(queueName, true, true);
        }
    }

    @Test
    void replayed_disposition_is_refused_and_writes_nothing() {
        long projectId = createProject();
        long req = seedRequirement(projectId, "Once is enough", 1, 1);
        long analysisId = seedPendingAnalysis(projectId, List.of(List.of(req)));

        analysisService.handle(1L, analysisId, "first");
        assertThrows(ServiceException.class, () -> analysisService.handle(1L, analysisId, "second"),
                "handled rows stay closed - no second writeback pass");
        assertEquals(2, versionOf(req), "exactly one version bump for the whole disposition");
    }
}
