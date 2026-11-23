package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.api.mcp.infrastructure.remote.McpInvocationRemoteInput;
import com.sixbean.testpike.api.mcp.infrastructure.remote.ToolInvocationRecordClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


public class McpInvocationRecorder implements ToolInvocationRecordClient {

    private static final Logger log = LoggerFactory.getLogger(McpInvocationRecorder.class);

    
    static final int QUEUE_CAPACITY = 256;

    private final ToolInvocationRecordClient delegate;
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor executor;

    public McpInvocationRecorder(ToolInvocationRecordClient delegate) {
        this.delegate = delegate;
        this.executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "mcp-invocation-record");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public void record(List<McpInvocationRemoteInput> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return;
        }
        List<McpInvocationRemoteInput> snapshot = List.copyOf(invocations);
        try {
            executor.execute(() -> {
                try {
                    delegate.record(snapshot);
                } catch (Exception e) {
                    log.warn("MCP Server 端留痕写入失败（观测面丢失，不阻断调用应答）count={}: {}",
                            snapshot.size(), e.getMessage());
                }
            });
        } catch (Exception e) {
            long total = dropped.addAndGet(snapshot.size());
            log.warn("MCP Server 端留痕队列已满，丢弃 {} 条（累计 {}）：{}",
                    snapshot.size(), total, e.getMessage());
        }
    }

    
    public long droppedCount() {
        return dropped.get();
    }
}
