package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Collection family bridge from a governed read row to the project that owns it.
 *
 * <p>The collection table carries the owning project as its isolation column, so the bridge reads
 * exactly that column — no detail assembly, no second ownership rule. Collections always hang
 * under a project: an unresolvable row fails closed.
 */
@Component
public class CollectionRowProjectLookup implements RowProjectLookup {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public CollectionRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "collection";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long collectionId = RowIdValues.longOrNull(rowIdValue);
        if (collectionId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(ApiRowOwnershipReads.owningProjectOfIsolationColumnRow(
                jdbcTemplate, "tp_apitest_collection", collectionId));
    }
}
