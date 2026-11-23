package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Engine-profile bridge from a governed read row to the project that owns it.
 *
 * <p>The profile table carries the owning project as its isolation column and the payload is
 * machine-level facts (SDK paths, devtools settings), never a credential — so the only question the
 * door needs is which project owns the row. Profiles always belong to a project: an unresolvable
 * row, or a row whose project column is empty, fails closed.
 */
@Component
public class EnvProfileRowProjectLookup implements RowProjectLookup {

    private static final String TABLE = "tp_appauto_env_profile";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public EnvProfileRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "envProfile";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long profileId = RowIdValues.longOrNull(rowIdValue);
        if (profileId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(RowOwnershipReads.owningProjectOfIsolationColumnRow(
                jdbcTemplate, TABLE, profileId));
    }
}
