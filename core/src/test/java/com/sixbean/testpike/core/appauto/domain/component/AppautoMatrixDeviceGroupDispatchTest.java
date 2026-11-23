package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixFanoutInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Label-group addressing for matrix fan-out device selection (W5 phase two, P5c).
 *
 * <p>Capability under test: a plan or a fan-out run binds a device <em>group</em> (one label
 * carried in tp_appauto_device.labels JSONB) instead of naming single machines, so a fleet can
 * grow or shrink by labelling devices and the run definition never changes. The candidate
 * narrowing reuses the same JSONB containment judgement the device list endpoint uses — one
 * predicate source for both surfaces.
 *
 * <p>Assertions stay ownership-scoped (the resident runner registers shared-slot devices, so
 * absolute row counts drift): each case only looks at the serials of the machines it inserted.
 *
 * <p>Real slot database, no mocks; fixtures are deleted in tearDown.
 */
@SpringBootTest(classes = CoreApplication.class)
class AppautoMatrixDeviceGroupDispatchTest {

    @Autowired
    private AppautoMatrixDeviceResolver deviceResolver;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        // Project code is VARCHAR(20): keep the prefix short, the nanoTime tail carries the rest.
        String code = "grp" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "圈组派发测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, code);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    /** ADB machine carrying the given labels (JSONB array), unique serial per run. */
    private String seedDevice(String labelJson, String... extraLabels) {
        String serial = "GRD-" + System.nanoTime();
        StringBuilder labels = new StringBuilder(labelJson);
        for (String extra : extraLabels) {
            labels.insert(labels.length() - 1, "," + extra);
        }
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, device_type, labels) "
                        + "VALUES (?, ?, ?, 'ADB', CAST(? AS jsonb))",
                projectId, "group-dev-" + serial, serial, labels.toString());
        return serial;
    }

    private String serialOf(Map<String, Object> leaseRef) {
        return (String) leaseRef.get("serial");
    }

    @Test
    void groupLabelRestrictsPoolToLabelledMachines() {
        String inGroup = seedDevice("[\"floor-3\"]");
        seedDevice("[\"floor-9\"]");
        seedDevice("[]");

        // Rotation over the group: every position must land on the labelled machine only.
        for (int ordinal = 0; ordinal < 3; ordinal++) {
            Map<String, Object> leaseRef =
                    deviceResolver.resolve(projectId, "REAL", ordinal, null, null, "floor-3");
            assertNotNull(leaseRef, "position " + ordinal + " must still address inside the group");
            assertEquals(inGroup, serialOf(leaseRef),
                    "position " + ordinal + " left the labelled group");
        }
    }

    @Test
    void noGroupLabelKeepsTheWholePool() {
        String first = seedDevice("[\"floor-3\"]");
        String second = seedDevice("[\"floor-9\"]");

        // A blank label is the legacy addressing surface: rotation may visit both machines.
        Set<String> visited = new HashSet<>();
        for (int ordinal = 0; ordinal < 2; ordinal++) {
            Map<String, Object> leaseRef =
                    deviceResolver.resolve(projectId, "REAL", ordinal, null, null, "  ");
            assertNotNull(leaseRef, "unbounded pool must address");
            visited.add(serialOf(leaseRef));
        }
        assertTrue(visited.contains(first) && visited.contains(second),
                "blank group must not narrow the pool, visited=" + visited);
    }

    @Test
    void emptyGroupStaysUnaddressable() {
        seedDevice("[\"floor-3\"]");

        // Devices outside the group are never borrowed: the envelope keeps the
        // no-lease semantics (no leaseRef key) instead of falling back to another fleet.
        assertNull(deviceResolver.resolve(projectId, "REAL", 0, null, null, "no-such-group"),
                "an empty group must not address machines that do not carry the label");
    }

    @Test
    void groupLabelWithQuotesIsEscapedThroughTheSharedSerializer() {
        String serial = seedDevice("[\"floor\\\"3\"]");

        // A raw-concatenated predicate would break on the quote; the shared JSONB
        // containment source serialises it and the machine is still found.
        Map<String, Object> leaseRef =
                deviceResolver.resolve(projectId, "REAL", 0, null, null, "floor\"3");
        assertNotNull(leaseRef, "label with an embedded quote must match through the serializer");
        assertEquals(serial, serialOf(leaseRef));
    }

    @Test
    void legacyFanoutInputCarriesNoGroupLabel() {
        // The transport record grew one field; every existing call site built through the
        // compatibility constructors must keep addressing the whole pool (null = unbound).
        AppautoMatrixFanoutInput legacyFive = new AppautoMatrixFanoutInput(
                projectId, 1L, "batch", null, java.util.List.of());
        assertNull(legacyFive.deviceGroupLabel());
    }
}
