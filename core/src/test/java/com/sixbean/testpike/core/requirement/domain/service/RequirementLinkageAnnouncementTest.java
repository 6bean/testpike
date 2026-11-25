package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher;
import com.sixbean.testpike.core.events.api.dto.RequirementEventMessage;
import com.sixbean.testpike.core.events.domain.service.RequirementEventPublisher;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementReviewAction;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

/**
 * Requirement save chains announce on the generic object-changed bus next to
 * the legacy requirement family (both under the shared requirement.changed
 * routing key — no second key is invented). Without the generic frame the
 * linkage edge detectors never see requirement edits: the wildcard bus
 * binding silently serves only the domains that adopted the publisher.
 * <p>Every really-written version must be announced at all three publish
 * points: the update chain, the review-pass door and the delete chain (the
 * flag flip is an audited change too). Duplicate frames on one version are
 * harmless by design — the impact ledger is keyed by edge + row + version.
 * <p>Spies wrap the REAL publishers (the live RabbitMQ send still runs); the
 * assertion is on the announcement contract each publish point emits. Owned
 * rows only, deleted by id afterwards.
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementLinkageAnnouncementTest {

    @MockitoSpyBean
    private ObjectChangedEventPublisher objectChangedEventPublisher;

    @MockitoSpyBean
    private RequirementEventPublisher requirementEventPublisher;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private JdbcTemplate jdbc;

    private long projectId;
    private long operatorId;
    private long requirementId;

    @BeforeEach
    void seedScope() {
        long nano = System.nanoTime();
        operatorId = jdbc.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        projectId = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "req-announce-" + nano, "reqa" + (nano % 1_000_000));
        requirementId = requirementService.create(operatorId,
                new RequirementCreateInput(projectId, 3, "ann-" + nano,
                        "双帧公告需求" + nano, "验收：通用帧在场", null, true)).id();
    }

    @AfterEach
    void removeOwnedRows() {
        jdbc.update("DELETE FROM tp_linkage_proposal WHERE impact_id IN"
                + " (SELECT id FROM tp_linkage_impact"
                + "  WHERE upstream_object_type = 'requirement' AND upstream_object_id = ?)",
                requirementId);
        jdbc.update("DELETE FROM tp_linkage_impact"
                + " WHERE upstream_object_type = 'requirement' AND upstream_object_id = ?",
                requirementId);
        jdbc.update("DELETE FROM tp_requirement_review_log WHERE requirement_id = ?", requirementId);
        jdbc.update("DELETE FROM tp_requirement_version WHERE requirement_id = ?", requirementId);
        jdbc.update("DELETE FROM tp_requirement WHERE id = ?", requirementId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    /** Generic frames captured for the requirement owned by this test only. */
    private List<ObjectChangedEventMessage> genericFrames() {
        ArgumentCaptor<ObjectChangedEventMessage> captor =
                ArgumentCaptor.forClass(ObjectChangedEventMessage.class);
        verify(objectChangedEventPublisher, atLeast(0)).publish(captor.capture());
        return captor.getAllValues().stream()
                .filter(f -> f != null && f.objectId() != null
                        && f.objectId() == requirementId)
                .toList();
    }

    /** Legacy requirement-family frames captured for the owned row only. */
    private List<RequirementEventMessage> legacyFrames() {
        ArgumentCaptor<RequirementEventMessage> captor =
                ArgumentCaptor.forClass(RequirementEventMessage.class);
        verify(requirementEventPublisher, atLeast(0)).publish(captor.capture());
        return captor.getAllValues().stream()
                .filter(f -> f != null && Long.valueOf(requirementId).equals(f.requirementId()))
                .toList();
    }

    @Test
    void save_chain_announces_legacy_and_generic_frames() {
        requirementService.update(operatorId, requirementId,
                new RequirementUpdateInput("双帧公告需求-改", null, null, null, null, null, null));

        List<RequirementEventMessage> legacy = legacyFrames();
        assertTrue(legacy.stream().anyMatch(f -> "CHANGED".equals(f.eventType())),
                "the legacy requirement.changed announcement must stay in place");
        List<ObjectChangedEventMessage> generic = genericFrames();
        assertEquals(1, generic.size(),
                "the save chain must also announce exactly one generic object-changed frame "
                        + "so the wildcard linkage bus sees requirement edits");
        ObjectChangedEventMessage frame = generic.get(0);
        assertEquals(ObjectChangedEventType.REQUIREMENT.name(), frame.eventType());
        assertEquals(Integer.valueOf(2), frame.objectVersion(),
                "the generic frame carries the post-write version (idempotency anchor)");
        assertTrue(frame.changedFields() == null || frame.changedFields().isEmpty(),
                "empty changed fields = conservative arming per the DTO contract");
    }

    @Test
    void review_pass_door_announces_the_generic_frame() {
        jdbc.update("UPDATE tp_requirement SET status = 2 WHERE id = ?", requirementId);
        requirementService.submitReview(operatorId, requirementId,
                RequirementReviewAction.PASS, null);

        List<ObjectChangedEventMessage> generic = genericFrames();
        assertTrue(generic.stream()
                        .anyMatch(f -> ObjectChangedEventType.REQUIREMENT.name()
                                .equals(f.eventType()) && f.objectVersion() != null),
                "the review-pass publish point must also arm the linkage bus "
                        + "via a generic frame");
    }

    @Test
    void delete_chain_announces_both_frames_with_the_bumped_version() {
        requirementService.delete(operatorId, requirementId);

        assertTrue(legacyFrames().stream().anyMatch(
                        f -> "CHANGED".equals(f.eventType())
                                && Integer.valueOf(2).equals(f.requirementVersion())),
                "the delete chain keeps its legacy announcement at the bumped version");
        List<ObjectChangedEventMessage> generic = genericFrames();
        assertEquals(1, generic.size(),
                "the soft-delete flag flip is an audited change and must arm "
                        + "the linkage bus with exactly one generic frame");
        assertEquals(Integer.valueOf(2), generic.get(0).objectVersion());
    }
}
