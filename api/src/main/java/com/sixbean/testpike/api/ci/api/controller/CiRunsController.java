package com.sixbean.testpike.api.ci.api.controller;

import com.sixbean.testpike.api.ci.api.dto.CiBadgeView;
import com.sixbean.testpike.api.ci.api.dto.CiCreateFromTemplateInput;
import com.sixbean.testpike.api.ci.api.dto.CiEnvOverrideInput;
import com.sixbean.testpike.api.ci.api.dto.CiRunInput;
import com.sixbean.testpike.api.ci.domain.component.CiBearerGate;
import com.sixbean.testpike.api.ci.domain.component.CiRunValidator;
import com.sixbean.testpike.api.ci.infrastructure.remote.client.CoreCiBatchClient;
import com.sixbean.testpike.api.mcp.domain.component.McpPrincipal;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/open/ci")
public class CiRunsController {

    
    private static final String JOB_PREFIX = "ci:";

    private final CiBearerGate gate;
    private final CiRunValidator validator;
    private final CoreCiBatchClient batchClient;
    private final IdempotentGuard idempotentGuard;

    public CiRunsController(CiBearerGate gate, CiRunValidator validator,
                            CoreCiBatchClient batchClient, IdempotentGuard idempotentGuard) {
        this.gate = gate;
        this.validator = validator;
        this.batchClient = batchClient;
        this.idempotentGuard = idempotentGuard;
    }

    
    @PostMapping("/runs")
    public Response<Map<String, Object>> run(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CiRunInput input) {
        McpPrincipal principal = gate.requireCiPrincipal(authorization);
        validator.validate(input);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ServiceException("缺少 Idempotency-Key 请求头：CI 触发必须携带幂等键"
                    + "（同键重复调用返回首次结果、不重复建批/启动——防管道重试重复跑批）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(idempotentGuard.execute(JOB_PREFIX + idempotencyKey,
                () -> new HashMap<>(doRun(input, principal.userId()))));
    }

    
    @GetMapping("/runs/{batchId}")
    public Response<Map<String, Object>> status(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long batchId) {
        gate.requireCiPrincipal(authorization);
        return Response.builderSuccess(batchClient.get(batchId));
    }

    
    @GetMapping("/runs/{batchId}/report")
    public Response<Map<String, Object>> report(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long batchId) {
        gate.requireCiPrincipal(authorization);
        CiBadgeView badge = batchClient.badgeByBatchId(batchId);
        Map<String, Object> report = new HashMap<>();
        report.put("batchId", badge.batchId());
        report.put("status", badge.status());
        report.put("statusLabel", badge.statusLabel());
        report.put("passRate", badge.passRate());
        report.put("passRatePhrase", badge.passRatePhrase());
        report.put("cases", batchClient.listCases(batchId));
        return Response.builderSuccess(report);
    }

    
    @GetMapping("/runs/{batchId}/verdict")
    public Response<Map<String, Object>> verdict(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long batchId) {
        gate.requireCiPrincipal(authorization);
        return Response.builderSuccess(batchClient.verdict(batchId));
    }

    
    private Map<String, Object> doRun(CiRunInput input, long operatorId) {
        if (input.batch() != null) {
            return batchClient.start(input.batch(), toOverride(input), operatorId);
        }
        long templateId = resolveTemplateId(input.projectId(), input.profile());
        Map<String, Object> created = batchClient.createFromTemplate(
                new CiCreateFromTemplateInput(templateId, null, input.envId()), operatorId);
        long newBatchId = ((Number) created.get("id")).longValue();
        return batchClient.start(newBatchId, toOverride(input), operatorId);
    }

    
    private CiEnvOverrideInput toOverride(CiRunInput input) {
        if (input.envOverrides() == null) {
            return null;
        }
        return new CiEnvOverrideInput(input.envOverrides().get("url"),
                input.envOverrides().get("accountRef"));
    }

    
    private long resolveTemplateId(long projectId, String profile) {
        List<Map<String, Object>> matched = batchClient.listTemplates(projectId).stream()
                .filter(t -> profile.equals(t.get("name")))
                .toList();
        if (matched.isEmpty()) {
            throw new ServiceException("项目 " + projectId + " 下无名为「" + profile
                    + "」的批次模板：请在 testPike「批次模板」页确认名称，或改用 batch 直传批次 id",
                    BizErrorCode.NOT_FOUND);
        }
        if (matched.size() > 1) {
            throw new ServiceException("模板名「" + profile + "」在项目 " + projectId
                    + " 下有 " + matched.size() + " 个同名模板，无法消歧：请改用 batch 直传批次 id",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return ((Number) matched.get(0).get("id")).longValue();
    }
}
