package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorCompatView;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.cluster.domain.component.ArtifactHostPolicy;
import com.sixbean.testpike.core.cluster.domain.component.ReleaseManifestRefusals;
import com.sixbean.testpike.core.cluster.domain.component.ReleaseArtifactRules;
import com.sixbean.testpike.core.cluster.domain.component.ReleaseRangeRules;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseManifestService;
import com.sixbean.testpike.core.cluster.infrastructure.dataobject.ReleaseManifest;
import com.sixbean.testpike.core.common.domain.component.VersionCompare;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;


@Service
public class ReleaseManifestServiceImpl implements ReleaseManifestService {

    private static final List<String> SOURCES = List.of("manual", "import");

    /** Download-host whitelist key (CSV; the mirror host is merged at read time). */
    static final String ALLOWED_HOSTS_KEY = ArtifactHostPolicy.ALLOWED_HOSTS_KEY;

    private static final String COLUMNS = """
            id, version, component_matrix::text AS component_matrix,
            breaking_changes::text AS breaking_changes,
            executor_version_min, executor_version_max, suggested_path,
            source, registered_at,
            artifacts::text AS artifacts, release_notes_md, published_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ArtifactHostPolicy hostPolicy;

    public ReleaseManifestServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
            ArtifactHostPolicy hostPolicy) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.hostPolicy = hostPolicy;
    }

    @Override
    public ReleaseManifestInfo register(ReleaseRegisterInput input) {
        String version = trimToNull(input.version());
        if (version == null) {
            throw new ServiceException(ReleaseManifestRefusals.versionMissing(input.version()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (version.length() > 60) {
            throw new ServiceException(ReleaseManifestRefusals.versionTooLong(version.length()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        String matrix = normalizeJsonArray(input.componentMatrixJson(), "部件矩阵", true);
        String changes = normalizeJsonArray(input.breakingChangesJson(), "破坏性变更清单", false);
        String min = trimToNull(input.executorVersionMin());
        String max = trimToNull(input.executorVersionMax());
        if (min != null && max != null
                && VersionCompare.compare(VersionCompare.firstVersionOf(min),
                        VersionCompare.firstVersionOf(max)) > 0) {
            throw new ServiceException(ReleaseManifestRefusals.compatRangeInvalid(min, max),
                    BizErrorCode.VALIDATION_ERROR);
        }
        String src = trimToNull(input.source()) == null ? "manual" : input.source().trim();
        if (!SOURCES.contains(src)) {
            throw new ServiceException(ReleaseManifestRefusals.sourceInvalid(input.source()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        String path = trimToNull(input.suggestedPath());
        if (path != null && path.length() > 500) {
            throw new ServiceException(ReleaseManifestRefusals.suggestedPathTooLong(path.length()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Artifact supply-chain gates (shape -> pinned coverage -> host whitelist;
        // verdict spectrum single-sourced in ReleaseArtifactRules).
        String artifacts = normalizeArtifacts(input.artifactsJson(), matrix);
        String notes = trimToNull(input.releaseNotesMd());

        jdbc.update("""
                INSERT INTO tp_release_manifest
                    (version, component_matrix, breaking_changes,
                     executor_version_min, executor_version_max, suggested_path, source,
                     artifacts, release_notes_md)
                VALUES (:version, :matrix::jsonb, :changes::jsonb,
                        :min, :max, :path, :source,
                        :artifacts::jsonb, :notes)
                ON CONFLICT ON CONSTRAINT uq_release_manifest_version DO UPDATE
                SET component_matrix     = EXCLUDED.component_matrix,
                    breaking_changes     = EXCLUDED.breaking_changes,
                    executor_version_min = EXCLUDED.executor_version_min,
                    executor_version_max = EXCLUDED.executor_version_max,
                    suggested_path       = EXCLUDED.suggested_path,
                    source               = EXCLUDED.source,
                    artifacts            = EXCLUDED.artifacts,
                    release_notes_md     = EXCLUDED.release_notes_md,
                    registered_at        = now(),
                    updated_at           = now()
                """, new MapSqlParameterSource()
                .addValue("version", version)
                .addValue("matrix", matrix)
                .addValue("changes", changes)
                .addValue("min", min)
                .addValue("max", max)
                .addValue("path", path)
                .addValue("source", src)
                .addValue("artifacts", artifacts)
                .addValue("notes", notes));

        return jdbc.query("SELECT " + COLUMNS + " FROM tp_release_manifest WHERE version = :version",
                new MapSqlParameterSource("version", version),
                new BeanPropertyRowMapper<>(ReleaseManifest.class))
                .stream().map(ReleaseManifestServiceImpl::toInfo).findFirst().orElseThrow();
    }

    @Override
    public ReleaseManifestInfo latest() {
        List<ReleaseManifestInfo> sorted = listAll();
        return sorted.isEmpty() ? null : sorted.get(0);
    }

    @Override
    public ReleaseManifestInfo findByVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM tp_release_manifest WHERE version = :v",
                new MapSqlParameterSource("v", version),
                new BeanPropertyRowMapper<>(ReleaseManifest.class))
                .stream().findFirst().map(ReleaseManifestServiceImpl::toInfo).orElse(null);
    }

    @Override
    public List<ReleaseManifestInfo> listAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM tp_release_manifest",
                new MapSqlParameterSource(),
                new BeanPropertyRowMapper<>(ReleaseManifest.class))
                .stream()
                .map(ReleaseManifestServiceImpl::toInfo)
                .sorted((a, b) -> {
                    int cmp = VersionCompare.compare(VersionCompare.firstVersionOf(a.version()),
                            VersionCompare.firstVersionOf(b.version()));
                    return cmp != 0 ? -cmp
                            : b.version().compareTo(a.version());
                })
                .toList();
    }

    @Override
    public List<ExecutorCompatView> compatDiff() {
        return scoreRows(latest());
    }

    @Override
    public List<ExecutorCompatView> compatDiffAgainst(String executorVersionMin, String executorVersionMax) {
        String min = trimToNull(executorVersionMin);
        String max = trimToNull(executorVersionMax);
        if (min != null && max != null
                && VersionCompare.compare(VersionCompare.firstVersionOf(min),
                        VersionCompare.firstVersionOf(max)) > 0) {
            throw new ServiceException(ReleaseManifestRefusals.hypotheticalRangeInvalid(min, max),
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Synthetic one-column manifest: the pre-check reuses the exact scoring row of
        // compatDiff() (same rangeText wording, same ReleaseRangeRules verdict) without
        // touching the authoritative table — pending candidate is not published yet.
        return scoreRows(new ReleaseManifestInfo(null, null, "[]", "[]", min, max, null, "candidate",
                null, null, null, null));
    }

    /** Shared verdict pass: every executor row scored against the given range (null range = fail-open). */
    private List<ExecutorCompatView> scoreRows(ReleaseManifestInfo range) {
        return jdbc.query("SELECT id, name, version FROM tp_executor ORDER BY id",
                new MapSqlParameterSource(),
                (rs, rowNum) -> {
                    String version = rs.getString("version");
                    boolean ok = ReleaseRangeRules.allowed(range, version);
                    return new ExecutorCompatView(rs.getLong("id"), rs.getString("name"), version, ok,
                            ok ? null : "与本平台协议不兼容（需 " + ReleaseRangeRules.rangeText(range)
                                    + "），请升级节点 agent");
                });
    }

    private static ReleaseManifestInfo toInfo(ReleaseManifest m) {
        return new ReleaseManifestInfo(m.getId(), m.getVersion(), m.getComponentMatrix(),
                m.getBreakingChanges(), m.getExecutorVersionMin(), m.getExecutorVersionMax(),
                m.getSuggestedPath(), m.getSource(), m.getRegisteredAt(),
                m.getArtifacts(), m.getReleaseNotesMd(), m.getPublishedAt());
    }

    
    private String normalizeJsonArray(String json, String fieldLabel, boolean componentItems) {
        String text = trimToNull(json);
        if (text == null) {
            return "[]";
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(text);
        } catch (Exception e) {
            throw new ServiceException(
                    ReleaseManifestRefusals.jsonParseFailed(fieldLabel, e.getMessage()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!node.isArray()) {
            throw new ServiceException(ReleaseManifestRefusals.jsonNotArray(fieldLabel),
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<String> hints = new ArrayList<>();
        for (JsonNode item : node) {
            if (componentItems) {
                String component = item.path("component").asText("");
                if (component.isBlank()) {
                    hints.add("部件矩阵存在缺部件族名（component）的条目");
                }
            } else if (!item.isTextual() || item.asText().isBlank()) {
                hints.add(fieldLabel + "存在空白/非文本条目（每条须写清理由）");
            }
        }
        if (!hints.isEmpty()) {
            throw new ServiceException(hints.get(0), BizErrorCode.VALIDATION_ERROR);
        }
        return node.toString();
    }

    /**
     * Artifact array gate: parse shape, then pinned-family coverage against the
     * matrix, then download-host whitelist. Returns the normalized json string
     * ("[]" when absent — announce-only registration stays legal by design).
     */
    private String normalizeArtifacts(String json, String matrixJson) {
        String text = trimToNull(json);
        JsonNode node;
        try {
            node = objectMapper.readTree(text == null ? "[]" : text);
        } catch (Exception e) {
            throw new ServiceException(ReleaseManifestRefusals.artifactsParseFailed(e.getMessage()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!node.isArray()) {
            throw new ServiceException(ReleaseManifestRefusals.artifactsNotArray(),
                    BizErrorCode.VALIDATION_ERROR);
        }
        for (JsonNode item : node) {
            String violation = ReleaseArtifactRules.itemViolation(item);
            if (violation != null) {
                throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
            }
        }
        JsonNode matrix = readMatrixForGate(matrixJson);
        java.util.Set<String> missing = ReleaseArtifactRules.pinnedFamiliesWithoutArtifact(matrix, node);
        if (!missing.isEmpty()) {
            throw new ServiceException(ReleaseManifestRefusals.pinnedWithoutArtifact(missing),
                    BizErrorCode.VALIDATION_ERROR);
        }
        java.util.Set<String> allowed = allowedHosts();
        for (JsonNode item : node) {
            if (!ReleaseArtifactRules.hostAllowed(item, allowed)) {
                throw new ServiceException(ReleaseManifestRefusals.hostNotAllowed(
                        item.path("component_family").asText(""), ALLOWED_HOSTS_KEY,
                        item.path("url").asText("")), BizErrorCode.VALIDATION_ERROR);
            }
        }
        return node.toString();
    }

    /** Matrix node for the pinned-coverage gate (already validated by normalizeJsonArray). */
    private JsonNode readMatrixForGate(String matrixJson) {
        try {
            return objectMapper.readTree(matrixJson);
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    /**
     * Whitelist read delegated to {@link ArtifactHostPolicy} (shared single source
     * with the dispatch pre-check prober — a second CSV+mirror-merge reader copy
     * is banned, red line 6).
     */
    private java.util.Set<String> allowedHosts() {
        return hostPolicy.allowedHosts();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
