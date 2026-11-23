package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Request family bridge from a governed read row to the project that owns it.
 *
 * <p>Request rows carry no project column — their containment edge is the collection — so the
 * bridge walks request → collection → project in one read (the same edge the request pages list
 * through) instead of inventing a second ownership rule. A request whose collection row is gone
 * cannot be judged and fails closed.
 */
@Component
public class RequestRowProjectLookup implements RowProjectLookup {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RequestRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "request";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long requestId = RowIdValues.longOrNull(rowIdValue);
        if (requestId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(
                ApiRowOwnershipReads.owningProjectOfRequest(jdbcTemplate, requestId));
    }
}
