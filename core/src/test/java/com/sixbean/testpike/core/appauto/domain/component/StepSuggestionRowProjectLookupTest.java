package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipDoor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Step-suggestion bridge on the read door: the governance row says "stepSuggestion"/"id" and the
 * owning project is read straight from the step-suggestion table's isolation column. Properties
 * held here: the entity name routes, numeric and string id forms resolve alike, a missing row fails
 * closed, an empty project column fails closed, and the door refuses a non-member.
 */
class StepSuggestionRowProjectLookupTest {

    private static final long ROW_ID = 461L;
    private static final long OWNING_PROJECT = 7L;
    private static final long OPERATOR = 42L;

    private NamedParameterJdbcTemplate jdbcTemplate;
    private StepSuggestionRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        lookup = new StepSuggestionRowProjectLookup(jdbcTemplate);
    }

    @Test
    void entityNameRoutesTheDoorToThisBridge() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "stepSuggestion", anchoredRow(),
                "id", ROW_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void existingSuggestionResolvesToItsOwningProject() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds(ROW_ID));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds("462"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void missingSuggestionResolvesToNothing() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of());
        RowOwnershipDoor.assertMissingRowIsRefused(lookup, anchoredRow(), "id", 999L, OPERATOR);
    }

    @Test
    void emptyProjectColumnFailsClosed() {
        // Arrays.asList tolerates the null element List.of rejects — the anomaly shape under test
        RowOwnershipDoor.stubRowRows(jdbcTemplate, java.util.Arrays.asList((Long) null));
        assertEquals(null, lookup.ownerProjectIds(ROW_ID),
                "suggestions always hang under a project; an empty column is not an open door");
    }

    @Test
    void nonNumericArgumentIsInert() {
        RowOwnershipDoor.assertNonNumericArgumentIsInert(lookup, "abc", null);
    }

    @Test
    void doorRefusesANonMemberThroughTheRealBridge() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        String problem = RowOwnershipDoor.door(OPERATOR, false, lookup)
                .problem(anchoredRow(), OPERATOR, Map.of("id", ROW_ID));
        assertNotNull(problem);
        assertTrue(problem.contains("越权读取已被拒绝"), problem);
    }

    private ApiAccessGovernanceView anchoredRow() {
        return new ApiAccessGovernanceView("GET /internal/appauto/step-suggestions/{id}", true, true,
                null, null, "stepSuggestion", "id");
    }
}
