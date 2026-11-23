package com.sixbean.testpike.core.asset.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.asset.api.dto.AssetPublishRequest;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.asset.domain.component.AssetChainQuery;
import com.sixbean.testpike.core.asset.domain.component.LoginRecipePayloadContract;
import com.sixbean.testpike.core.asset.domain.component.SharedAssetChangeNotifier;
import com.sixbean.testpike.core.asset.domain.component.SharedAssetChangeNotifier.ConsumerHost;
import com.sixbean.testpike.core.asset.domain.component.SharedAssetVisibilityGuard;
import com.sixbean.testpike.core.asset.domain.service.SharedAssetService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared asset publish chain implementation over tp_asset (V358).
 * <p>Single-table version chain shaped after the api spec chain (V109): a re-publish of
 * the same (project, kind, name) appends asset_version+1 linked through prev_asset_id,
 * so the arrival of a new version row is the update fact itself and superseded rows stay
 * readable for pinned references. Kind/name/payload validation runs before the first SQL
 * write so a CHECK violation can never poison the surrounding transaction. Ownership and
 * discoverability semantics are delegated to {@link SharedAssetVisibilityGuard} (single
 * source, R40) — this class never re-implements the fence.
 * <p>segment 3 wiring lives here, not in a second flow: a re-publish that still has
 * consumer pins schedules ASSET_UPDATED fan-out through
 * {@link SharedAssetChangeNotifier}, and a withdrawal is fail-closed — active consumer
 * projects are named in the rejection, while the force path notifies every consumer
 * (ASSET_WITHDRAWN) and marks their pinned scripts pending-update before the chain
 * leaves the pool. All consumer lookups share one SQL single source (zero N+1).
 */
@Service
public class SharedAssetServiceImpl implements SharedAssetService {

    /** Four-value kind whitelist mirroring the tp_asset CHECK (V358). */
    private static final Set<String> PUBLISHABLE_KINDS =
            Set.of("case_fragment", "action_component", "login_recipe", "gen_function");

    /** Head-derived asset projection; SQL single-sourced in AssetChainQuery (R40). */
    private static final String SELECT_WITH_HEAD = AssetChainQuery.SELECT_WITH_HEAD;

    private static final RowMapper<SharedAssetInfo> MAPPER = (rs, rowNum) -> new SharedAssetInfo(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getString("owner_project_name"),
            rs.getString("kind"),
            rs.getString("name"),
            rs.getInt("asset_version"),
            rs.getObject("prev_asset_id", Long.class),
            rs.getInt("status"),
            rs.getString("payload_json"),
            rs.getBoolean("is_head"),
            rs.getInt("ref_count"));

    private final NamedParameterJdbcTemplate jdbc;
    private final SharedAssetVisibilityGuard guard;
    private final SharedAssetChangeNotifier notifier;

    public SharedAssetServiceImpl(NamedParameterJdbcTemplate jdbc,
                                  SharedAssetVisibilityGuard guard,
                                  SharedAssetChangeNotifier notifier) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.notifier = notifier;
    }

    @Override
    @Transactional
    public SharedAssetInfo publish(long operatorId, long callerProjectId,
                                   AssetPublishRequest request) {
        requirePublishableKind(request.kind());
        requireNonBlank(request.name(), "Asset name");
        requireNonBlank(request.payloadJson(), "Asset payload");
        // The login recipe carries a runnable step sequence and is readable across
        // projects, so its payload shape and secret handling are gated here — before the
        // first SQL write, in the same posture as the blank checks above.
        if ("login_recipe".equals(request.kind())) {
            LoginRecipePayloadContract.requirePublishablePayload(request.payloadJson());
        }

        String ownerProjectName = projectNameOr404(request.projectId());
        guard.requireOwner(callerProjectId, request.projectId(), ownerProjectName);

        MapSqlParameterSource chainKey = new MapSqlParameterSource()
                .addValue("projectId", request.projectId())
                .addValue("kind", request.kind())
                .addValue("name", request.name());
        List<HeadRow> currentHead = jdbc.query(
                "SELECT id, asset_version FROM tp_asset"
                        + " WHERE project_id = :projectId AND kind = :kind AND name = :name"
                        + " ORDER BY asset_version DESC LIMIT 1",
                chainKey, (rs, rowNum) -> new HeadRow(rs.getLong("id"), rs.getInt("asset_version")));
        long nextVersion = currentHead.isEmpty() ? 1L : (long) currentHead.get(0).version() + 1L;
        Long prevAssetId = currentHead.isEmpty() ? null : currentHead.get(0).id();

        MapSqlParameterSource insert = new MapSqlParameterSource()
                .addValue("projectId", request.projectId())
                .addValue("kind", request.kind())
                .addValue("name", request.name())
                .addValue("version", nextVersion)
                .addValue("prevAssetId", prevAssetId)
                .addValue("status", SharedAssetVisibilityGuard.STATUS_PUBLISHED)
                .addValue("payloadJson", request.payloadJson())
                .addValue("operatorId", operatorId);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO tp_asset (project_id, kind, name, asset_version, prev_asset_id,
                                      status, payload_json, created_by)
                VALUES (:projectId, :kind, :name, :version, :prevAssetId,
                        :status, :payloadJson, :operatorId)
                """, insert, keyHolder, new String[] {"id"});
        long newId = keyHolder.getKey().longValue();
        SharedAssetInfo landed = get(newId);
        if (prevAssetId != null) {
            List<ConsumerHost> consumers = notifier.consumerHostsOfChain(
                    request.projectId(), request.kind(), request.name());
            notifier.scheduleUpdateNotices(consumers, request.kind(), request.name(),
                    ownerProjectName, landed);
        }
        return landed;
    }

    @Override
    public SharedAssetInfo get(long assetId) {
        List<SharedAssetInfo> rows = jdbc.query(
                SELECT_WITH_HEAD + " WHERE a.id = :id",
                new MapSqlParameterSource("id", assetId), MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException(
                    "Asset version row " + assetId + " does not exist", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public PageResult<SharedAssetInfo> listDiscoverable(String kind, int page, int size) {
        return listDiscoverable(kind, page, size, null, null);
    }

    /**
     * Sort whitelist for the pool list (server-side sort baseline, FB N-2). Keys are
     * the sortBy tokens the pool page emits; values are columns of the head-derived
     * outer projection. Only whitelist output reaches ORDER BY — the shared
     * sort-column gate refuses anything else fail-closed with a 400 naming the
     * allowed keys, so user input never enters SQL concatenation.
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "t.name",
            "kind", "t.kind",
            "owner_project_name", "t.owner_project_name",
            "asset_version", "t.asset_version");

    /** ORDER BY builder: a blank key keeps the historical updated_at DESC default. */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "asset");
        if (column == null) {
            return " ORDER BY t.updated_at DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", t.id DESC";
    }

    @Override
    public PageResult<SharedAssetInfo> listDiscoverable(String kind, int page, int size,
                                                        String sortBy, String sortDir) {
        int safeSize = PageQueryPolicy.enforceSize(size);
        int safePage = Math.max(1, page);
        boolean kindFilter = kind != null && !kind.isBlank();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("kind", kindFilter ? kind.trim() : null)
                .addValue("limit", safeSize)
                .addValue("offset", (long) (safePage - 1) * safeSize);
        String kindClause = kindFilter ? " AND t.kind = :kind" : "";

        List<SharedAssetInfo> rows = jdbc.query(
                "SELECT * FROM (" + SELECT_WITH_HEAD + ") t"
                        + " WHERE t.status = :published AND t.is_head" + kindClause
                        + orderClause(sortBy, sortDir) + " LIMIT :limit OFFSET :offset",
                params.addValue("published", SharedAssetVisibilityGuard.STATUS_PUBLISHED),
                MAPPER);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (" + SELECT_WITH_HEAD + ") t"
                        + " WHERE t.status = :published AND t.is_head" + kindClause,
                params, Long.class);
        return new PageResult<>(rows, total == null ? 0L : total);
    }

    @Override
    public long resolveHeadId(long projectId, String kind, String name) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("kind", kind)
                .addValue("name", name);
        List<Long> head = jdbc.queryForList(
                "SELECT id FROM tp_asset"
                        + " WHERE project_id = :projectId AND kind = :kind AND name = :name"
                        + " ORDER BY asset_version DESC LIMIT 1",
                params, Long.class);
        if (head.isEmpty()) {
            throw new ServiceException(
                    "No asset chain for project " + projectId + ", kind " + kind
                            + ", name " + name, BizErrorCode.NOT_FOUND);
        }
        return head.get(0);
    }

    @Override
    @Transactional
    public void withdraw(long operatorId, long callerProjectId, long assetId, boolean force) {
        SharedAssetInfo row = get(assetId);
        guard.requireOwner(callerProjectId, row.projectId(), row.ownerProjectName());
        List<ConsumerHost> consumers = notifier.consumerHostsOfChain(
                row.projectId(), row.kind(), row.name());
        if (!consumers.isEmpty()) {
            if (!force) {
                throw new ServiceException("Asset chain \"" + row.name() + "\" (" + row.kind()
                        + ") is still pinned by consumer project(s): "
                        + activeConsumerNames(consumers)
                        + ". Withdraw blocked. Withdraw with force=true to notify every"
                        + " consumer and mark their pinned scripts pending-update.",
                        BizErrorCode.RESOURCE_CONFLICT);
            }
            notifier.invalidatePinnedScripts(consumers);
            notifier.scheduleWithdrawNotices(consumers, row.kind(), row.name(),
                    row.ownerProjectName(), assetId);
        }
        jdbc.update("UPDATE tp_asset SET status = :withdrawn, updated_at = now()"
                        + " WHERE project_id = :projectId AND kind = :kind AND name = :name",
                new MapSqlParameterSource()
                        .addValue("withdrawn", SharedAssetVisibilityGuard.STATUS_WITHDRAWN)
                        .addValue("projectId", row.projectId())
                        .addValue("kind", row.kind())
                        .addValue("name", row.name()));
    }

    private String activeConsumerNames(List<ConsumerHost> consumers) {
        return consumers.stream()
                .map(ConsumerHost::referrerProjectName)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .toString();
    }

    private String projectNameOr404(long projectId) {
        List<String> names = jdbc.queryForList(
                "SELECT name FROM tp_project WHERE id = :id",
                new MapSqlParameterSource("id", projectId), String.class);
        if (names.isEmpty()) {
            throw new ServiceException("Project " + projectId
                    + " does not exist; assets can only be published into a real project",
                    BizErrorCode.NOT_FOUND);
        }
        return names.get(0);
    }

    private void requirePublishableKind(String kind) {
        if (kind == null || !PUBLISHABLE_KINDS.contains(kind)) {
            throw new ServiceException("Asset kind \"" + kind + "\" is not publishable; expected one of "
                    + "case_fragment, action_component, login_recipe or gen_function",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(label + " must not be blank", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Chain head locator row (id + version of the newest chain member). */
    private record HeadRow(long id, int version) {
    }
}
