package com.sixbean.testpike.core.llmconfig.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import com.sixbean.testpike.core.llmconfig.domain.component.GoldenRebindReplayTrigger;
import com.sixbean.testpike.core.llmconfig.domain.service.LlmConfigService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import com.sixbean.testpike.core.support.LlmConfigSharedDbFixture;

/**
 * 3 wiring contract: the project binding save hands the golden
 * route replay trigger exactly one dispatch per real route change — old and
 * new effective route names captured around the write — and never lets a
 * dispatch failure leak back into the human save. The trigger itself is a
 * mock here (its fan-out semantics have their own test class); what these
 * cases pin is when the trigger is asked, with what arguments, and that the
 * save path is fire-and-forget.
 */
@SpringBootTest(classes = CoreApplication.class)
class LlmConfigServiceRebindTriggerTest {

    @Autowired
    private LlmConfigService llmConfigService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private GoldenRebindReplayTrigger rebindReplayTrigger;

    private long projectId;

    @BeforeEach
    void cleanAndCreateProject() {
        // R37: no TRUNCATE — factory rows preserved, only this fixture's residue.
        LlmConfigSharedDbFixture.cleanPreservingFactory(jdbcTemplate, List.of("REBINDWIRE"));
        projectId = createProject();
    }

    @AfterEach
    void cleanTables() {
        LlmConfigSharedDbFixture.cleanPreservingFactory(jdbcTemplate, List.of("REBINDWIRE"));
    }

    private long createProject() {
        return projectService.create(5L, new com.sixbean.testpike.core.project.api.dto.ProjectCreateInput(
                "换档接线项目", "REBINDWIRE", "zh-CN", "P1", null));
    }

    private long createModel(String name) {
        LlmConfigInfo info = llmConfigService.create(5L, new LlmConfigInput(
                name, "mock-provider", name + "-model", null, null, 1, 0, "fixture"));
        return info.id();
    }

    @Test
    void bindSwap_dispatchesOnceWithOldAndNewRouteNames() {
        long modelA = createModel("route-a");
        long modelB = createModel("route-b");
        llmConfigService.updateProjectConfig(5L, projectId, modelA);
        clearInvocations(rebindReplayTrigger);

        llmConfigService.updateProjectConfig(5L, projectId, modelB);

        verify(rebindReplayTrigger, times(1)).onProjectRouteChanged(projectId, "route-a", "route-b");
    }

    @Test
    void rebindToSameRow_dispatchesNothing() {
        long modelA = createModel("route-a");
        llmConfigService.updateProjectConfig(5L, projectId, modelA);
        clearInvocations(rebindReplayTrigger);

        llmConfigService.updateProjectConfig(5L, projectId, modelA);

        verify(rebindReplayTrigger, never()).onProjectRouteChanged(anyLong(), any(), any());
    }

    @Test
    void unbindToDefaultless_dispatchesWithNullCandidate() {
        // Baseline-aware: the generation install-default row seeded by
        // tools/verification/seed_slot_llm_default.py is resident shared baseline (R37);
        // park it around this body instead of deleting it, then restore in finally.
        LlmConfigSharedDbFixture.withParkedGenerationInstallDefaults(jdbcTemplate, () -> {
            long modelA = createModel("route-a");
            llmConfigService.updateProjectConfig(5L, projectId, modelA);
            clearInvocations(rebindReplayTrigger);

            llmConfigService.updateProjectConfig(5L, projectId, null);

            // No generation default exists in this fixture state, so the route
            // falls to null — the trigger's own contract is to stay silent on a
            // null candidate; the wiring just reports the honest change.
            ArgumentCaptor<String> control = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> candidate = ArgumentCaptor.forClass(String.class);
            verify(rebindReplayTrigger, times(1))
                    .onProjectRouteChanged(anyLong(), control.capture(), candidate.capture());
            assertEquals("route-a", control.getValue());
            assertEquals(null, candidate.getValue());
            });
    }

    @Test
    void triggerThrowing_stillLeavesSaveCommitted() {
        long modelA = createModel("route-a");
        long modelB = createModel("route-b");
        llmConfigService.updateProjectConfig(5L, projectId, modelA);
        clearInvocations(rebindReplayTrigger);
        doThrow(new IllegalStateException("simulated dispatch outage"))
                .when(rebindReplayTrigger).onProjectRouteChanged(anyLong(), any(), any());

        assertDoesNotThrow(() -> llmConfigService.updateProjectConfig(5L, projectId, modelB));

        Long persisted = jdbcTemplate.queryForObject(
                "SELECT llm_config_id FROM tp_project WHERE id = ?", Long.class, projectId);
        assertEquals(modelB, persisted, "save must stay committed despite the failing dispatch");
    }
}
