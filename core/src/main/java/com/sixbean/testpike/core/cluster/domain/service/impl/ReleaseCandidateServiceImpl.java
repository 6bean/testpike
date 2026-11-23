package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateDetectedInput;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseCandidateService;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseManifestService;
import com.sixbean.testpike.core.cluster.infrastructure.dataobject.ReleaseCandidate;
import com.sixbean.testpike.core.common.domain.component.VersionCompare;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Release candidate service implementation (AF batch two-state model: detection
 * lands in the candidate table, publishing stays a human-authorised act).
 * <p>Upsert idempotency = one row per version, ON CONFLICT refreshes only the
 * detection frame (payload/detected_at/channel/detected_source); status and
 * confirmed_manifest_id stay out of the SET clause so disposed rows can never
 * roll back (re-detection never reopens a confirmed/dismissed/replaced banner).
 * A detection whose version already exists in the authoritative table lands as
 * replaced right away (honest trail, no duplicate reminder — the other half of
 * "dismissed keeps banners from lingering").
 * <p>Promotion single point = the existing {@link ReleaseManifestService#register}
 * validation reused as-is (no second validation chain, ruling #1); source is
 * forced to import (machine intake = import class, enum unextended, ruling #5).
 * Lower detected candidates turn replaced after each detection/confirmation —
 * the upstream feed only tracks the latest release. Version ordering goes
 * through {@link VersionCompare} (single judgement spectrum).
 * <p>jsonb columns are written via :param::jsonb and read back as ::text
 * (domain-layer codec single point, same baseline as ReleaseManifest); the
 * pending list is sorted numerically per version segment in Java (string order
 * puts 1.10 below 1.9 — a known trap).
 */
@Service
public class ReleaseCandidateServiceImpl implements ReleaseCandidateService {

    private static final List<String> CHANNELS = List.of("released", "prerelease");
    private static final List<String> DETECTED_SOURCES = List.of("poll", "webhook", "import");

    private static final String COLUMNS = """
            id, version, channel, detected_source, detected_at,
            payload::text AS payload, status, confirmed_manifest_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ReleaseManifestService manifestService;
    private final ClusterComponentService componentService;
    private final ObjectMapper objectMapper;

    public ReleaseCandidateServiceImpl(NamedParameterJdbcTemplate jdbc,
                                       ReleaseManifestService manifestService,
                                       ClusterComponentService componentService,
                                       ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.manifestService = manifestService;
        this.componentService = componentService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ReleaseCandidateInfo recordDetected(ReleaseCandidateDetectedInput input) {
        String version = trimToNull(input.version());
        if (version == null) {
            throw new ServiceException("候选检出必须提供版本号（version 未随检出提交）——请携带检出的实际版本号重试（示例：1.2.0），或在「升级中心」点「刷新」等下轮探测重新检出", BizErrorCode.VALIDATION_ERROR);
        }
        if (version.length() > 60) {
            throw new ServiceException("候选版本号超长（上限 60 字符，本次提交 " + version.length() + " 字符）——请改为检出的实际版本号（示例：1.2.0-beta.1）后重新触发检出", BizErrorCode.VALIDATION_ERROR);
        }
        String channel = trimToNull(input.channel()) == null ? "released" : input.channel().trim();
        if (!CHANNELS.contains(channel)) {
            throw new ServiceException("候选发布通道必须是 " + String.join(" / ", CHANNELS)
                    + " 之一（当前：" + input.channel() + "）——请改为 released 或 prerelease（示例：released）", BizErrorCode.VALIDATION_ERROR);
        }
        String source = trimToNull(input.detectedSource()) == null
                ? "poll" : input.detectedSource().trim();
        if (!DETECTED_SOURCES.contains(source)) {
            throw new ServiceException("候选检出来源必须是 " + String.join(" / ", DETECTED_SOURCES)
                    + " 之一（当前：" + input.detectedSource() + "）——请改为 poll、webhook 或 import（示例：poll）", BizErrorCode.VALIDATION_ERROR);
        }
        String payload = normalizeJsonObject(input.payloadJson());

        // Version already on the authoritative surface: the detection lands as
        // replaced for a fresh row; existing rows keep their disposed status.
        Integer published = jdbc.queryForObject(
                "SELECT count(*) FROM tp_release_manifest WHERE version = :version",
                new MapSqlParameterSource("version", version), Integer.class);
        String initialStatus = published != null && published > 0 ? "replaced" : "detected";

        jdbc.update("""
                INSERT INTO tp_release_candidate
                    (version, channel, detected_source, detected_at, payload, status)
                VALUES (:version, :channel, :source, now(), :payload::jsonb, :status)
                ON CONFLICT ON CONSTRAINT uq_release_candidate_version DO UPDATE
                SET channel         = EXCLUDED.channel,
                    detected_source = EXCLUDED.detected_source,
                    detected_at     = now(),
                    payload         = EXCLUDED.payload,
                    updated_at      = now()
                """, new MapSqlParameterSource()
                .addValue("version", version)
                .addValue("channel", channel)
                .addValue("source", source)
                .addValue("payload", payload)
                .addValue("status", initialStatus));

        if ("detected".equals(initialStatus)) {
            replaceLowerDetected(version);
        }
        return loadByVersion(version);
    }

    @Override
    public List<ReleaseCandidateInfo> listPending() {
        return jdbc.query("SELECT " + COLUMNS + " FROM tp_release_candidate WHERE status = 'detected'",
                new MapSqlParameterSource(),
                new BeanPropertyRowMapper<>(ReleaseCandidate.class))
                .stream().map(ReleaseCandidateServiceImpl::toInfo)
                .sorted((a, b) -> {
                    int cmp = VersionCompare.compare(VersionCompare.firstVersionOf(a.version()),
                            VersionCompare.firstVersionOf(b.version()));
                    return cmp != 0 ? -cmp : b.version().compareTo(a.version());
                })
                .toList();
    }

    @Override
    public int pendingCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tp_release_candidate WHERE status = 'detected'",
                new MapSqlParameterSource(), Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public ReleaseCandidateInfo findByVersion(String version) {
        String trimmed = trimToNull(version);
        if (trimmed == null) {
            return null;
        }
        List<ReleaseCandidate> rows = jdbc.query("SELECT " + COLUMNS
                        + " FROM tp_release_candidate WHERE version = :version",
                new MapSqlParameterSource("version", trimmed),
                new BeanPropertyRowMapper<>(ReleaseCandidate.class));
        return rows.isEmpty() ? null : toInfo(rows.get(0));
    }

    @Override
    @Transactional
    public ReleaseManifestInfo confirm(Long candidateId, ReleaseRegisterInput input) {
        ReleaseCandidate candidate = loadById(candidateId);
        requireDetected(candidate, "确认发布");
        String candidateVersion = candidate.getVersion();
        String inputVersion = trimToNull(input.version());
        if (inputVersion == null || !inputVersion.equals(candidateVersion)) {
            throw new ServiceException("确认发布的版本号（" + input.version() + "）与候选版本（"
                    + candidateVersion + "）不一致——防误点错版，请在「升级中心」候选卡点「一键确认发布」，把弹层中的「版本号」改为候选行版本后重新提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Single promotion path = existing register validation; source forced to
        // import (machine intake class, authoritative enum unextended — ruling #5).
        ReleaseManifestInfo published = manifestService.register(new ReleaseRegisterInput(
                inputVersion, input.componentMatrixJson(), input.breakingChangesJson(),
                input.executorVersionMin(), input.executorVersionMax(), input.suggestedPath(),
                "import", input.artifactsJson(), input.releaseNotesMd()));

        jdbc.update("""
                UPDATE tp_release_candidate
                SET status = 'confirmed', confirmed_manifest_id = :manifestId, updated_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("manifestId", published.id())
                .addValue("id", candidateId));
        replaceLowerDetected(candidateVersion);
        // promotion linkage: the released version becomes the platform
        // ledger target in the same transaction (three-colour panorama
        // re-scores through the shared judgement spectrum; missing platform
        // rows = honest no-op until agent enrollment creates them in ).
        componentService.applyReleaseTarget(candidateVersion);
        return published;
    }

    @Override
    @Transactional
    public ReleaseCandidateInfo dismiss(Long candidateId, String reason) {
        ReleaseCandidate candidate = loadById(candidateId);
        String trimmedReason = trimToNull(reason);
        if (trimmedReason == null) {
            throw new ServiceException("作废候选必须填写理由（写清理由，留审计痕迹；本次提交的理由为空）——请在「升级中心」候选卡点「作废」，在理由栏填写示例「上游撤回该版本」后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        requireDetected(candidate, "作废");
        // Keep the dismissal reason inside the payload trail (awareness-surface
        // audit frame; the authoritative surface is untouched).
        jdbc.update("""
                UPDATE tp_release_candidate
                SET status = 'dismissed',
                    payload = jsonb_set(payload, '{dismiss_reason}',
                            (:reason::text)::jsonb, true),
                    updated_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("reason", objectMapper.valueToTree(trimmedReason).toString())
                .addValue("id", candidateId));
        return toInfo(loadById(candidateId));
    }

    /** Lower detected candidates become replaced (feed tracks latest only; VersionCompare single spectrum). */
    private void replaceLowerDetected(String newestVersion) {
        List<ReleaseCandidate> detectedRows = jdbc.query("SELECT " + COLUMNS
                        + " FROM tp_release_candidate WHERE status = 'detected'",
                new MapSqlParameterSource(),
                new BeanPropertyRowMapper<>(ReleaseCandidate.class));
        List<Long> lowerIds = new ArrayList<>();
        for (ReleaseCandidate row : detectedRows) {
            if (!row.getVersion().equals(newestVersion) && VersionCompare.compare(
                    VersionCompare.firstVersionOf(row.getVersion()),
                    VersionCompare.firstVersionOf(newestVersion)) < 0) {
                lowerIds.add(row.getId());
            }
        }
        if (!lowerIds.isEmpty()) {
            jdbc.update("UPDATE tp_release_candidate SET status = 'replaced', updated_at = now()"
                    + " WHERE id IN (:ids)", new MapSqlParameterSource("ids", lowerIds));
        }
    }

    /** Uniform plain-word rejection for non-detected rows (confirm/dismiss share one spectrum). */
    private void requireDetected(ReleaseCandidate candidate, String action) {
        switch (candidate.getStatus()) {
            case "confirmed" -> throw new ServiceException("该候选已确认发布（权威清单已生效），"
                    + "不能重复" + action + "——该版本已入权威清单，可在「升级中心」的「版本清单」页签核对；如需修订请重新登记该版本清单（发版时登记或从发布件导入）", BizErrorCode.VALIDATION_ERROR);
            case "dismissed" -> throw new ServiceException("该候选已作废（他人已处置），不能再" + action
                    + "——如需再次确认该版本，可在「升级中心」点「刷新」等下轮探测重新检出，或由管理员手动登记",
                    BizErrorCode.VALIDATION_ERROR);
            case "replaced" -> throw new ServiceException("该候选已被更新版本检出取代或同版本已在权威清单，"
                    + "不能" + action + "——请在「升级中心」待确认候选卡处理最新检出版本", BizErrorCode.VALIDATION_ERROR);
            default -> {
                // detected = allowed
            }
        }
    }

    private ReleaseCandidate loadById(Long candidateId) {
        if (candidateId == null) {
            throw new ServiceException("候选操作必须提供候选行 id（本次提交的 id 为空）——请从「升级中心」待确认候选卡点「一键确认发布」或「作废」发起，不要直接调用接口", BizErrorCode.VALIDATION_ERROR);
        }
        List<ReleaseCandidate> rows = jdbc.query("SELECT " + COLUMNS
                        + " FROM tp_release_candidate WHERE id = :id",
                new MapSqlParameterSource("id", candidateId),
                new BeanPropertyRowMapper<>(ReleaseCandidate.class));
        if (rows.isEmpty()) {
            throw new ServiceException("候选行不存在（id=" + candidateId + "）——可能已被清理，请在「升级中心」点「刷新」重新加载待确认候选后再操作",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    private ReleaseCandidateInfo loadByVersion(String version) {
        ReleaseCandidateInfo info = findByVersion(version);
        if (info == null) {
            throw new ServiceException("候选写入后回读失败（version=" + version + "）——请在「升级中心」点「刷新」后重试，持续失败请查看 core 服务日志定位", BizErrorCode.INTERNAL_ERROR);
        }
        return info;
    }

    /** Payload validation: must be a JSON object (raw frame, fill what the feed provides; null = {}). */
    private String normalizeJsonObject(String json) {
        String text = trimToNull(json);
        if (text == null) {
            return "{}";
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(text);
        } catch (Exception e) {
            throw new ServiceException("候选检出内容必须是合法 JSON 对象（解析失败："
                    + e.getMessage() + "）——请检出源改按示例格式提交 {release_name: 版本号}，修正后重新触发", BizErrorCode.VALIDATION_ERROR);
        }
        if (!node.isObject()) {
            throw new ServiceException("候选检出内容必须是 JSON 对象（示例：{\"release_name\":\"1.2.0\"}），"
                    + "当前不是对象形态——请检出源改为 JSON 对象后重新提交", BizErrorCode.VALIDATION_ERROR);
        }
        return node.toString();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ReleaseCandidateInfo toInfo(ReleaseCandidate c) {
        return new ReleaseCandidateInfo(c.getId(), c.getVersion(), c.getChannel(),
                c.getDetectedSource(), c.getDetectedAt(), c.getPayload(),
                c.getStatus(), c.getConfirmedManifestId());
    }
}
