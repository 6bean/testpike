package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipDoor;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Case family bridge on the read door: the governance row says "case"/"caseId" and this bridge
 * turns that row id into the owning project through the one existing case detail path (never a
 * second ownership query). The door then applies its shared membership decision. Properties held
 * here: the entity name routes, numeric and string id forms both resolve, a missing row resolves
 * to nothing (fail closed), unrelated service problems are not swallowed, and a non-numeric
 * argument never reaches the service.
 */
class CaseRowProjectLookupTest {

    private static final long CASE_ID = 501L;
    private static final long OWNING_PROJECT = 7L;
    private static final long OPERATOR = 42L;

    private CaseService caseService;
    private CaseRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        caseService = mock(CaseService.class);
        lookup = new CaseRowProjectLookup(caseService);
    }

    private static CaseInfo caseRow(long projectId) {
        return new CaseInfo(CASE_ID, "C-501", projectId, "login smoke", null, null, 2, 80, null,
                1, null, 1, null, null, 0, null, null, null, null, List.of(), 0, null, null, 0,
                null, null, null, null);
    }

    @Test
    void entityNameRoutesTheDoorToThisBridge() {
        when(caseService.get(CASE_ID)).thenReturn(caseRow(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "case", anchoredCaseRow(),
                "caseId", CASE_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void existingCaseResolvesToItsOwningProject() {
        when(caseService.get(CASE_ID)).thenReturn(caseRow(OWNING_PROJECT));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds(CASE_ID));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds("501"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void missingCaseResolvesToNothing() {
        when(caseService.get(999L)).thenThrow(
                new ServiceException("case not found", BizErrorCode.NOT_FOUND));
        RowOwnershipDoor.assertMissingRowIsRefused(lookup, anchoredCaseRow(),
                "caseId", 999L, OPERATOR);
    }

    @Test
    void unrelatedServiceProblemsAreNotSwallowed() {
        when(caseService.get(CASE_ID)).thenThrow(new ServiceException("db down", 500));
        assertThrows(ServiceException.class, () -> lookup.ownerProjectIds(CASE_ID),
                "only the not-found verdict may be folded into an unresolvable row");
    }

    @Test
    void nonNumericArgumentIsNotResolvableAndNeverReachesTheService() {
        RowOwnershipDoor.assertNonNumericArgumentIsInert(lookup, "abc", null);
        verifyNoInteractions(caseService);
    }

    private ApiAccessGovernanceView anchoredCaseRow() {
        return new ApiAccessGovernanceView("GET /internal/cases/{caseId}", true, true,
                null, null, "case", "caseId");
    }

    @Test
    void doorServesAMemberThroughTheRealBridge() {
        when(caseService.get(CASE_ID)).thenReturn(caseRow(OWNING_PROJECT));
        assertNull(RowOwnershipDoor.door(OPERATOR, true, lookup)
                .problem(anchoredCaseRow(), OPERATOR, Map.of("caseId", "501")),
                "the routed bridge must keep the door's member verdict open");
    }
}
