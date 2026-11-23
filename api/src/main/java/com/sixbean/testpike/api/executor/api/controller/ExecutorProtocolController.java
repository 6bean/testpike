package com.sixbean.testpike.api.executor.api.controller;

import com.sixbean.testpike.api.executor.infrastructure.remote.client.CoreExecutorClient;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/internal")
public class ExecutorProtocolController {

    private final CoreExecutorClient coreExecutorClient;

    public ExecutorProtocolController(CoreExecutorClient coreExecutorClient) {
        this.coreExecutorClient = coreExecutorClient;
    }

    
    @PostMapping("/executors/register")
    public Response<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.register(body));
    }

    
    @PostMapping("/executors/heartbeat")
    public Response<Map<String, Object>> heartbeat(@RequestParam long executorId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.heartbeat(executorId,
                body == null ? Map.of() : body));
    }

    
    @PostMapping("/tasks/claim")
    public Response<Map<String, Object>> claim(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.claim(body));
    }

    
    @PostMapping("/tasks/result")
    public Response<Map<String, Object>> submitResult(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.submitResult(body));
    }

 
    @PostMapping("/tasks/artifact")
    public Response<Map<String, Object>> grantArtifact(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.grantArtifact(body));
    }

    
    @PostMapping("/tasks/session-artifacts")
    public Response<Map<String, Object>> ingestSessionArtifacts(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.ingestSessionArtifacts(body));
    }

 
    @PostMapping("/pool-items/{id}/occupy")
    public Response<Map<String, Object>> occupyPoolItem(@PathVariable long id,
                                                        @RequestParam long projectId,
                                                        @RequestParam long lockedBy) {
        return Response.builderSuccess(coreExecutorClient.occupyPoolItem(id, projectId, lockedBy));
    }

    
    @PostMapping("/pool-items/{id}/release")
    public Response<Map<String, Object>> releasePoolItem(@PathVariable long id,
                                                         @RequestParam long projectId) {
        return Response.builderSuccess(coreExecutorClient.releasePoolItem(id, projectId));
    }

    
    @PostMapping("/executors/tasks/{taskId}/cancel")
    public Response<Map<String, Object>> cancelTask(@PathVariable long taskId) {
        return Response.builderSuccess(coreExecutorClient.cancelTask(taskId));
    }

    
    @PostMapping("/appauto/devices/{deviceId}/lease/renew")
    public Response<Map<String, Object>> renewDeviceLease(@PathVariable long deviceId,
                                                          @RequestParam long executorId) {
        return Response.builderSuccess(coreExecutorClient.renewDeviceLease(deviceId, executorId));
    }

    
    @PostMapping("/appauto/packages/download-grant")
    public Response<Map<String, Object>> grantPackageDownload(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.grantPackageDownload(body));
    }

    /**
 * Perf compiled-artifact download grant : relay the
 * executor's objectKey to core verbatim with zero business semantics — the
 * perf-artifact scene guard and presigning live in core; the downloadUrl is
 * for in-memory use during the run only (never step text, never persisted).
 */
    @PostMapping("/perf/artifacts/download-grant")
    public Response<Map<String, Object>> grantPerfArtifactDownload(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.grantPerfArtifactDownload(body));
    }

    
    @PostMapping("/appauto/devices/{deviceId}/lease/release")
    public Response<Map<String, Object>> releaseDeviceLease(@PathVariable long deviceId,
                                                            @RequestParam long executorId,
                                                            @RequestParam(required = false) String reason) {
        return Response.builderSuccess(
                coreExecutorClient.releaseDeviceLease(deviceId, executorId, reason));
    }

    
    @PostMapping("/appauto/takeover/input/poll")
    public Response<java.util.List<Map<String, Object>>> pollTakeoverInput(
            @RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.takeoverInputPoll(body));
    }

    
    @PostMapping("/session-capture/claim")
    public Response<Map<String, Object>> claimCaptureSession(@RequestBody Map<String, Object> body) {
        // The executor hands off executorId in the JSON body (same posture as
        // /tasks/claim); the L3 four-state window proved a @RequestParam door
        // here never receives it, so the protocol face parses the body and
        // refuses a missing/non-numeric id with an honest validation error.
        Object raw = body == null ? null : body.get("executorId");
        if (!(raw instanceof Number n)) {
            throw new ServiceException("缺少必填参数: executorId", BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(coreExecutorClient.claimCaptureSession(n.longValue()));
    }

    
    @PostMapping("/session-capture/{sessionId}/report")
    public Response<Long> reportCaptureSession(@PathVariable String sessionId,
                                              @RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.reportCaptureSession(sessionId, body));
    }

    /**
 * Commit-round value write-back : the api
 * protocol door forwards the Map verbatim with zero business semantics —
 * the checked-subset re-filter, the origin whitelist gate and the vault
 * registration live in core. Values transit memory only: this layer never
 * persists them and never logs them (red lines 3/4).
 */
    @PostMapping("/session-capture/{sessionId}/commit-values")
    public Response<Long> commitCaptureValues(@PathVariable String sessionId,
                                              @RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.commitCaptureValues(sessionId, body));
    }

    
    @PostMapping("/appauto/visual-locate")
    public Response<Map<String, Object>> visualLocate(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.visualLocate(body));
    }

    
    @PostMapping("/appauto/ai-samples/cache-hit")
    public Response<Map<String, Object>> aiSampleCacheHit(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.aiSampleCacheHit(body));
    }

 
    @PostMapping("/appauto/ai-samples/record")
    public Response<Map<String, Object>> aiSampleRecord(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.aiSampleRecord(body));
    }

    
    @PostMapping("/appauto/step-suggestions/record")
    public Response<Map<String, Object>> stepSuggestionRecord(
            @RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.stepSuggestionRecord(body));
    }

    /**
 * Evidence-plane baseline grant ( piece 2b; core reads the ledger
 * row's evidence.screenshotKey and answers a presigned GET plus the live
 * revision). api protocol entrance Map passthrough with zero business
 * semantics — the found/blank/dirty judgements all live in core.
 */
    @PostMapping("/appauto/element-evidence-grant")
    public Response<Map<String, Object>> elementEvidenceGrant(
            @RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreExecutorClient.elementEvidenceGrant(body));
    }
}
