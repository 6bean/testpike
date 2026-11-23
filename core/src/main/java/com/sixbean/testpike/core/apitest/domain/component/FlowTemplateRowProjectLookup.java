package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Flow-template family bridge from a governed read row to the project that owns it.
 *
 * <p>Template rows carry the owning project as their isolation column; the source-collection
 * column is provenance only and may dangle after its collection is deleted, so the bridge reads
 * the project column directly rather than walking provenance edges. An unresolvable row fails
 * closed.
 */
@Component
public class FlowTemplateRowProjectLookup implements RowProjectLookup {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public FlowTemplateRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "flowTemplate";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long templateId = RowIdValues.longOrNull(rowIdValue);
        if (templateId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(ApiRowOwnershipReads.owningProjectOfIsolationColumnRow(
                jdbcTemplate, "tp_apitest_flow_template", templateId));
    }
}
