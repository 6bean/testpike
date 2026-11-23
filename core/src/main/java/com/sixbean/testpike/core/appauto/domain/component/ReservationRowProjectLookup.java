package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reservation bridge from a governed read row to the projects that govern it.
 *
 * <p>A reservation books a device slot and carries the booking project in its isolation column;
 * the column is nullable on purpose (a shared-slot booking, the same semantics the device pool
 * uses), and a shared booking only exposes a time window and the reserver's name — visible on the
 * booking board to everyone. So the bridge answers the two tiers the page renders: the owning
 * project gates its booking, a booking with no owning project is readable by every logged-in
 * operator. Only an unresolvable reservation fails closed.
 */
@Component
public class ReservationRowProjectLookup implements RowProjectLookup {

    private static final String TABLE = "tp_appauto_reservation";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReservationRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "reservation";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long reservationId = RowIdValues.longOrNull(rowIdValue);
        if (reservationId == null) {
            return null;
        }
        return RowOwnershipReads.tiersOfNullableIsolationColumnRow(jdbcTemplate, TABLE, reservationId);
    }
}
