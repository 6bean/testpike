package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Device bridge from a governed read row to the projects that govern it.
 *
 * <p>Two governed entries route here: the device detail and the device lease-run ledger
 * ({@code GET /internal/appauto/devices/{id}/lease-runs}), whose path id is the device — the run
 * rows deliberately do not grow a second bridge, they are answered through the device they hang
 * under. Device rows come in two tiers exactly as the device wall renders them: a project-owned
 * device is gated by that project's membership, while a shared-pool device (no owning project, the
 * shape the wall lists under {@code project_id IS NULL}) is readable by every logged-in operator.
 * Only an unresolvable device fails closed.
 */
@Component
public class DeviceRowProjectLookup implements RowProjectLookup {

    private static final String TABLE = "tp_appauto_device";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DeviceRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "device";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long deviceId = RowIdValues.longOrNull(rowIdValue);
        if (deviceId == null) {
            return null;
        }
        return RowOwnershipReads.tiersOfNullableIsolationColumnRow(jdbcTemplate, TABLE, deviceId);
    }
}
