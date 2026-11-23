package com.sixbean.testpike.core.asset.domain.component;

/**
 * Shared read SQL for the tp_asset version chain (V358). Both the asset rows projection
 * and the reference-pinned projection need "which row is the newest version of its
 * (project, kind, name) chain"; this class is the single source of that head derivation
 * so a second copy can never drift from the first (R40).
 */
public final class AssetChainQuery {

    /** Newest version per chain key, joined as derived table alias {@code h}. */
    public static final String CHAIN_HEAD_AGGREGATE = """
            SELECT project_id, kind, name, MAX(asset_version) AS max_version
            FROM tp_asset
            GROUP BY project_id, kind, name
            """;

    /**
 * Chain-level reference pin counter: one row per (project, kind, name) chain
 * holding how many pins attach to ANY version row of that chain. A grouped
 * aggregate joined into the list projection, so every discoverable row gets its
 * counter inside the single SELECT — resolving it per row would reintroduce the
 * N+1 the list endpoint forbids by contract .
 */
    public static final String CHAIN_REF_COUNT_AGGREGATE = """
            SELECT ar.project_id, ar.kind, ar.name, COUNT(*) AS ref_count
            FROM tp_asset_reference r
            JOIN tp_asset ar ON ar.id = r.asset_row_id
            GROUP BY ar.project_id, ar.kind, ar.name
            """;

    /**
     * Read projection over tp_asset: every version row joined with its owner project
     * name, a derived head flag (row is the newest version of its chain) and the
     * chain-level reference counter (0 for unpinned chains, never null).
     */
    public static final String SELECT_WITH_HEAD = """
            SELECT a.id, a.project_id, p.name AS owner_project_name, a.kind, a.name,
                   a.asset_version, a.prev_asset_id, a.status, a.payload_json, a.updated_at,
                   a.asset_version = COALESCE(h.max_version, a.asset_version) AS is_head,
                   COALESCE(rc.ref_count, 0) AS ref_count
            FROM tp_asset a
            JOIN tp_project p ON p.id = a.project_id
            LEFT JOIN (%s) h
              ON h.project_id = a.project_id AND h.kind = a.kind AND h.name = a.name
            LEFT JOIN (%s) rc
              ON rc.project_id = a.project_id AND rc.kind = a.kind AND rc.name = a.name
            """.formatted(CHAIN_HEAD_AGGREGATE, CHAIN_REF_COUNT_AGGREGATE);

    /**
     * Every host pin attached to any version row of one (project, kind, name) chain,
     * with the consumer project name resolved in the same SQL. Single source for both
     * the change-notice fan-out and the fail-closed withdrawal check — a second copy
     * of this join could drift and silently miss a consumer (R40).
     */
    public static final String SELECT_CONSUMER_HOSTS_OF_CHAIN = """
            SELECT r.referrer_project_id, rp.name AS referrer_project_name,
                   r.referrer_kind, r.referrer_id
            FROM tp_asset_reference r
            JOIN tp_asset a ON a.id = r.asset_row_id
            JOIN tp_project rp ON rp.id = r.referrer_project_id
            WHERE a.project_id = :chainProjectId AND a.kind = :chainKind AND a.name = :chainName
            ORDER BY r.referrer_project_id, r.referrer_kind, r.referrer_id
            """;

    /**
 * Version timeline of one (project, kind, name) chain: every version row with
 * the pin count attached to exactly that row, newest first. The counter comes
 * from a LEFT JOIN + GROUP BY inside this single statement — resolving pins per
 * version row would reintroduce the N+1 the panel contract forbids .
 * The head flag reuses the chain head derivation via the same aggregate alias.
 */
    public static final String SELECT_VERSION_TIMELINE_OF_CHAIN = """
            SELECT a.id, a.asset_version, a.status, a.updated_at,
                   a.asset_version = COALESCE(h.max_version, a.asset_version) AS is_head,
                   COUNT(r.id) AS pinned_count
            FROM tp_asset a
            LEFT JOIN tp_asset_reference r ON r.asset_row_id = a.id
            LEFT JOIN (%s) h
              ON h.project_id = a.project_id AND h.kind = a.kind AND h.name = a.name
            WHERE a.project_id = :chainProjectId AND a.kind = :chainKind AND a.name = :chainName
            GROUP BY a.id, a.asset_version, a.status, a.updated_at, h.max_version
            ORDER BY a.asset_version DESC
            """.formatted(CHAIN_HEAD_AGGREGATE);

    private AssetChainQuery() {
        // SQL constant holder, not instantiable.
    }
}
