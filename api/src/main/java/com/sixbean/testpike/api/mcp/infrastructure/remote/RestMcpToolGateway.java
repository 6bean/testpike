package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>Case-write trio (create_case/tag_case/delete_case): each execution point
 * gates the operator's project membership first through the same core guard,
 * so an out-of-slice project id refuses before any write hop.
 * <p>Mock self-serve family: the two read tools ride mcp.read, the two write
 * tools ride mcp.mock. H1 isolation is composed here against the one guard
 * core exposes (/internal/projects/access-check, MEMBER spectrum — shared with
 * the admin bridge, no second ownership spectrum): a space-scoped call first
 * resolves the space's owning project, so a guessed foreign space id is a
 * plain-word refusal that leaks no row.
 *
 */
@Component
public class RestMcpToolGateway implements McpToolGateway {

    private static final ParameterizedTypeReference<Response<JsonNode>> RESP_NODE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Response<List<JsonNode>>> RESP_NODE_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient rest;
    private final java.util.function.LongSupplier docMaxBytesSupplier;
    private final java.util.function.LongSupplier genMaxRequirementsSupplier;

    public RestMcpToolGateway(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this(coreBaseUrl, () -> DEFAULT_DOC_MAX_BYTES, () -> DEFAULT_GEN_MAX_REQUIREMENTS);
    }

    /** Size-cap seam: hot governance reads are wired here, tests pin a tiny cap. */
    public RestMcpToolGateway(String coreBaseUrl,
                              java.util.function.LongSupplier docMaxBytesSupplier) {
        this(coreBaseUrl, docMaxBytesSupplier, () -> DEFAULT_GEN_MAX_REQUIREMENTS);
    }

    /** Cap seams for both governance ceilings (byte ceiling of the doc channel,
     *  requirement-count ceiling of the generation trigger); the three-argument
     *  shape lets tests pin a tiny cap without reflection. */
    public RestMcpToolGateway(String coreBaseUrl,
                              java.util.function.LongSupplier docMaxBytesSupplier,
                              java.util.function.LongSupplier genMaxRequirementsSupplier) {
        this(coreBaseUrl, docMaxBytesSupplier, genMaxRequirementsSupplier,
                DEFAULT_FORWARD_READ_TIMEOUT_SECONDS);
    }

    /** Explicit forward-budget shape: the api→core leg must outlive the inner
     *  core→ai-service scenario-preview leg instead of inheriting the shared
     *  30s internal bridge default (R56 nested-hop contract). */
    public RestMcpToolGateway(String coreBaseUrl,
                              java.util.function.LongSupplier docMaxBytesSupplier,
                              java.util.function.LongSupplier genMaxRequirementsSupplier,
                              long forwardReadTimeoutSeconds) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl,
                java.time.Duration.ofSeconds(forwardReadTimeoutSeconds));
        this.docMaxBytesSupplier = docMaxBytesSupplier;
        this.genMaxRequirementsSupplier = genMaxRequirementsSupplier;
    }

    /** Spring wiring: both ceilings ride the same hot governance read channel
     *  as the rate/quota keys, so a settings-page edit takes effect on the next
     *  call. The forward read budget is its own key (never the shared 30s). */
    @org.springframework.beans.factory.annotation.Autowired
    public RestMcpToolGateway(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl,
            RestCoreConfigClient configClient,
            @Value("${mcp.gateway.forward-read-timeout-seconds:880}") long forwardReadTimeoutSeconds) {
        this(coreBaseUrl, configClient.docMaxBytes(), configClient.genMaxRequirements(),
                forwardReadTimeoutSeconds);
    }

    /** Fallback cap when the governance key is unreadable (kept equal to the seed default). */
    public static final long DEFAULT_DOC_MAX_BYTES = 33_554_432L;

    /** Floor read budget for the api→core gateway leg. The generation trigger and
     *  doc-channel tools ride synchronous chains whose inner worst case is the
     *  core→ai-service forward leg (820s), so this outer leg carries its own
     *  default strictly above it and above the R56 per-hop floor (800s). */
    public static final long DEFAULT_FORWARD_READ_TIMEOUT_SECONDS = 880L;

    /** Governance key name; also quoted in the refusal message (same spectrum as the quota guard). */
    public static final String DOC_MAX_BYTES_KEY = "mcp.doc.max-bytes";

    /** Fallback requirement-count ceiling for the generation trigger
     *  (kept equal to the seeded default of mcp.gen.max-requirements). */
    public static final long DEFAULT_GEN_MAX_REQUIREMENTS = 200L;

    /** Governance key name quoted in the over-cap refusal (same shape as the byte ceiling). */
    public static final String GEN_MAX_REQUIREMENTS_KEY = "mcp.gen.max-requirements";

    @Override
    public String invoke(String toolName, Map<String, Object> arguments, McpCaller caller) {
        return switch (toolName) {
            case "create_case" -> createCase(arguments, caller);
            case "tag_case" -> tagCase(arguments, caller);
            case "get_cases" -> getCases(arguments, caller);
            case "get_exec_result" -> getExecResult(arguments, caller);
            case "get_script" -> getScript(arguments, caller);
            case "list_requirements" -> listRequirements(arguments, caller);
            case "delete_case" -> deleteCase(arguments, caller);
            case "list_mock_spaces" -> listMockSpaces(arguments, caller);
            case "inspect_mock_hits" -> inspectMockHits(arguments, caller);
            case "create_mock_stub" -> createMockStub(arguments, caller);
            case "publish_mock_space" -> publishMockSpace(arguments, caller);
            case "doc.upload" -> docUpload(arguments, caller);
            case "doc_task.poll" -> docTaskPoll(arguments, caller);
            case "doc_task.cancel" -> docTaskCancel(arguments, caller);
            case "gen.cases_from_requirement" -> genCasesFromRequirement(arguments, caller);
            case "script.writeback" -> scriptWriteback(arguments, caller);
            case "mcp.review.confirm" -> reviewConfirm(arguments, caller);
            case "exec.run" -> execRun(arguments, caller);
            case "exec.status" -> execStatus(arguments, caller);
            case "exec.cancel" -> execCancel(arguments, caller);
            case "report.fetch" -> reportFetch(arguments, caller);
            default -> throw new ServiceException("未知工具: " + toolName, BizErrorCode.NOT_FOUND);
        };
    }

    @Override
    public List<McpPassThroughRemoteTool> listPassThroughTools() {
        JsonNode rows = call(HttpMethod.POST, "/internal/api-access/mcp-tools", Map.of(),
                RESP_NODE, 0L);
        // core wraps in Response.data (an array here); map row-wise, unknown shapes = empty set.
        List<McpPassThroughRemoteTool> out = new java.util.ArrayList<>();
        if (rows == null || !rows.isArray()) {
            return out;
        }
        for (JsonNode row : rows) {
            out.add(new McpPassThroughRemoteTool(row.path("name").asText(""),
                    row.path("description").asText(""),
                    row.path("inputSchemaJson").asText("{}"),
                    row.path("endpointKey").asText("")));
        }
        return out;
    }

    @Override
    public String invokePassThrough(String endpointKey, Map<String, Object> arguments, long operatorUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpointKey", endpointKey);
        body.put("arguments", arguments == null ? Map.of() : arguments);
        return call(HttpMethod.POST, "/internal/api-access/read", body, RESP_NODE, operatorUserId).toString();
    }


    private String createCase(Map<String, Object> args, McpCaller caller) {
        long projectId = longArg(args, "project_id");
        requireProjectMember(projectId, caller);
        String steps = stringArg(args, "steps");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("title", stringArg(args, "title"));
        body.put("expectedResult", stringArg(args, "expected"));
        body.put("steps", List.of(Map.of("stepNo", 1, "description", steps, "isAssert", 0)));
        body.put("sourceRef", "mcp");
        String created = call(HttpMethod.POST, "/internal/cases", body, RESP_NODE, caller.userId()).toString();
        Object requirementId = args.get("requirement_id");
        if (requirementId instanceof Number number) {
            long caseId = requireNode(created).path("id").asLong();
            call(HttpMethod.POST, "/internal/requirements/" + number.longValue() + "/cases",
                    Map.of("caseIds", List.of(caseId)), RESP_NODE, caller.userId());
        }
        return created;
    }


    private String tagCase(Map<String, Object> args, McpCaller caller) {
        long projectId = longArg(args, "project_id");
        requireProjectMember(projectId, caller);
        long caseId = longArg(args, "case_id");
        List<String> names = listArg(args, "tags");
        List<JsonNode> dict = call(HttpMethod.GET, "/internal/tags?projectId=" + projectId,
                null, RESP_NODE_LIST, caller.userId());
        Map<String, Long> byName = dict.stream().collect(Collectors.toMap(
                node -> node.path("name").asText(), node -> node.path("id").asLong(),
                (a, b) -> a, LinkedHashMap::new));
        List<Long> tagIds = names.stream().map(name -> {
            Long id = byName.get(name);
            if (id == null) {
                throw new ServiceException("标签「" + name + "」不在项目标签字典中，请先在平台创建标签",
                        BizErrorCode.VALIDATION_ERROR);
            }
            return id;
        }).toList();
        return call(HttpMethod.PUT, "/internal/tags/cases/" + caseId + "/tags",
                Map.of("projectId", projectId, "tagIds", tagIds), RESP_NODE_LIST, caller.userId()).toString();
    }


    private String deleteCase(Map<String, Object> args, McpCaller caller) {
        long projectId = longArg(args, "project_id");
        requireProjectMember(projectId, caller);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("targetType", 1);
        body.put("targetId", longArg(args, "case_id"));
        body.put("reason", stringArg(args, "reason"));
        return call(HttpMethod.POST, "/internal/delete-requests", body, RESP_NODE, caller.userId()).toString();
    }


    private String getCases(Map<String, Object> args, McpCaller caller) {
        caller.assertWithinProjectPin(longArg(args, "project_id"));
        if (args.containsKey("suite_id")) {
            throw new ServiceException(
                    "暂不支持按用例集（suite_id）过滤：core 用例查询面未提供该维度，请去掉 suite_id 按项目/关键词查询",
                    BizErrorCode.VALIDATION_ERROR);
        }
        StringBuilder uri = new StringBuilder("/internal/cases?projectId=")
                .append(longArg(args, "project_id"));
        appendIfPresent(uri, "keyword", args.get("keyword") == null ? null
                : URLEncoder.encode(String.valueOf(args.get("keyword")), StandardCharsets.UTF_8));
        appendPaging(uri, args);
        return call(HttpMethod.GET, uri.toString(), null, RESP_NODE, caller.userId()).toString();
    }

    private String getExecResult(Map<String, Object> args, McpCaller caller) {
        caller.assertWithinProjectPin(longArg(args, "project_id"));
        long caseId = longArg(args, "case_id");
        return call(HttpMethod.GET, "/internal/executors/cases/" + caseId
                + "/history?projectId=" + longArg(args, "project_id") + "&page=1&size=1",
                null, RESP_NODE, caller.userId()).toString();
    }

    private String getScript(Map<String, Object> args, McpCaller caller) {
        return call(HttpMethod.GET, "/internal/scripts/" + longArg(args, "case_id"),
                null, RESP_NODE, caller.userId()).toString();
    }

    private String listRequirements(Map<String, Object> args, McpCaller caller) {
        caller.assertWithinProjectPin(longArg(args, "project_id"));
        StringBuilder uri = new StringBuilder("/internal/requirements?projectId=")
                .append(longArg(args, "project_id"));
        appendIfPresent(uri, "keyword", args.get("keyword") == null ? null
                : URLEncoder.encode(String.valueOf(args.get("keyword")), StandardCharsets.UTF_8));
        appendPaging(uri, args);
        return call(HttpMethod.GET, uri.toString(), null, RESP_NODE, caller.userId()).toString();
    }

 // ===== Mock family : agent self-serve mock through the same
 // internal doors the admin bridge uses. H1 isolation is enforced here by
 // composing /internal/projects/access-check (MEMBER spectrum, the one guard
 // core exposes — no second ownership spectrum is invented) before every
 // space-scoped door; space rows are resolved back to their owning project via
 // /internal/mock/space/get, so a guessed space id from another project is a
 // plain-word refusal, never a leaked page. =====

    private String listMockSpaces(Map<String, Object> args, McpCaller caller) {
        long projectId = longArg(args, "project_id");
        requireProjectMember(projectId, caller);
        return call(HttpMethod.GET, "/internal/mock/space/list?projectId=" + projectId,
                null, RESP_NODE_LIST, caller.userId()).toString();
    }

    private String inspectMockHits(Map<String, Object> args, McpCaller caller) {
        long spaceId = longArg(args, "space_id");
        requireSpaceMember(spaceId, caller);
        StringBuilder uri = new StringBuilder("/internal/mock/hit/list?spaceId=").append(spaceId);
        Object matched = args.get("matched");
        if (matched instanceof Boolean flag) {
            uri.append("&matched=").append(flag);
        }
        uri.append("&page=").append(intOr(args.get("page"), 1));
        uri.append("&pageSize=")
                .append(Math.min(intOr(args.get("page_size"), 20), 100));
        return call(HttpMethod.GET, uri.toString(), null, RESP_NODE, caller.userId()).toString();
    }

    private String createMockStub(Map<String, Object> args, McpCaller caller) {
        long spaceId = longArg(args, "space_id");
        requireSpaceMember(spaceId, caller);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", stringArg(args, "name"));
        body.put("method", stringArg(args, "method"));
        body.put("pathPattern", stringArg(args, "path_pattern"));
        body.put("pathMatchType", args.get("path_match_type") == null
                ? "exact" : String.valueOf(args.get("path_match_type")));
        if (args.get("priority") instanceof Number number) {
            body.put("priority", number.intValue());
        }
        // Response builder payload passes through verbatim (shape gates live in
        // the core save chain — the gateway never repairs or rejects payloads
        // it is not the single point for). Absent = {} which renders 200 empty.
        body.put("responseJson", args.get("response") == null ? "{}"
                : writeJson(args.get("response")));
        body.put("source", "manual");
        return call(HttpMethod.POST, "/internal/mock/stub/create?spaceId=" + spaceId,
                body, RESP_NODE, caller.userId()).toString();
    }

    private String publishMockSpace(Map<String, Object> args, McpCaller caller) {
        long spaceId = longArg(args, "space_id");
        // One space read serves both the ownership gate and the version echo —
        // no double lookup, no stale-window race between them.
        JsonNode space = requireNode(call(HttpMethod.GET,
                "/internal/mock/space/get?spaceId=" + spaceId, null, RESP_NODE, caller.userId()).toString());
        requireProjectMember(resolveSpaceProjectId(space), caller);
        // Explicit echo wins (optimistic lock stays honest); when omitted we
        // publish at the version we just gated on — the transition service
        // still refuses illegal edges, this mirrors the UI publish click.
        int expectedVersion = args.get("expected_version") instanceof Number number
                ? number.intValue()
                : space.path("version").asInt();
        return call(HttpMethod.POST, "/internal/mock/space/transition?spaceId=" + spaceId
                + "&targetStatus=1&expectedVersion=" + expectedVersion,
                null, RESP_NODE, caller.userId()).toString();
    }

    // ===== Shared guards (mock family + case-write tools) =====


    
    private String scriptWriteback(Map<String, Object> args, McpCaller caller) {
        long projectId = longArg(args, "project_id");
        requireProjectMember(projectId, caller);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("caseId", longArg(args, "case_id"));
        Object schemaVersion = args.get("schema_version");
        body.put("schemaVersion", schemaVersion == null ? "v2" : schemaVersion.toString());
        body.put("actionJson", stringArg(args, "script"));
        if (args.get("evidence") instanceof Map<?, ?> evidence) {
            Map<String, Object> camel = new LinkedHashMap<>();
            camel.put("ranAt", evidence.get("ran_at"));
            camel.put("envFingerprint", evidence.get("env_fingerprint"));
            camel.put("passed", evidence.get("passed"));
            camel.put("failed", evidence.get("failed"));
            camel.put("logSha256", evidence.get("log_sha256"));
            body.put("evidence", camel);
        }
        if (args.get("expected_version") instanceof Number version) {
            body.put("expectedVersion", version.intValue());
        }
        JsonNode ticket = requireNode(call(HttpMethod.POST,
                "/internal/mcp-confirm/drafts/script-writeback", body, RESP_NODE, caller.userId()).toString());
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("confirm_ref", ticket.path("confirmRef").asText(null));
        receipt.put("summary", ticket.path("summary").asText(null));
        receipt.put("expires_in_seconds", ticket.path("expiresInSeconds").asLong(0));
        receipt.put("next_step", "在会话内调用 mcp.review.confirm（decision=accept 落库 / reject 作废），无需登录平台界面");
        return writeJson(receipt);
    }

    
    private String reviewConfirm(Map<String, Object> args, McpCaller caller) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("confirmRef", stringArg(args, "confirm_ref"));
        body.put("decision", stringArg(args, "decision"));
        Object reason = args.get("reason");
        if (reason != null) {
            body.put("reason", reason.toString());
        }
        JsonNode outcome = requireNode(call(HttpMethod.POST, "/internal/mcp-confirm/confirm",
                body, RESP_NODE, caller.userId()).toString());
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("domain", outcome.path("domain").asText(null));
        receipt.put("decision", outcome.path("decision").asText(null));
        receipt.put("landed", outcome.path("landed").asBoolean(false));
        receipt.put("message", outcome.path("message").asText(null));
        return writeJson(receipt);
    }

    /**
     * MEMBER gate against the core single point (/internal/projects/access-check,
     * the same spectrum the admin bridge rides — no second ownership check).
     * Every external-agent write tool calls it before any business hop, so a
     * foreign project id is a plain-word 403 that writes nothing.
     * GA1 pins are decided first, at this same point: a token limited to a
     * pinned project set is refused before even the membership hop, and the
     * refusal names the token's current project set.
     */
    private void requireProjectMember(long projectId, McpCaller caller) {
        caller.assertWithinProjectPin(projectId);
        call(HttpMethod.POST, "/internal/projects/access-check",
                Map.of("projectId", projectId, "userId", caller.userId(), "requiredRole", "MEMBER"),
                RESP_NODE, caller.userId());
    }

    /** Space → owning project reverse lookup, then the same member gate. */
    private void requireSpaceMember(long spaceId, McpCaller caller) {
        JsonNode space = requireNode(call(HttpMethod.GET,
                "/internal/mock/space/get?spaceId=" + spaceId, null, RESP_NODE, caller.userId()).toString());
        requireProjectMember(resolveSpaceProjectId(space), caller);
    }

    /** Owning project of a space row (missing shape = honest refusal, fail-closed). */
    private static long resolveSpaceProjectId(JsonNode space) {
        JsonNode projectId = space.get("projectId");
        if (projectId == null || !projectId.canConvertToLong()) {
            throw new ServiceException("空间行缺少归属项目（core 返回形状异常，已拒绝继续）",
                    BizErrorCode.INTERNAL_ERROR);
        }
        return projectId.asLong();
    }

    // ===== generation trigger (gen.cases_from_requirement) =====
    // Rides the same doors as the in-app generation chain: requirement reads on
    // GET /internal/requirements/{id}, the stage0 scenario enumeration leg on
    // POST /internal/ai-scenario-previews and queueing on POST /internal/ai-batches.
    // No second generator surface exists here (single-source rule); the call returns
    // the batch handle immediately and results are read back through get_cases.

    private String genCasesFromRequirement(Map<String, Object> args, McpCaller caller) {
        long projectId = longArg(args, "project_id");
        List<Long> requirementIds = longListArg(args, "requirement_ids");
        if (requirementIds.isEmpty()) {
            throw new ServiceException("requirement_ids 至少需要一个需求点，未提交生成",
                    BizErrorCode.VALIDATION_ERROR);
        }
        requireProjectMember(projectId, caller);
        long cap = Math.max(1L, genMaxRequirementsSupplier.getAsLong());
        if (requirementIds.size() > cap) {
            throw new ServiceException("需求点条数 " + requirementIds.size() + " 超过上限 " + cap
                    + "（治理键 " + GEN_MAX_REQUIREMENTS_KEY + "，可在系统设置调整），未提交生成",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String mode = genModeOf(args);
        List<JsonNode> rows = new java.util.ArrayList<>();
        for (Long id : requirementIds) {
            rows.add(requireNode(call(HttpMethod.GET, "/internal/requirements/" + id,
                    null, RESP_NODE, caller.userId()).toString()));
        }
        if (Boolean.TRUE.equals(args.get("dry_run"))) {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("dry_run", true);
            receipt.put("accepted", true);
            receipt.put("project_id", projectId);
            receipt.put("requirement_count", rows.size());
            receipt.put("mode", mode);
            return writeJson(receipt);
        }
        if ("scenario".equals(mode)) {
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> preview = new LinkedHashMap<>();
                preview.put("seq", i);
                preview.put("projectId", projectId);
                preview.put("operatorId", caller.userId());
                preview.put("requirementText", requirementTextOf(rows.get(i)));
                preview.put("priority", 1);
                call(HttpMethod.POST, "/internal/ai-scenario-previews", preview, RESP_NODE, caller.userId());
            }
        }
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("text", requirementTextOf(rows.get(i)));
            item.put("requirementId", requirementIds.get(i));
            items.add(item);
        }
        JsonNode batch = requireNode(call(HttpMethod.POST, "/internal/ai-batches",
                Map.of("projectId", projectId, "items", items), RESP_NODE, caller.userId()).toString());
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("task_id", batch.path("batchId").asLong());
        receipt.put("mode", mode);
        receipt.put("requirement_count", rows.size());
        return writeJson(receipt);
    }

    /** Generation mode value domain: scenario (default, stage0 enumeration first) | plain. */
    private static String genModeOf(Map<String, Object> args) {
        Object raw = args.get("mode");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return "scenario";
        }
        String mode = String.valueOf(raw).trim();
        if (!"scenario".equals(mode) && !"plain".equals(mode)) {
            throw new ServiceException("mode 只支持 scenario 或 plain（当前：" + mode + "），未提交生成",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return mode;
    }

    /** Requirement line text for the generator: title plus acceptance criteria when present. */
    private static String requirementTextOf(JsonNode row) {
        String title = row.path("title").asText("");
        String criteria = row.path("acceptanceCriteria").asText("");
        return criteria.isBlank() ? title : title + "\n" + criteria;
    }

    /** Numeric id list argument (tolerates any JSON number form the MCP client sends). */
    private static List<Long> longListArg(Map<String, Object> args, String name) {
        return longListFrom(args, name, "未提交生成");
    }

    /**
 * Numeric id list read from any object argument shape (top level or nested
 * selection object) — one parser for every id list on this surface, so the
 * refusal wording stays identical and the outcome phrase names the caller's
 */
    private static List<Long> longListFrom(Map<?, ?> source, String name, String outcome) {
        Object raw = source.get(name);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new ServiceException(name + " 必须是数组，" + outcome, BizErrorCode.VALIDATION_ERROR);
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (Object element : list) {
            if (element instanceof Number number) {
                ids.add(number.longValue());
            } else if (element != null && String.valueOf(element).matches("\\d+")) {
                ids.add(Long.parseLong(String.valueOf(element)));
            } else {
                throw new ServiceException(name + " 只接受数字 ID，" + outcome, BizErrorCode.VALIDATION_ERROR);
            }
        }
        return ids;
    }

    // ===== execution channel (exec.run / exec.status / exec.cancel / report.fetch) =====
    // The trigger is the very same core door the CI channel and the in-app batch
    // page ride (POST /internal/batches then POST /internal/batches/{id}/start):
    // an agent-started run is a normal batch, no second execution path is opened
    // here. Selection (a suite's own case list, or an explicit case list) and the
    // environment's project ownership are pre-checked before anything is written,
    // and dry_run answers without creating a batch. Reads resolve the owning
    // project from the batch row itself, so a guessed batch id is a plain refusal
    // that reveals nothing. Pass-rate comes from the core badge chain and the
    // per-result counts from the closeout snapshot — the api layer never
    // recomputes a ratio and never dumps per-case rows.

    /** Batch states that can no longer change (completed / partial failure / canceled). */
    private static final java.util.Set<Integer> BATCH_TERMINAL_STATES =
            java.util.Set.of(6, 7, 8);

    private String execRun(Map<String, Object> args, McpCaller caller) {
        long projectId = longArg(args, "project_id");
        Map<?, ?> selection = selectionArg(args);
        Object suiteRaw = selection.get("suite_id");
        Object caseIdsRaw = selection.get("case_ids");
        if (suiteRaw != null && caseIdsRaw != null) {
            throw new ServiceException("selection 的 suite_id（用例集）与 case_ids（用例清单）"
                    + "只能二选一，两者同时给出无法确定执行范围，未提交执行", BizErrorCode.VALIDATION_ERROR);
        }
        if (suiteRaw == null && caseIdsRaw == null) {
            throw new ServiceException("selection 必须给出 suite_id（用例集）或 case_ids（用例清单）之一，未提交执行",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<Long> caseIds;
        String selectionSource;
        List<Long> suiteEnvIds = List.of();
        if (caseIdsRaw != null) {
            caseIds = longListFrom(selection, "case_ids", "未提交执行").stream().distinct().toList();
            if (caseIds.isEmpty()) {
                throw new ServiceException("case_ids 至少需要一条用例，未提交执行",
                        BizErrorCode.VALIDATION_ERROR);
            }
            selectionSource = "case_ids:" + caseIds.size();
        } else {
            caseIds = List.of();
            selectionSource = null;
        }
        // Argument shape errors cost no hop; everything that has to be read from
        // the platform is read after the membership gate.
        requireProjectMember(projectId, caller);
        if (suiteRaw != null) {
            long suiteId = numberArg(suiteRaw, "selection.suite_id");
            JsonNode suite = requireNode(call(HttpMethod.GET, "/internal/suites/" + suiteId,
                    null, RESP_NODE, caller.userId()).toString());
            long suiteProject = suite.path("projectId").asLong(-1);
            if (suiteProject != projectId) {
                throw new ServiceException("用例集 " + suiteId + " 不属于项目 " + projectId
                        + "（其归属项目为 " + suiteProject + "），未提交执行", BizErrorCode.VALIDATION_ERROR);
            }
            caseIds = suiteCaseIds(suite, suiteId);
            suiteEnvIds = longListNode(suite.path("envIds"));
            selectionSource = "suite:" + suiteId;
        }
        Long envId = optionalLongArg(args, "env_id");
        String envSource = "参数 env_id 指定";
        // Environment resolution (shared by the preflight and the write half-beat):
        // an explicit env_id wins; a suite selection inherits the single environment
        // the suite itself declares; anything else is unresolved. The core start door
        // refuses a batch with no environment, so an unresolved environment must never
        // reach the create hop — it would leave a draft batch that can never run and
        // would let a replay of the same idempotency key add another one.
        if (envId == null && !suiteEnvIds.isEmpty()) {
            if (suiteEnvIds.size() > 1) {
                throw new ServiceException("用例集 " + suiteRaw + " 绑定了多个执行环境 "
                        + suiteEnvIds + "，请在参数 env_id 指定本次执行环境，未创建批次、未启动执行",
                        BizErrorCode.VALIDATION_ERROR);
            }
            envId = suiteEnvIds.get(0);
            envSource = "用例集自带执行环境 " + envId;
        }
        if (Boolean.TRUE.equals(args.get("dry_run"))) {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("dry_run", true);
            receipt.put("project_id", projectId);
            receipt.put("selection", selectionSource);
            receipt.put("case_count", caseIds.size());
            receipt.put("env_id", envId);
            if (envId == null) {
                // Preflight honesty: a run the start door would reject cannot be
                // reported as passed; the answer names the parameter and the way out.
                receipt.put("accepted", false);
                receipt.put("message", "预检未通过：" + caseIds.size()
                        + " 条用例尚未确定执行环境。请在参数 env_id 传入本项目的执行环境编号"
                        + "（站内「项目 - 执行环境」页可查），或改用已绑定执行环境的用例集"
                        + "（selection.suite_id）；本次未创建批次、未启动");
                return writeJson(receipt);
            }
            requireEnvOfProject(envId, projectId, caller);
            receipt.put("accepted", true);
            receipt.put("message", "预检通过：" + caseIds.size() + " 条用例与环境 " + envId
                    + " 归属一致；本次未创建批次、未启动");
            return writeJson(receipt);
        }
        if (envId == null) {
            throw new ServiceException("缺少执行环境：请在参数 env_id 传入本项目的执行环境编号"
                    + "（站内「项目 - 执行环境」页可查），或改用已绑定执行环境的用例集"
                    + "（selection.suite_id），未创建批次、未启动执行",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("name", batchNameOf(args));
        body.put("envId", envId);
        body.put("caseIds", caseIds);
        JsonNode created = requireNode(call(HttpMethod.POST, "/internal/batches",
                body, RESP_NODE, caller.userId()).toString());
        long batchId = created.path("id").asLong();
        JsonNode started = requireNode(call(HttpMethod.POST,
                "/internal/batches/" + batchId + "/start", null, RESP_NODE, caller.userId()).toString());
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("batch_id", batchId);
        receipt.put("batch_no", created.path("batchNo").asText(null));
        receipt.put("case_count", caseIds.size());
        receipt.put("env_id", envId);
        receipt.put("env_source", envSource);
        receipt.put("status", started.path("status").asInt());
        receipt.put("status_label", started.path("statusLabel").asText(null));
        receipt.put("queue_pos", intOrNull(started, "queuePos"));
        receipt.put("estimated_start_at", started.path("estimatedStartAt").asText(null));
        receipt.put("message", started.path("message").asText(null));
        receipt.put("next_step", "进度轮询用 exec.status，结果摘要用 report.fetch，"
                + "需要中止时用 exec.cancel 停止未派发任务（已完成的执行结果保留）");
        return writeJson(receipt);
    }

    /** Selection object argument (suite or explicit case list); shape errors cost no hop. */
    private static Map<?, ?> selectionArg(Map<String, Object> args) {
        Object raw = args.get("selection");
        if (raw instanceof Map<?, ?> map && !map.isEmpty()) {
            return map;
        }
        throw new ServiceException("缺少 selection：请给出 suite_id（用例集）或 case_ids（用例清单），未提交执行",
                BizErrorCode.VALIDATION_ERROR);
    }

    /**
     * Environment ownership pre-check for the preflight half-beat (GA2): a
     * foreign env id is refused with both project numbers named, before any
     * batch exists. On the write path the core create door enforces the same
     * rule, so no duplicate read is paid there.
     */
    private void requireEnvOfProject(Long envId, long projectId, McpCaller caller) {
        if (envId == null) {
            return;
        }
        JsonNode env = requireNode(call(HttpMethod.GET, "/internal/projects/envs/" + envId,
                null, RESP_NODE, caller.userId()).toString());
        long envProject = env.path("projectId").asLong(-1);
        if (envProject != projectId) {
            throw new ServiceException("执行环境 " + envId + " 不属于项目 " + projectId
                    + "（其归属项目为 " + envProject + "），未提交执行", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Id list carried by a suite row's environment field (absent = unbound). */
    private static List<Long> longListNode(JsonNode array) {
        List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode node : array) {
            if (node.canConvertToLong()) {
                ids.add(node.asLong());
            }
        }
        return List.copyOf(ids);
    }

    /** Case list of a suite row (empty suite = honest refusal before any batch). */
    private static List<Long> suiteCaseIds(JsonNode suite, long suiteId) {
        List<Long> ids = longListNode(suite.path("caseIds"));
        if (ids.isEmpty()) {
            throw new ServiceException("用例集 " + suiteId + " 内没有用例，请先在用例集中添加用例，未提交执行",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return ids.stream().distinct().toList();
    }

    /** Batch name for an agent-started run: caller's own label or a dated default. */
    private static String batchNameOf(Map<String, Object> args) {
        Object raw = args.get("name");
        if (raw != null && !String.valueOf(raw).isBlank()) {
            return String.valueOf(raw).trim();
        }
        return "站外智能体触发批次-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /** Progress view of a batch: curated fields, statuses verbatim from core. */
    private String execStatus(Map<String, Object> args, McpCaller caller) {
        JsonNode batch = requireBatch(longArg(args, "batch_id"), caller);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("batch_id", batch.path("id").asLong());
        receipt.put("name", batch.path("name").asText(null));
        receipt.put("status", batch.path("status").asInt());
        receipt.put("status_label", batch.path("statusLabel").asText(null));
        receipt.put("case_count", batch.path("caseCount").asLong());
        receipt.put("queue_pos", intOrNull(batch, "queuePos"));
        receipt.put("env_id", longOrNull(batch, "envId"));
        receipt.put("env_name", batch.path("envName").asText(null));
        receipt.put("created_at", batch.path("createdAt").asText(null));
        receipt.put("started_at", batch.path("startedAt").asText(null));
        receipt.put("finished_at", batch.path("finishedAt").asText(null));
        return writeJson(receipt);
    }

    /**
     * Cancel through the one core cancel door. A terminal batch answers with an
     * idempotent zero-change receipt (the outward contract), while every other
     * state is decided by the core state machine — its refusal wording rides
     * through unchanged, so no second stop rule is invented here.
     */
    private String execCancel(Map<String, Object> args, McpCaller caller) {
        JsonNode batch = requireBatch(longArg(args, "batch_id"), caller);
        long batchId = batch.path("id").asLong();
        int status = batch.path("status").asInt(-1);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("batch_id", batchId);
        if (BATCH_TERMINAL_STATES.contains(status)) {
            receipt.put("cancelled", false);
            receipt.put("state", status);
            receipt.put("state_label", batch.path("statusLabel").asText(null));
            receipt.put("message", "批次已处于「" + batch.path("statusLabel").asText("未知状态")
                    + "」终态，取消请求未产生任何变更（已完成的执行结果保留）");
            return writeJson(receipt);
        }
        JsonNode cancelled = requireNode(call(HttpMethod.POST,
                "/internal/batches/" + batchId + "/cancel", null, RESP_NODE, caller.userId()).toString());
        receipt.put("cancelled", true);
        receipt.put("state", cancelled.path("status").asInt());
        receipt.put("state_label", cancelled.path("statusLabel").asText(null));
        receipt.put("message", cancelled.path("message").asText("批次已取消"));
        return writeJson(receipt);
    }

    /**
     * Result summary: pass rate comes from the badge chain and the per-result
     * counts from the closeout snapshot, both read verbatim (no recomputation,
     * no per-case rows). While the batch has not closed out, the snapshot door
     * is not called at all — the receipt says what is known and where to poll.
     */
    private String reportFetch(Map<String, Object> args, McpCaller caller) {
        JsonNode batch = requireBatch(longArg(args, "batch_id"), caller);
        long batchId = batch.path("id").asLong();
        String name = batch.path("name").asText("批次");
        JsonNode badge = requireNode(call(HttpMethod.GET,
                "/internal/batches/" + batchId + "/badge", null, RESP_NODE, caller.userId()).toString());
        int status = badge.path("status").asInt();
        JsonNode rateNode = badge.path("passRate");
        java.math.BigDecimal passRate = rateNode.isNumber() ? rateNode.decimalValue() : null;
        String ratePhrase = com.sixbean.testpike.common.report.BadgeRatePhrase.of(passRate);
        String label = badge.path("statusLabel").asText("未知状态");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("batch_id", batchId);
        receipt.put("name", name);
        receipt.put("status", status);
        receipt.put("status_label", label);
        receipt.put("pass_rate", passRate);
        receipt.put("pass_rate_phrase", ratePhrase);
        Map<String, Object> counts = new LinkedHashMap<>();
        if (BATCH_TERMINAL_STATES.contains(status)) {
            JsonNode snapshot = requireNode(call(HttpMethod.GET,
                    "/internal/batches/" + batchId + "/report", null, RESP_NODE, caller.userId()).toString());
            counts.put("total", snapshot.path("total").asInt());
            counts.put("pass", snapshot.path("passCount").asInt());
            counts.put("fail", snapshot.path("failCount").asInt());
            counts.put("skip", snapshot.path("skipCount").asInt());
            counts.put("timeout", snapshot.path("timeoutCount").asInt());
            receipt.put("counts", counts);
            receipt.put("fail_dist", snapshot.path("failDist"));
            receipt.put("duration_ms", intOrNull(snapshot, "durationMs"));
            receipt.put("finished_at", batch.path("finishedAt").asText(null));
            long durationMs = snapshot.path("durationMs").asLong(0);
            receipt.put("summary", "批次「" + name + "」" + label + "：共 " + counts.get("total")
                    + " 条，通过 " + counts.get("pass") + " / 失败 " + counts.get("fail")
                    + " / 跳过 " + counts.get("skip") + " / 超时 " + counts.get("timeout")
                    + "，" + ratePhrase
                    + (durationMs > 0 ? "，耗时 " + durationMs / 1000 + " 秒" : ""));
        } else {
            counts.put("total", batch.path("caseCount").asLong());
            counts.put("pass", null);
            counts.put("fail", null);
            counts.put("skip", null);
            counts.put("timeout", null);
            receipt.put("counts", counts);
            receipt.put("started_at", batch.path("startedAt").asText(null));
            receipt.put("summary", "批次「" + name + "」" + label + "：用例 " + counts.get("total")
                    + " 条，通过率：" + ratePhrase
                    + "；结果快照在批次收口时生成，进度请用 exec.status 轮询，收口后再用 report.fetch 取摘要");
        }
        return writeJson(receipt);
    }

    /** Batch row + membership gate against the project the row itself declares. */
    private JsonNode requireBatch(long batchId, McpCaller caller) {
        JsonNode batch = requireNode(call(HttpMethod.GET, "/internal/batches/" + batchId,
                null, RESP_NODE, caller.userId()).toString());
        requireProjectMember(batch.path("projectId").asLong(-1), caller);
        return batch;
    }

    /** Optional numeric id argument (absent or blank = null = platform default applies). */
    private static Long optionalLongArg(Map<String, Object> args, String name) {
        Object raw = args.get(name);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        return numberArg(raw, name);
    }

    private static long numberArg(Object raw, String name) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (String.valueOf(raw).matches("\\d+")) {
            return Long.parseLong(String.valueOf(raw));
        }
        throw new ServiceException(name + " 必须是数字 ID", BizErrorCode.VALIDATION_ERROR);
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.get(field).intValue() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        return node.path(field).canConvertToLong() ? node.get(field).asLong() : null;
    }


    
    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type, long operator) {
        return RestClientInvoker.call(rest, method, uri, body, type, operator);
    }

    @Override
    public String tokenUsage(long tokenId, long operatorUserId) {
        return call(HttpMethod.GET, "/internal/mcp-tokens/" + tokenId + "/usage",
                null, RESP_NODE, operatorUserId).toString();
    }

    // ===== document channel (doc.upload / doc_task.poll / doc_task.cancel) =====
    // Rides the same doors as the in-product document chain — POST /internal/documents
    // (multipart), POST /{id}/extract, GET /{id} + /{id}/extraction, POST /{id}/
    // extraction-cancel. No second upload or extraction path is invented here (R40);
    // the gateway only assembles the external-agent contract: base64 payload, byte
    // ceiling from the mcp.doc.max-bytes governance key, dry_run preflight half-beat,
    // terminal-state cancel as an idempotent zero-change receipt. The task handle is
    // the document id itself (one extraction generation per row, token rotation is
    // an internal detail the poll surface never needs).

    private String docUpload(Map<String, Object> args, McpCaller caller) {
        long projectId = longArg(args, "project_id");
        requireProjectMember(projectId, caller);
        String name = stringArg(args, "name");
        byte[] content = decodeDocBase64(stringArg(args, "content_base64"));
        long cap = Math.max(1L, docMaxBytesSupplier.getAsLong());
        if (content.length > cap) {
            throw new ServiceException("文档大小 " + content.length + " 字节超过上限 " + cap
                    + " 字节（治理键 " + DOC_MAX_BYTES_KEY + "，可在系统设置调整），未上传",
                    BizErrorCode.VALIDATION_ERROR);
        }
        requireDocFormat(name, args.get("doc_type"));
        if (Boolean.TRUE.equals(args.get("dry_run"))) {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("dry_run", true);
            receipt.put("accepted", true);
            receipt.put("project_id", projectId);
            receipt.put("name", name);
            receipt.put("size_bytes", content.length);
            return writeJson(receipt);
        }
        org.springframework.http.client.MultipartBodyBuilder parts =
                new org.springframework.http.client.MultipartBodyBuilder();
        parts.part("file", new org.springframework.core.io.ByteArrayResource(content),
                        org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .filename(name);
        JsonNode doc = requireNode(RestClientInvoker.callMultipart(rest,
                "/internal/documents?projectId=" + projectId, parts, RESP_NODE,
                caller.userId()).toString());
        long documentId = doc.path("id").asLong();
        call(HttpMethod.POST, "/internal/documents/" + documentId + "/extract",
                Map.of(), RESP_NODE, caller.userId());
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("document_id", documentId);
        receipt.put("task_id", documentId);
        return writeJson(receipt);
    }

    private String docTaskPoll(Map<String, Object> args, McpCaller caller) {
        long taskId = longArg(args, "task_id");
        JsonNode doc = requireDocument(taskId, caller);
        int docStatus = doc.path("status").asInt(-1);
        Map<String, Object> out = new LinkedHashMap<>();
        if (docStatus == 2) {
            // parse-domain failures carry their plain-word reason on the row;
            // the preview door adds nothing (progress never fabricated).
            out.put("state", "parse_failed");
            out.put("progress", null);
            out.put("requirement_count", 0);
            out.put("fidelity_summary", null);
            out.put("reason", doc.path("errorMsg").asText(null));
            return writeJson(out);
        }
        JsonNode preview = requireNode(call(HttpMethod.GET,
                "/internal/documents/" + taskId + "/extraction?page=1&size=1",
                null, RESP_NODE, caller.userId()).toString());
        out.put("state", stateOfExtraction(preview.path("status").asInt(docStatus)));
        out.put("progress", latestStageLabel(preview));
        out.put("requirement_count", preview.path("total").asLong(0));
        out.put("fidelity_summary", preview.get("fidelity"));
        String extractError = preview.path("extractError").asText(null);
        if (extractError != null && !extractError.isBlank()) {
            out.put("reason", extractError);
        }
        return writeJson(out);
    }

    private String docTaskCancel(Map<String, Object> args, McpCaller caller) {
        long taskId = longArg(args, "task_id");
        requireDocument(taskId, caller);
        try {
            JsonNode rolled = requireNode(call(HttpMethod.POST,
                    "/internal/documents/" + taskId + "/extraction-cancel",
                    Map.of(), RESP_NODE, caller.userId()).toString());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cancelled", true);
            out.put("state", stateOfExtraction(rolled.path("status").asInt(-1)));
            return writeJson(out);
        } catch (ServiceException ex) {
            if (!Integer.valueOf(BizErrorCode.STATE_MACHINE_REJECT).equals(ex.getCode())) {
                throw ex; // foreign/missing rows propagate, never folded
            }
            // terminal-state cancel = idempotent zero-change receipt
            // (same judgment source as the in-product stop-extraction door).
            JsonNode preview = requireNode(call(HttpMethod.GET,
                    "/internal/documents/" + taskId + "/extraction?page=1&size=1",
                    null, RESP_NODE, caller.userId()).toString());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cancelled", false);
            out.put("state", stateOfExtraction(preview.path("status").asInt(-1)));
            return writeJson(out);
        }
    }

    /** Document row read + H1 segment gate (missing ownership shape = fail-closed). */
    private JsonNode requireDocument(long documentId, McpCaller caller) {
        JsonNode doc = requireNode(call(HttpMethod.GET, "/internal/documents/" + documentId,
                null, RESP_NODE, caller.userId()).toString());
        JsonNode projectId = doc.get("projectId");
        if (projectId == null || !projectId.canConvertToLong()) {
            throw new ServiceException("文档行缺少归属项目（core 返回形状异常，已拒绝继续）",
                    BizErrorCode.INTERNAL_ERROR);
        }
        requireProjectMember(projectId.asLong(), caller);
        return doc;
    }

    private static byte[] decodeDocBase64(String value) {
        try {
            return java.util.Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("content_base64 不是合法的 Base64 编码，未上传",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Format whitelist mirrors the core upload door (pdf/markdown), refused up front. */
    private static void requireDocFormat(String name, Object explicitType) {
        String type = explicitType == null
                ? extensionOf(name) : String.valueOf(explicitType).toLowerCase();
        if (!type.equals("pdf") && !type.equals("markdown") && !type.equals("md")) {
            throw new ServiceException("暂仅支持 PDF 与 Markdown 文档（当前格式: "
                    + (type.isEmpty() ? "未识别扩展名" : type) + "），请转换格式后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /** tp_document.status extension domain mapped to plain state words
     *  (0 parsing / 1 parsed / 2 parse failed / 3 extracting / 4 extracted). */
    private static String stateOfExtraction(int status) {
        return switch (status) {
            case 0 -> "parsing";
            case 1 -> "parsed";
            case 2 -> "parse_failed";
            case 3 -> "extracting";
            case 4 -> "extracted";
            default -> "unknown";
        };
    }

    /** Progress = the last landed stage text of this generation (honest read, no fabricated percent). */
    private static String latestStageLabel(JsonNode preview) {
        JsonNode stages = preview.path("stageLedger");
        if (!stages.isArray() || stages.isEmpty()) {
            return null;
        }
        return stages.get(stages.size() - 1).path("label").asText(null);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper ARG_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static JsonNode requireNode(String json) {
        try {
            return ARG_MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new ServiceException("core 返回解析失败：" + ex.getMessage(),
                    BizErrorCode.INTERNAL_ERROR);
        }
    }

    /** Agent-supplied object argument → compact JSON text (response builder pass-through). */
    private static String writeJson(Object value) {
        try {
            return ARG_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ServiceException("响应构造无法序列化为 JSON：" + ex.getMessage(),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static void appendIfPresent(StringBuilder uri, String name, String encodedValue) {
        if (encodedValue != null && !encodedValue.isBlank()) {
            uri.append('&').append(name).append('=').append(encodedValue);
        }
    }

    private static void appendPaging(StringBuilder uri, Map<String, Object> args) {
        uri.append("&page=").append(intOr(args.get("page"), 1));
        uri.append("&size=").append(Math.min(intOr(args.get("size"), 20), 100));
    }

    private static int intOr(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long longArg(Map<String, Object> args, String name) {
        if (args.get(name) instanceof Number number) {
            return number.longValue();
        }
        throw new ServiceException("缺少必填参数: " + name, BizErrorCode.VALIDATION_ERROR);
    }

    private static String stringArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ServiceException("缺少必填参数: " + name, BizErrorCode.VALIDATION_ERROR);
        }
        return String.valueOf(value);
    }

    private static List<String> listArg(Map<String, Object> args, String name) {
        if (args.get(name) instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        throw new ServiceException("缺少必填参数: " + name, BizErrorCode.VALIDATION_ERROR);
    }
}
