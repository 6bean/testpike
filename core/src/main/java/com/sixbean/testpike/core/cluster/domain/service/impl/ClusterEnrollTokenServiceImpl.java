package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.cluster.api.dto.EnrollAcceptedView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollHeartbeatView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssueInput;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssuedView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenView;
import com.sixbean.testpike.core.cluster.domain.component.ClusterComponentIngestor;
import com.sixbean.testpike.core.cluster.domain.component.EnrollTokenCodec;
import com.sixbean.testpike.core.cluster.domain.service.ClusterEnrollTokenService;
import com.sixbean.testpike.core.cluster.infrastructure.dataobject.ClusterEnrollToken;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one-time token implementation for installation pool entry is governed by frozen documentation and security red lines.
 * The token value domain has three roles, executor, ai-node, and platform, representing machine profiles, with the migration artifact CHECK constraint using the same set.
 * The TTL is read live from the K8 key {@code installkit.token-ttl-seconds}, and missing or invalid values use the fallback conservative default 3600.
 * Hot updates take effect immediately, secondary caching is prohibited, and this follows the same baseline as ResourceLeaseServiceImpl.ttlSeconds.
 * The redemption transition is atomic in a single statement, incrementing the count by 1 only when status=0, the token is not expired, and either no fingerprint is bound or the fingerprint matches.
 * When exhausted, the status flips to USED, and on contention failure the row is read back to classify each cause and reject with human-readable messages instead of 500, with a different-machine fingerprint failing closed.
 * Plaintext appears only once in the issue response, and the entire chain persists or compares only sha256 digests, per red line 4, and must not enter logs.
 */
@Service
public class ClusterEnrollTokenServiceImpl implements ClusterEnrollTokenService {

    private static final Logger log = LoggerFactory.getLogger(ClusterEnrollTokenServiceImpl.class);

    /** The step projection serialization artifact converts between Map and jsonb text and works with the default configuration. */
    private static final com.fasterxml.jackson.databind.ObjectMapper STEP_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

 /** The role value domain has three profiles, and unknown types are rejected with human-readable messages without creating a dictionary table. */
    static final Set<String> ROLES = Set.of("executor", "ai-node", "platform");

 /** The pool-entry token TTL K8 key has migration artifact seed 3600, and consumers read it live instead of hard-coding a second source. */
    static final String TTL_KEY = "installkit.token-ttl-seconds";

 /** The issuance operator role whitelist K8 key has migration artifact seed allowing all four roles, is read live, and missing or blank means no restriction. */
    static final String ALLOW_ROLES_KEY = "installkit.allow-roles";

 /** The installation image-source global redirection K8 key has migration artifact seed empty string for the official source, and -5 is read live with secondary caching prohibited. */
    static final String MIRROR_KEY = "installkit.mirror-base-url";

 /** Missing keys or invalid values use the fallback conservative default seconds, identical to the migration artifact seed. */
    static final int DEFAULT_TTL_SECONDS = 3600;

 /** The installation step key value domain sets the 03 step trailing key to -2 as the single source for the node card step-bar four cells, and scripts must not create their own values. */
    static final Set<String> ENROLL_STEPS = Set.of("download", "deps", "register", "healthcheck");

    /** The step status value domain defaults to done. */
    static final Set<String> ENROLL_STEP_STATUSES = Set.of("running", "done", "failed");

 /** The status value domain is fixed by the migration artifact column comment as 0 unused, 1 redeemed, 2 expired, and 3 revoked. */
    static final int STATUS_PENDING = 0;
    static final int STATUS_USED = 1;
    static final int STATUS_EXPIRED = 2;
    static final int STATUS_REVOKED = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigCacheHolder configCacheHolder;
    private final EnrollTokenCodec tokenCodec;
    private final ClusterComponentIngestor clusterComponentIngestor;

    public ClusterEnrollTokenServiceImpl(NamedParameterJdbcTemplate jdbc,
                                         ConfigCacheHolder configCacheHolder,
                                         EnrollTokenCodec tokenCodec,
                                         ClusterComponentIngestor clusterComponentIngestor) {
        this.jdbc = jdbc;
        this.configCacheHolder = configCacheHolder;
        this.tokenCodec = tokenCodec;
        this.clusterComponentIngestor = clusterComponentIngestor;
    }

    @Override
    @Audit(objectType = "cluster_enroll_token", action = "issue", objectId = "#result.id()")
    public EnrollTokenIssuedView issue(EnrollTokenIssueInput input) {
        String role = input.role() == null ? "" : input.role().trim();
        if (!ROLES.contains(role)) {
            throw new ServiceException("入池令牌角色必须是 executor / ai-node / platform 之一"
                    + "（当前：" + (role.isEmpty() ? "未提供" : role) + "）"
                    + "——请管理员经「入池令牌签发」接口（POST /internal/cluster/enroll-tokens）重新签发，role 传三者之一",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int maxUses = input.maxUses() == null ? 1 : input.maxUses();
        if (maxUses < 1) {
            throw new ServiceException("可核销次数至少 1 次（默认一次一机）"
                    + "——请把签发请求的 maxUses 改为 ≥1 的整数（格式：如 1 或 5）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int ttlSeconds = ttlSeconds();
        assertActorAllowed(input.actorRole());
        String plaintext = tokenCodec.newPlaintextToken();
        List<Long> ids = jdbc.query("""
                INSERT INTO tp_cluster_enroll_token
                    (token_digest, role, project_scope, max_uses, status, expires_at, remark)
                VALUES (:digest, :role, :scope, :maxUses, 0, now() + make_interval(secs => :ttl), :remark)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("digest", tokenCodec.digest(plaintext))
                .addValue("role", role)
                .addValue("scope", trimToNull(input.projectScope()))
                .addValue("maxUses", maxUses)
                .addValue("ttl", ttlSeconds)
                .addValue("remark", trimToNull(input.remark())),
                (rs, n) -> rs.getLong(1));
        Long id = ids.get(0);
        LocalDateTime expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM tp_cluster_enroll_token WHERE id = :id",
                new MapSqlParameterSource("id", id), LocalDateTime.class);
        log.info("入池令牌已签发 id={} role={} ttl={}s（明文不落日志）", id, role, ttlSeconds);
        return new EnrollTokenIssuedView(id, plaintext, role, trimToNull(input.projectScope()),
                maxUses, expiresAt);
    }

    @Override
    public EnrollAcceptedView enroll(String token, String fingerprint) {
        if (token == null || token.isBlank()) {
            throw new ServiceException("入池令牌不能为空——装机侧请经 installkit/enroll_client.py 发起入池（自动携带令牌）；"
                    + "接口直调请在「入池令牌核销」请求（POST /internal/cluster/enroll）传 token 参数",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new ServiceException("机器指纹不能为空（enroll 命令必须携带本机指纹）"
                    + "——装机侧请经 installkit/enroll_client.py 发起（自动携带本机指纹）；"
                    + "接口直调请传 fingerprint 参数（格式：installkit 装机时生成的本机指纹串）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (fingerprint.length() > 200) {
            throw new ServiceException("机器指纹超长（上限 200 字符）"
                    + "——请改用 installkit/enroll_client.py 生成的指纹串（格式：不超过 200 字符）后重新发起入池",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String digest = tokenCodec.digest(token.trim());
        ClusterEnrollToken row = findByDigest(digest);
        if (row == null) {
            throw new ServiceException("入池令牌无效或不存在——请管理员经「入池令牌签发」接口"
                    + "（POST /internal/cluster/enroll-tokens）重新签发一条令牌，装机侧再执行入池",
                    BizErrorCode.NOT_FOUND);
        }
        int rows = jdbc.update("""
                UPDATE tp_cluster_enroll_token
                SET used_count = used_count + 1,
                    bound_fingerprint = COALESCE(bound_fingerprint, :fp),
                    status = CASE WHEN used_count + 1 >= max_uses THEN 1 ELSE 0 END,
                    updated_at = now()
                WHERE id = :id AND status = 0 AND expires_at > now()
                  AND (bound_fingerprint IS NULL OR bound_fingerprint = :fp)
                """, new MapSqlParameterSource().addValue("id", row.getId()).addValue("fp", fingerprint));
        if (rows == 0) {
            rejectEnroll(row, fingerprint);
        }
        return new EnrollAcceptedView(row.getId(), row.getRole(), row.getProjectScope());
    }

    @Override
    public void heartbeat(String token, String fingerprint) {
        heartbeat(token, fingerprint, null, null, null, null);
    }

    @Override
    public EnrollHeartbeatView heartbeat(String token, String fingerprint, String step,
                                         String stepStatus, String message) {
        return heartbeat(token, fingerprint, step, stepStatus, message, null);
    }

    @Override
    public EnrollHeartbeatView heartbeat(String token, String fingerprint, String step,
                                         String stepStatus, String message, List<?> components) {
        if (token == null || token.isBlank()) {
            throw new ServiceException("入池令牌不能为空——心跳上报请携带 token 参数（装机侧由 installkit/enroll_client.py 自动附带，"
                    + "令牌取自「入池令牌签发」接口回执）", BizErrorCode.VALIDATION_ERROR);
        }
        ClusterEnrollToken row = findByDigest(tokenCodec.digest(token.trim()));
        if (row == null) {
            throw new ServiceException("入池心跳令牌无效或不存在——请核对心跳请求（POST /internal/cluster/enroll/heartbeat）"
                    + "的 token 是否为「入池令牌签发」回执原值；令牌已用尽或被撤销请重新签发", BizErrorCode.NOT_FOUND);
        }
        if (row.getBoundFingerprint() != null && !row.getBoundFingerprint().isBlank()
                && !row.getBoundFingerprint().equals(fingerprint)) {
            throw new ServiceException("心跳机器指纹与入池绑定指纹不一致（拒绝伪造进度）"
                    + "——请用首次入池的同一台机器经 installkit/enroll_client.py 上报（自动携带绑定指纹）；"
                    + "确需换机请管理员经「入池令牌签发」接口重新签发令牌",
                    BizErrorCode.FORBIDDEN);
        }
 // The step trailing key (03) enforces the value-domain gate and overwrites the last_step projection when a step is present, while using the old behavior of recording only to the ledger when absent.
        String normalizedStep = step == null || step.isBlank() ? null : step.trim();
        if (normalizedStep != null && !ENROLL_STEPS.contains(normalizedStep)) {
            throw new ServiceException("装机步骤键必须是 download / deps / register / healthcheck"
                    + " 之一（当前：" + normalizedStep + "）——请按 03 协议把 step 改为四键之一"
                    + "（正确写法：download、deps、register、healthcheck），脚本侧禁自造步骤名",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String normalizedStatus = stepStatus == null || stepStatus.isBlank()
                ? "done" : stepStatus.trim();
        if (normalizedStep != null && !ENROLL_STEP_STATUSES.contains(normalizedStatus)) {
            throw new ServiceException("装机步骤状态只能是 running / done / failed（当前："
                    + normalizedStatus + "）——请改为三态之一（正确写法：running、done、failed）后重新上报",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String stepJson = null;
        if (normalizedStep != null) {
            Map<String, Object> projection = new java.util.LinkedHashMap<>();
            projection.put("step", normalizedStep);
            projection.put("stepStatus", normalizedStatus);
            projection.put("message", message == null ? null
                    : message.length() > 500 ? message.substring(0, 500) : message);
            projection.put("at", java.time.OffsetDateTime.now().toString());
            try {
                stepJson = STEP_MAPPER.writeValueAsString(projection);
            } catch (Exception e) {
                throw new ServiceException("装机步骤投影序列化失败——本次步骤未落账但心跳主链不受影响；"
                        + "请经「入池心跳」请求（POST /internal/cluster/enroll/heartbeat）重新上报该步骤一次，"
                        + "持续失败请携时间点与步骤名反馈平台侧排查",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        jdbc.update("""
                UPDATE tp_cluster_enroll_token
                SET updated_at = now(),
                    last_step = COALESCE(CAST(:stepJson AS jsonb), last_step)
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", row.getId())
                .addValue("stepJson", stepJson));
        // package C (03 §2.8 trailing components key): the host agent's
        // platform-family report lands on the SAME ledger channel as executor
        // heartbeats (ClusterComponentIngestor → upsert, one ledger one spectrum).
        // Missing/empty = zero calls (old reporters zero drift); any ingest
        // failure degrades to a log line — the heartbeat main chain never breaks.
        if (components != null && !components.isEmpty()) {
            try {
                clusterComponentIngestor.ingest("token:" + row.getId(), components);
            } catch (RuntimeException e) {
                log.warn("装机心跳部件扩报入账失败（打点主链零影响）: tokenId={} cause={}",
                        row.getId(), e.getMessage());
            }
        }
        Map<String, Object> lastStep = readLastStep(row.getId());
        return new EnrollHeartbeatView(Boolean.TRUE, row.getStatus(), lastStep);
    }

    /** The latest checkpoint projection readback overwrites a single row, and malformed JSON returns an honest null without breaking the observability surface. */
    private Map<String, Object> readLastStep(Long tokenId) {
        List<String> jsons = jdbc.query("SELECT last_step::text FROM tp_cluster_enroll_token"
                        + " WHERE id = :id",
                new MapSqlParameterSource("id", tokenId), (rs, n) -> rs.getString(1));
        if (jsons.isEmpty() || jsons.get(0) == null || jsons.get(0).isBlank()) {
            return null;
        }
        try {
            return STEP_MAPPER.readValue(jsons.get(0), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @Audit(objectType = "cluster_enroll_token", action = "revoke", objectId = "#id")
    public void revoke(Long id) {
        ClusterEnrollToken row = findById(id);
        if (row == null) {
            throw new ServiceException("入池令牌不存在（id=" + id + "）——令牌 id 请在「入池令牌签发」回执记录核对；"
                    + "确需新令牌请管理员经 POST /internal/cluster/enroll-tokens 重新签发", BizErrorCode.NOT_FOUND);
        }
        int rows = jdbc.update("UPDATE tp_cluster_enroll_token SET status = 3, updated_at = now()"
                        + " WHERE id = :id AND status = 0",
                new MapSqlParameterSource("id", id));
        if (rows == 0) {
            throw new ServiceException("仅待用中的令牌可撤销（当前状态：" + statusLabel(row.getStatus()) + "）"
                    + "——仅「待用」状态可撤销，已核销/已过期/已撤销均为终态；"
                    + "如需继续装机请管理员经「入池令牌签发」接口重新签发",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
    }

    @Override
    public List<EnrollTokenView> list() {
        // Ledger = action view of live rows: unconsumed-and-unexpired tokens
        // plus redeemed history. Revoked/expired rows leave the view without
        // waiting for the sweeper to flip the status (wizard e2e contract
        // "revoke -> this run's row is gone"; DB rows stay for audit).
        return jdbc.query("""
                SELECT id, role, project_scope, max_uses, used_count, status,
                       bound_fingerprint, expires_at, remark, created_at, updated_at
                FROM tp_cluster_enroll_token
                WHERE (status = 0 AND expires_at > now()) OR status = 1
                ORDER BY created_at DESC, id DESC
                """, new MapSqlParameterSource(), new BeanPropertyRowMapper<>(ClusterEnrollToken.class))
                .stream()
                .map(t -> new EnrollTokenView(t.getId(), t.getRole(), t.getProjectScope(),
                        t.getMaxUses(), t.getUsedCount(), t.getStatus(), t.getBoundFingerprint(),
                        t.getExpiresAt(), t.getRemark(), t.getCreatedAt(), t.getUpdatedAt()))
                .toList();
    }

    /** On redemption contention failure, the row is read back to classify each cause and reject with human-readable messages, and expired hits flip to EXPIRED with a trace. */
    private void rejectEnroll(ClusterEnrollToken row, String fingerprint) {
        ClusterEnrollToken fresh = findById(row.getId());
        if (fresh == null) {
            throw new ServiceException("入池令牌不存在（并发删除）——令牌刚被撤销或删除，无需重试；"
                    + "如需继续装机请管理员经「入池令牌签发」接口（POST /internal/cluster/enroll-tokens）重新签发",
                    BizErrorCode.NOT_FOUND);
        }
        if (fresh.getStatus() == STATUS_USED) {
            throw new ServiceException("入池令牌已核销用完——请管理员经「入池令牌签发」接口"
                    + "（POST /internal/cluster/enroll-tokens）重新签发一条令牌，装机侧再执行入池",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (fresh.getStatus() == STATUS_REVOKED) {
            throw new ServiceException("入池令牌已被撤销——撤销不可恢复；如需继续装机请管理员"
                    + "经「入池令牌签发」接口（POST /internal/cluster/enroll-tokens）重新签发",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (fresh.getExpiresAt().isBefore(LocalDateTime.now())) {
            jdbc.update("UPDATE tp_cluster_enroll_token SET status = 2, updated_at = now()"
                            + " WHERE id = :id AND status = 0",
                    new MapSqlParameterSource("id", fresh.getId()));
            throw new ServiceException("入池令牌已过期（TTL 内未完成装机）——请管理员经「入池令牌签发」接口"
                    + "（POST /internal/cluster/enroll-tokens）重新签发，装机侧再执行入池；"
                    + "有效期可在「系统配置」调大配置项 installkit.token-ttl-seconds",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (fresh.getBoundFingerprint() != null && !fresh.getBoundFingerprint().equals(fingerprint)) {
            throw new ServiceException("机器指纹与令牌首绑指纹不一致（一次一机，禁止跨机复用）"
                    + "——请在首次入池的同一台机器上执行（installkit/enroll_client.py 自动携带绑定指纹）；"
                    + "确需换机请管理员经「入池令牌签发」接口重新签发令牌",
                    BizErrorCode.FORBIDDEN);
        }
        throw new ServiceException("入池令牌核销失败（并发竞争）——请稍后重试一次；"
                + "仍失败请经 GET /internal/cluster/enroll-tokens 查询令牌「已用次数与上限」确认是否被他机占用，"
                + "确需换机请重新签发令牌",
                BizErrorCode.STATE_MACHINE_REJECT);
    }

    private ClusterEnrollToken findByDigest(String digest) {
        List<ClusterEnrollToken> rows = jdbc.query(
                "SELECT id, token_digest, role, project_scope, max_uses, used_count, status,"
                        + " bound_fingerprint, expires_at, remark, created_at, updated_at"
                        + " FROM tp_cluster_enroll_token WHERE token_digest = :digest",
                new MapSqlParameterSource("digest", digest),
                new BeanPropertyRowMapper<>(ClusterEnrollToken.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

 /** The protocol-side digest parsing handles the 03 poll enroll form and a, returning null when not found without breaking the observability surface. */
    @Override
    public Long resolveTokenIdByDigest(String tokenDigest) {
        if (tokenDigest == null || tokenDigest.isBlank()) {
            return null;
        }
        ClusterEnrollToken row = findByDigest(tokenDigest.trim());
        return row == null ? null : row.getId();
    }

    private ClusterEnrollToken findById(Long id) {
        List<ClusterEnrollToken> rows = jdbc.query(
                "SELECT id, token_digest, role, project_scope, max_uses, used_count, status,"
                        + " bound_fingerprint, expires_at, remark, created_at, updated_at"
                        + " FROM tp_cluster_enroll_token WHERE id = :id",
                new MapSqlParameterSource("id", id),
                new BeanPropertyRowMapper<>(ClusterEnrollToken.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The image source is read live, with missing or blank values treated as an empty string for the official source, secondary caching prohibited, hot updates effective immediately, and following the same baseline as ttlSeconds. */
    @Override
    public String mirrorBaseUrl() {
        String raw = configCacheHolder.get(MIRROR_KEY);
        return raw == null ? "" : raw.trim();
    }

    /** The TTL is read live, with missing, invalid, or non-positive values using the fallback default identical to the seed, secondary caching prohibited, and hot updates effective immediately. */
    int ttlSeconds() {
        String raw = configCacheHolder.get(TTL_KEY);
        if (raw != null && !raw.isBlank()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Invalid values use the fallback conservative default, following the same baseline as the lease TTL tiers.
            }
        }
        return DEFAULT_TTL_SECONDS;
    }

    /**
 * The issuance whitelist gate uses the migration artifact key installkit.allow-roles, is read live with secondary caching prohibited, and takes effect immediately on hot updates.
     * A missing or blank key means no whitelist is configured and all roles are allowed, while the conservative default seed fixes the four roles for out-of-the-box use and tightening occurs by changing the key.
     * When the key exists, the CSV is trimmed and the operator role is matched exactly, and if the role is not in the list or is not provided, the system fails closed with human-readable rejection instead of 500.
     */
    void assertActorAllowed(String actorRole) {
        String raw = configCacheHolder.get(ALLOW_ROLES_KEY);
        if (raw == null || raw.isBlank()) {
            return;
        }
        // Case-insensitive comparison: session role codes arrive in the cased
        // form the identity provider stores (observed 2026-11-22: an admin
        // session presented "ADMIN" while the shipped CSV seed is lowercase,
        // so the exact-match gate refused every issuance in the browser).
        Set<String> allowed = Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (allowed.isEmpty()) {
            return;
        }
        String actor = actorRole == null ? "" : actorRole.trim().toLowerCase();
        if (actor.isEmpty()) {
            throw new ServiceException("签发装机入池令牌需要提供操作人角色（当前签发白名单已启用："
                    + String.join(" / ", allowed.stream().sorted().toList()) + "）"
                    + "——请在签发请求传 actorRole 参数（正确写法：白名单内角色名之一）",
                    BizErrorCode.FORBIDDEN);
        }
        if (!allowed.contains(actor)) {
            throw new ServiceException("当前角色「" + (actorRole == null ? "" : actorRole.trim()) + "」不在允许签发装机令牌的角色清单内（"
                    + String.join(" / ", allowed.stream().sorted().toList())
                    + "）——如需放开请系统管理员前往「系统配置」检索配置项 installkit.allow-roles，"
                    + "把该角色加入清单（格式：逗号分隔角色名）",
                    BizErrorCode.FORBIDDEN);
        }
    }

    private String statusLabel(int status) {
        return switch (status) {
            case STATUS_PENDING -> "待用";
            case STATUS_USED -> "已核销";
            case STATUS_EXPIRED -> "已过期";
            case STATUS_REVOKED -> "已撤销";
            default -> String.valueOf(status);
        };
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
