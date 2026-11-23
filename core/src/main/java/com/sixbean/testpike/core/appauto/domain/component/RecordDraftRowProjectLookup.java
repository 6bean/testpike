package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Record-draft bridge from a governed read row to the project that owns it.
 *
 * <p>Drafts are recorded against one case inside one project and carry the owning project as their
 * isolation column, so the bridge reads exactly that column; the recorded step array is board
 * material, not a secret, so no extra tier is invented. Only an unresolvable row, or a row whose
 * project column is empty, fails closed. The draft list endpoint has no row anchor and keeps the
 * family-level verdict.
 */
@Component
public class RecordDraftRowProjectLookup implements RowProjectLookup {

    private static final String TABLE = "tp_appauto_record_draft";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RecordDraftRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "recordDraft";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long draftId = RowIdValues.longOrNull(rowIdValue);
        if (draftId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(RowOwnershipReads.owningProjectOfIsolationColumnRow(
                jdbcTemplate, TABLE, draftId));
    }
}
