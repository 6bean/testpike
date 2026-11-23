package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Row-level ownership on the read door: a governance row may name the entity and the id parameter
 * locating the concrete data row, and the door then applies the same project membership decision it
 * already applies to project-anchored calls. The properties guarded here: rows without an anchor
 * keep their family-level verdict (no regression for the registered entries), anchored rows fail
 * closed whenever the row cannot be resolved (no id argument, family bridge not wired, unknown row),
 * members of the owning project are served, and administrators pass through the shared decision.
 */
class ApiAccessRowOwnershipTest {

    private static final String KEY = "GET /internal/cases/{caseId}";
    private static final long OPERATOR = 42L;
    private static final long ADMIN = 9L;
    private static final long CASE_ID = 501L;
    private static final long OWNING_PROJECT = 7L;

    private ProjectRoleResolver roleResolver;
    private AdminAlertRecipients admins;

    @BeforeEach
    void setUp() {
        roleResolver = mock(ProjectRoleResolver.class);
        admins = mock(AdminAlertRecipients.class);
        when(admins.adminUserIds()).thenReturn(List.of(ADMIN));
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private ApiAccessRowOwnership doorWith(RowProjectLookup... lookups) {
        ObjectProvider<RowProjectLookup> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(lookups));
        return new ApiAccessRowOwnership(roleResolver, admins, provider);
    }

    private ApiAccessGovernanceView anchoredRow() {
        return new ApiAccessGovernanceView(KEY, true, true, null, null, "case", "caseId");
    }

    @SuppressWarnings("unchecked")
    private RowProjectLookup caseLookup(List<Long> ownerProjects) {
        RowProjectLookup lookup = mock(RowProjectLookup.class);
        when(lookup.entity()).thenReturn("case");
        when(lookup.ownerProjectIds(CASE_ID)).thenReturn(ownerProjects);
        return lookup;
    }

    @SuppressWarnings("unchecked")
    private RowProjectLookup systemLevelLookup() {
        RowProjectLookup lookup = mock(RowProjectLookup.class);
        when(lookup.entity()).thenReturn("case");
        when(lookup.ownerProjectIds(CASE_ID)).thenReturn(List.of());
        return lookup;
    }

    @Test
    void rowsWithoutARowAnchorKeepTheFamilyLevelVerdict() {
        ApiAccessGovernanceView plain = new ApiAccessGovernanceView(KEY, true, true, null, null);
        assertNull(doorWith().problem(plain, OPERATOR, Map.of("caseId", CASE_ID)),
                "registered family verdicts must not start failing because of this change");
        assertNull(doorWith().problem(null, OPERATOR, Map.of()));
    }

    @Test
    void anchoredRowFailsClosedWhenTheEntityBridgeIsNotWired() {
        String problem = doorWith().problem(anchoredRow(), OPERATOR, Map.of("caseId", CASE_ID));
        assertNotNull(problem, "an anchored row whose family bridge has not landed must stay closed");
        assertTrue(problem.contains("尚未接入"), problem);
    }

    @Test
    void anchoredRowWithoutTheIdArgumentIsRefused() {
        assertNotNull(doorWith(caseLookup(List.of(OWNING_PROJECT))).problem(anchoredRow(), OPERATOR, Map.of()),
                "without the id argument there is no row to judge, so the door must refuse");
    }

    @Test
    void anchoredRowOnAnUnknownRowIsRefused() {
        assertNotNull(doorWith(caseLookup(null)).problem(anchoredRow(), OPERATOR, Map.of("caseId", CASE_ID)),
                "a row id that resolves to nothing is not an open door");
    }

    @Test
    void memberOfTheOwningProjectIsServed() {
        when(roleResolver.isMember(OWNING_PROJECT, OPERATOR)).thenReturn(true);
        assertNull(doorWith(caseLookup(List.of(OWNING_PROJECT))).problem(anchoredRow(), OPERATOR,
                Map.of("caseId", CASE_ID)));
    }

    @Test
    void nonMemberIsRefusedEvenWhenTheFamilyWasVerified() {
        when(roleResolver.isMember(OWNING_PROJECT, OPERATOR)).thenReturn(false);
        String problem = doorWith(caseLookup(List.of(OWNING_PROJECT))).problem(anchoredRow(), OPERATOR,
                Map.of("caseId", CASE_ID));
        assertNotNull(problem);
        assertTrue(problem.contains("越权读取已被拒绝"), problem);
    }

    @Test
    void systemLevelRowIsServedToEveryLoggedOperatorWithoutAMembershipQuestion() {
        assertNull(doorWith(systemLevelLookup()).problem(anchoredRow(), OPERATOR,
                        Map.of("caseId", CASE_ID)),
                "a row no project owns (system bucket) keeps its page semantics: login is enough");
    }

    @Test
    void systemLevelRowStillRefusesWhenNoOperatorIsKnown() {
        assertNotNull(doorWith(systemLevelLookup()).problem(anchoredRow(), null,
                        Map.of("caseId", CASE_ID)),
                "even a global row is only ever served in the name of an identified operator");
    }

    @Test
    void administratorsPassThroughTheSameSharedDecision() {
        when(roleResolver.isMember(OWNING_PROJECT, ADMIN)).thenReturn(false);
        assertNull(doorWith(caseLookup(List.of(OWNING_PROJECT))).problem(anchoredRow(), ADMIN,
                Map.of("caseId", CASE_ID)),
                "the row check must reuse the admin set the scope check already uses");
    }
}
