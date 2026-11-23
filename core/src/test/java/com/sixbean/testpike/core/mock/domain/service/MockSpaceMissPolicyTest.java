package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockRuleSnapshotView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Space-level miss policy : the three-rung ladder from the
 * design (passthrough | reject | llm-fallback) must be a real, saved,
 * per-space choice — refused on unknown values, carried into the pinned
 * snapshot the traffic plane consumes, and movable afterwards with the
 * optimistic-lock posture the transition door already uses.
 * <p>Default 'reject' keeps the semantics honest: a plain miss
 * answers 404+trace unless the space explicitly chose another rung.
 * <p>Real dev database, zero mocks (same posture as MockSpaceLifecycleTest).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockSpaceMissPolicyTest {

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t366b6" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private MockSpaceInput input(String key, String missPolicy) {
        return new MockSpaceInput("Space " + key, key, null, null,
                "prefix", "none", null, null, 100, null, missPolicy);
    }

    private String newKey() {
        return "mp-" + UUID.randomUUID().toString().substring(0, 10);
    }

    @Test
    void create_persists_chosen_policy_and_exposes_it_on_the_info_row() {
        String key = newKey();
        long id = spaceService.create(newProject(), input(key, "passthrough"));
        assertEquals("passthrough", spaceService.get(id).missPolicy());
        // Row truth: the column actually holds the choice (DTO echo alone proves nothing).
        String stored = jdbcTemplate.queryForObject(
                "SELECT miss_policy FROM tp_mock_space WHERE id = ?", String.class, id);
        assertEquals("passthrough", stored);
    }

    @Test
    void create_without_a_policy_choice_keeps_the_derived_llm_fallback_ladder() {
        // Default 'llm-fallback' = exactly the derived behaviour
        // (recording proxy rung, then the gated AI rung, then 404) — zero
        // regression for every space created before the policy existed.
        String key = newKey();
        long id = spaceService.create(newProject(), input(key, null));
        assertEquals("llm-fallback", spaceService.get(id).missPolicy());
    }

    @Test
    void create_refuses_an_unknown_policy_with_plain_words() {
        String key = newKey();
        var ex = assertThrows(com.sixbean.testpike.common.exception.ServiceException.class,
                () -> spaceService.create(newProject(), input(key, "teleport")));
        assertTrue(ex.getMessage().contains("passthrough"),
                "refusal must name the allowed rungs: " + ex.getMessage());
    }

    @Test
    void snapshot_carries_the_policy_in_the_pinned_space_header() {
        String key = newKey();
        long id = spaceService.create(newProject(), input(key, "llm-fallback"));
        spaceService.transition(id, 1, 0);
        MockRuleSnapshotView snap = spaceService.snapshot(key);
        assertEquals("llm-fallback", snap.space().missPolicy());
    }

    @Test
    void policy_update_moves_the_pin_and_keeps_the_optimistic_lock_honest() {
        String key = newKey();
        long id = spaceService.create(newProject(), input(key, "reject"));
        spaceService.transition(id, 1, 0);
        String pinBefore = spaceService.snapshot(key).version();

        // The policy door must move the pin: the loader re-pins on real drift.
        spaceService.updateMissPolicy(id, "passthrough", 1);
        assertEquals("passthrough", spaceService.get(id).missPolicy());
        assertNotEquals(pinBefore, spaceService.snapshot(key).version());

        // A stale expected version is refused, never a silent overwrite.
        assertThrows(com.sixbean.testpike.common.exception.ServiceException.class,
                () -> spaceService.updateMissPolicy(id, "llm-fallback", 1));
    }
}
