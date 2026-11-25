package com.sixbean.testpike.core.linkage.infrastructure.mq;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.events.api.dto.RequirementEventMessage;
import com.sixbean.testpike.core.events.api.dto.RequirementEventType;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.common.mq.MqDeadLetterTopology;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Legacy requirement frame survival on the linkage bus. The knowledge-base
 * ingest door keeps publishing the pre-generic requirement shape (eventId
 * token {@code CHANGED} carrying {@code requirementId}); that frame shares
 * the {@code requirement.changed} routing key with the generic family, so
 * the wildcard-bound linkage queue receives both. A frame the consumer
 * cannot identify must never dead-letter a legacy announcement that still
 * arms real edges (requirement fan of the production edge ledger).
 * <p>Real bus, real database, no mocks; fixtures are owned rows only
 * (nano-suffixed names, deleted by id afterwards).
 */
@SpringBootTest(classes = CoreApplication.class)
class ChangeLinkageLegacyFrameTest {

    private static final String EDGE_KEY = "requirement->case";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.sixbean.testpike.core.linkage.infrastructure.mq.ChangeLinkageConsumer consumer;

    private long projectId;
    private long operatorId;
    private long requirementId;
    private long caseId;

    @BeforeEach
    void seedLinkedRequirement() {
        long nano = System.nanoTime();
        operatorId = jdbc.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        projectId = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "lnk-legacy-" + nano, "lnkl" + (nano % 1_000_000));
        requirementId = jdbc.queryForObject(
                "INSERT INTO tp_requirement (project_id, source, source_id, title, status, version)"
                        + " VALUES (?, 3, ?, '联动旧帧需求" + nano + "', 1, 1) RETURNING id",
                Long.class, projectId, "legacy-" + nano);
        caseId = jdbc.queryForObject(
                "INSERT INTO tp_test_case (case_no, project_id, title) VALUES (?, ?, ?) RETURNING id",
                Long.class, "LKL" + nano, projectId, "联动旧帧用例" + nano);
        jdbc.update("INSERT INTO tp_case_requirement (case_id, requirement_id, created_by)"
                + " VALUES (?, ?, ?)", caseId, requirementId, operatorId);
    }

    @AfterEach
    void removeOwnedRows() {
        jdbc.update("DELETE FROM tp_linkage_proposal WHERE impact_id IN"
                + " (SELECT id FROM tp_linkage_impact WHERE edge_key = ? AND downstream_id = ?)",
                EDGE_KEY, caseId);
        jdbc.update("DELETE FROM tp_linkage_impact WHERE edge_key = ? AND downstream_id = ?",
                EDGE_KEY, caseId);
        jdbc.update("DELETE FROM tp_case_requirement WHERE case_id = ?", caseId);
        jdbc.update("DELETE FROM tp_requirement_version WHERE requirement_id = ?", requirementId);
        jdbc.update("DELETE FROM tp_requirement WHERE id = ?", requirementId);
        jdbc.update("DELETE FROM tp_test_case WHERE id = ?", caseId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    @Test
    void legacy_requirement_frame_on_the_bus_arms_the_edge_without_poisoning_the_dead_letter()
            throws Exception {
        String dlqName = MqDeadLetterTopology.dlqName(ChangeLinkageQueueConfig.QUEUE_NAME);
        int dlqBefore = amqpAdmin.getQueueInfo(dlqName).getMessageCount();

        // the exact wire shape RabbitRequirementEventPublisher sends (legacy DTO,
        // routing key requirement.changed — no generic frame involved)
        rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE, "requirement.changed",
                new RequirementEventMessage(RequirementEventType.CHANGED.name(),
                        requirementId, projectId, operatorId, 1, System.currentTimeMillis()));

        Long impactId = null;
        for (int i = 0; i < 60 && impactId == null; i++) {
            Thread.sleep(500);
            impactId = jdbc.query(
                    "SELECT id FROM tp_linkage_impact WHERE edge_key = ?"
                            + " AND upstream_object_type = 'requirement'"
                            + " AND upstream_object_id = ? AND downstream_object_type = 'case'"
                            + " AND downstream_id = ? AND upstream_version = 1",
                    rs -> rs.next() ? rs.getLong(1) : null, EDGE_KEY, requirementId, caseId);
        }
        assertNotNull(impactId,
                "a legacy requirement.changed frame must still arm the requirement->case edge "
                        + "instead of dead-lettering on an unresolvable objectId");
        int dlqAfter = amqpAdmin.getQueueInfo(dlqName).getMessageCount();
        assertEquals(dlqBefore, dlqAfter,
                "the legacy frame must never reach the linkage dead-letter queue");
        assertTrue(dlqAfter >= 0);
    }

    @Test
    void a_frame_without_identity_in_either_shape_is_rejected_not_swallowed() {
        // A frame the consumer can neither read as generic nor fold from the
        // legacy shape must fail loudly (retry then dead letter) — a silent
        // ack here is how announcements vanish.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> consumer.onObjectChanged(java.util.Map.of("eventType", "CHANGED",
                        "projectId", 5L)));
        // The review-pass legacy shape is NOT a changed announcement: folding
        // it would arm edges the door never intended.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> consumer.onObjectChanged(java.util.Map.of("eventType", "REVIEW_PASSED",
                        "requirementId", 9L)));
    }
}
