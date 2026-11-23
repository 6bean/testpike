package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Takeover-request bridge from a governed read row to the project that owns it.
 *
 * <p>A takeover request names the device it takes over, the project that asked and the people on
 * both sides; it carries the owning project as its isolation column and exposes no session token, so
 * the owning project is the whole verdict. An unresolvable row, or a row whose project column is
 * empty, fails closed. The queued input of a takeover is a separate governed family and is not
 * answered here.
 */
@Component
public class TakeoverRowProjectLookup implements RowProjectLookup {

    private static final String TABLE = "tp_appauto_takeover_request";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TakeoverRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "takeover";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long takeoverId = RowIdValues.longOrNull(rowIdValue);
        if (takeoverId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(RowOwnershipReads.owningProjectOfIsolationColumnRow(
                jdbcTemplate, TABLE, takeoverId));
    }
}
