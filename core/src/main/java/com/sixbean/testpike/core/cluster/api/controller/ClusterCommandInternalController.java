package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandInput;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandView;
import com.sixbean.testpike.core.cluster.domain.service.ClusterCommandService;
import com.sixbean.testpike.core.cluster.domain.service.ClusterEnrollTokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Cluster command queue internal interface (a; invoked by the admin management bridge and the API protocol entry).
 * Five thin-forwarding endpoints have zero business semantics (value domain, target gate, CAS transitions, and drain coordination are all in service, ClusterEnrollInternal).
 * Controller uses the same approach: enqueue/list are management plane, poll/progress/result are protocol plane.
 * poll dispatches in two forms: executorId means an executor already in the pool claims directly; tokenDigest means a machine waiting to join.
 * The API layer has already converted the plaintext token into a sha256 digest (core does not accept plaintext), and after parsing the token row id from the digest it proceeds.
 * Fingerprint-bound claim; if parsing fails, yield an empty set (an invalid token means no available command, and the observability plane must not crash).
 */
@RestController
@RequestMapping("/internal/cluster/commands")
public class ClusterCommandInternalController {

    private final ClusterCommandService commandService;
    private final ClusterEnrollTokenService enrollTokenService;

    public ClusterCommandInternalController(ClusterCommandService commandService,
                                            ClusterEnrollTokenService enrollTokenService) {
        this.commandService = commandService;
        this.enrollTokenService = enrollTokenService;
    }

    /** Enqueue dispatch (command type, target type, engine catalog, and token state are gated item by item in service). */
    @PostMapping("/enqueue")
    public Response<ClusterCommandView> enqueue(@RequestBody Map<String, Object> body) {
        ClusterCommandInput input = new ClusterCommandInput(
                str(body.get("commandType")), str(body.get("targetType")),
                nullableLong(body.get("targetId"), "targetId"), payload(body.get("payload")),
                str(body.get("operator")));
        return Response.builderSuccess(commandService.enqueue(input));
    }

    /** Command list (management-plane projection; targetExecutorId defaults to all). */
    @GetMapping
    public Response<List<ClusterCommandView>> list(
            @RequestParam(required = false) Long targetExecutorId) {
        return Response.builderSuccess(commandService.list(targetExecutorId));
    }

    /** Pull and claim (CAS 0→1; two forms: executorId direct claim, or tokenDigest plus fingerprint claim via the token row). */
    @PostMapping("/poll")
    public Response<List<ClusterCommandView>> poll(@RequestBody Map<String, Object> body) {
        int limit = body.get("limit") == null ? 5 : (int) asLong(body.get("limit"), "limit");
        Long executorId = nullableLong(body.get("executorId"), "executorId");
        if (executorId != null) {
            return Response.builderSuccess(commandService.pollClaimed(executorId, limit));
        }
        String tokenDigest = str(body.get("tokenDigest"));
        if (tokenDigest != null && !tokenDigest.isBlank()) {
            Long tokenId = enrollTokenService.resolveTokenIdByDigest(tokenDigest);
            if (tokenId == null) {
                return Response.builderSuccess(List.of());
            }
            return Response.builderSuccess(commandService.pollClaimedForEnroll(
                    tokenId, str(body.get("fingerprint")), limit));
        }
        throw new ServiceException("领取命令必须携带执行器 id 或入池令牌摘要之一（本请求实收字段："
                + body.keySet() + "）——已在池内的执行器请传 executorId（id 可在「执行器池」页面查看）；"
                + "等待入池的机器请传 tokenDigest（入池令牌经 POST /internal/cluster/enroll-tokens 签发，"
                + "取其明文的 SHA-256 摘要填入，格式：tokenDigest=64 位十六进制串）",
                BizErrorCode.VALIDATION_ERROR);
    }

    /** Progress advance (1→2 idempotent refresh; step/message overwrite the node-card projection). */
    @PostMapping("/progress")
    public Response<Void> progress(@RequestBody Map<String, Object> body) {
        commandService.progress(asLong(body.get("commandId"), "commandId"),
                asLong(body.get("claimerId"), "claimerId"),
                str(body.get("step")), str(body.get("message")),
                str(body.get("tokenDigest")), str(body.get("fingerprint")));
        return Response.builderSuccess(null);
    }

    /** Receipt final state (2→3/4 with fault tolerance for 1→3/4; a successful scale-in releases the drain flag in the same transaction). */
    @PostMapping("/result")
    public Response<Void> result(@RequestBody Map<String, Object> body) {
        Object success = body.get("success");
        if (!(success instanceof Boolean flag)) {
            throw new ServiceException("命令回执必须携带 success 成败标志（当前：" + success + "）"
                    + "——请在 POST /internal/cluster/commands/result 请求体以布尔值回传执行结果，"
                    + "格式：\"success\": true（成功）或 false（失败，建议同时在 resultDetail 写明失败原因）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        commandService.complete(asLong(body.get("commandId"), "commandId"),
                asLong(body.get("claimerId"), "claimerId"),
                flag, str(body.get("resultDetail")),
                str(body.get("tokenDigest")), str(body.get("fingerprint")));
        return Response.builderSuccess(null);
    }

    /** Cancel only pending dispatch (already claimed cannot be canceled; plain-language rejection is in service). */
    @PostMapping("/{id}/revoke")
    public Response<Void> revoke(@PathVariable Long id) {
        commandService.revokePending(id);
        return Response.builderSuccess(null);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Per-field guidance text (entry screen / issuing endpoint / format example) reused by missing & non-numeric rejections. */
    private static final Map<String, String> FIELD_GUIDE = Map.of(
            "limit", "取值为纯数字，示例：\"limit\": 5，不传时按每次 5 条",
            "executorId", "执行器 id 可在「执行器池」页面查看",
            "targetId", "目标机器 id 可在「执行器池」页面查看；升级命令也可在「升级中心」→「升级计划」的「升级范围」中选择目标机器",
            "commandId", "命令 id 取 POST /internal/cluster/commands/poll 领取返回值中的 id，或经 GET /internal/cluster/commands 命令列表查询",
            "claimerId", "领取方执行器 id 即成功领取该命令的执行器 id，可在「执行器池」页面查看");

    /** Required numeric fields (missing or non-numeric yields a field-named rejection with recovery guidance; never a bare 500). */
    private static long asLong(Object value, String field) {
        Long parsed = nullableLong(value, field);
        if (parsed == null) {
            throw new ServiceException("命令协议请求缺少必填字段 " + field + "（格式：纯数字，示例：\""
                    + field + "\": 12）——"
                    + FIELD_GUIDE.getOrDefault(field, "请在请求体携带该字段")
                    + "；请补齐后重新发起本请求",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return parsed;
    }

    private static Long nullableLong(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("命令协议请求字段 " + field + " 必须是数字（当前：" + value
                    + "，格式：纯数字，示例：\"" + field + "\": 12）——"
                    + FIELD_GUIDE.getOrDefault(field, "请在请求体携带该字段")
                    + "；请改为纯数字后重新发起本请求",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
