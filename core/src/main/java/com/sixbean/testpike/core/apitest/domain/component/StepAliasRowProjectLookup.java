package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Step-alias family bridge from a governed read row to the project that owns it.
 *
 * <p>Alias rows carry the owning project as their isolation column. An alias is pure addressing
 * data with no secrets, but it is still project data — which step a project has chosen to name
 * belongs to that project — so the bridge gates it exactly like every other row in the family.
 */
@Component
public class StepAliasRowProjectLookup implements RowProjectLookup {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StepAliasRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "stepAlias";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long aliasId = RowIdValues.longOrNull(rowIdValue);
        if (aliasId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(ApiRowOwnershipReads.owningProjectOfIsolationColumnRow(
                jdbcTemplate, "tp_step_alias", aliasId));
    }
}
