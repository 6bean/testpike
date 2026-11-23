package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Run-set family bridge from a governed read row to the project that owns it.
 *
 * <p>Run-set rows stamp the owning project as their isolation column at creation, so the bridge
 * reads exactly that column. The governed reads in this family address the run set by its own
 * path parameter, which the governance row records as the id parameter; the bridge itself stays
 * parameter-agnostic and only answers for the entity "runSet".
 */
@Component
public class RunSetRowProjectLookup implements RowProjectLookup {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RunSetRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "runSet";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long runSetId = RowIdValues.longOrNull(rowIdValue);
        if (runSetId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(ApiRowOwnershipReads.owningProjectOfIsolationColumnRow(
                jdbcTemplate, "tp_apitest_run_set", runSetId));
    }
}
