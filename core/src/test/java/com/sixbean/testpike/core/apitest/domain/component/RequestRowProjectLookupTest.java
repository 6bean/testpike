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
 * Request family bridge on the read door: the governance row says "request"/"id". Request rows
 * carry no project column, so ownership travels the one existing containment edge
 * request → collection → project in a single read. Properties held here: the entity name routes,
 * numeric and string id forms resolve alike, a missing request (or a request whose collection row
 * is gone) fails closed, non-numeric arguments stay inert, and the door refuses a non-member.
 */
class RequestRowProjectLookupTest {

    private static final long ROW_ID = 411L;
    private static final long OWNING_PROJECT = 7L;
    private static final long OPERATOR = 42L;

    private NamedParameterJdbcTemplate jdbcTemplate;
    private RequestRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        lookup = new RequestRowProjectLookup(jdbcTemplate);
    }

    @Test
    void entityNameRoutesTheDoorToThisBridge() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "request", anchoredRow(),
                "id", ROW_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void existingRequestResolvesThroughItsCollection() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds(ROW_ID));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds("412"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void missingRequestResolvesToNothing() {
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
        return new ApiAccessGovernanceView("GET /internal/apitest/requests/{id}", true, true,
                null, null, "request", "id");
    }
}
