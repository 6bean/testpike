package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.cluster.api.dto.ClusterCommandView;

import java.util.List;

/**
 * Read port for the cluster command ledger (admin side of the fleet command surface).
 * Mirrors core GET /internal/cluster/commands; filtering and windowing stay in core.
 */
public interface CoreClusterCommandClient {

    /**
     * Command list projection.
     *
     * @param targetExecutorId optional executor filter; null spans the whole fleet
     *     (the predicate itself is core's single source, never re-implemented here).
     * @return command rows as core gave them.
     */
    List<ClusterCommandView> list(Long targetExecutorId);
}
