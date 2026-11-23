package com.sixbean.testpike.core.apitest.domain.component;

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
 * Step-alias family bridge on the read door: the governance row says "stepAlias"/"id". Alias rows
 * carry the owning project as their isolation column, so the bridge reads exactly that column —
 * an alias is pure addressing data, but it is still project data and must not leak across
 * projects. Properties held here: the entity name routes, numeric and string id forms resolve
 * alike, a missing row fails closed, non-numeric arguments stay inert, and the door refuses a
 * non-member.
 */
class StepAliasRowProjectLookupTest {

    private static final long ROW_ID = 801L;
    private static final long OWNING_PROJECT = 7L;
    private static final long OPERATOR = 42L;

    private NamedParameterJdbcTemplate jdbcTemplate;
    private StepAliasRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        lookup = new StepAliasRowProjectLookup(jdbcTemplate);
    }

    @Test
    void entityNameRoutesTheDoorToThisBridge() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "stepAlias", anchoredRow(),
                "id", ROW_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void existingAliasResolvesToItsOwningProject() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds(ROW_ID));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds("802"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void missingAliasResolvesToNothing() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of());
        RowOwnershipDoor.assertMissingRowIsRefused(lookup, anchoredRow(), "id", 999L, OPERATOR);
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
        return new ApiAccessGovernanceView("GET /internal/apitest/step-aliases/{id}", true, true,
                null, null, "stepAlias", "id");
    }
}
