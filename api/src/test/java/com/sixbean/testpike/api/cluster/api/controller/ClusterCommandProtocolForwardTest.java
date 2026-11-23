package com.sixbean.testpike.api.cluster.api.controller;

import com.sixbean.testpike.api.cluster.infrastructure.remote.client.CoreClusterEnrollClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ClusterCommandProtocolForwardTest {

    
    private static final class RecordingClient implements CoreClusterEnrollClient {
        final List<String> calls = new ArrayList<>();
        Map<String, Object> enrollReturn = Map.of();
        List<Map<String, Object>> pollReturn = List.of(Map.of("id", 12L));

        @Override
        public Map<String, Object> enroll(String token, String fingerprint) {
            throw new UnsupportedOperationException();
        }

        Map<String, Object> heartbeatReturn = Map.of("accepted", true);
        Map<String, Object> recipeReturn = Map.of("code", "mobilewright");

        @Override
        public Map<String, Object> heartbeat(String token, String fingerprint, String step,
                                             String stepStatus, String message,
                                             List<?> components) {
            calls.add("heartbeat:" + token + ":" + fingerprint + ":" + step
                    + ":" + stepStatus + ":" + message + ":" + components);
            return heartbeatReturn;
        }

        @Override
        public Map<String, Object> installkitConfig() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> engineRecipe(String code) {
            calls.add("recipe:" + code);
            return recipeReturn;
        }

        @Override
        public List<Map<String, Object>> pollCommand(Map<String, Object> body) {
            calls.add("poll:" + body);
            return pollReturn;
        }

        @Override
        public void progressCommand(Map<String, Object> body) {
            calls.add("progress:" + body);
        }

        @Override
        public void completeCommand(Map<String, Object> body) {
            calls.add("result:" + body);
        }
    }

    @Test
    void poll转发_请求体直传桥客户端_响应透传() {
        RecordingClient client = new RecordingClient();
        ClusterEnrollProtocolController controller = new ClusterEnrollProtocolController(client);
        Map<String, Object> body = Map.of("token", "t1", "fingerprint", "fp");
        Response<List<Map<String, Object>>> resp = controller.poll(body);
        assertEquals("0", resp.getCode());
        assertSame(client.pollReturn, resp.getData(), "响应零加工透传");
        String recorded = client.calls.get(0);
        assertTrue(recorded.startsWith("poll:") && recorded.contains("token=t1")
                        && recorded.contains("fingerprint=fp"),
                "控制器零解析，token 明文原样交桥层折摘要（单点=桥客户端）: " + recorded);
    }

    @Test
    void progress与result转发_零解析直传() {
        RecordingClient client = new RecordingClient();
        ClusterEnrollProtocolController controller = new ClusterEnrollProtocolController(client);
        controller.progress(Map.of("commandId", 12, "claimerId", 3, "step", "s1"));
        controller.result(Map.of("commandId", 12, "claimerId", 3, "success", true));
        assertEquals(2, client.calls.size());
        assertTrue(client.calls.get(0).startsWith("progress:")
                && client.calls.get(0).contains("commandId=12")
                && client.calls.get(0).contains("step=s1"), client.calls.get(0));
        assertTrue(client.calls.get(1).startsWith("result:")
                && client.calls.get(1).contains("success=true"), client.calls.get(1));
    }

    @Test
    void heartbeat转发_step三键体透传桥客户端() {
        RecordingClient client = new RecordingClient();
        ClusterEnrollProtocolController controller = new ClusterEnrollProtocolController(client);
        var resp = controller.heartbeat(Map.of("token", "t1", "fingerprint", "fp",
                "step", "deps", "stepStatus", "done", "message", "依赖装毕"));
        assertEquals("0", resp.getCode());
        String recorded = client.calls.get(0);
        assertTrue(recorded.startsWith("heartbeat:t1:fp:deps:done:依赖装毕"), recorded);
    }

    @Test
    void heartbeat转发_components尾追键原样交桥() {
        // pack C (03 §2.8): the protocol entry stays parse-free —
        // trailing components rows hand over to the bridge client untouched.
        RecordingClient client = new RecordingClient();
        ClusterEnrollProtocolController controller = new ClusterEnrollProtocolController(client);
        var rows = java.util.List.of(Map.of("family", "core", "currentVersion", "1.0.0"));
        var resp = controller.heartbeat(Map.of("token", "t9", "fingerprint", "fp9",
                "components", rows));
        assertEquals("0", resp.getCode());
        assertTrue(client.calls.get(0).startsWith("heartbeat:t9:fp9:null:null:null:["),
                client.calls.get(0));
        assertTrue(client.calls.get(0).contains("core"), client.calls.get(0));
    }

    @Test
    void engineRecipe转发_按码直传_响应透传() {
        RecordingClient client = new RecordingClient();
        ClusterEnrollProtocolController controller = new ClusterEnrollProtocolController(client);
        var resp = controller.engineRecipe("mobilewright");
        assertEquals("0", resp.getCode());
        assertSame(client.recipeReturn, resp.getData(), "引擎详情零加工透传");
        assertEquals("recipe:mobilewright", client.calls.get(0));
    }
}
