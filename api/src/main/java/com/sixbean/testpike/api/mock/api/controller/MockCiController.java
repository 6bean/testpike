package com.sixbean.testpike.api.mock.api.controller;

import com.sixbean.testpike.api.ci.domain.component.CiBearerGate;
import com.sixbean.testpike.api.mcp.domain.component.McpPrincipal;
import com.sixbean.testpike.api.mock.api.dto.MockCiExportView;
import com.sixbean.testpike.api.mock.api.dto.MockCiSpaceCreateInput;
import com.sixbean.testpike.api.mock.api.dto.MockCiSpaceInput;
import com.sixbean.testpike.api.mock.infrastructure.remote.client.CoreMockCiClient;
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
 * CI-facing mock doors , 02 /open/mock/ci family): three endpoints so a
 * pipeline can mint a throwaway space, export a space to another dialect for
 * migration, and ask "did my run actually hit the mock" without paging the
 * whole inspector.
 * <p>Authentication = the shared {@link CiBearerGate} (scope=ci, one spectrum
 * with /open/ci — no second gate, red line 6). The create door is idempotent:
 * a missing Idempotency-Key is a plain-word 400 and the namespace prefix
 * {@code mockci:} keeps CI keys from colliding with batch keys (CiRunsController
 * JOB_PREFIX posture).
 * <p>Red line 6 / numeric honesty: all verdicts, counts and export contents come
 * straight from core services — the door computes nothing. Space-shape checks
 * beyond a non-blank key (charset, env ban, TTL range) stay single-point in core;
 * this intake only refuses what cannot possibly reach the bridge meaningfully.
 */
@RestController
@RequestMapping("/open/mock/ci")
public class MockCiController {

    /** Idempotency namespace — IdempotentGuard prefixes storage further; domain isolation prevents cross-domain key clashes. */
    private static final String JOB_PREFIX = "mockci:";

    private final CiBearerGate gate;
    private final CoreMockCiClient mockClient;
    private final IdempotentGuard idempotentGuard;

    public MockCiController(CiBearerGate gate, CoreMockCiClient mockClient,
                            IdempotentGuard idempotentGuard) {
        this.gate = gate;
        this.mockClient = mockClient;
        this.idempotentGuard = idempotentGuard;
    }

    /**
     * Mint a space for a pipeline run (POST /spaces?projectId=).
     * <p>ttlHours stamps a throwaway expiry the scheduled sweep later honours;
     * omitted = the space never expires (a deliberate, UI-created space).
     * A space-key clash surfaces core's 409 in plain words through the bridge.
     */
    @PostMapping("/spaces")
    public Response<Map<String, Object>> createSpace(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam long projectId,
            @RequestBody(required = false) MockCiSpaceCreateInput input) {
        McpPrincipal principal = gate.requireCiPrincipal(authorization);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ServiceException("缺少 Idempotency-Key 请求头：CI 建 mock 空间必须携带幂等键"
                    + "（同键重复调用返回首次结果、不重复建档——防管道重试堆僵尸空间）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        MockCiSpaceCreateInput body = input == null
                ? new MockCiSpaceCreateInput(null, null, null) : input;
        if (body.spaceKey() == null || body.spaceKey().isBlank()) {
            throw new ServiceException("spaceKey 必填：它是 /mocks/{spaceKey} 路由的对外句柄，"
                    + "CI 侧建议用「作业名-构建号」形态", BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(idempotentGuard.execute(JOB_PREFIX + idempotencyKey,
                () -> new HashMap<String, Object>(
                        Map.of("spaceId", doCreateSpace(projectId, body, principal.userId())))));
    }

    /**
     * Dialect export of a space (GET /spaces/{spaceId}/export?format=).
     * <p>The notes loss-list travels untouched — a migrated mock must never
     * pretend fidelity it lacks; pipelines print the notes next to the file.
     */
    @GetMapping("/spaces/{spaceId}/export")
    public Response<MockCiExportView> exportSpace(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long spaceId,
            @RequestParam String format) {
        gate.requireCiPrincipal(authorization);
        return Response.builderSuccess(mockClient.exportSpace(spaceId, format));
    }

    /**
     * Hit-ledger count for a space (GET /spaces/{spaceId}/hits/count).
     * <p>since is an ISO-8601 local date-time string passed raw (core owns
     * parsing and the ghost-space refusal); matched omitted = both spectra.
     */
    @GetMapping("/spaces/{spaceId}/hits/count")
    public Response<Long> countHits(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long spaceId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) Boolean matched) {
        gate.requireCiPrincipal(authorization);
        return Response.builderSuccess(mockClient.countHits(spaceId, since, matched));
    }

    /** First execution inside the idempotency window: name defaults to the routing key, untouched dials stay null for core defaults. */
    private long doCreateSpace(long projectId, MockCiSpaceCreateInput input, long operatorId) {
        String key = input.spaceKey().trim();
        String name = input.name() == null || input.name().isBlank() ? key : input.name().trim();
        MockCiSpaceInput spaceInput = new MockCiSpaceInput(name, key, null, null,
                null, null, null, null, null, null);
        return mockClient.createSpace(projectId, input.ttlHours(), spaceInput, operatorId);
    }
}
