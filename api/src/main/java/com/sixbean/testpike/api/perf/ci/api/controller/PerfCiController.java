package com.sixbean.testpike.api.perf.ci.api.controller;

import com.sixbean.testpike.api.ci.domain.component.CiBearerGate;
import com.sixbean.testpike.api.mcp.domain.component.McpPrincipal;
import com.sixbean.testpike.api.perf.ci.api.dto.PerfCiRunInput;
import com.sixbean.testpike.api.perf.ci.infrastructure.remote.client.PerfCiClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * CI-facing perf doors : launch a run, poll its
 * status, and ask the gate verdict, so a pipeline can wire load testing into
 * the same three-step shape it already uses for functional batches.
 * <p>Authentication = the shared {@link CiBearerGate} (scope=ci, one spectrum
 * with /open/ci — no second gate). The launch door is idempotent: a missing
 * Idempotency-Key is a plain 400 naming the header, and the {@code perfc:}
 * namespace keeps pipeline keys from colliding with batch keys (CiRunsController
 * JOB_PREFIX posture).
 * <p>Red line 6 / numeric honesty: status and verdict payloads are core's
 * single-source results passed straight through — the SLA judge head and every
 * launch gate (A-1 safety, environment lock, pinned snapshot) decide in core.
 * An invalid intake (missing ids) is refused here before touching the bridge;
 * ownership and business-shape refusals propagate with core's own wording.
 */
@RestController
@RequestMapping("/open/perf/ci")
public class PerfCiController {

    /** Idempotency namespace — IdempotentGuard prefixes storage further; domain isolation prevents cross-domain key clashes. */
    private static final String JOB_PREFIX = "perfc:";

    private final CiBearerGate gate;
    private final PerfCiClient perfClient;
    private final IdempotentGuard idempotentGuard;

    public PerfCiController(CiBearerGate gate, PerfCiClient perfClient,
                            IdempotentGuard idempotentGuard) {
        this.gate = gate;
        this.perfClient = perfClient;
        this.idempotentGuard = idempotentGuard;
    }

    /**
     * Launch one perf run from a pipeline (POST /runs; scope=ci +
     * Idempotency-Key required — a retried pipeline stage replays the first
     * result instead of pressing the target system twice).
     */
    @PostMapping("/runs")
    public Response<Map<String, Object>> run(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PerfCiRunInput input) {
        McpPrincipal principal = gate.requireCiPrincipal(authorization);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ServiceException("缺少 Idempotency-Key 请求头：CI 压测触发必须携带幂等键"
                    + "（同键重复调用返回首次结果、不重复起压——防流水线重试对目标系统二次加压）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input == null || input.projectId() == null || input.scenarioId() == null) {
            throw new ServiceException("请求体缺少 projectId 或 scenarioId："
                    + "CI 压测触发需指定项目与压测场景（场景 id 见性能压测页场景列表）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long projectId = input.projectId();
        long scenarioId = input.scenarioId();
        Long envId = input.envId();
        long operatorId = principal.userId();
        return Response.builderSuccess(idempotentGuard.execute(JOB_PREFIX + idempotencyKey,
                () -> {
                    long runId = perfClient.launch(projectId, scenarioId, envId, operatorId);
                    Map<String, Object> body = new HashMap<>();
                    body.put("runId", runId);
                    return body;
                }));
    }

    /** Status polling (GET /runs/{runId}?projectId=): core run view passed through. */
    @GetMapping("/runs/{runId}")
    public Response<Map<String, Object>> status(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long runId,
            @RequestParam long projectId) {
        gate.requireCiPrincipal(authorization);
        return Response.builderSuccess(perfClient.view(runId, projectId));
    }

    /** Gate verdict (GET /runs/{runId}/verdict?projectId=): core SLA judge head, zero local computation. */
    @GetMapping("/runs/{runId}/verdict")
    public Response<Map<String, Object>> verdict(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long runId,
            @RequestParam long projectId) {
        gate.requireCiPrincipal(authorization);
        return Response.builderSuccess(perfClient.ciVerdict(runId, projectId));
    }
}
