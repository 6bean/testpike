package com.sixbean.testpike.core.tool.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.tool.api.dto.QueryResult;
import com.sixbean.testpike.core.tool.api.dto.QuerySpecRunRequest;
import com.sixbean.testpike.core.tool.api.dto.QueryTargetView;
import com.sixbean.testpike.core.tool.domain.service.QuerySpecService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal door of the declarative read-only aggregate proxy (AO batch
 * P3 : the ai-service dialog tool runs a model-composed QuerySpec
 * through this door and the server computes the numbers.
 * <p>Thin by rule — every boundary (registered target, DSL shape, column
 * allow list, project scope) is enforced by the interpreter behind
 * {@link QuerySpecService}; this door only pins the transport contract and
 * the fail-closed identity rule: an aggregate read with no operator behind it
 * is refused before any statement runs, because the five-way-gate visibility
 * re-check inside the interpreter is keyed on that identity.
 * <p>Additive door like ChatSessionInternalController (system-service level,
 * no project segment baked into the path — the caller names the project and
 * the interpreter forces the scope column).
 */
@RestController
@RequestMapping("/internal/query-spec")
public class QuerySpecInternalController {

    private final QuerySpecService querySpecService;

    public QuerySpecInternalController(QuerySpecService querySpecService) {
        this.querySpecService = querySpecService;
    }

    /** Compile + run one spec; the refusal sentences ride through untouched. */
    @PostMapping("/run")
    public Response<QueryResult> run(@RequestParam long projectId,
                                     @RequestHeader(value = "X-Operator-Id",
                                             required = false) Long operatorId,
                                     @RequestBody QuerySpecRunRequest request) {
        requireKnownOperator(operatorId);
        if (request == null || request.spec() == null || request.spec().isNull()) {
            throw new ServiceException("查询规格为空，已拒绝执行——请在工具入参中补充查询规格对象"
                    + "（格式: {\"target\":\"<可查询对象>\",\"filters\":[…]}，可查询对象清单见查询目标目录）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(
                querySpecService.runJson(request.spec().toString(), projectId, operatorId));
    }

    /** Callable-target vocabulary for the dialog tool description. */
    @GetMapping("/targets")
    public Response<List<QueryTargetView>> targets(
            @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        requireKnownOperator(operatorId);
        return Response.builderSuccess(querySpecService.callableTargets());
    }

    /** Fail-closed identity rule shared by both doors (single judgement, no copy). */
    private static void requireKnownOperator(Long operatorId) {
        if (operatorId == null || operatorId <= 0) {
            throw new ServiceException("只读聚合查询缺少操作人身份（X-Operator-Id），"
                    + "已拒绝执行——请在「AI 对话」中由登录用户发起统计；"
                    + "直连调用需补传请求头 X-Operator-Id（格式: 数字用户 id）", BizErrorCode.FORBIDDEN);
        }
    }
}
