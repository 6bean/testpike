package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test fixture assembling the real read door around one family's row bridge.
 *
 * <p>Every family's bridge test must prove the same contract against the actual
 * {@link ApiAccessRowOwnership} (not a re-implemented verdict), so the wiring and the shared
 * properties live here once — adding a family never copies the door scaffolding again.
 */
public final class RowOwnershipDoor {

    private RowOwnershipDoor() {
    }

    /** Door whose membership answer is fixed: {@code member} decides every project/operator pair. */
    @SafeVarargs
    @SuppressWarnings("unchecked")
    public static ApiAccessRowOwnership door(long operatorId, boolean member,
                                             RowProjectLookup... lookups) {
        ProjectRoleResolver roleResolver = mock(ProjectRoleResolver.class);
        AdminAlertRecipients admins = mock(AdminAlertRecipients.class);
        when(admins.adminUserIds()).thenReturn(List.of());
        lenient().when(roleResolver.isMember(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.eq(operatorId))).thenReturn(member);
        ObjectProvider<RowProjectLookup> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(lookups));
        return new ApiAccessRowOwnership(roleResolver, admins, provider);
    }

    /**
     * Stub the shared isolation-column read ("SELECT project_id ... WHERE id = :id" shape) that
     * direct-SQL bridges issue through NamedParameterJdbcTemplate.
     */
    @SuppressWarnings("unchecked")
    public static void stubRowRows(NamedParameterJdbcTemplate template, List<Long> rows) {
        when(template.queryForList(anyString(),
                ArgumentMatchers.<Map<String, ?>>any(), eq(Long.class))).thenReturn(rows);
    }

    /**
     * Shared property: each bridge declares exactly one governance entity name, and that name is
     * what routes the door to it — an anchored row resolves through this bridge only when the
     * entity matches.
     */
    public static void assertEntityNameRoutes(RowProjectLookup lookup, String entityName,
                                              ApiAccessGovernanceView anchoredRow,
                                              String idParam, long rowId, long owningProject,
                                              long operatorId) {
        assertEquals(entityName, lookup.entity());
        assertNull(door(operatorId, true, lookup)
                .problem(anchoredRow, operatorId, Map.of(idParam, rowId)),
                "the declared entity name must route the door to this bridge");
    }

    /**
     * Shared property every row bridge shares: a row id matching nothing resolves to nothing
     * and the door refuses the call — one statement of the rule, asserted by each family against
     * its own bridge.
     */
    public static void assertMissingRowIsRefused(RowProjectLookup lookup,
                                                 ApiAccessGovernanceView anchoredRow,
                                                 String idParam, long missingRowId, long operatorId) {
        assertNull(lookup.ownerProjectIds(missingRowId),
                "a row id that matches nothing is not an open door");
        String problem = door(operatorId, true, lookup)
                .problem(anchoredRow, operatorId, Map.of(idParam, missingRowId));
        assertNotNull(problem, "an unresolvable row must be refused by the door");
        assertTrue(problem.contains("未找到对应数据行"), problem);
    }

    /**
     * Inertness property shared by every row bridge: a non-numeric (or absent) address never
     * resolves into an ownership answer.
     */
    public static void assertNonNumericArgumentIsInert(RowProjectLookup lookup, Object... junkValues) {
        for (Object junk : junkValues) {
            assertNull(lookup.ownerProjectIds(junk),
                    "an argument that cannot name a row is not an address");
        }
    }
}
