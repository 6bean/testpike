package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.api.dto.ComponentVerdict;
import com.sixbean.testpike.core.cluster.domain.component.ComponentSupportRules;
import com.sixbean.testpike.core.cluster.domain.component.ThirdPartyHomepageRules;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import com.sixbean.testpike.core.cluster.infrastructure.dataobject.ClusterComponent;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Component version ledger implementation (frozen document).
 * <p>Write chain = single-statement upsert (node×family unique key ON CONFLICT overwrites the current value; requirement/target
 * Uses COALESCE stickiness (when the reporter omits the baseline, retain the original baseline; when the scanner or prober provides it, refresh), then read back the merged row and hand it over
 * The {@link ComponentSupportRules} decision spectrum performs single-source re-evaluation of three colors plus gray and writes a human-readable reason at the same time (secondary judgment in the view layer is prohibited).
 * <p>Source value domain is heartbeat/scan/manual (DB CHECK uses the same set, unknown sources are rejected with a human-readable message, and dirty values are prohibited from entering the ledger).
 */
@Service
public class ClusterComponentServiceImpl implements ClusterComponentService {

    private static final List<String> SOURCES = List.of("heartbeat", "scan", "manual");

    private static final String COLUMNS = """
            id, node_id, component_family, current_version, target_version,
            requirement, support_state, state_reason, source, reported_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ClusterComponentServiceImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ClusterComponentInfo upsert(String nodeId, String componentFamily, String currentVersion,
                                       String source, String requirement, String targetVersion) {
        String node = trimToNull(nodeId);
        if (node == null) {
            throw new ServiceException("部件台账写入必须提供节点标识（node_id）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (node.length() > 120) {
            throw new ServiceException("节点标识超长（上限 120 字符）", BizErrorCode.VALIDATION_ERROR);
        }
        String family = trimToNull(componentFamily);
        if (family == null) {
            throw new ServiceException("部件台账写入必须提供部件族（component_family）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (family.length() > 80) {
            throw new ServiceException("部件族标识超长（上限 80 字符）", BizErrorCode.VALIDATION_ERROR);
        }
        String src = trimToNull(source) == null ? "heartbeat" : source.trim();
        if (!SOURCES.contains(src)) {
            throw new ServiceException("部件台账来源必须是 " + String.join(" / ", SOURCES)
                    + " 之一（当前：" + source + "）", BizErrorCode.VALIDATION_ERROR);
        }
        jdbc.update("""
                INSERT INTO tp_cluster_component
                    (node_id, component_family, current_version, target_version, requirement,
                     source, reported_at, support_state, state_reason)
                VALUES (:node, :family, :current, :target, :requirement,
                        :source, CASE WHEN :current IS NULL THEN NULL ELSE now() END, 'UNKNOWN', '')
                ON CONFLICT ON CONSTRAINT uq_cluster_component_node_family DO UPDATE
                SET current_version = EXCLUDED.current_version,
                    target_version  = COALESCE(EXCLUDED.target_version, tp_cluster_component.target_version),
                    requirement     = COALESCE(EXCLUDED.requirement, tp_cluster_component.requirement),
                    source          = EXCLUDED.source,
                    reported_at     = CASE WHEN EXCLUDED.current_version IS NULL
                                          THEN tp_cluster_component.reported_at ELSE now() END,
                    updated_at      = now()
                """, new MapSqlParameterSource()
                .addValue("node", node)
                .addValue("family", family)
                // CASE WHEN :current IS NULL requires the driver to send setNull(VARCHAR) with an explicit type.
 // Otherwise, PostgreSQL reports could not determine data type of parameter (empirically verified).
                .addValue("current", trimToNull(currentVersion), Types.VARCHAR)
                .addValue("target", trimToNull(targetVersion), Types.VARCHAR)
                .addValue("requirement", trimToNull(requirement), Types.VARCHAR)
                .addValue("source", src));

        ClusterComponent row = findRow(node, family);
        ComponentVerdict verdict = ComponentSupportRules.judge(
                row.getComponentFamily(), row.getCurrentVersion(), row.getRequirement(), row.getTargetVersion());
        jdbc.update("UPDATE tp_cluster_component SET support_state = :state, state_reason = :reason,"
                        + " updated_at = now() WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("state", verdict.supportState())
                        .addValue("reason", truncate(verdict.stateReason(), 300))
                        .addValue("id", row.getId()));
        return toInfo(findRow(node, family), engineHomepageCatalog());
    }

    @Override
    public int applyReleaseTarget(String targetVersion) {
        String target = trimToNull(targetVersion);
        if (target == null) {
            throw new ServiceException("平台发布基准回写必须提供版本号", BizErrorCode.VALIDATION_ERROR);
        }
        // Rows only — never fabricate ledger rows for families whose agents
        // have not enrolled yet (honest no-op when the platform set is empty).
        List<ClusterComponent> rows = jdbc.query("SELECT " + COLUMNS
                        + " FROM tp_cluster_component WHERE component_family IN (:families)",
                new MapSqlParameterSource("families",
                        ComponentSupportRules.PLATFORM_RELEASE_FAMILIES),
                new BeanPropertyRowMapper<>(ClusterComponent.class));
        for (ClusterComponent row : rows) {
            jdbc.update("UPDATE tp_cluster_component SET target_version = :target, updated_at = now()"
                            + " WHERE id = :id",
                    new MapSqlParameterSource().addValue("target", target).addValue("id", row.getId()));
            ComponentVerdict verdict = ComponentSupportRules.judge(
                    row.getComponentFamily(), row.getCurrentVersion(), row.getRequirement(), target);
            jdbc.update("UPDATE tp_cluster_component SET support_state = :state, state_reason = :reason,"
                            + " updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource()
                            .addValue("state", verdict.supportState())
                            .addValue("reason", truncate(verdict.stateReason(), 300))
                            .addValue("id", row.getId()));
        }
        return rows.size();
    }

    @Override
    public List<ClusterComponentInfo> listByNode(String nodeId) {
        return query("WHERE node_id = :node ORDER BY component_family",
                new MapSqlParameterSource("node", trimToNull(nodeId)));
    }

    @Override
    public List<ClusterComponentInfo> listAll() {
        return query("ORDER BY node_id, component_family", new MapSqlParameterSource());
    }

    private List<ClusterComponentInfo> query(String where, MapSqlParameterSource params) {
        Map<String, String> catalog = engineHomepageCatalog();
        return jdbc.query("SELECT " + COLUMNS + " FROM tp_cluster_component " + where,
                params, new BeanPropertyRowMapper<>(ClusterComponent.class))
                .stream()
                .map(c -> toInfo(c, catalog))
                .toList();
    }

    /**
     * Engine catalog snapshot (lowercased code -> install_doc_url, blanks kept
     * so resolution can distinguish "catalog row exists but honest empty").
     * One query per list call — the ledger is small and the catalog is the
     * authoritative single source for engine homepages (front-end must never
     * maintain its own copy).
     */
    private Map<String, String> engineHomepageCatalog() {
        return jdbc.query("SELECT code, install_doc_url FROM tp_appauto_engine",
                new MapSqlParameterSource(), new ColumnMapRowMapper())
                .stream()
                .filter(row -> row.get("code") != null)
                .collect(Collectors.toMap(
                        row -> String.valueOf(row.get("code")).toLowerCase(Locale.ROOT),
                        row -> row.get("install_doc_url") == null ? "" : String.valueOf(row.get("install_doc_url")),
                        (a, b) -> a));
    }

    private ClusterComponent findRow(String nodeId, String family) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tp_cluster_component"
                        + " WHERE node_id = :node AND component_family = :family",
                new MapSqlParameterSource().addValue("node", nodeId).addValue("family", family),
                new BeanPropertyRowMapper<>(ClusterComponent.class))
                .get(0);
    }

    private static ClusterComponentInfo toInfo(ClusterComponent c, Map<String, String> catalog) {
        return new ClusterComponentInfo(c.getId(), c.getNodeId(), c.getComponentFamily(),
                c.getCurrentVersion(), c.getTargetVersion(), c.getRequirement(),
                c.getSupportState(), c.getStateReason(), c.getSource(), c.getReportedAt(),
                ThirdPartyHomepageRules.resolve(c.getComponentFamily(), catalog));
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }
}
