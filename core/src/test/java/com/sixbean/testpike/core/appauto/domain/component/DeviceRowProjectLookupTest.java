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
 * Device bridge on the read door: the governance rows "device"/"id" for the device detail and
 * "device"/"id" for its lease-run ledger both route here, and the owning project is read straight
 * from the device table's isolation column — the lease-run sub-read never grows a second ownership
 * rule. Properties held here: the entity name routes, numeric and string id forms resolve alike, a
 * missing row fails closed, an empty project column fails closed, and the door refuses a non-member.
 */
class DeviceRowProjectLookupTest {

    private static final long ROW_ID = 411L;
    private static final long OWNING_PROJECT = 7L;
    private static final long OPERATOR = 42L;

    private NamedParameterJdbcTemplate jdbcTemplate;
    private DeviceRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        lookup = new DeviceRowProjectLookup(jdbcTemplate);
    }

    @Test
    void entityNameRoutesTheDoorToThisBridge() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "device", anchoredRow("device"),
                "id", ROW_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void leaseRunSubReadRoutesThroughTheDeviceBridge() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "device",
                anchoredRow("devices/{id}/lease-runs"), "id", ROW_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void existingDeviceResolvesToItsOwningProject() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds(ROW_ID));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds("412"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void missingDeviceResolvesToNothing() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of());
        RowOwnershipDoor.assertMissingRowIsRefused(lookup, anchoredRow("device"), "id", 999L, OPERATOR);
    }

    @Test
    void sharedDeviceResolvesToTheSystemLevelTier() {
        // Arrays.asList tolerates the null element List.of rejects — the shared-pool shape under test
        RowOwnershipDoor.stubRowRows(jdbcTemplate, java.util.Arrays.asList((Long) null));
        assertEquals(List.of(), lookup.ownerProjectIds(ROW_ID),
                "a shared-pool device exists but no project owns it — empty set, not a miss");
    }

    @Test
    void doorServesASharedDeviceWithoutAskingMembership() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, java.util.Arrays.asList((Long) null));
        org.junit.jupiter.api.Assertions.assertNull(RowOwnershipDoor.door(OPERATOR, false, lookup)
                        .problem(anchoredRow("device"), OPERATOR, Map.of("id", ROW_ID)),
                "the device wall lists shared devices to every logged-in user; the door must match");
    }

    @Test
    void nonNumericArgumentIsInert() {
        RowOwnershipDoor.assertNonNumericArgumentIsInert(lookup, "abc", null);
    }

    @Test
    void doorRefusesANonMemberThroughTheRealBridge() {
        RowOwnershipDoor.stubRowRows(jdbcTemplate, List.of(OWNING_PROJECT));
        String problem = RowOwnershipDoor.door(OPERATOR, false, lookup)
                .problem(anchoredRow("device"), OPERATOR, Map.of("id", ROW_ID));
        assertNotNull(problem);
        assertTrue(problem.contains("越权读取已被拒绝"), problem);
    }

    private ApiAccessGovernanceView anchoredRow(String shape) {
        String key = "devices/{id}/lease-runs".equals(shape)
                ? "GET /internal/appauto/devices/{id}/lease-runs"
                : "GET /internal/appauto/devices/{id}";
        return new ApiAccessGovernanceView(key, true, true, null, null, "device", "id");
    }
}
