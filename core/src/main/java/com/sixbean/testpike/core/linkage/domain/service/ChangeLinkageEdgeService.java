package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInfo;
import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInput;

import java.util.List;

/**
 * Change-linkage edge registry (table {@code tp_change_linkage}).
 * <p>An upstream save may invalidate downstream objects; which ones is decided
 * by registered edge rows, never by scattered hooks. The registry therefore
 * refuses rows the bus could not act on (a non-exempt edge without a detector)
 * and rows a reviewer could not audit (an exemption without a written reason).
 * <p>Re-registering an identical edge is a no-op: replayed registrations must
 * not move {@code updated_at} or rebuild the row.
 */
public interface ChangeLinkageEdgeService {

    /**
     * Register (insert or update) one edge by its stable key.
     *
     * @param input edge definition; validated against the closed vocabularies
     * @return the stored edge view
     */
    ChangeLinkageEdgeInfo register(ChangeLinkageEdgeInput input);

    /**
     * Active, dispatchable edges for one upstream object type
     * (enabled and non-exempt), the lookup the bus runs on every save announcement.
     *
     * @param upstreamObjectType bus token, e.g. {@code case}
     */
    List<ChangeLinkageEdgeInfo> findActiveEdges(String upstreamObjectType);

    /**
     * One edge by key regardless of state (ledger display, audit).
     *
     * @param edgeKey stable edge identity
     */
    ChangeLinkageEdgeInfo findByEdgeKey(String edgeKey);

    /**
     * Stop dispatching an edge while keeping the row for audit (enabled=0).
     *
     * @param edgeKey stable edge identity
     * @return the disabled edge view
     */
    ChangeLinkageEdgeInfo disable(String edgeKey);
}
