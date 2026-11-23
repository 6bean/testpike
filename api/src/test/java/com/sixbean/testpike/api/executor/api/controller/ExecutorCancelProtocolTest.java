package com.sixbean.testpike.api.executor.api.controller;

import com.sixbean.testpike.api.executor.infrastructure.remote.client.CoreExecutorClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ExecutorCancelProtocolTest {

    
    private static final class StubCoreExecutorClient implements CoreExecutorClient {
        final List<String> calls = new ArrayList<>();
        Map<String, Object> cancelReturn = Map.of("ok", true, "status", 7);
        Map<String, Object> cacheHitReturn = Map.of();
        Map<String, Object> suggestionRecordReturn = Map.of("suggestionId", 77L);
        Map<String, Object> heartbeatReturn = Map.of(
                "ok", true, "serverTime", "now", "actions", List.of(),
                "cancelTaskIds", List.of(11L, 12L));

        @Override
        public Map<String, Object> register(Map<String, Object> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> heartbeat(long executorId, Map<String, Object> body) {
            calls.add("heartbeat:" + executorId);
            return heartbeatReturn;
        }

        @Override
        public Map<String, Object> claim(Map<String, Object> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> submitResult(Map<String, Object> body) {
            calls.add("submitResult:" + body.get("taskId"));
            return Map.of("accepted", true);
        }

        @Override
        public Map<String, Object> grantArtifact(Map<String, Object> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> elementEvidenceGrant(Map<String, Object> body) {
            return grantArtifact(body);
        }

        @Override
        public Map<String, Object> occupyPoolItem(long itemId, long projectId, long lockedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> releasePoolItem(long itemId, long projectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> cancelTask(long taskId) {
            calls.add("cancelTask:" + taskId);
            return cancelReturn;
        }

        @Override
        public Map<String, Object> ingestSessionArtifacts(Map<String, Object> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> renewDeviceLease(long deviceId, long executorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> releaseDeviceLease(long deviceId, long executorId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> visualLocate(Map<String, Object> body) {
            calls.add("visualLocate:" + body.get("taskId"));
            return Map.of("x", 512, "y", 384, "label", "登录按钮", "confidence", 0.93);
        }

        @Override
        public Map<String, Object> aiSampleCacheHit(Map<String, Object> body) {
            calls.add("aiSampleCacheHit:" + body.get("taskId"));
            return cacheHitReturn;
        }

        @Override
        public Map<String, Object> aiSampleRecord(Map<String, Object> body) {
            calls.add("aiSampleRecord:" + body.get("taskId"));
            return Map.of("sampleId", 501L);
        }

        @Override
        public Map<String, Object> stepSuggestionRecord(Map<String, Object> body) {
            calls.add("stepSuggestionRecord:" + body.get("taskId"));
            return suggestionRecordReturn;
        }

        @Override
        public Map<String, Object> grantPackageDownload(Map<String, Object> body) {
            calls.add("grantPackageDownload:" + body.get("artifactRef"));
            return Map.of("appId", 8L, "downloadUrl", "http://s3/presigned");
        }

        @Override
        public Map<String, Object> grantPerfArtifactDownload(Map<String, Object> body) {
            // Not exercised by the cancel spectra; the relay contract lives in
            // the perf protocol tests. Stub kept complete so the module compiles.
            throw new UnsupportedOperationException("perf grant is not exercised here");
        }

        @Override
        public List<Map<String, Object>> takeoverInputPoll(Map<String, Object> body) {
            calls.add("takeoverInputPoll:" + body.get("executorId"));
            return List.of(Map.of("verb", "tap", "x", 1, "y", 2));
        }

        @Override
        public Map<String, Object> claimCaptureSession(long executorId) {
            // Capture-session claiming belongs to the login-state capture
            // spectra (executor-side); the cancel spectra never hit it.
            throw new UnsupportedOperationException("capture claim is not exercised here");
        }

        @Override
        public Long reportCaptureSession(String sessionId, Map<String, Object> body) {
            // The capture-session relay is exercised by the executor-side and
            // core-side protocol tests; the cancel spectra never hit it.
            // Stub kept complete so the module compiles.
            throw new UnsupportedOperationException("capture report is not exercised here");
        }

        @Override
        public Long commitCaptureValues(String sessionId, Map<String, Object> body) {
            // The commit-round value door belongs to the capture spectra
            // (executor-side); the cancel spectra never hit it. Stub kept
            // complete so the module compiles.
            throw new UnsupportedOperationException("capture commit values is not exercised here");
        }
    }

    @Test
    void cancel端点直转core响应透传() {
        StubCoreExecutorClient stub = new StubCoreExecutorClient();
        ExecutorProtocolController controller = new ExecutorProtocolController(stub);

        Response<Map<String, Object>> resp = controller.cancelTask(42L);

        assertEquals(List.of("cancelTask:42"), stub.calls);
        assertEquals(true, resp.getData().get("ok"));
        assertEquals(7, resp.getData().get("status"), "core 终态码透传不加工（api 层零业务语义）");
    }

    @Test
    void visualLocate端点直转core响应点位透传() {
        StubCoreExecutorClient stub = new StubCoreExecutorClient();
        ExecutorProtocolController controller = new ExecutorProtocolController(stub);

        Map<String, Object> inquire = Map.of(
                "taskId", 88L,
                "screenshotB64", "ZmFrZQ==",
                "screenshotMime", "image/png",
                "verb", "tap");
        Response<Map<String, Object>> resp = controller.visualLocate(inquire);

        assertEquals(List.of("visualLocate:88"), stub.calls,
                "api 协议入口仅转发（Map 直转零业务语义，执行器禁直连 core/ai-service）");
        assertEquals(512, resp.getData().get("x"), "core 归一点位原样透传不加工");
        assertEquals(384, resp.getData().get("y"));
        assertEquals(0.93, resp.getData().get("confidence"));
    }

    @Test
    void aiSamples两端点直转core响应透传() {
        StubCoreExecutorClient stub = new StubCoreExecutorClient();
        ExecutorProtocolController controller = new ExecutorProtocolController(stub);

        Map<String, Object> inquire = Map.of("taskId", 91L, "stepNo", 3,
                "anchor", "step3|app.tap|deadbeef", "deviceId", 7L);
        Response<Map<String, Object>> miss = controller.aiSampleCacheHit(inquire);
        assertTrue(miss.getData().isEmpty(), "空票应答原样透传（api 零业务语义）");

        stub.cacheHitReturn = Map.of("sampleId", 5L, "source", "vision", "x", 120, "y", 640);
        Response<Map<String, Object>> hit = controller.aiSampleCacheHit(inquire);
        assertEquals(120, hit.getData().get("x"), "core 确认票点位原样透传");

        Response<Map<String, Object>> recorded = controller.aiSampleRecord(
                Map.of("taskId", 91L, "reviewResult", "confirmed", "x", 120, "y", 640));
        assertEquals(501L, recorded.getData().get("sampleId"));
        assertEquals(List.of("aiSampleCacheHit:91", "aiSampleCacheHit:91", "aiSampleRecord:91"),
                stub.calls, "归属解析/值域闸全在 core，api 只转发");
    }

    @Test
    void 自愈建议单建单口直转core响应透传() {
        StubCoreExecutorClient stub = new StubCoreExecutorClient();
        ExecutorProtocolController controller = new ExecutorProtocolController(stub);

        Response<Map<String, Object>> resp = controller.stepSuggestionRecord(Map.of(
                "taskId", 91L, "stepNo", 2, "failureClass", 2,
                "anchor", "step2|app.tap|deadbeef", "source", "vision",
                "proposedAction", "app.tap", "proposedTarget", "{\"contentDesc\":\"登录\"}",
                "x", 180, "y", 640));

        assertEquals(List.of("stepSuggestionRecord:91"), stub.calls, "api 只转发禁二套校验");
        assertEquals(77L, resp.getData().get("suggestionId"), "core 单号原样透传");
    }

    @Test
    void 包源下载授权口直转core响应透传() {
        StubCoreExecutorClient stub = new StubCoreExecutorClient();
        ExecutorProtocolController controller = new ExecutorProtocolController(stub);

        Response<Map<String, Object>> resp = controller.grantPackageDownload(Map.of(
                "taskId", 77L, "artifactRef", "com.demo.app"));

        assertEquals(List.of("grantPackageDownload:com.demo.app"), stub.calls,
                "api 只转发禁二套校验");
        assertEquals("http://s3/presigned", resp.getData().get("downloadUrl"),
                "core 签发预签名原样透传");
    }

    @Test
    void heartbeat响应保留cancelTaskIds槽() {
        StubCoreExecutorClient stub = new StubCoreExecutorClient();
        ExecutorProtocolController controller = new ExecutorProtocolController(stub);

        Response<Map<String, Object>> resp = controller.heartbeat(7L, Map.of("runningTasks", 1));

        assertEquals(List.of("heartbeat:7"), stub.calls);
        assertEquals(List.of(11L, 12L), resp.getData().get("cancelTaskIds"),
                "kill 指令槽透传（03 协议 2.2 cancelTaskIds，执行器按此终止进程）");
    }

    @Test
    void 接管输入领取端点直转core零业务语义() {
        StubCoreExecutorClient stub = new StubCoreExecutorClient();
        ExecutorProtocolController controller = new ExecutorProtocolController(stub);

        Response<List<Map<String, Object>>> resp = controller.pollTakeoverInput(
                java.util.Map.of("executorId", 9L, "limit", 5));

        assertEquals(List.of("takeoverInputPoll:9"), stub.calls,
                "api 协议入口只转发——生效态/持有者闸全在 core");
        assertEquals(1, resp.getData().size(), "core 出队投影原样透传");
    }
}
