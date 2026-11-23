package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * App-package bridge from a governed read row to the project that owns it.
 *
 * <p>The app table carries the owning project as its isolation column, so the bridge reads exactly
 * that column — no detail assembly, no second ownership rule. Uploaded packages always belong to a
 * project: an unresolvable row, or a row whose project column is empty, fails closed.
 */
@Component
public class AppRowProjectLookup implements RowProjectLookup {

    private static final String TABLE = "tp_appauto_app";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AppRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "app";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long appId = RowIdValues.longOrNull(rowIdValue);
        if (appId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(RowOwnershipReads.owningProjectOfIsolationColumnRow(
                jdbcTemplate, TABLE, appId));
    }
}
