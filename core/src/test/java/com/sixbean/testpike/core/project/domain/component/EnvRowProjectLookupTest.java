package com.sixbean.testpike.core.project.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipDoor;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Env family bridge on the read door: governed rows name "env" plus the global envId parameter and
 * this bridge turns it into the owning project through the one existing per-envId read (the same
 * reverse lookup the admin env pages use before their membership check). Environment rows always
 * hang under a project, so a row whose project column is empty is a data anomaly and must fail
 * closed — unlike the system-level buckets elsewhere, "env" never means "visible to all".
 * Properties held here: the entity name routes, numeric and string id forms resolve, a missing row
 * fails closed, an ownerless row fails closed, unrelated service problems are not swallowed and a
 * non-numeric argument never reaches the service.
 */
class EnvRowProjectLookupTest {

    private static final long ENV_ID = 811L;
    private static final long OWNING_PROJECT = 7L;
    private static final long OPERATOR = 42L;

    private ProjectEnvService projectEnvService;
    private EnvRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        projectEnvService = mock(ProjectEnvService.class);
        lookup = new EnvRowProjectLookup(projectEnvService);
    }

    private static ProjectEnvInfo envRow(Long projectId) {
        return new ProjectEnvInfo(ENV_ID, projectId, "staging", 3, "https://staging.example.test",
                null, "web", "chromium", 1, null, null, null, null, null);
    }

    private ApiAccessGovernanceView anchoredEnvRow() {
        return new ApiAccessGovernanceView("GET /internal/projects/envs/{envId}", true, true,
                null, null, "env", "envId");
    }

    @Test
    void entityNameRoutesTheDoorToThisBridge() {
        when(projectEnvService.getByEnvId(ENV_ID)).thenReturn(envRow(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "env", anchoredEnvRow(),
                "envId", ENV_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void existingEnvResolvesToItsOwningProject() {
        when(projectEnvService.getByEnvId(ENV_ID)).thenReturn(envRow(OWNING_PROJECT));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds(ENV_ID));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds("811"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void ownerlessEnvRowIsRefusedNotOpened() {
        when(projectEnvService.getByEnvId(ENV_ID)).thenReturn(envRow(null));
        assertNull(lookup.ownerProjectIds(ENV_ID),
                "an env row with no project column is a data anomaly, never a global row");
    }

    @Test
    void missingEnvResolvesToNothing() {
        when(projectEnvService.getByEnvId(999L)).thenThrow(
                new ServiceException("env not found", BizErrorCode.NOT_FOUND));
        RowOwnershipDoor.assertMissingRowIsRefused(lookup, anchoredEnvRow(),
                "envId", 999L, OPERATOR);
    }

    @Test
    void unrelatedServiceProblemsAreNotSwallowed() {
        when(projectEnvService.getByEnvId(ENV_ID)).thenThrow(new ServiceException("db down", 500));
        assertThrows(ServiceException.class, () -> lookup.ownerProjectIds(ENV_ID),
                "only the not-found verdict may be folded into an unresolvable row");
    }

    @Test
    void nonNumericArgumentIsNotResolvableAndNeverReachesTheService() {
        RowOwnershipDoor.assertNonNumericArgumentIsInert(lookup, "abc", null);
        verifyNoInteractions(projectEnvService);
    }

    @Test
    void doorRefusesAnOutsiderThroughTheRealBridge() {
        when(projectEnvService.getByEnvId(ENV_ID)).thenReturn(envRow(OWNING_PROJECT));
        String problem = RowOwnershipDoor.door(OPERATOR, false, lookup)
                .problem(anchoredEnvRow(), OPERATOR, Map.of("envId", ENV_ID));
        assertNotNull(problem);
        assertTrue(problem.contains("越权读取已被拒绝"), problem);
    }
}
