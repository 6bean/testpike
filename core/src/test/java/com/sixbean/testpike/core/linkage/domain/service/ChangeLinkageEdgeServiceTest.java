package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInfo;
import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Change-linkage edge registry contract test against the real slot database,
 * no mocks. The registry is the loop breaker for "which downstream objects does
 * saving X touch?": the bus may only act on rows that passed these checks, so
 * an edge without a detector (not a registered exemption) must be rejected —
 * otherwise the edge ledger silently rots and the orphan-edge scan can never
 * trust the table. Fixtures are own rows only (unique edge keys, deleted by key
 * in teardown; never a whole-table wipe).
 */
@SpringBootTest(classes = CoreApplication.class)
class ChangeLinkageEdgeServiceTest {

    @Autowired
    private ChangeLinkageEdgeService changeLinkageEdgeService;

    @Autowired
    private NamedParameterJdbcTemplate named;

    private final List<String> createdEdgeKeys = new java.util.ArrayList<>();

    @AfterEach
    void removeOwnedFixtures() {
        if (!createdEdgeKeys.isEmpty()) {
            named.update("DELETE FROM tp_change_linkage WHERE edge_key IN (:keys)",
                    new MapSqlParameterSource("keys", createdEdgeKeys));
            createdEdgeKeys.clear();
        }
    }

    private String edgeKey(String upstream, String downstream) {
        String key = upstream + "->" + downstream + ":t" + System.nanoTime();
        createdEdgeKeys.add(key);
        return key;
    }

    private ChangeLinkageEdgeInput validInput(String edgeKey) {
        return new ChangeLinkageEdgeInput(edgeKey, "case", "plan_case",
                1, "caseImpactDetector", "title,expectation",
                "mark_only", 1, 0, null,
                "Editing a case may stale the plans that schedule it");
    }

    @Test
    void missing_edge_failures_point_at_the_pending_panel_and_the_next_step() {
        String absentKey = "case->plan_case:absent" + System.nanoTime();

        ServiceException looked = assertThrows(ServiceException.class,
                () -> changeLinkageEdgeService.findByEdgeKey(absentKey));
        assertTrue(looked.getMessage().contains(absentKey),
                "the refusal must name the edge key the caller passed");
        assertTrue(looked.getMessage().contains("待处置的未提交改动"),
                "guidance must point at a panel the user can actually open");
        assertTrue(looked.getMessage().contains("保存一次"),
                "guidance must state the retry action that rebuilds the edge impact");

        ServiceException disabled = assertThrows(ServiceException.class,
                () -> changeLinkageEdgeService.disable(absentKey));
        assertTrue(disabled.getMessage().contains(absentKey));
        assertTrue(disabled.getMessage().contains("无需重复操作"),
                "an already-cleaned edge must tell the caller no further action is needed");
    }

    @Test
    void register_then_active_lookup_returns_the_edge() {
        String key = edgeKey("case", "plan_case");
        ChangeLinkageEdgeInfo saved = changeLinkageEdgeService.register(validInput(key));

        assertNotNull(saved.id(), "registered edge must come back with its row id");
        assertEquals(key, saved.definition().edgeKey());
        assertEquals(1, saved.enabled(), "a fresh edge is active by default");

        List<ChangeLinkageEdgeInfo> active = changeLinkageEdgeService.findActiveEdges("case");
        assertTrue(active.stream().anyMatch(e -> e.definition().edgeKey().equals(key)),
                "the bus looks edges up by upstream type; a registered active edge must be found");
    }

    @Test
    void register_edge_without_detector_is_rejected() {
        String key = edgeKey("case", "exec_batch");
        ChangeLinkageEdgeInput noDetector = new ChangeLinkageEdgeInput(key, "case", "exec_batch",
                1, "  ", null, "mark_only", 1, 0, null, null);

        ServiceException e = assertThrows(ServiceException.class,
                () -> changeLinkageEdgeService.register(noDetector));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode(),
                "a non-exempt edge without a detector reference must not enter the ledger");
    }

    @Test
    void register_exempt_edge_without_reason_is_rejected() {
        String key = edgeKey("skill", "case");
        ChangeLinkageEdgeInput noReason = new ChangeLinkageEdgeInput(key, "skill", "case",
                3, null, null, "exempt", 1, 1, "   ", null);

        assertThrows(ServiceException.class, () -> changeLinkageEdgeService.register(noReason),
                "an exemption row without a written reason is unauditable and must be refused");
    }

    @Test
    void register_exempt_edge_keeps_it_out_of_the_active_bus_lookup() {
        String key = edgeKey("tag", "case");
        ChangeLinkageEdgeInput exempt = new ChangeLinkageEdgeInput(key, "tag", "case",
                3, null, null, "exempt", 1, 1,
                "Renaming a tag only re-groups labels; downstream content is not invalidated",
                null);
        changeLinkageEdgeService.register(exempt);

        List<ChangeLinkageEdgeInfo> active = changeLinkageEdgeService.findActiveEdges("tag");
        assertTrue(active.stream().noneMatch(e -> e.definition().edgeKey().equals(key)),
                "exempt edges are ledger coverage for the orphan scan, not dispatch targets");
    }

    @Test
    void re_registering_the_identical_edge_is_a_no_op() {
        String key = edgeKey("api_spec", "mock_stub");
        ChangeLinkageEdgeInput input = validInput(key);
        ChangeLinkageEdgeInfo first = changeLinkageEdgeService.register(input);
        ChangeLinkageEdgeInfo replay = changeLinkageEdgeService.register(input);

        assertEquals(first.id(), replay.id(), "identical replay must not rebuild the row");
        assertEquals(first.updatedAt(), replay.updatedAt(),
                "identical replay must not move updated_at (no fabricated change facts)");
    }

    @Test
    void disable_edge_removes_it_from_active_lookup_but_keeps_the_audit_row() {
        String key = edgeKey("plan", "defect");
        changeLinkageEdgeService.register(validInput(key));
        ChangeLinkageEdgeInfo disabled = changeLinkageEdgeService.disable(key);

        assertEquals(0, disabled.enabled());
        assertTrue(changeLinkageEdgeService.findActiveEdges("plan").stream()
                .noneMatch(e -> e.definition().edgeKey().equals(key)), "disabled edge must stop dispatching");
        assertNotNull(changeLinkageEdgeService.findByEdgeKey(key).definition().edgeKey(),
                "disabled edge stays in the ledger for audit");
    }

    @Test
    void register_rejects_disposition_outside_the_known_shapes() {
        String key = edgeKey("case", "script");
        ChangeLinkageEdgeInput bogus = new ChangeLinkageEdgeInput(key, "case", "script",
                1, "scriptImpactDetector", null, "auto_rewrite_everything", 2, 0, null, null);

        assertThrows(ServiceException.class, () -> changeLinkageEdgeService.register(bogus),
                "the disposition vocabulary is closed; an unknown shape must be refused");
    }
}
