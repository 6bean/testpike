package com.sixbean.testpike.core.plans.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.plans.api.dto.RehearsalDraftView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * anchors: the read-only rehearsal-draft assembly. The composer
 * turns one suite (or one existing plan template) of the server-carried
 * project segment into a PLAN_WRITE-shaped draft payload; it writes nothing and
 * refuses every ambiguous or out-of-slice source by name.
 */
class RehearsalDraftComposerTest {

    private JdbcTemplate jdbcTemplate;
    private RehearsalDraftComposer composer;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        composer = new RehearsalDraftComposer(jdbcTemplate, new ObjectMapper());
    }

    /** Stub the single-row suite read (jsonb cast to text keeps the mock simple). */
    private void givenSuite(long projectId, long id, String name, String caseIdsJson, String envIdsJson) {
        when(jdbcTemplate.queryForList(anyString(), eq(projectId), eq(id)))
                .thenReturn(List.of(Map.of("name", name, "cases", caseIdsJson, "envs", envIdsJson)));
    }

    @Test
    void refusesWhenNeitherSuiteNorPlanIsNamed() {
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> composer.compose(1L, null, null));
        assertTrue(refusal.getMessage().contains("suiteId"),
                "refusal must name the accepted sources: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("planId"), refusal.getMessage());
    }

    @Test
    void refusesWhenBothSourcesAreNamedAtOnce() {
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> composer.compose(1L, 5L, 9L));
        assertTrue(refusal.getMessage().contains("二选一"),
                "must say only one source may be named: " + refusal.getMessage());
    }

    @Test
    void assemblesSuiteDraftWithExplicitCaseScopeAndDefaultCriteria() {
        givenSuite(1L, 77L, "支付回归集", "[101,102,103]", "[9]");

        RehearsalDraftView view = composer.compose(1L, 77L, null);

        assertEquals("SUITE", view.sourceKind());
        assertEquals(3, view.caseCount());
        assertTrue(view.planName().startsWith("演练·支付回归集·"), view.planName());
        Map<String, Object> payload = view.planCreatePayload();
        assertEquals(view.planName(), payload.get("name"));
        assertEquals(List.of(9L), payload.get("envIds"));
        Map<?, ?> scope = (Map<?, ?>) payload.get("caseScope");
        assertEquals(List.of(101L, 102L, 103L), scope.get("explicitCaseIds"));
        Map<?, ?> exit = (Map<?, ?>) payload.get("exitCriteria");
        assertEquals(0.95, ((Number) exit.get("minPassRate")).doubleValue(), 1e-9);
        assertEquals(Boolean.TRUE, exit.get("noOpenDefects"));
        assertFalse(payload.containsKey("projectId"),
                "the payload must never carry a model-fillable project key");
    }

    @Test
    void refusesASuiteThatHoldsNoCases() {
        givenSuite(1L, 77L, "空回归集", "[]", "[9]");

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> composer.compose(1L, 77L, null));
        assertTrue(refusal.getMessage().contains("空回归集"),
                "refusal must name the suite: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("用例"), refusal.getMessage());
    }

    @Test
    void refusesASuiteOutsideTheSliceOrMissingById() {
        when(jdbcTemplate.queryForList(anyString(), eq(1L), eq(404L))).thenReturn(List.of());

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> composer.compose(1L, 404L, null));
        assertTrue(refusal.getMessage().contains("404"), "must name the refused id: " + refusal.getMessage());
    }

    @Test
    void annotatesWhenTheSuiteSavedNoEnvironments() {
        givenSuite(1L, 77L, "缺环境回归集", "[101]", "[]");

        RehearsalDraftView view = composer.compose(1L, 77L, null);

        assertTrue(view.envIdsNote() != null && view.envIdsNote().contains("环境"),
                "empty envIds must surface an honest note naming the env gap: " + view.envIdsNote());
    }

    @Test
    void copiesPlanTemplateScopeAndSavedCriteriaVerbatim() {
        when(jdbcTemplate.queryForList(anyString(), eq(1L), eq(88L)))
                .thenReturn(List.of(Map.of(
                        "name", "月度回归计划",
                        "envs", "[7,8]",
                        "scope", "{\"phases\":[3],\"explicitCaseIds\":[]}",
                        "entry", "{\"requireSmokePass\":false}",
                        "exit", "{\"minPassRate\":0.9,\"noOpenDefects\":false}")));

        RehearsalDraftView view = composer.compose(1L, null, 88L);

        assertEquals("PLAN", view.sourceKind());
        Map<?, ?> scope = (Map<?, ?>) view.planCreatePayload().get("caseScope");
        assertEquals(List.of(3), scope.get("phases"));
        Map<?, ?> exit = (Map<?, ?>) view.planCreatePayload().get("exitCriteria");
        assertEquals(0.9, ((Number) exit.get("minPassRate")).doubleValue(), 1e-9,
                "the plan's saved exit key is copied verbatim, never re-defaulted");
        Map<?, ?> entry = (Map<?, ?>) view.planCreatePayload().get("entryCriteria");
        assertEquals(Boolean.FALSE, entry.get("requireSmokePass"));
        assertTrue(view.planName().startsWith("演练·月度回归计划·"), view.planName());
    }
}
