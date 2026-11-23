package com.sixbean.testpike.core.asset.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.asset.api.dto.AssetReferenceInfo;
import com.sixbean.testpike.core.asset.api.dto.AssetVersionTimelineInfo;
import com.sixbean.testpike.core.asset.domain.component.AssetChainQuery;
import com.sixbean.testpike.core.asset.domain.service.SharedAssetReferenceService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reference pins of the shared asset pool over tp_asset_reference (V359).
 * <p>One host entity (referrer_kind + referrer_id) keeps exactly one pin row: a re-pin
 * upserts the same row through the unique-constraint conflict target instead of
 * duplicating it, and an upstream re-publish never moves the pin — the chain head is
 * derived from the single-sourced {@link AssetChainQuery#CHAIN_HEAD_AGGREGATE} in the
 * same SQL, so both query directions read staleness without any per-row follow-up query
 * (zero N+1). All argument and existence validation runs before the first SQL write so
 * an FK/CHECK violation can never poison the surrounding transaction (aborted-state
 * pitfall). Withdrawn chains stay visible here — this surface is also the fail-closed
 * source the withdrawal flow uses to name every active consumer .
 */
@Service
public class SharedAssetReferenceServiceImpl implements SharedAssetReferenceService {

    /** Join skeleton shared by the row page query and the count query (single source). */
    private static final String FROM_JOINS = """
            FROM tp_asset_reference r
            JOIN tp_asset a ON a.id = r.asset_row_id
            JOIN tp_project p ON p.id = a.project_id
            JOIN tp_project rp ON rp.id = r.referrer_project_id
            LEFT JOIN (%s) h
              ON h.project_id = a.project_id AND h.kind = a.kind AND h.name = a.name
            """.formatted(AssetChainQuery.CHAIN_HEAD_AGGREGATE);

    private static final String SELECT_COLUMNS = """
            SELECT r.id, r.asset_row_id,
                   a.project_id AS asset_project_id, p.name AS owner_project_name,
                   a.kind, a.name,
                   a.asset_version AS pinned_version,
                   a.status AS asset_status,
                   COALESCE(h.max_version, a.asset_version) AS head_version,
                   r.referrer_project_id, rp.name AS referrer_project_name,
                   r.referrer_kind, r.referrer_id, r.created_at
            """;

    private static final RowMapper<AssetReferenceInfo> MAPPER = (rs, rowNum) -> {
        int pinnedVersion = rs.getInt("pinned_version");
        int headVersion = rs.getInt("head_version");
        return new AssetReferenceInfo(
                rs.getLong("id"),
                rs.getLong("asset_row_id"),
                rs.getLong("asset_project_id"),
                rs.getString("owner_project_name"),
                rs.getString("kind"),
                rs.getString("name"),
                pinnedVersion,
                rs.getInt("asset_status"),
                headVersion,
                pinnedVersion < headVersion,
                rs.getLong("referrer_project_id"),
                rs.getString("referrer_project_name"),
                rs.getString("referrer_kind"),
                rs.getLong("referrer_id"),
                rs.getTimestamp("created_at").toInstant());
    };

    private final NamedParameterJdbcTemplate jdbc;

    public SharedAssetReferenceServiceImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public AssetReferenceInfo pin(long callerProjectId, long assetRowId,
                                  String referrerKind, long referrerId) {
        requireNonBlank(referrerKind, "Referrer kind");
        if (referrerId <= 0) {
            throw new ServiceException("Referrer id must be a positive host row id, got "
                    + referrerId, BizErrorCode.VALIDATION_ERROR);
        }
        Long pinnedChainProject = firstLong(
                "SELECT project_id FROM tp_asset WHERE id = :id",
                new MapSqlParameterSource("id", assetRowId));
        if (pinnedChainProject == null) {
            throw new ServiceException("Asset version row " + assetRowId
                    + " does not exist; a pin must target a concrete published version row",
                    BizErrorCode.NOT_FOUND);
        }
        // Chain coordinates of the pinned row: a host keeps at most one pin per asset
        // chain, so switching to a newer version row retires the older pin atomically and
        // per-version pin timelines stay exact.
        java.util.Map<String, Object> pinnedChain = jdbc.queryForMap(
                "SELECT project_id, kind, name FROM tp_asset WHERE id = :id",
                new MapSqlParameterSource("id", assetRowId));
        if (firstLong("SELECT id FROM tp_project WHERE id = :id",
                new MapSqlParameterSource("id", callerProjectId)) == null) {
            throw new ServiceException("Consumer project " + callerProjectId
                    + " does not exist; pins can only be registered by a real project",
                    BizErrorCode.NOT_FOUND);
        }

        jdbc.update("""
                DELETE FROM tp_asset_reference r
                USING tp_asset a
                WHERE r.referrer_kind = :referrerKind AND r.referrer_id = :referrerId
                  AND r.asset_row_id = a.id
                  AND a.project_id = :chainProjectId AND a.kind = :chainKind
                  AND a.name = :chainName AND a.id <> :assetRowId
                """, new MapSqlParameterSource()
                .addValue("referrerKind", referrerKind.trim())
                .addValue("referrerId", referrerId)
                .addValue("chainProjectId", pinnedChain.get("project_id"))
                .addValue("chainKind", pinnedChain.get("kind"))
                .addValue("chainName", pinnedChain.get("name"))
                .addValue("assetRowId", assetRowId));
        jdbc.update("""
                INSERT INTO tp_asset_reference (asset_row_id, referrer_project_id,
                                                referrer_kind, referrer_id)
                VALUES (:assetRowId, :callerProjectId, :referrerKind, :referrerId)
                ON CONFLICT (asset_row_id, referrer_kind, referrer_id) DO UPDATE
                SET referrer_project_id = EXCLUDED.referrer_project_id
                """, new MapSqlParameterSource()
                .addValue("assetRowId", assetRowId)
                .addValue("callerProjectId", callerProjectId)
                .addValue("referrerKind", referrerKind.trim())
                .addValue("referrerId", referrerId));
        return pinByHostRow(referrerKind.trim(), referrerId, assetRowId);
    }

    @Override
    public PageResult<AssetReferenceInfo> listReferencesToChain(long chainProjectId, String kind,
                                                                String name, int page, int size) {
        requireNonBlank(kind, "Asset kind");
        requireNonBlank(name, "Asset name");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("chainProjectId", chainProjectId)
                .addValue("kind", kind.trim())
                .addValue("name", name.trim());
        return pageByWhere("WHERE a.project_id = :chainProjectId AND a.kind = :kind"
                + " AND a.name = :name", params, page, size);
    }

    @Override
    public PageResult<AssetReferenceInfo> listReferencesByConsumer(long consumerProjectId,
                                                                   int page, int size) {
        MapSqlParameterSource params =
                new MapSqlParameterSource("consumerProjectId", consumerProjectId);
        return pageByWhere("WHERE r.referrer_project_id = :consumerProjectId",
                params, page, size);
    }

    /** Newest-first version rows of one chain with per-row pin counts. */
    private static final RowMapper<AssetVersionTimelineInfo> TIMELINE_MAPPER = (rs, rowNum) ->
            new AssetVersionTimelineInfo(
                    rs.getLong("id"),
                    rs.getInt("asset_version"),
                    rs.getInt("status"),
                    rs.getBoolean("is_head"),
                    rs.getLong("pinned_count"),
                    rs.getTimestamp("updated_at").toInstant());

    @Override
    public List<AssetVersionTimelineInfo> listChainVersionTimeline(long chainProjectId,
                                                                   String kind, String name) {
        requireNonBlank(kind, "Asset kind");
        requireNonBlank(name, "Asset name");
        return jdbc.query(AssetChainQuery.SELECT_VERSION_TIMELINE_OF_CHAIN,
                new MapSqlParameterSource()
                        .addValue("chainProjectId", chainProjectId)
                        .addValue("chainKind", kind.trim())
                        .addValue("chainName", name.trim()),
                TIMELINE_MAPPER);
    }

    private AssetReferenceInfo pinByHostRow(String referrerKind, long referrerId,
                                            long assetRowId) {
        List<AssetReferenceInfo> rows = jdbc.query(
                SELECT_COLUMNS + FROM_JOINS
                        + " WHERE r.referrer_kind = :referrerKind AND r.referrer_id = :referrerId"
                        + " AND r.asset_row_id = :assetRowId",
                new MapSqlParameterSource()
                        .addValue("referrerKind", referrerKind)
                        .addValue("referrerId", referrerId)
                        .addValue("assetRowId", assetRowId),
                MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException("Pin for host " + referrerKind + "#" + referrerId
                    + " is missing after upsert", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    /** Newest-first page plus total, both sharing the same FROM/WHERE text (no drift). */
    private PageResult<AssetReferenceInfo> pageByWhere(String whereClause,
                                                       MapSqlParameterSource params,
                                                       int page, int size) {
        int safeSize = PageQueryPolicy.enforceSize(size);
        int safePage = Math.max(1, page);
        List<AssetReferenceInfo> rows = jdbc.query(
                SELECT_COLUMNS + FROM_JOINS + " " + whereClause
                        + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", safeSize)
                        .addValue("offset", (long) (safePage - 1) * safeSize),
                MAPPER);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) " + FROM_JOINS + " " + whereClause, params, Long.class);
        return new PageResult<>(rows, total == null ? 0L : total);
    }

    private Long firstLong(String sql, MapSqlParameterSource params) {
        List<Long> ids = jdbc.queryForList(sql, params, Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(label + " must not be blank", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
