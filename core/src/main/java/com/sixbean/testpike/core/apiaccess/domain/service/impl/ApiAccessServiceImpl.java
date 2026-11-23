package com.sixbean.testpike.core.apiaccess.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessEndpointView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessVerdict;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteRequest;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteResult;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteTarget;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadResult;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessTarget;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessVerdict;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessCatalogRelevance;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessDial;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessDomainGrouper;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessMcpToolView;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessGovernanceRepository;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessIdentityAnchor;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessMcpToolProjector;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessPolicy;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessPermissionGate;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessQuotaLedger;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessRequestFactory;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessRowOwnership;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiEndpointCatalog;
import com.sixbean.testpike.core.apiaccess.domain.service.ApiAccessService;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pass-through door over this application's own read endpoints.
 *
 * <p>Dispatch shape: the catalog entry is resolved first, then the policy verdict, then the scope
 * guard, then the budget, and only then is a request sent — to this service's own loopback address,
 * carrying the acting operator in the trusted internal header. The model never supplies a URL, a
 * method, or an identity, which is what makes a generated catalog safe to expose.
 *
 * <p>Response discipline: ceilings on rows, bytes, timeout and catalog width are K8 keys so a
 * deployment can tighten or loosen them without a rebuild. An over-sized payload comes back as a
 * labelled summary rather than a clipped half-JSON document.
 */
@Service
public class ApiAccessServiceImpl implements ApiAccessService {

    /** Row ceiling inside one delivered payload (K8 hot-updatable). */
    public static final String KEY_ROW_MAX = "ai.api-access.row-max";

    /** Byte ceiling of the delivered payload (K8 hot-updatable). */
    public static final String KEY_MAX_BYTES = "ai.api-access.response-max-bytes";

    /** Target call timeout in milliseconds (K8 hot-updatable). */
    public static final String KEY_TIMEOUT_MS = "ai.api-access.timeout-ms";

    /** How many catalog rows one listing returns (K8 hot-updatable). */
    public static final String KEY_CATALOG_MAX = "ai.api-access.catalog-max-rows";

    /** How many domain rows the layered index view lists before folding the tail (K8 hot-updatable). */
    public static final String KEY_CATALOG_GROUP_MAX = "ai.api-access.catalog-group-max";

    private static final long DEFAULT_ROW_MAX = 100L;
    private static final long DEFAULT_MAX_BYTES = 40_000L;
    private static final long DEFAULT_TIMEOUT_MS = 8_000L;
    private static final long DEFAULT_CATALOG_MAX = 120L;
    private static final long DEFAULT_CATALOG_GROUP_MAX = 60L;

    /** Array keys the row ceiling looks for before falling back to a top-level array. */
    private static final List<String> LIST_KEYS = List.of("list", "records", "items");

    private static final Logger log = LoggerFactory.getLogger(ApiAccessServiceImpl.class);

    private final ApiEndpointCatalog catalog;
    private final ApiAccessPolicy policy;
    private final ApiAccessGovernanceRepository governance;
    private final ApiAccessMcpToolProjector mcpProjector;
    private final ApiAccessRequestFactory requestFactory;
    private final ApiAccessQuotaLedger quota;
    private final ProjectRoleResolver projectRoleResolver;
    private final AdminAlertRecipients adminAlertRecipients;
    private final ApiAccessRowOwnership rowOwnership;
    private final ApiAccessPermissionGate permissionGate;
    private final AuditLogService auditLogService;
    private final SystemConfigReader configReader;
    private final ObjectMapper objectMapper;
    private final String selfBase;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ApiAccessServiceImpl(ApiEndpointCatalog catalog,
                                ApiAccessPolicy policy,
                                ApiAccessGovernanceRepository governance,
                                ApiAccessRequestFactory requestFactory,
                                ApiAccessQuotaLedger quota,
                                ProjectRoleResolver projectRoleResolver,
                                AdminAlertRecipients adminAlertRecipients,
                                ApiAccessRowOwnership rowOwnership,
                                ApiAccessPermissionGate permissionGate,
                                AuditLogService auditLogService,
                                SystemConfigReader configReader,
                                ApiAccessMcpToolProjector mcpProjector,
                                ObjectMapper objectMapper,
                                @Value("${api.access.self-base-url:http://127.0.0.1:${server.port:8083}}")
                                String selfBase) {
        this.catalog = catalog;
        this.policy = policy;
        this.governance = governance;
        this.requestFactory = requestFactory;
        this.quota = quota;
        this.projectRoleResolver = projectRoleResolver;
        this.adminAlertRecipients = adminAlertRecipients;
        this.rowOwnership = rowOwnership;
        this.permissionGate = permissionGate;
        this.auditLogService = auditLogService;
        this.configReader = configReader;
        this.mcpProjector = mcpProjector;
        this.objectMapper = objectMapper;
        this.selfBase = selfBase;
    }

    @Override
    public List<ApiAccessMcpToolView> mcpTools() {
        return mcpProjector.tools();
    }

    @Override
    public List<ApiAccessEndpointView> catalog(String keyword, boolean onlyReachable) {
        Map<String, ApiAccessGovernanceView> overlay = governance.loadAll();
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<ApiAccessEndpointView> rows = new ArrayList<>();
        // Relevance tier per matched entry, captured here because the delivered view carries
        // neither the spec nor the overlay row. Only the truncating cut reads it (single consumer
        // of one ranking implementation, see ApiAccessCatalogRelevance).
        Map<String, Integer> tiers = new HashMap<>();
        // Reachable set BEFORE the keyword filter: the zero-hit guidance has to summarize the
        // domains the caller CAN drill into, which an empty filtered list would hide.
        List<ApiAccessEndpointView> reachableRows = new ArrayList<>();
        for (ApiEndpointSpec spec : catalog.endpoints().values()) {
            ApiAccessVerdict verdict = policy.decide(spec, overlay);
            if (onlyReachable && !verdict.allowed()) {
                continue;
            }
            ApiAccessGovernanceView row = overlay.get(spec.key());
            ApiAccessEndpointView view = new ApiAccessEndpointView(spec.key(), spec.httpMethod(),
                    spec.pathPattern(), addressableParams(spec), spec.writeClass(),
                    spec.projectAnchor() != null, verdict.allowed(), verdict.reason(),
                    describe(spec, row), spec.bodyType(), spec.bodyFields());
            if (verdict.allowed()) {
                reachableRows.add(view);
            }
            if (!needle.isEmpty() && !matches(spec, needle, overlay)) {
                continue;
            }
            ApiAccessCatalogRelevance.record(tiers, needle, spec, row);
            rows.add(view);
        }
        rows.sort(Comparator.comparing(ApiAccessEndpointView::key));
        if (!needle.isEmpty() && rows.isEmpty()) {
            // A miss never ships a bare empty array: the dialog model would only learn "nothing",
            // which invites inventing an entry name or dropping the conversation. The guidance row
            // says what to do next and counts the reachable domains through the SAME grouper the
            // layered index uses (single source, R40).
            String guidance = "目录内没有与「" + needle + "」匹配的条目；建议换个用词重试，"
                    + "或用下列域前缀作为 keyword 下钻（前缀即可命中路径检索）——"
                    + ApiAccessDomainGrouper.summaryText(ApiAccessDomainGrouper.group(reachableRows));
            return List.of(new ApiAccessEndpointView("__no_match__", "—", "—", List.of(),
                    false, false, false, guidance, guidance, null, List.of()));
        }
        long ceiling = configReader.getPositiveLong(KEY_CATALOG_MAX, DEFAULT_CATALOG_MAX);
        if (rows.size() > ceiling) {
            if (needle.isEmpty() && onlyReachable) {
                // A blank browse of a big ledger truncated to the first ceiling keys teaches the
                // model only which rows come first, never which families exist. Deliver a
                // per-domain index instead: counts come from the SAME grouper the guidance row
                // uses (R40 single source), and feeding a prefix back as the keyword drills
                // through the existing substring search — no new parameter, detail shapes
                // everywhere else (keyword given, blocked rows listed, or it fits) unchanged.
                int groupMax = (int) configReader.getPositiveLong(KEY_CATALOG_GROUP_MAX,
                        DEFAULT_CATALOG_GROUP_MAX);
                return ApiAccessDomainGrouper.indexRows(ApiAccessDomainGrouper.group(rows), groupMax);
            }
            // The cut decides which entries the dialog model ever gets to see, so it cuts by
            // relevance: entries whose registered synonyms name the keyword answer the caller,
            // description echoes do not. Name order stays inside one relevance band, so a kept row
            // reads exactly as it did before — only who survives the cut changes.
            List<ApiAccessEndpointView> ordered =
                    ApiAccessCatalogRelevance.orderForDelivery(rows, tiers);
            ApiAccessEndpointView last = ordered.get(ordered.size() - 1);
            List<ApiAccessEndpointView> trimmed = new ArrayList<>(ordered.subList(0, (int) ceiling));
            String cutNote = needle.isEmpty()
                    ? "本视图只交付前 " + ceiling + " 条（上限=系统配置页参数 " + KEY_CATALOG_MAX + "）"
                    : "本视图按与关键词的相关度排序后交付前 " + ceiling
                            + " 条（相同相关度内按条目名排序；上限=系统配置页参数 " + KEY_CATALOG_MAX + "）";
            trimmed.add(new ApiAccessEndpointView("__truncated__", "—", "—", List.of(), false, false,
                    false, "目录共 " + ordered.size() + " 条，" + cutNote + "，请带 keyword 精确检索",
                    last.description(), null, List.of()));
            return trimmed;
        }
        return rows;
    }

    @Override
    public ApiAccessReadResult read(Long operatorId, Long taskId, ApiAccessReadRequest request) {
        if (request == null || request.endpointKey() == null || request.endpointKey().isBlank()) {
            return ApiAccessReadResult.blocked(null, "未点名要读的条目（endpointKey 必填，先查目录）");
        }
        String key = request.endpointKey().trim();
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();

        ApiEndpointSpec spec = catalog.find(key);
        Map<String, ApiAccessGovernanceView> overlay = governance.loadAll();
        ApiAccessVerdict verdict = policy.decide(spec, overlay);
        if (!verdict.allowed()) {
            audit(operatorId, firstAnchor(spec, arguments), key, arguments, false, 0, false, -1, 0L, verdict.reason());
            return ApiAccessReadResult.blocked(key, verdict.reason());
        }

        // Identity-scoped entries are served strictly as the acting person: the door owns the
        // userId value (claimed ones are overwritten, absent ones filled), so "my projects" type
        // reads never need — and never accept — a model-named id. No addressable operator means
        // no honest scope to serve, refuse.
        if (spec.userAnchor() != null) {
            if (operatorId == null) {
                String reason = "该条目按登录人限定代读，本次调用没有可判定的操作人";
                audit(operatorId, firstAnchor(spec, arguments), key, arguments, false, 0, false, -1, 0L, reason);
                return ApiAccessReadResult.blocked(key, reason);
            }
            arguments = ApiAccessIdentityAnchor.restamp(spec, arguments, operatorId);
        }

        // The dialog link stamps the session project segment under one fixed key before dispatch;
        // conform it to what this entry actually declares (transfer to projectIds/pid, drop on
        // entries with no project scope) so a reachable entry never dies on the blind stamp.
        arguments = ApiAccessIdentityAnchor.conformProjectSlice(spec, arguments);

        // Third ownership face: when the registered row names the page's functional permission,
        // the door holds it to the same face as the page — checked against the live aggregation
        // the page interceptor itself uses, fail-closed, refusal naming the permission point.
        String permissionProblem = permissionGate.problem(overlay.get(key), operatorId);
        if (permissionProblem != null) {
            audit(operatorId, firstAnchor(spec, arguments), key, arguments, false, 0, false, -1, 0L,
                    permissionProblem);
            return ApiAccessReadResult.blocked(key, permissionProblem);
        }

        String scopeProblem = scopeVerdict(spec, operatorId, arguments);
        if (scopeProblem != null) {
            audit(operatorId, firstAnchor(spec, arguments), key, arguments, false, 0, false, -1, 0L, scopeProblem);
            return ApiAccessReadResult.blocked(key, scopeProblem);
        }

        // Row-level ownership: when the governance row names the entity and the id parameter this
        // call addresses, the door resolves that row's owning project and applies the shared
        // membership decision — a verified family verdict no longer opens every project's rows.
        String rowProblem = rowOwnership.problem(overlay.get(key), operatorId, arguments);
        if (rowProblem != null) {
            audit(operatorId, firstAnchor(spec, arguments), key, arguments, false, 0, false, -1, 0L, rowProblem);
            return ApiAccessReadResult.blocked(key, rowProblem);
        }

        String bucket = taskId != null ? "task:" + taskId : "op:" + operatorId;
        String overQuota = quota.tryClaim(bucket);
        if (overQuota != null) {
            audit(operatorId, firstAnchor(spec, arguments), key, arguments, false, 0, false, -1, 0L, overQuota);
            return ApiAccessReadResult.blocked(key, overQuota);
        }

        ApiAccessTarget target;
        try {
            target = requestFactory.build(spec, arguments);
        } catch (IllegalArgumentException ex) {
            return ApiAccessReadResult.blocked(key, ex.getMessage());
        }

        ApiAccessDial dial = dial("GET", target.requestUri(), null, operatorId);
        if (dial.error() != null) {
            return ApiAccessReadResult.blocked(key, dial.error());
        }
        return deliver(spec, operatorId, key, arguments, dial.status(), dial.body(), dial.elapsedMs());
    }

    /**
     * The one place that dials this application's own door. Both doors go through it so the trusted
     * operator header, the timeout ceiling and the byte ceiling can never diverge between the read
     * path and a confirmed landing.
     */
    private ApiAccessDial dial(String method, String requestUri, String bodyJson, Long operatorId) {
        long started = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(selfBase + requestUri))
                    .timeout(Duration.ofMillis(configReader.getPositiveLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)))
                    .header("Accept", "application/json")
                    .header(OperatorContext.OPERATOR_ID_HEADER, String.valueOf(operatorId));
            if (bodyJson == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(bodyJson, java.nio.charset.StandardCharsets.UTF_8));
            }
            HttpResponse<InputStream> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String body = readCapped(response.body(), configReader.getPositiveLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES));
            return new ApiAccessDial(status, body, System.currentTimeMillis() - started, null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ApiAccessDial(0, null, System.currentTimeMillis() - started, "调用被中断：" + ex.getMessage());
        } catch (Exception ex) {
            log.warn("pass-through dial failed method={} uri={} cause={}", method, requestUri, ex.toString());
            return new ApiAccessDial(0, null, System.currentTimeMillis() - started,
                    "目标接口不可达：" + ex.getClass().getSimpleName() + " " + String.valueOf(ex.getMessage()));
        }
    }

    @Override
    public ApiAccessWriteResult landConfirmedChange(Long operatorId, long sessionProjectId,
                                                   ApiAccessWriteRequest request) {
        if (request == null || request.endpointKey() == null || request.endpointKey().isBlank()) {
            return ApiAccessWriteResult.blocked(null, "草案没有点名要落地哪个条目");
        }
        String key = request.endpointKey().trim();
        Map<String, Object> arguments = new java.util.LinkedHashMap<>(
                request.arguments() == null ? Map.of() : request.arguments());
        ApiEndpointSpec spec = catalog.find(key);
        Map<String, ApiAccessGovernanceView> landingOverlay = governance.loadAll();
        ApiAccessVerdict verdict = policy.decide(spec, landingOverlay, true);
        if (!verdict.allowed()) {
            auditChange(operatorId, sessionProjectId, key, arguments, false, 0, null, 0L, verdict.reason());
            return ApiAccessWriteResult.blocked(key, verdict.reason());
        }
        if (operatorId == null) {
            String reason = "变更落地必须有名可查的操作人（确认卡片的人）";
            auditChange(operatorId, sessionProjectId, key, arguments, false, 0, null, 0L, reason);
            return ApiAccessWriteResult.blocked(key, reason);
        }
        // Same page-permission face as the read door: the confirming person must hold the
        // permission the page's own button enforces, checked on the same live aggregation.
        ApiAccessGovernanceView landingRow = landingOverlay.get(key);
        String landingPermission = permissionGate.problem(landingRow, operatorId);
        if (landingPermission != null) {
            auditChange(operatorId, sessionProjectId, key, arguments, false, 0, null, 0L, landingPermission);
            return ApiAccessWriteResult.blocked(key, landingPermission);
        }
        // A registered row that names the page's permission has a third ownership form: the
        // permission itself is the authority (the page's button checks no project membership),
        // so such an entry may land without a project carrier. Everything else keeps the rule:
        // no carrier = no honest project attribution = refusal.
        boolean pageGatedLanding = landingRow != null && landingRow.requiredPermission() != null
                && !landingRow.requiredPermission().isBlank();
        String carrier = spec.anchorCarrier();
        if (carrier == null && !pageGatedLanding) {
            String reason = "该条目不带项目归属锚，变更无法判定落在哪个项目，拒绝落地：" + key;
            auditChange(operatorId, sessionProjectId, key, arguments, false, 0, null, 0L, reason);
            return ApiAccessWriteResult.blocked(key, reason);
        }
        if (carrier != null) {
            // Ownership is stamped by the server from the chat session, never trusted from the proposal.
            // The stamp uses the anchor name the entry itself declares (projectId / projectIds / pid),
            // so a body anchor spelled other than the default does not ride in as an undeclared field.
            if (ApiEndpointSpec.ANCHOR_BODY.equals(carrier)) {
                arguments.put(spec.projectAnchor(), sessionProjectId);
            } else {
                List<Long> claimed = requestFactory.anchorProjectIds(spec, arguments);
                if (!claimed.equals(List.of(sessionProjectId))) {
                    String reason = "草案指向的项目与会话项目不一致（会话项目 " + sessionProjectId
                            + "，草案声称 " + claimed + "），拒绝跨项目落地";
                    auditChange(operatorId, sessionProjectId, key, arguments, false, 0, null, 0L, reason);
                    return ApiAccessWriteResult.blocked(key, reason);
                }
            }
            if (!allowedInProject(operatorId, sessionProjectId)) {
                String reason = "操作人不属于项目 " + sessionProjectId + "，变更未落地";
                auditChange(operatorId, sessionProjectId, key, arguments, false, 0, null, 0L, reason);
                return ApiAccessWriteResult.blocked(key, reason);
            }
        }
        // Same person-anchor posture as the read door: a change entry that addresses a user acts
        // as the confirming person, never as a claimed one.
        if (spec.userAnchor() != null) {
            arguments = ApiAccessIdentityAnchor.restamp(spec, arguments, operatorId);
        }
        String overQuota = quota.tryClaim("change:op:" + operatorId);
        if (overQuota != null) {
            auditChange(operatorId, sessionProjectId, key, arguments, false, 0, null, 0L, overQuota);
            return ApiAccessWriteResult.blocked(key, overQuota);
        }

        ApiAccessWriteTarget target;
        try {
            target = requestFactory.buildWrite(spec, arguments);
        } catch (IllegalArgumentException ex) {
            return ApiAccessWriteResult.blocked(key, ex.getMessage());
        }

        ApiAccessDial dial = dial(target.httpMethod(), target.requestUri(), target.bodyJson(), operatorId);
        if (dial.error() != null) {
            auditChange(operatorId, sessionProjectId, key, arguments, false, 0, null, dial.elapsedMs(), dial.error());
            return ApiAccessWriteResult.blocked(key, dial.error());
        }
        Long producedId = null;
        String reason = null;
        String receipt = null;
        try {
            JsonNode root = objectMapper.readTree(dial.body());
            String code = root.path("code").asText("");
            receipt = clip(writeAsString(root.path("data")), 2_000);
            if (!"0".equals(code)) {
                reason = "目标接口返回 code=" + (code.isEmpty() ? "未知" : code)
                        + (root.path("msg").asText("").isEmpty() ? "" : "：" + root.path("msg").asText(""));
            }
            JsonNode idNode = root.path("data").path("id");
            if (idNode.canConvertToLong()) {
                producedId = idNode.asLong();
            }
        } catch (Exception ex) {
            reason = "目标返回的不是 JSON，前 500 字符：" + clip(dial.body(), 500);
        }
        boolean landed = reason == null;
        auditChange(operatorId, sessionProjectId, key, arguments, landed, dial.status(), producedId,
                dial.elapsedMs(), reason);
        return new ApiAccessWriteResult(key, landed, reason, dial.status(), producedId, receipt, dial.elapsedMs());
    }

    /** Membership for a landing: the admin set already used by the tool gate, otherwise real membership. */
    private boolean allowedInProject(Long operatorId, long projectId) {
        if (adminAlertRecipients.adminUserIds().contains(operatorId)) {
            return true;
        }
        return projectRoleResolver.isMember(projectId, operatorId);
    }

    private void auditChange(Long operatorId, long projectId, String key, Map<String, Object> arguments,
                             boolean landed, int httpStatus, Long producedId, long elapsed, String reason) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("endpointKey", key);
        before.put("argumentNames", new ArrayList<>(arguments.keySet()));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("landed", landed);
        after.put("httpStatus", httpStatus);
        after.put("producedId", producedId);
        after.put("elapsedMs", elapsed);
        if (reason != null) {
            after.put("reason", clip(reason, 300));
        }
        try {
            auditLogService.record(new AuditLogInput(operatorId, projectId, "api_access", key, "change",
                    before, after));
        } catch (RuntimeException ex) {
            log.warn("pass-through landing audit failed key={} cause={}", key, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // delivery shaping
    // ------------------------------------------------------------------

    /** Parse the envelope, apply the row and byte ceilings, audit, and hand back one result. */
    private ApiAccessReadResult deliver(ApiEndpointSpec spec, Long operatorId, String key,
                                        Map<String, Object> arguments, int httpStatus,
                                        String body, long elapsed) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception ex) {
            audit(operatorId, firstAnchor(spec, arguments), key, arguments, true, httpStatus, true, -1,
                    elapsed, "目标返回的不是 JSON");
            return new ApiAccessReadResult(key, true, "目标返回的不是 JSON，前 500 字符：" + clip(body, 500),
                    httpStatus, null, true, -1, elapsed);
        }
        String code = root.path("code").asText("");
        String message = root.path("msg").asText("");
        JsonNode data = root.path("data");
        long rowMax = configReader.getPositiveLong(KEY_ROW_MAX, DEFAULT_ROW_MAX);
        boolean truncated = false;
        int rowCount = -1;
        JsonNode shaped = data;
        if (data.isArray()) {
            rowCount = data.size();
            if (rowCount > rowMax) {
                shaped = trimArray(data, rowMax);
                truncated = true;
            }
        } else if (data.isObject()) {
            for (String listKey : LIST_KEYS) {
                JsonNode list = data.get(listKey);
                if (list != null && list.isArray()) {
                    rowCount = list.size();
                    if (rowCount > rowMax) {
                        ObjectNode copy = data.deepCopy();
                        copy.set(listKey, trimArray(list, rowMax));
                        shaped = copy;
                        truncated = true;
                    }
                    break;
                }
            }
        }

        String payload = writeAsString(shaped);
        long byteMax = configReader.getPositiveLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES);
        if (payload.length() > byteMax) {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("truncated", true);
            summary.put("ceilingBytes", byteMax);
            summary.put("payloadBytes", payload.length());
            summary.put("preview", clip(payload, (int) Math.min(byteMax, 4_000L)));
            summary.put("note", "回包超出直通字节上限，已只交付预览；请用参数收窄（项目/分页/关键字）或调高系统配置页参数 "
                    + KEY_MAX_BYTES);
            payload = writeAsString(summary);
            truncated = true;
        }

        String reason = null;
        if (!"0".equals(code)) {
            reason = "目标接口返回 code=" + (code.isEmpty() ? "未知" : code)
                    + (message.isEmpty() ? "" : "：" + message);
            truncated = truncated || httpStatus < 200 || httpStatus >= 300;
        }
        audit(operatorId, firstAnchor(spec, arguments), key, arguments, true, httpStatus, truncated,
                rowCount, elapsed, reason);
        return new ApiAccessReadResult(key, true, reason, httpStatus, payload, truncated, rowCount, elapsed);
    }

    private com.fasterxml.jackson.databind.node.ArrayNode trimArray(JsonNode list, long rowMax) {
        com.fasterxml.jackson.databind.node.ArrayNode out = objectMapper.createArrayNode();
        int keep = (int) rowMax;
        for (int index = 0; index < Math.min(keep, list.size()); index++) {
            out.add(list.get(index));
        }
        return out;
    }

    private String writeAsString(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            return String.valueOf(node);
        }
    }

    // ------------------------------------------------------------------
    // scope guard and helpers
    // ------------------------------------------------------------------

    /**
     * Horizontal-privilege guard: a project-anchored read is only served when the acting operator
     * can see every project the call names. System administrators pass on the same admin set the
     * tool gate already uses; an operator that is not named at all is refused.
     */
    private String scopeVerdict(ApiEndpointSpec spec, Long operatorId, Map<String, Object> arguments) {
        String anchor = spec.projectAnchor();
        if (anchor == null) {
            return null;
        }
        if (operatorId == null) {
            return "直通只代读操作人自己可见的数据，本次调用没有可判定的操作人";
        }
        List<Long> ids = requestFactory.anchorProjectIds(spec, arguments);
        if (ids.isEmpty()) {
            return "该条目按项目归属放行，参数 " + anchor + " 必须点名项目 id";
        }
        return rowOwnership.membershipProblem(ids, operatorId);
    }

    private Long firstAnchor(ApiEndpointSpec spec, Map<String, Object> arguments) {
        if (spec == null) {
            return null;
        }
        List<Long> ids = requestFactory.anchorProjectIds(spec, arguments);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void audit(Long operatorId, Long projectId, String key, Map<String, Object> arguments,
                       boolean served, int httpStatus, boolean truncated, int rowCount, long elapsed,
                       String reason) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("endpointKey", key);
        before.put("argumentNames", new ArrayList<>(arguments.keySet()));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("served", served);
        after.put("httpStatus", httpStatus);
        after.put("truncated", truncated);
        after.put("rowCount", rowCount);
        after.put("elapsedMs", elapsed);
        if (reason != null) {
            after.put("reason", clip(reason, 300));
        }
        try {
            auditLogService.record(new AuditLogInput(operatorId, projectId, "api_access", key, "read",
                    before, after));
        } catch (RuntimeException ex) {
            log.warn("pass-through audit failed key={} cause={}", key, ex.getMessage());
        }
    }

    private List<ApiEndpointParam> addressableParams(ApiEndpointSpec spec) {
        List<ApiEndpointParam> out = new ArrayList<>();
        for (ApiEndpointParam param : spec.params()) {
            if (param.addressable()) {
                out.add(param);
            }
        }
        return List.copyOf(out);
    }

    private boolean matches(ApiEndpointSpec spec, String needle,
                            Map<String, ApiAccessGovernanceView> overlay) {
        if (spec.key().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (ApiEndpointParam param : spec.params()) {
            if (param.name().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        ApiAccessGovernanceView row = overlay.get(spec.key());
        if (row == null) {
            return false;
        }
        if (row.description() != null && row.description().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        // Registered user-vocabulary synonyms widen the surface per row only: a null column keeps
        // the pre-corpus shape, so the search can never blanket-match (see the corpus gate on the
        // tooling side for what rows are allowed to register here).
        return row.searchTerms() != null
                && row.searchTerms().toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Path-derived description when nothing was registered; says so instead of pretending. */
    private String describe(ApiEndpointSpec spec, ApiAccessGovernanceView row) {
        if (row != null && row.description() != null && !row.description().isBlank()) {
            return row.description();
        }
        if (spec.userAnchor() != null) {
            return "只读门按登录人限定 " + spec.pathPattern() + "（" + spec.userAnchor()
                    + " 由门按操作人服务端重打，载荷声称无效；说明未登记，按路径生成）";
        }
        return "只读门 " + spec.pathPattern() + "（说明未登记，按路径生成）";
    }


    /** Read the response body but never past the ceiling plus a slack window. */
    private String readCapped(InputStream stream, long byteMax) throws Exception {
        long budget = byteMax + 8_192L;
        byte[] buffer = stream.readNBytes((int) Math.min(budget, Integer.MAX_VALUE));
        stream.close();
        return new String(buffer, StandardCharsets.UTF_8);
    }

    private String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
