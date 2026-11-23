package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Batch-template family bridge from a governed read row to the project that owns it.
 *
 * <p>The template table carries the owning project as its isolation column (V24), so the bridge
 * names only its table to the shared isolation-column read — the statement itself lives once in
 * {@link RowOwnershipReads} and can never drift from the sibling bridges. A missing row is not an
 * ownership answer: the door fails closed.
 */
@Component
public class BatchTemplateRowProjectLookup implements RowProjectLookup {

    private static final String TEMPLATE_TABLE = "tp_batch_template";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public BatchTemplateRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "batchTemplate";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long templateId = RowIdValues.longOrNull(rowIdValue);
        if (templateId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(RowOwnershipReads.owningProjectOfIsolationColumnRow(
                jdbcTemplate, TEMPLATE_TABLE, templateId));
    }
}
