package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Run family bridge from a governed read row to the project that owns it.
 *
 * <p>Run rows carry neither a project nor a collection column — the only containment edge stored
 * on the row is its request — so the bridge walks run → request → collection → project in one
 * read. A run whose chain is broken (request deleted) cannot be judged and fails closed.
 */
@Component
public class RunRowProjectLookup implements RowProjectLookup {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RunRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "run";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long runId = RowIdValues.longOrNull(rowIdValue);
        if (runId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(
                ApiRowOwnershipReads.owningProjectOfRun(jdbcTemplate, runId));
    }
}
