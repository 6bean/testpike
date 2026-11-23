package com.sixbean.testpike.aiservice.mcp.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpInvocationRemoteInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


@Component
public class ToolInvocationRecorder {

    private static final Logger log = LoggerFactory.getLogger(ToolInvocationRecorder.class);

    
    static final int QUEUE_CAPACITY = 256;

    private final CoreGateway coreGateway;
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor executor;

    public ToolInvocationRecorder(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
        this.executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "tool-invocation");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    
    public void record(List<McpInvocationRemoteInput> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return;
        }
        List<McpInvocationRemoteInput> snapshot = List.copyOf(invocations);
        try {
            executor.execute(() -> {
                try {
                    coreGateway.recordToolInvocations(snapshot);
                } catch (Exception e) {
                    log.warn("MCP 调用留痕写入失败（观测面丢失，不阻断生成）count={}: {}",
                            snapshot.size(), e.getMessage());
                }
            });
        } catch (Exception e) {
            long total = dropped.addAndGet(snapshot.size());
            log.warn("MCP 调用留痕队列已满，丢弃 {} 条（累计 {}）：{}",
                    snapshot.size(), total, e.getMessage());
        }
    }

    
    public long droppedCount() {
        return dropped.get();
    }
}
