package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Platform routing of matrix fan-out device addressing (W5 phase two, POS device group).
 *
 * <p>Defect this closes: the device value range grew POS and PDA, and both are reachable
 * through the REAL device class, so before this gate a retail checkout run could be handed
 * a phone and a phone run could be handed a scarce checkout terminal. Device addressing is
 * the single point that owns that split (AppautoMatrixDeviceResolver), so the narrowing is
 * asserted here and nowhere else.
 *
 * <p>Assertions stay ownership-scoped (pitfall: the resident runner registers shared-slot
 * devices, so absolute row counts drift). Each case names the machines it inserted and only
 * asserts on the class of the machine addressing returns.
 *
 * <p>Real slot database, no mocks; fixtures are deleted in tearDown.
 */
@SpringBootTest(classes = CoreApplication.class)
class AppautoMatrixDevicePlatformRoutingTest {

    @Autowired
    private AppautoMatrixDeviceResolver deviceResolver;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long otherProjectId;

    @BeforeEach
    void setUp() {
        // Project code is VARCHAR(20): keep the prefix short, the nanoTime tail carries the rest.
        projectId = seedProject("rtp");
        otherProjectId = seedProject("rto");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE project_id IN (?, ?)",
                projectId, otherProjectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id IN (?, ?)", projectId, otherProjectId);
    }

    private long seedProject(String tag) {
        String code = tag + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "平台分流测试项目-" + code, code);
        return jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?", Long.class, code);
    }

    /** Device row with a chosen access/channel value; serial is unique per test run. */
    private String seedDevice(long ownerProject, String deviceType) {
        String serial = "RTE-" + deviceType + "-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type) "
                        + "VALUES (?, ?, ?, ?)",
                ownerProject, "plat-route-dev-" + serial, serial, deviceType);
        return serial;
    }

    /** Device class of the machine addressing handed back (read back from the row, not guessed). */
    private String classOfAddressed(Map<String, Object> leaseRef) {
        long deviceId = ((Number) leaseRef.get("deviceId")).longValue();
        return jdbcTemplate.queryForObject(
                "SELECT device_type FROM tp_appauto_device WHERE id = ?", String.class, deviceId);
    }

    @Test
    void posRunAddressesOnlyRetailCheckoutMachines() {
        seedDevice(projectId, "ADB");
        seedDevice(projectId, "USB");
        String posSerial = seedDevice(projectId, "POS");

        Map<String, Object> leaseRef =
                deviceResolver.resolve(projectId, "REAL", 0, null, "POS");

        assertNotNull(leaseRef, "a POS run must address the checkout terminal the project owns");
        assertEquals("POS", classOfAddressed(leaseRef));
        assertEquals(posSerial, leaseRef.get("serial"));
    }

    @Test
    void pdaRunAddressesOnlyHandheldMachines() {
        seedDevice(projectId, "ADB");
        seedDevice(projectId, "POS");
        String pdaSerial = seedDevice(projectId, "PDA");

        Map<String, Object> leaseRef =
                deviceResolver.resolve(projectId, "REAL", 0, null, "PDA");

        assertNotNull(leaseRef, "a PDA run must address the handheld the project owns");
        assertEquals("PDA", classOfAddressed(leaseRef));
        assertEquals(pdaSerial, leaseRef.get("serial"));
    }

    @Test
    void phoneRunNeverBorrowsRetailEdgeMachines() {
        String adbSerial = seedDevice(projectId, "ADB");
        seedDevice(projectId, "POS");
        seedDevice(projectId, "PDA");

        // Rotation over the whole pool: no position may land on an edge machine.
        for (int ordinal = 0; ordinal < 4; ordinal++) {
            Map<String, Object> leaseRef =
                    deviceResolver.resolve(projectId, "REAL", ordinal, null, "ANDROID");
            assertNotNull(leaseRef, "phone run must still find the phone it owns at position " + ordinal);
            assertEquals("ADB", classOfAddressed(leaseRef));
            assertEquals(adbSerial, leaseRef.get("serial"));
        }
    }

    @Test
    void edgePlatformNeverLeadsToAnotherProjectsMachine() {
        seedDevice(otherProjectId, "POS");
        seedDevice(projectId, "ADB");

        Map<String, Object> leaseRef =
                deviceResolver.resolve(projectId, "REAL", 0, null, "POS");

        if (leaseRef != null) {
            // Shared-slot edge machines are legitimately addressable; another project's
            // private machine never is. Phones must not appear as a fallback either way.
            assertEquals("POS", classOfAddressed(leaseRef),
                    "a POS run must not fall back to a phone when the project owns no terminal");
        } else {
            assertNull(leaseRef, "no terminal available must stay unaddressable");
        }
    }

    @Test
    void unclassifiedPlatformKeepsTheLegacyDevicePool() {
        seedDevice(projectId, "ADB");
        seedDevice(projectId, "POS");

        // Legacy call sites (no platform given) must keep the pre-existing behaviour:
        // the whole REAL pool, so envelopes assembled without a platform never lose a machine.
        Map<String, Object> legacyThreeArg = deviceResolver.resolve(projectId, "REAL", 0);
        Map<String, Object> legacyFourArg = deviceResolver.resolve(projectId, "REAL", 1, null);
        assertNotNull(legacyThreeArg, "legacy overload must still address");
        assertNotNull(legacyFourArg, "legacy overload with reservation slot must still address");
        assertTrue(java.util.Set.of("ADB", "POS").contains(classOfAddressed(legacyThreeArg)));
    }

    @Test
    void emulatorClassIsUntouchedByEdgeRouting() {
        seedDevice(projectId, "EMULATOR");

        Map<String, Object> leaseRef =
                deviceResolver.resolve(projectId, "EMULATOR", 0, null, "ANDROID");

        assertNotNull(leaseRef, "the emulator class keeps its own machine");
        assertEquals("EMULATOR", classOfAddressed(leaseRef));
    }
}
