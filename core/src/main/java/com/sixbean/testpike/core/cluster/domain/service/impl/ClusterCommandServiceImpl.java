package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandInput;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandView;
import com.sixbean.testpike.core.cluster.domain.component.ComponentSupportRules;
import com.sixbean.testpike.core.cluster.domain.service.ClusterCommandService;
import com.sixbean.testpike.core.cluster.domain.service.ClusterEnrollTokenService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;


@Service
public class ClusterCommandServiceImpl implements ClusterCommandService {

    private static final Logger log = LoggerFactory.getLogger(ClusterCommandServiceImpl.class);

 /** Command-type domain (V172 four + V178 upgrade two, CHECK mirrors 03 §2.9 —
 * extending the set requires syncing the contract line in the same segment).
 * Since also carries the four platform/agent types (consumer =
 * installkit service-agent on the host). */
    static final Set<String> COMMAND_TYPES =
            Set.of("engine-install", "engine-uninstall", "engine-upgrade",
                    "engine-rollback", "scale-in", "node-remove",
                    "platform-service-upgrade", "platform-service-rollback",
                    "agent-upgrade", "agent-rollback",
                    "platform-service-control", "node-self-check");

 /** Start-stop action domain (03 §2.9 T294b; mirrors service_agent
     * _CONTROL_ACTIONS — the agent maps each verb onto systemctl verbatim). */
    static final Set<String> SERVICE_CONTROL_ACTIONS = Set.of("start", "stop", "restart");

    
    static final Set<String> TARGET_TYPES = Set.of("executor", "enroll-token");

 
    static final String TTL_KEY = "installkit.command-ttl-seconds";

 
    static final int DEFAULT_TTL_SECONDS = 86400;

    
    static final int RESULT_DETAIL_MAX = 2000;

 
    static final int STATUS_PENDING = 0;
    static final int STATUS_CLAIMED = 1;
    static final int STATUS_RUNNING = 2;
    static final int STATUS_SUCCESS = 3;
    static final int STATUS_FAILED = 4;
    static final int STATUS_REVOKED = 5;

    private static final String VIEW_COLUMNS = """
            id, command_type, target_type, target_id, payload::text, status, claimed_by,
            last_progress::text, result_detail, expires_at, created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigCacheHolder configCacheHolder;
    private final com.sixbean.testpike.core.executor.domain.component.ExecutorDrainCoordinator
            drainCoordinator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Lifecycle audit sink (enqueue/claim/complete rows ride the existing chain writer, R40 single source). */
    private final AuditLogService auditLogService;

    /** Enroll token resolver, reused for the receipts-side identity reconciliation
     *  (digest resolution + bound-fingerprint equality share one source with the
     *  claim leg; ClusterEnrollTokenService does not depend back on this class). */
    private final ClusterEnrollTokenService enrollTokenService;

    public ClusterCommandServiceImpl(NamedParameterJdbcTemplate jdbc,
                                     ConfigCacheHolder configCacheHolder,
                                     com.sixbean.testpike.core.executor.domain.component
                                             .ExecutorDrainCoordinator drainCoordinator,
                                     AuditLogService auditLogService,
                                     ClusterEnrollTokenService enrollTokenService) {
        this.jdbc = jdbc;
        this.configCacheHolder = configCacheHolder;
        this.drainCoordinator = drainCoordinator;
        this.auditLogService = auditLogService;
        this.enrollTokenService = enrollTokenService;
    }

    @Override
    @Transactional
    @Audit(objectType = "cluster_command", action = "enqueue", objectId = "#result.id()",
            after = "{commandType: #result.commandType(), targetType: #result.targetType(),"
                    + " targetId: #result.targetId(), operator: #input.operator()}")
    public ClusterCommandView enqueue(ClusterCommandInput input) {
        String type = input.commandType() == null ? "" : input.commandType().trim();
        if (!COMMAND_TYPES.contains(type)) {
            throw new ServiceException("命令型必须是 engine-install / engine-uninstall /"
                    + " engine-upgrade / engine-rollback / scale-in / node-remove /"
                    + " platform-service-upgrade / platform-service-rollback /"
                    + " agent-upgrade / agent-rollback /"
                    + " platform-service-control / node-self-check 之一（当前："
                    + (type.isEmpty() ? "未提供" : type)
                    + "）——请在「升级中心」→「升级计划」按上述命令型下发；确需新增命令型请先发布该命令型的动作契约与值域", BizErrorCode.VALIDATION_ERROR);
        }
        String targetType = input.targetType() == null ? "" : input.targetType().trim();
        if (!TARGET_TYPES.contains(targetType)) {
            throw new ServiceException("命令目标型只能是 executor 或 enroll-token（当前："
                    + (targetType.isEmpty() ? "未提供" : targetType) + "）——机器装机预装请选 enroll-token（入池令牌），已入池机器请选 executor（机器列表见「执行器池」）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long targetId = input.targetId();
        if (targetId == null) {
            throw new ServiceException("命令必须指明目标机器 id——升级命令请在「升级中心」新建计划的「升级范围」中选择目标机器；接口直调请传 target_id（机器 id 可在「执行器池」查看）", BizErrorCode.VALIDATION_ERROR);
        }
        if (("scale-in".equals(type) || "node-remove".equals(type)) && !"executor".equals(targetType)) {
            throw new ServiceException(type + " 命令目标必须是已入池执行器（enroll-token 目标"
                    + "没有派发面可排空）——请在「执行器池」确认该机器已入池在线后，把目标型改为 executor 重新下发", BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> payload = input.payload() == null ? Map.of() : input.payload();
        if (type.startsWith("engine-")) {
            assertEnginesKnown(payload);
        }
        if ("engine-rollback".equals(type)) {
            Object pin = payload.get("target_version");
            if (pin == null || String.valueOf(pin).isBlank()) {
                throw new ServiceException("engine-rollback 命令 payload 必含 target_version"
                        + "（回滚目标版本=装机台账账本旧版，禁凭空猜版本回滚）——回滚目标版本请在「升级中心」→「版本清单」查看该机上一已装版本后填写（格式：如 1.4.2）",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
 // : the four platform/agent types are consumed by the host service-agent
 // over the enroll-token heartbeat channel, never by the executor runner. Fail-closed at
 // enqueue — a command missing any execution element (service code / pinned
 // version / artifact url / sha256) is rejected here so the agent can never
 // receive one it would have to guess at (bad package never reaches the host).
 // T294b pack B: the two host-operation types ride the same agent channel but
 // carry NO artifact, so they are judged BEFORE the generic platform-service-
 // gate — otherwise the upgrade-element checks would misfire on a start-stop
 // command that legitimately has no target_version/artifact_url/sha256.
        if ("platform-service-control".equals(type)) {
            assertServiceControlWellFormed(targetType, payload);
        } else if ("node-self-check".equals(type)) {
            assertNodeSelfCheckWellFormed(targetType, payload);
        } else if (type.startsWith("platform-service-") || type.startsWith("agent-")) {
            assertPlatformCommandWellFormed(type, targetType, payload);
        }
        assertTargetExists(targetType, targetId, type);

        int ttlSeconds = ttlSeconds();
        List<ClusterCommandView> rows = jdbc.query("""
                INSERT INTO tp_cluster_command
                    (command_type, target_type, target_id, payload, status, expires_at, operator)
                VALUES (:type, :targetType, :targetId, CAST(:payload AS jsonb), 0,
                        now() + make_interval(secs => :ttl), :operator)
                RETURNING
                """ + VIEW_COLUMNS,
                new MapSqlParameterSource()
                        .addValue("type", type)
                        .addValue("targetType", targetType)
                        .addValue("targetId", targetId)
                        .addValue("payload", toJson(payload))
                        .addValue("ttl", ttlSeconds)
                        .addValue("operator", trimToNull(input.operator())),
                (rs, n) -> mapView(rs.getLong("id"), rs.getString("command_type"),
                        rs.getString("target_type"), rs.getLong("target_id"),
                        rs.getString("payload"), rs.getInt("status"),
                        (Long) rs.getObject("claimed_by"), rs.getString("last_progress"),
                        rs.getString("result_detail"),
                        rs.getObject("expires_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class)));
        ClusterCommandView view = rows.get(0);
        if ("scale-in".equals(type)) {
            drainCoordinator.beginDrain(targetId, "cmd:" + view.id());
        }
        log.info("机群命令已入队 id={} type={} target={}:{}（TTL={}s）",
                view.id(), type, targetType, targetId, ttlSeconds);
        return view;
    }

    @Override
    public List<ClusterCommandView> pollClaimed(long executorId, int limit) {
        return claim("""
                SELECT id FROM tp_cluster_command
                WHERE status = 0 AND expires_at > now()
                  AND target_type = 'executor' AND target_id = :targetId
                ORDER BY id LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, executorId, limit, executorId);
    }

    @Override
    public List<ClusterCommandView> pollClaimedForEnroll(long enrollTokenId, String fingerprint,
                                                         int limit) {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new ServiceException("等待加入机器领取命令必须携带本机指纹——装机侧请经 installkit/enroll_client.py 发起领取（自动携带本机指纹）；接口直调请传 fingerprint 参数（格式：installkit 装机时生成的本机指纹串）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Bound fingerprint of the token row must equal the caller fingerprint
        // (cross-machine dispatch guard; an unbound token owns nothing claimable).
        if (!enrollTokenFingerprintMatches(enrollTokenId, fingerprint)) {
            return List.of();
        }
        return claim("""
                SELECT id FROM tp_cluster_command
                WHERE status = 0 AND expires_at > now()
                  AND target_type = 'enroll-token' AND target_id = :targetId
                ORDER BY id LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, enrollTokenId, limit, enrollTokenId);
    }

    
    private List<ClusterCommandView> claim(String candidateSql, long targetId, int limit,
                                           long claimerId) {
        int capped = Math.max(1, Math.min(limit, 20));
        String sql = "UPDATE tp_cluster_command"
                + " SET status = 1, claimed_by = :claimerId, claimed_at = now(), updated_at = now()"
                + " WHERE id IN (" + candidateSql + ")"
                + " RETURNING " + VIEW_COLUMNS;
        List<ClusterCommandView> views = jdbc.query(sql,
                new MapSqlParameterSource().addValue("targetId", targetId)
                        .addValue("limit", capped)
                        .addValue("claimerId", claimerId),
                (rs, n) -> mapView(rs.getLong("id"), rs.getString("command_type"),
                        rs.getString("target_type"), rs.getLong("target_id"),
                        rs.getString("payload"), rs.getInt("status"),
                        (Long) rs.getObject("claimed_by"), rs.getString("last_progress"),
                        rs.getString("result_detail"),
                        rs.getObject("expires_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class)));
        // Claim is a protocol-plane write: one attributed audit row per claimed command keeps
        // dispatch and consumption separable in the trail even after the live row has moved on.
        for (ClusterCommandView v : views) {
            auditLogService.record(new AuditLogInput(null, null, "cluster_command",
                    String.valueOf(v.id()), "claim",
                    Map.of("commandType", v.commandType(), "targetType", v.targetType(),
                            "statusBefore", 0),
                    Map.of("claimedBy", claimerId, "targetId", v.targetId())));
        }
        return views;
    }

    @Override
    public void progress(long commandId, long claimerId, String step, String message) {
        progress(commandId, claimerId, step, message, null, null);
    }

    /**
     * Progress advance in identity form (03 §2.9 receipts half of the bidirectional
     * authentication contract). Enroll-token targets must reconcile the presented
     * token digest + fingerprint against the claimed token row before any write;
     * executor targets ignore the identity keys (documented intranet-trust shape).
     */
    @Override
    @Transactional
    public void progress(long commandId, long claimerId, String step, String message,
                         String tokenDigest, String fingerprint) {
        assertEnrollReceiptIdentity(findRow(commandId), commandId, claimerId,
                tokenDigest, fingerprint);
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("step", step == null ? "" : step);
        projection.put("message", message == null ? null : truncate(message, 500));
        projection.put("at", LocalDateTime.now().toString());
        int rows = jdbc.update("""
                UPDATE tp_cluster_command
                SET status = 2, last_progress = CAST(:prog AS jsonb), updated_at = now()
                WHERE id = :id AND status IN (1, 2) AND claimed_by = :claimerId
                """, new MapSqlParameterSource().addValue("id", commandId)
                .addValue("prog", toJson(projection))
                .addValue("claimerId", claimerId));
        if (rows == 0) {
            rejectTransition(commandId, claimerId, "推进进度");
        }
    }

    @Override
    @Transactional
    @Audit(objectType = "cluster_command", action = "complete", objectId = "#commandId")
    public void complete(long commandId, long claimerId, boolean success, String resultDetail) {
        complete(commandId, claimerId, success, resultDetail, null, null);
    }

    /**
     * Final receipt in identity form (03 §2.9 receipts half); enforcement scope
     * mirrors the progress overload. The reconciliation runs before the audit
     * context and the CAS update, so a refused attempt never changes state.
     */
    @Override
    @Transactional
    @Audit(objectType = "cluster_command", action = "complete", objectId = "#commandId")
    public void complete(long commandId, long claimerId, boolean success, String resultDetail,
                         String tokenDigest, String fingerprint) {
        Map<String, Object> row = findRow(commandId);
        assertEnrollReceiptIdentity(row, commandId, claimerId, tokenDigest, fingerprint);
        if (row != null) {
            // Receipt attribution: the audit row alone re-states the command category, the
            // pre-receipt status and the honest outcome (category-wise full audit trail).
            Map<String, Object> prior = new LinkedHashMap<>();
            prior.put("commandType", row.get("command_type"));
            prior.put("targetType", row.get("target_type"));
            prior.put("targetId", row.get("target_id"));
            prior.put("statusBefore", row.get("status"));
            AuditLogContext.setBefore(prior);
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("success", success);
            outcome.put("claimerId", claimerId);
            outcome.put("detail", resultDetail == null ? null
                    : truncate(resultDetail, RESULT_DETAIL_MAX));
            AuditLogContext.setAfter(outcome);
        }
        if (row != null && asInt(row.get("status")) >= STATUS_SUCCESS) {
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        int rows = jdbc.update("""
                UPDATE tp_cluster_command
                SET status = :finalStatus, result = CAST(:result AS jsonb),
                    result_detail = :detail, updated_at = now()
                WHERE id = :id AND status IN (1, 2) AND claimed_by = :claimerId
                """, new MapSqlParameterSource().addValue("id", commandId)
                .addValue("finalStatus", success ? STATUS_SUCCESS : STATUS_FAILED)
                .addValue("result", toJson(result))
                .addValue("detail", resultDetail == null ? null
                        : truncate(resultDetail, RESULT_DETAIL_MAX))
                .addValue("claimerId", claimerId));
        if (rows == 0) {
            rejectTransition(commandId, claimerId, "回执");
        }
        if (row != null && success && "scale-in".equals(row.get("command_type"))
                && "executor".equals(row.get("target_type"))) {
            drainCoordinator.finishDrain(((Number) row.get("target_id")).longValue(),
                    "cmd:" + commandId);
        }
    }

    @Override
    @Transactional
    @Audit(objectType = "cluster_command", action = "revoke", objectId = "#commandId")
    public void revokePending(long commandId) {
        int rows = jdbc.update("""
                UPDATE tp_cluster_command SET status = 5, updated_at = now()
                WHERE id = :id AND status = 0
                """, new MapSqlParameterSource("id", commandId));
        if (rows == 0) {
            Map<String, Object> row = findRow(commandId);
            if (row == null) {
                throw new ServiceException("机群命令不存在（id=" + commandId + "）——请在「升级中心」→「升级计划」刷新列表确认命令在册；超过待下发时限的命令会自动出队",
                        BizErrorCode.NOT_FOUND);
            }
            throw new ServiceException("仅待下发中的命令可撤销（当前："
                    + statusLabel(asInt(row.get("status"))) + "——已领取的命令执行侧可能已在装机，"
                    + "禁撤回以免半途而废）——可在「升级中心」→「升级计划」该行「状态」查看当前态；仅「待下发」状态可撤销", BizErrorCode.STATE_MACHINE_REJECT);
        }
    }

    @Override
    public List<ClusterCommandView> list(Long targetExecutorId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = "";
        if (targetExecutorId != null) {
            where = " WHERE target_type = 'executor' AND target_id = :targetId";
            params.addValue("targetId", targetExecutorId);
        }
        return jdbc.query("SELECT " + VIEW_COLUMNS + " FROM tp_cluster_command" + where
                + " ORDER BY id DESC", params,
                (rs, n) -> mapView(rs.getLong("id"), rs.getString("command_type"),
                        rs.getString("target_type"), rs.getLong("target_id"),
                        rs.getString("payload"), rs.getInt("status"),
                        (Long) rs.getObject("claimed_by"), rs.getString("last_progress"),
                        rs.getString("result_detail"),
                        rs.getObject("expires_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class)));
    }


    
    private void assertEnginesKnown(Map<String, Object> payload) {
        Object raw = payload.get("engines");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException("装/卸引擎命令必须携带 engines 清单"
                    + "（引擎描述符 code 列表，如 [\"MOBILEWRIGHT\"]）——引擎 code 请在「引擎中心」查看，升级向导请在「引擎」多选区勾选（至少一个）", BizErrorCode.VALIDATION_ERROR);
        }
        for (Object code : list) {
            String engineCode = code == null ? "" : String.valueOf(code).trim();
            Integer known = jdbc.queryForObject(
                    "SELECT count(*) FROM tp_appauto_engine WHERE code = :code",
                    new MapSqlParameterSource("code", engineCode), Integer.class);
            if (known == null || known == 0) {
                throw new ServiceException("引擎「" + engineCode + "」不在引擎描述符目录"
                        + "（tp_appauto_engine）——请先在「引擎中心」确认该引擎 code 已登记，再重新下发",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

 /** sha256 as 64 hex chars (the agent re-verifies before swapping; a malformed
    or missing digest is rejected at enqueue, never queued for rejection later). */
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

 /** Host-operation channel gate (T294b pack B, shared by both operation
  *  types): the consumer is the installkit service-agent over the
  *  enroll-token heartbeat channel. The executor runner never consumes
  *  these types, so an executor target is rejected before enqueue instead
  *  of burning a command nobody will claim. */
 private void assertHostOperationChannel(String targetType, String type) {
     if (!"enroll-token".equals(targetType)) {
         throw new ServiceException(type + " 命令目标必须是已入池主机令牌（enroll-token）"
                 + "——主机运维命令由 service-agent 经心跳通道消费，executor 通道无此消费方，"
                 + "请管理员经「入池令牌签发」接口（POST /internal/cluster/enroll-tokens）"
                 + "为该主机签发令牌后重新下发", BizErrorCode.VALIDATION_ERROR);
     }
 }

 /** Managed-family gate for an operation payload; the single judgment
  *  source stays ComponentSupportRules.PLATFORM_RELEASE_FAMILIES (R40 —
  *  zero new decision families). optional=true (node-self-check) reads an
  *  absent code as the honest fleet-wide default; any stray code is always
  *  refused naming the offending value and the valid list (R63). */
 private void assertManagedServiceCode(Map<String, Object> payload, boolean optional) {
     Object svc = payload.get("service");
     String code = svc == null ? "" : String.valueOf(svc).trim();
     if (code.isEmpty()) {
         if (optional) {
             return;
         }
         throw new ServiceException("启停命令 payload 必含 service（平台族码，当前：未提供）"
                 + "——有效服务码：" + String.join(" / ",
                 ComponentSupportRules.PLATFORM_RELEASE_FAMILIES)
                 + "；服务码可在「升级中心」→「版本清单」核对已发布族（格式：如 core）",
                 BizErrorCode.VALIDATION_ERROR);
     }
     if (!ComponentSupportRules.PLATFORM_RELEASE_FAMILIES.contains(code)) {
         throw new ServiceException("平台服务码「" + code + "」不在平台可发布族（有效值："
                 + String.join(" / ", ComponentSupportRules.PLATFORM_RELEASE_FAMILIES)
                 + "，当前：" + code + "）——可用服务码请在「升级中心」→「版本清单」核对已发布件；"
                 + "确需新增平台服务请先扩族谱定义处并发布后再入队", BizErrorCode.VALIDATION_ERROR);
     }
 }

 /** platform-service-control enqueue gate: agent channel + managed service
  *  code + action verb (start/stop/restart — the same domain service_agent
  *  maps onto systemctl). Never touches the artifact checks on purpose. */
 private void assertServiceControlWellFormed(String targetType, Map<String, Object> payload) {
     assertHostOperationChannel(targetType, "platform-service-control");
     assertManagedServiceCode(payload, false);
     Object act = payload.get("action");
     String action = act == null ? "" : String.valueOf(act).trim();
     if (!SERVICE_CONTROL_ACTIONS.contains(action)) {
         throw new ServiceException("启停命令的 action 必须是 start / stop / restart 之一（当前："
                 + (action.isEmpty() ? "未提供" : action)
                 + "）——请把取值改成三者之一后重新下发：接口直调经 POST /internal/cluster/commands/enqueue 传字段「payload.action」，"
                 + "动作只作用于该族在册 systemd 单元", BizErrorCode.VALIDATION_ERROR);
     }
 }

 /** node-self-check enqueue gate: agent channel + optional service filter
  *  (absent = check every managed family on the host). */
 private void assertNodeSelfCheckWellFormed(String targetType, Map<String, Object> payload) {
     assertHostOperationChannel(targetType, "node-self-check");
     assertManagedServiceCode(payload, true);
 }

 /**
 * fail-closed enqueue gate for the four platform/agent command types
 * (03 §2.9, design 8A.3).
 * <p>Consumer = installkit service-agent over the enroll-token heartbeat
 * channel (agent and executor runner coexist as separate units, so an
 * executor-target platform command would burn unconsumed). The service-code
 * domain reads the single source ComponentSupportRules.PLATFORM_RELEASE_FAMILIES
 * — zero new judgment families.
 */
    private void assertPlatformCommandWellFormed(String type, String targetType,
                                                 Map<String, Object> payload) {
        if (!"enroll-token".equals(targetType)) {
            throw new ServiceException(type + " 命令目标必须是已入池主机令牌（enroll-token）"
                    + "——平台服务由主机 service-agent 消费，executor 通道无此消费方，"
                    + "请管理员经「入池令牌签发」接口（POST /internal/cluster/enroll-tokens）为该主机签发新令牌后重新下发", BizErrorCode.VALIDATION_ERROR);
        }
        Object pin = payload.get("target_version");
        if (pin == null || String.valueOf(pin).isBlank()) {
            throw new ServiceException(type + " 命令 payload 必含 target_version"
                    + "（升级/回滚目标版本=发布台账钉版，禁凭空猜版本换包）——目标版本请在「升级中心」→「版本清单」选择已登记版本（格式：如 2026.11.1-r1）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Object url = payload.get("artifact_url");
        if (url == null || String.valueOf(url).isBlank()) {
            throw new ServiceException(type + " 命令 payload 必含 artifact_url"
                    + "（部署包下载地址，缺件无包可换）——下载地址请取自「版本清单」该版本发布件（格式：https:// 开头的完整地址）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Object digest = payload.get("sha256");
        if (digest == null || !SHA256_HEX.matcher(String.valueOf(digest)).matches()) {
            throw new ServiceException(type + " 命令 payload 的 sha256 必须是 64 位十六进制摘要"
                    + "（agent 换包前强制校验，坏包绝不上机——缺摘要=不可验证）——摘要请取发布件校验清单登记值（正确写法：64 位 0-9/a-f 十六进制字符串）",
                    BizErrorCode.VALIDATION_ERROR);
        }
 // platform types also gate the service code against the publishable
 // families (single judgment source — stray codes like "mysqld" never queue)
        if (type.startsWith("platform-service-")) {
            Object service = payload.get("service");
            String code = service == null ? "" : String.valueOf(service).trim();
            if (!ComponentSupportRules.PLATFORM_RELEASE_FAMILIES.contains(code)) {
                throw new ServiceException("platform 服务码「" + (code.isEmpty() ? "未提供" : code)
                        + "」不在平台可发布族（"
                        + String.join(" / ", ComponentSupportRules.PLATFORM_RELEASE_FAMILIES)
                        + "）——可用服务码请在「升级中心」→「版本清单」核对已发布件；确需新增平台服务请先扩族谱定义处并发布后再入队",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    
    private void assertTargetExists(String targetType, Long targetId, String type) {
        if ("executor".equals(targetType)) {
            Integer exists = jdbc.queryForObject(
                    "SELECT count(*) FROM tp_executor WHERE id = :id",
                    new MapSqlParameterSource("id", targetId), Integer.class);
            if (exists == null || exists == 0) {
                throw new ServiceException("目标执行器不存在（id=" + targetId
                        + "）——请在「执行器池」确认该执行器已注册在线（执行器启动后自动注册入列）", BizErrorCode.NOT_FOUND);
            }
        } else {
            List<Map<String, Object>> tokens = jdbc.queryForList(
                    "SELECT status, (expires_at > now()) AS alive FROM tp_cluster_enroll_token"
                            + " WHERE id = :id",
                    new MapSqlParameterSource("id", targetId));
            if (tokens.isEmpty()) {
                throw new ServiceException("目标入池令牌不存在（id=" + targetId + "）——令牌 id 请在「入池令牌签发」回执记录查找；确需新令牌请管理员重新签发",
                        BizErrorCode.NOT_FOUND);
            }
            int status = asInt(tokens.get(0).get("status"));
            boolean alive = Boolean.TRUE.equals(tokens.get(0).get("alive"));
            if (status == 3) {
                throw new ServiceException("该入池令牌已撤销，不再向其目标机器下发命令（type="
                        + type + "）——请管理员经「入池令牌签发」接口（POST /internal/cluster/enroll-tokens）重新签发，机器再执行装机入池", BizErrorCode.STATE_MACHINE_REJECT);
            }
            if (status == 2 || !alive) {
                throw new ServiceException("该入池令牌已过期，不再下发预装命令——请管理员经「入池令牌签发」接口（POST /internal/cluster/enroll-tokens）重新签发，机器再执行装机入池",
                        BizErrorCode.STATE_MACHINE_REJECT);
            }
        }
    }

    
    private void rejectTransition(long commandId, long claimerId, String action) {
        Map<String, Object> row = findRow(commandId);
        if (row == null) {
            throw new ServiceException("机群命令不存在（id=" + commandId + "）——请在「升级中心」→「升级计划」刷新列表确认命令在册；超过待下发时限的命令会自动出队",
                    BizErrorCode.NOT_FOUND);
        }
        int status = asInt(row.get("status"));
        if (status == STATUS_PENDING) {
            throw new ServiceException("命令尚未被本机领取，不能" + action + "（请先 poll 领取）——机器侧领取入口为「领取命令」接口（POST /internal/cluster/commands/poll），领取后方可回执",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (status == STATUS_REVOKED) {
            throw new ServiceException("命令已撤销，" + action + "不再受理——如需继续变更请在「升级中心」→「升级计划」重新下发命令",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (status >= STATUS_SUCCESS) {
            throw new ServiceException("命令已终态（" + statusLabel(status) + "），" + action
                    + "被拒——回执已在「升级中心」→「升级计划」该命令行留存，请查看回执后再决定是否重新下发", BizErrorCode.STATE_MACHINE_REJECT);
        }
        throw new ServiceException("只有领取该命令的机器才能" + action + "（当前领取者不是本机）——命令回执由领取机独占，换机请在「升级中心」→「升级计划」重新下发命令",
                BizErrorCode.FORBIDDEN);
    }

    /**
     * Receipts-side identity reconciliation for enroll-token targets
     * (03 §2.9, the second half of the bidirectional authentication contract):
     * the digest must resolve to the very token row recorded as claimed_by and the
     * presented fingerprint must equal the fingerprint bound on that row. Missing
     * or mismatched identity is refused on the receipt write path (fail-closed,
     * never a silent pass); executor targets keep the documented intranet-trust
     * shape with zero drift — neither widened nor relaxed here.
     */
    private void assertEnrollReceiptIdentity(Map<String, Object> row, long commandId,
                                             long claimerId, String tokenDigest,
                                             String fingerprint) {
        if (row == null || !"enroll-token".equals(String.valueOf(row.get("target_type")))) {
            return;
        }
        boolean hasDigest = tokenDigest != null && !tokenDigest.isBlank();
        boolean hasFingerprint = fingerprint != null && !fingerprint.isBlank();
        if (!hasDigest || !hasFingerprint) {
            throw new ServiceException("面向等待加入机器的命令回执必须携带令牌摘要与本机指纹"
                    + "（本次请求：commandId=" + commandId + "，claimerId=" + claimerId
                    + "，tokenDigest=" + (hasDigest ? "已携带" : "未携带")
                    + "，fingerprint=" + (hasFingerprint ? "已携带" : "未携带") + "）——"
                    + "装机侧请用「installkit/enroll_client.py poll --ack」或保持 service_agent 常驻，"
                    + "两形态自动携带本机身份；接口直调请在该回执请求体补 tokenDigest"
                    + "（入池令牌明文的 SHA-256 小写十六进制摘要）与 fingerprint"
                    + "（入池时生成的本机指纹，可经 installkit/enroll_client.py fingerprint 查看）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long tokenId = enrollTokenService.resolveTokenIdByDigest(tokenDigest);
        if (tokenId == null) {
            throw new ServiceException("该回执的令牌摘要没有对应的入池令牌（令牌可能已核销，"
                    + "或摘要不是领取本命令的机器所持令牌）——请用领取该命令的机器自己的令牌"
                    + "重新发起本回执；指纹核对方式：「installkit/enroll_client.py fingerprint」查看后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (tokenId.longValue() != claimerId) {
            throw new ServiceException("回执令牌与领取令牌不是同一枚（摘要解析出的令牌 id=" + tokenId
                    + "，命令记录的实际领取方 id=" + claimerId + "）——只有领取该命令的机器本人可以回执，"
                    + "请改用领取机自己的令牌重新发起本回执（回执请求体补「tokenDigest」与「fingerprint」字段）",
                    BizErrorCode.FORBIDDEN);
        }
        if (!enrollTokenFingerprintMatches(tokenId, fingerprint)) {
            throw new ServiceException("本机指纹与该令牌入池时绑定的指纹不一致（疑似他机持同枚令牌摘要代回）——"
                    + "请在入池时的原机器上回执；本机指纹可经「installkit/enroll_client.py fingerprint」查看核对",
                    BizErrorCode.FORBIDDEN);
        }
    }

    /** Bound-fingerprint equality on the enroll token row (one source for the
     *  claim leg and the receipts leg, 03 §2.8/§2.9; R40 — no second copy). */
    private boolean enrollTokenFingerprintMatches(long tokenId, String fingerprint) {
        Integer matched = jdbc.queryForObject("""
                SELECT count(*) FROM tp_cluster_enroll_token
                WHERE id = :id AND bound_fingerprint = :fp
                """, new MapSqlParameterSource().addValue("id", tokenId)
                .addValue("fp", fingerprint), Integer.class);
        return matched != null && matched > 0;
    }

    private Map<String, Object> findRow(long commandId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, command_type, target_type, target_id FROM tp_cluster_command"
                        + " WHERE id = :id", new MapSqlParameterSource("id", commandId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    int ttlSeconds() {
        String raw = configCacheHolder.get(TTL_KEY);
        if (raw != null && !raw.isBlank()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                log.warn("命令队列 TTL 键 {} 非法值「{}」，回退保守默认 {}", TTL_KEY, raw,
                        DEFAULT_TTL_SECONDS);
            }
        }
        return DEFAULT_TTL_SECONDS;
    }

    private static String statusLabel(int status) {
        return switch (status) {
            case STATUS_PENDING -> "待下发";
            case STATUS_CLAIMED -> "已领取";
            case STATUS_RUNNING -> "执行中";
            case STATUS_SUCCESS -> "成功";
            case STATUS_FAILED -> "失败";
            case STATUS_REVOKED -> "已撤销";
            default -> "未知(" + status + ")";
        };
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : -1;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private ClusterCommandView mapView(Long id, String commandType, String targetType,
                                       Long targetId, String payloadJson, Integer status,
                                       Long claimedBy, String progressJson, String resultDetail,
                                       LocalDateTime expiresAt, LocalDateTime createdAt) {
        Map<String, Object> payload = fromJson(payloadJson);
        return new ClusterCommandView(id, commandType, targetType, targetId,
                payload == null ? Map.of() : payload, status, claimedBy,
                fromJson(progressJson), resultDetail, expiresAt, createdAt);
    }
}
