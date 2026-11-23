package com.sixbean.testpike.core.linkage.infrastructure.dataobject;

import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInfo;
import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInput;

import java.sql.Timestamp;

/**
 * tp_change_linkage row object: the registered definition travels as one
 * {@link ChangeLinkageEdgeInput} (the field list is declared once, consumed by
 * composition) plus the identity/lifecycle columns the database manages.
 * A record rather than a setter bean — columns map once in
 * ChangeLinkageRowMapper. DO never leaves the infrastructure layer; outside
 * the domain it is always {@link #toInfo()}.
 */
public record ChangeLinkageRow(
        Long id,
        ChangeLinkageEdgeInput definition,
        Integer enabled,
        Timestamp createdAt,
        Timestamp updatedAt) {

    /** Convert to the api view consumed by the linkage bus and the edge ledger. */
    public ChangeLinkageEdgeInfo toInfo() {
        return new ChangeLinkageEdgeInfo(id, enabled,
                createdAt == null ? null : createdAt.toLocalDateTime(),
                updatedAt == null ? null : updatedAt.toLocalDateTime(), definition);
    }
}
