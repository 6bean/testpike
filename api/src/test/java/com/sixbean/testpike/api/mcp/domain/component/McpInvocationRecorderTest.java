package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.api.mcp.infrastructure.remote.McpInvocationRemoteInput;
import com.sixbean.testpike.api.mcp.infrastructure.remote.ToolInvocationRecordClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class McpInvocationRecorderTest {

    
    private static final class StubRecordClient implements ToolInvocationRecordClient {
        private final List<List<McpInvocationRemoteInput>> batches = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch gate;
        private volatile boolean fail = false;

        @Override
        public void record(List<McpInvocationRemoteInput> invocations) {
            if (fail) {
                throw new IllegalStateException("core 不可用");
            }
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    assertTrue(g.await(5, TimeUnit.SECONDS), "替身门闩超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            batches.add(new ArrayList<>(invocations));
        }
    }

    private static McpInvocationRemoteInput call(String tool) {
        return McpInvocationRemoteInput.ok(42L, null, tool, 10L, "{}");
    }

    @Test
    void 异步批量转发_单批一次HTTP() throws Exception {
        StubRecordClient client = new StubRecordClient();
        McpInvocationRecorder recorder = new McpInvocationRecorder(client);

        recorder.record(List.of(call("create_case"), call("tag_case")));

        long deadline = System.currentTimeMillis() + 3000;
        while (client.batches.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, client.batches.size());
        assertEquals(2, client.batches.get(0).size());
        assertEquals("create_case", client.batches.get(0).get(0).toolName());
        assertEquals("mcp", client.batches.get(0).get(0).caller());
    }

    @Test
    void 空批与null_零外呼() throws Exception {
        StubRecordClient client = new StubRecordClient();
        McpInvocationRecorder recorder = new McpInvocationRecorder(client);

        recorder.record(null);
        recorder.record(List.of());
        Thread.sleep(300);
        assertEquals(0, client.batches.size());
        assertEquals(0, recorder.droppedCount());
    }

    @Test
    void 写失败吞掉不阻断_后续批仍写() throws Exception {
        StubRecordClient client = new StubRecordClient();
        McpInvocationRecorder recorder = new McpInvocationRecorder(client);

        client.fail = true;
        recorder.record(List.of(call("get_cases")));
        long deadline = System.currentTimeMillis() + 3000;
        while (client.batches.isEmpty() && recorder.droppedCount() == 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        client.fail = false;
        recorder.record(List.of(call("get_cases")));
        deadline = System.currentTimeMillis() + 3000;
        while (client.batches.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, client.batches.size(), "失败链路不污染成功链路");
    }

    @Test
    void 队列满_丢弃计数_不抛业务() throws Exception {
        StubRecordClient client = new StubRecordClient();
        client.gate = new CountDownLatch(1);
        McpInvocationRecorder recorder = new McpInvocationRecorder(client);

        for (int i = 0; i < 300; i++) {
            recorder.record(List.of(call("get_cases")));
        }
        assertEquals(43, recorder.droppedCount(), "满队丢弃必须计数可见（丢观测不丢业务）");
        client.gate.countDown();
    }
}
