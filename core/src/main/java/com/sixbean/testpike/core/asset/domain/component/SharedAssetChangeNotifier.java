package com.sixbean.testpike.core.asset.domain.component;

import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.script.domain.component.ScriptInvalidationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Change notices and invalidation linkage for the shared asset pool.
 * <p>A re-publish informs every consumer project that pins any version row of the
 * chain (owners union creator via {@link ProjectOwners}, the single recipient
 * resolution point); a forced withdrawal notifies the same audience and moves each
 * pinned script to pending-update through {@link ScriptInvalidationSupport} — the
 * UPDATE-style marking stays a single source, this class never re-implements it
 * (cross-domain component injection follows that class's documented placement note).
 * <p>Consumer lookup SQL is single-sourced in
 * {@link AssetChainQuery#SELECT_CONSUMER_HOSTS_OF_CHAIN}: one batched join, no
 * per-pin follow-up query (zero N+1). Notices run after commit so a rolled-back
 * write never sends a letter, and a failing recipient only warns — one broken
 * inbox must not undo the landed version row.
 */
@Component
public class SharedAssetChangeNotifier {

    /** Re-publish notice (event dictionary: ASSET_UPDATED:{newRowId}:{userId}). */
    public static final String EVENT_ASSET_UPDATED = "ASSET_UPDATED";
    /** Forced-withdrawal notice (event dictionary: ASSET_WITHDRAWN:{rowId}:{userId}). */
    public static final String EVENT_ASSET_WITHDRAWN = "ASSET_WITHDRAWN";

    /** Host pin row of one asset chain, consumer project name resolved in the same SQL. */
    public record ConsumerHost(long referrerProjectId, String referrerProjectName,
                               String referrerKind, long referrerId) {
    }

    private static final Logger log = LoggerFactory.getLogger(SharedAssetChangeNotifier.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ProjectOwners projectOwners;
    private final NotificationService notificationService;
    private final AfterCommitExecutor afterCommitExecutor;
    private final ScriptInvalidationSupport invalidation;

    public SharedAssetChangeNotifier(NamedParameterJdbcTemplate jdbc,
                                     ProjectOwners projectOwners,
                                     NotificationService notificationService,
                                     AfterCommitExecutor afterCommitExecutor,
                                     ScriptInvalidationSupport invalidation) {
        this.jdbc = jdbc;
        this.projectOwners = projectOwners;
        this.notificationService = notificationService;
        this.afterCommitExecutor = afterCommitExecutor;
        this.invalidation = invalidation;
    }

    /**
     * Every host pin attached to any version row of one (project, kind, name) chain.
     * Same surface feeds the fail-closed withdrawal check and both notice fan-outs,
     * so "who uses me" can never drift between them.
     */
    public List<ConsumerHost> consumerHostsOfChain(long chainProjectId, String kind, String name) {
        return jdbc.query(AssetChainQuery.SELECT_CONSUMER_HOSTS_OF_CHAIN,
                new MapSqlParameterSource()
                        .addValue("chainProjectId", chainProjectId)
                        .addValue("chainKind", kind)
                        .addValue("chainName", name),
                (rs, rowNum) -> new ConsumerHost(rs.getLong("referrer_project_id"),
                        rs.getString("referrer_project_name"),
                        rs.getString("referrer_kind"), rs.getLong("referrer_id")));
    }

    /** Schedule one ASSET_UPDATED letter per recipient of every consumer project after commit. */
    public void scheduleUpdateNotices(List<ConsumerHost> hosts, String kind, String assetName,
                                      String ownerProjectName, SharedAssetInfo newVersion) {
        if (hosts.isEmpty()) {
            return;
        }
        Map<Long, String> consumers = distinctConsumers(hosts);
        String title = "Pinned shared asset has a new version";
        String content = "Shared asset \"" + assetName + "\" (" + kind + ") from project \""
                + ownerProjectName + "\" moved to v" + newVersion.assetVersion()
                + ". Your pins keep pointing at the older version; re-pin to the new one "
                + "from the reference panel when you want the update.";
        afterCommitExecutor.execute(() -> consumers.forEach(
                (projectId, ignored) -> fanOut(projectId, EVENT_ASSET_UPDATED,
                        newVersion.id(), title, content)));
    }

    /** Schedule one ASSET_WITHDRAWN letter per recipient of every consumer project after commit. */
    public void scheduleWithdrawNotices(List<ConsumerHost> hosts, String kind, String assetName,
                                        String ownerProjectName, long withdrawnRowId) {
        if (hosts.isEmpty()) {
            return;
        }
        Map<Long, String> consumers = distinctConsumers(hosts);
        String title = "Pinned shared asset was withdrawn from the pool";
        String content = "Shared asset \"" + assetName + "\" (" + kind + ") from project \""
                + ownerProjectName + "\" has been withdrawn. Scripts in your project that "
                + "pinned it are marked pending-update; attach a replacement asset to make "
                + "them executable again.";
        afterCommitExecutor.execute(() -> consumers.forEach(
                (projectId, ignored) -> fanOut(projectId, EVENT_ASSET_WITHDRAWN,
                        withdrawnRowId, title, content)));
    }

    /**
     * Move every pinned script to pending-update. Host kind {@code case} is already a
     * case id; {@code case_step} resolves to its owning case in one batched query;
     * host kinds without script semantics are skipped honestly (the UPDATE-style
     * marking stays a silent no-op for missing rows, never an error).
     */
    @Transactional
    public void invalidatePinnedScripts(List<ConsumerHost> hosts) {
        Set<Long> caseIds = new LinkedHashSet<>();
        List<Long> stepIds = new ArrayList<>();
        for (ConsumerHost host : hosts) {
            if ("case".equals(host.referrerKind())) {
                caseIds.add(host.referrerId());
            } else if ("case_step".equals(host.referrerKind())) {
                stepIds.add(host.referrerId());
            }
        }
        if (!stepIds.isEmpty()) {
            caseIds.addAll(jdbc.queryForList(
                    "SELECT case_id FROM tp_case_step WHERE id IN (:stepIds)",
                    new MapSqlParameterSource("stepIds", stepIds), Long.class));
        }
        caseIds.forEach(invalidation::markPending);
    }

    private Map<Long, String> distinctConsumers(List<ConsumerHost> hosts) {
        Map<Long, String> consumers = new LinkedHashMap<>();
        for (ConsumerHost host : hosts) {
            consumers.putIfAbsent(host.referrerProjectId(), host.referrerProjectName());
        }
        return consumers;
    }

    private void fanOut(long consumerProjectId, String eventType, long anchorRowId,
                        String title, String content) {
        for (Long userId : projectOwners.resolveWithCreator(consumerProjectId)) {
            try {
                notificationService.create(new NotificationCreateInput(userId, consumerProjectId,
                        eventType, title, content,
                        eventType + ":" + anchorRowId + ":" + userId));
            } catch (RuntimeException e) {
                log.warn("asset notice {} delivery failed (projectId={}, userId={}): {}",
                        eventType, consumerProjectId, userId, e.getMessage());
            }
        }
    }
}
