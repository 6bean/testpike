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
 * Reservation bridge on the read door: the governance row says "reservation"/"id" and the owning
 * project is read straight from the reservation table's isolation column. Properties held here: the
 * entity name routes, numeric and string id forms resolve alike, a missing row fails closed, an
 * empty project column fails closed, and the door refuses a non-member.
 */
class ReservationRowProjectLookupTest {

    private static final long ROW_ID = 451L;
    private static final long OWNING_PROJECT = 7L;
    private static final long OPERATOR = 42L;

    private NamedParameterJdbcTemplate jdbcTemplate;
    private ReservationRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        lookup = new ReservationRowProjectLookup(jdbcTemplate);
    }

    @Test
    void entityNameRoutesTheDoorToThisBridge() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "reservation", anchoredRow(),
                "id", ROW_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void existingReservationResolvesToItsOwningProject() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds(ROW_ID));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds("452"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void missingReservationResolvesToNothing() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of());
        RowOwnershipDoor.assertMissingRowIsRefused(lookup, anchoredRow(), "id", 999L, OPERATOR);
    }

    @Test
    void sharedSlotReservationResolvesToTheSystemLevelTier() {
        // Arrays.asList tolerates the null element List.of rejects — the shared-slot shape under test
        RowOwnershipDoor.stubRowRows(jdbcTemplate, java.util.Arrays.asList((Long) null));
        assertEquals(List.of(), lookup.ownerProjectIds(ROW_ID),
                "a shared-slot booking exists but no project owns it — empty set, not a miss");
    }

    @Test
    void doorServesASharedSlotReservationWithoutAskingMembership() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, java.util.Arrays.asList((Long) null));
        org.junit.jupiter.api.Assertions.assertNull(RowOwnershipDoor.door(OPERATOR, false, lookup)
                        .problem(anchoredRow(), OPERATOR, Map.of("id", ROW_ID)),
                "the booking board shows shared-slot bookings to everyone; the door must match");
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
        return new ApiAccessGovernanceView("GET /internal/appauto/reservation/{id}", true, true,
                null, null, "reservation", "id");
    }
}
