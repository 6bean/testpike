package com.sixbean.testpike.core.project.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipDoor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Project family bridge on the read door: the governance row says "project"/"id" and this bridge
 * judges ownership straight from the project row itself — a project owns its own data, so the
 * answer is the row id when the row exists and nothing when it does not. No detail assembly runs
 * per governed read. Properties held here: the entity name routes, an existing project resolves to
 * itself, a missing project resolves to nothing (fail closed), a non-numeric argument never reaches
 * the database, and the real door opens for a member and refuses an outsider through this bridge.
 */
class ProjectRowProjectLookupTest {

    private static final long PROJECT_ID = 7L;
    private static final long OPERATOR = 42L;

    private NamedParameterJdbcTemplate jdbcTemplate;
    private ProjectRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        lookup = new ProjectRowProjectLookup(jdbcTemplate);
    }

    @Test
    void projectEntityNameRoutesTheDoor() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(PROJECT_ID));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "project", anchoredProjectRow(),
                "id", PROJECT_ID, PROJECT_ID, OPERATOR);
    }

    @Test
    void existingProjectResolvesToItself() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(PROJECT_ID));
        assertEquals(List.of(PROJECT_ID), lookup.ownerProjectIds(PROJECT_ID));
        assertEquals(List.of(PROJECT_ID), lookup.ownerProjectIds("7"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void missingProjectResolvesToNothing() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of());
        RowOwnershipDoor.assertMissingRowIsRefused(lookup, anchoredProjectRow(), "id", 999L, OPERATOR);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonNumericArgumentNeverReachesTheDatabase() {
        RowOwnershipDoor.assertNonNumericArgumentIsInert(lookup, "abc", null);
        verify(jdbcTemplate, never()).queryForList(anyString(),
                ArgumentMatchers.<Map<String, ?>>any(), eq(Long.class));
        assertNull(lookup.ownerProjectIds("7.5"),
                "a fractional address names no row and still must not reach the database");
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void lookupAsksOnlyForRowExistence() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(PROJECT_ID));
        lookup.ownerProjectIds(PROJECT_ID);
        verify(jdbcTemplate).queryForList(
                eq("SELECT id FROM tp_project WHERE id = :id"),
                ArgumentMatchers.<Map<String, ?>>any(), eq(Long.class));
    }

    private ApiAccessGovernanceView anchoredProjectRow() {
        return new ApiAccessGovernanceView("GET /internal/projects/{id}", true, true,
                null, null, "project", "id");
    }

    @Test
    void doorRefusesAnOutsiderThroughTheRealBridge() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(PROJECT_ID));
        String problem = RowOwnershipDoor.door(OPERATOR, false, lookup)
                .problem(anchoredProjectRow(), OPERATOR, Map.of("id", PROJECT_ID));
        assertNotNull(problem);
        assertTrue(problem.contains("越权读取已被拒绝"), problem);
    }
}
