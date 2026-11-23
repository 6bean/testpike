package com.sixbean.testpike.core.plans.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipDoor;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.plans.domain.service.PlanService;
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
 * Plan family bridge on the read door: the governance row says "plan"/"id" and this bridge turns
 * that row id into the owning project through the one existing plan detail path (never a second
 * ownership query). Properties held here: the entity name routes, numeric and string id forms both
 * resolve, a missing row resolves to nothing (fail closed), unrelated service problems are not
 * swallowed, a non-numeric argument never reaches the service, and the real door opens for a
 * member and refuses an outsider through this bridge.
 */
class PlanRowProjectLookupTest {

    private static final long PLAN_ID = 301L;
    private static final long OWNING_PROJECT = 7L;
    private static final long OPERATOR = 42L;

    private PlanService planService;
    private PlanRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        lookup = new PlanRowProjectLookup(planService);
    }

    private static PlanInfo planRow(long projectId) {
        return new PlanInfo(PLAN_ID, projectId, "regression round", null, 1,
                PlanInfo.statusLabel(1), 1L, List.of(), null, null, null,
                null, null, 0L, 0L, 1L, null, 1);
    }

    @Test
    void entityNameRoutesTheDoorToThisBridge() {
        when(planService.get(PLAN_ID)).thenReturn(planRow(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "plan", anchoredPlanRow(),
                "id", PLAN_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void existingPlanResolvesToItsOwningProject() {
        when(planService.get(PLAN_ID)).thenReturn(planRow(OWNING_PROJECT));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds(PLAN_ID));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds("301"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void missingPlanResolvesToNothing() {
        when(planService.get(999L)).thenThrow(
                new ServiceException("plan not found", BizErrorCode.NOT_FOUND));
        assertNull(lookup.ownerProjectIds(999L),
                "a row id that matches nothing is not an open door");
    }

    @Test
    void unrelatedServiceProblemsAreNotSwallowed() {
        when(planService.get(PLAN_ID)).thenThrow(new ServiceException("db down", 500));
        assertThrows(ServiceException.class, () -> lookup.ownerProjectIds(PLAN_ID),
                "only the not-found verdict may be folded into an unresolvable row");
    }

    @Test
    void nonNumericArgumentIsNotResolvableAndNeverReachesTheService() {
        RowOwnershipDoor.assertNonNumericArgumentIsInert(lookup, "abc", null);
        verifyNoInteractions(planService);
    }

    private ApiAccessGovernanceView anchoredPlanRow() {
        return new ApiAccessGovernanceView("GET /internal/plans/{id}", true, true,
                null, null, "plan", "id");
    }

    @Test
    void doorServesAMemberThroughTheRealBridge() {
        when(planService.get(PLAN_ID)).thenReturn(planRow(OWNING_PROJECT));
        assertNull(RowOwnershipDoor.door(OPERATOR, true, lookup)
                .problem(anchoredPlanRow(), OPERATOR, Map.of("id", "301")),
                "the routed bridge must keep the door's member verdict open");
    }

    @Test
    void doorRefusesAnOutsiderThroughTheRealBridge() {
        when(planService.get(PLAN_ID)).thenReturn(planRow(OWNING_PROJECT));
        String problem = RowOwnershipDoor.door(OPERATOR, false, lookup)
                .problem(anchoredPlanRow(), OPERATOR, Map.of("id", PLAN_ID));
        assertNotNull(problem);
        assertTrue(problem.contains("越权读取已被拒绝"), problem);
    }
}
