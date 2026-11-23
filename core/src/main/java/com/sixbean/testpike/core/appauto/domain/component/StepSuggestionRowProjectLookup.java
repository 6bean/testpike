package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Step-suggestion bridge from a governed read row to the project that owns it.
 *
 * <p>Healing suggestions are produced for one case inside one project and carry the owning project
 * as their isolation column (the project id is looked up authoritatively from the source task, so
 * forged anchors never enter the table). A suggestion row is anchor signatures and candidate
 * locators — board material visible to the project's members. An unresolvable row, or a row whose
 * project column is empty, fails closed.
 */
@Component
public class StepSuggestionRowProjectLookup implements RowProjectLookup {

    private static final String TABLE = "tp_appauto_step_suggestion";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StepSuggestionRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "stepSuggestion";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long suggestionId = RowIdValues.longOrNull(rowIdValue);
        if (suggestionId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(RowOwnershipReads.owningProjectOfIsolationColumnRow(
                jdbcTemplate, TABLE, suggestionId));
    }
}
