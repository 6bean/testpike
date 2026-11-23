package com.sixbean.testpike.core.analysis.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.analysis.api.dto.AnalysisRunRequest;
import com.sixbean.testpike.core.analysis.domain.component.AnalysisFunctionDispatcher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal door of the read-only analysis channel : the
 * ai-service dialog analysis tool asks the server to compute coverage or
 * assertion-density numbers for the conversation's project segment.
 * <p>Thin by rule — the function vocabulary, the filter shape and the project
 * visibility re-check all live behind {@link AnalysisFunctionDispatcher}; this
 * door only pins the transport contract and the fail-closed identity rule, the
 * same posture as the aggregate-query door: an analysis read with no operator
 * behind it is refused before any statement runs, because the visibility
 * re-check is keyed on that identity.
 * <p>The project segment is a transport parameter, never a body key: the dialog
 * side hands its own trusted conversation projectId, so the model can never
 * name a project it should not see.
 */
@RestController
@RequestMapping("/internal/analysis")
public class AnalysisInternalController {

    private final AnalysisFunctionDispatcher dispatcher;

    public AnalysisInternalController(AnalysisFunctionDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** Run one registered analysis function; refusal sentences ride through. */
    @PostMapping("/run")
    public Response<Object> run(@RequestParam long projectId,
                                @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId,
                                @RequestBody(required = false) AnalysisRunRequest request) {
        requireKnownOperator(operatorId);
        if (request == null) {
            throw new ServiceException("分析请求体为空，已拒绝执行（需要 {function, args?} 对象）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(
                dispatcher.run(projectId, operatorId, request.function(), request.args()));
    }

    /** Fail-closed identity rule of the door (same judgement wording family). */
    private static void requireKnownOperator(Long operatorId) {
        if (operatorId == null || operatorId <= 0) {
            throw new ServiceException("只读分析查询缺少操作人身份（X-Operator-Id），已拒绝执行——"
                    + "覆盖率与断言密度需由登录用户的对话发起", BizErrorCode.FORBIDDEN);
        }
    }
}
