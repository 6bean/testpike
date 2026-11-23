package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillUsageRemoteInput;
import com.sixbean.testpike.aiservice.aictx.domain.component.CtxMemoryHitWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


@Component
public class SkillUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageRecorder.class);

    
    static final int QUEUE_CAPACITY = 256;

    private final CoreGateway coreGateway;
    private final CtxMemoryHitWriter memoryHitWriter;
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor executor;

    public SkillUsageRecorder(CoreGateway coreGateway, CtxMemoryHitWriter memoryHitWriter) {
        this.coreGateway = coreGateway;
        this.memoryHitWriter = memoryHitWriter;
        this.executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "skill-usage");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
 * Project-memory injection hits : every tp_ai_ctx_memory
 * id that actually took part in a system-prompt assembly bumps its hit_count —
 * async through the SAME single-thread bounded queue as SKILL traces (full
 * queue = drop-with-counter; the observation plane never blocks generation).
 * Write side is single-sourced in the aictx domain's CtxMemoryHitWriter.
 */
    public void recordMemoryHits(List<Long> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        List<Long> snapshot = List.copyOf(memoryIds);
        try {
            executor.execute(() -> {
                try {
                    memoryHitWriter.recordHits(snapshot);
                } catch (Exception e) {
                    log.warn("项目记忆命中自增失败（观测面不阻断）: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            long total = dropped.incrementAndGet();
            log.warn("项目记忆命中队列满/拒绝，丢弃 {} 条（累计丢弃 {}）: {}",
                    snapshot.size(), total, e.getMessage());
        }
    }

    
    public void record(Long taskId, List<SkillUsageTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return;
        }
        List<SkillUsageRemoteInput> inputs = new ArrayList<>(traces.size());
        for (SkillUsageTrace trace : traces) {
            inputs.add(new SkillUsageRemoteInput(taskId, trace.skillId(), trace.version(),
                    trace.stage(), trace.dropped()));
        }
        try {
            executor.execute(() -> {
                try {
                    coreGateway.recordSkillUsages(inputs);
                } catch (Exception e) {
                    log.warn("SKILL 消费留痕写失败（观测面不阻断）: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            long total = dropped.incrementAndGet();
            log.warn("SKILL 留痕队列满/拒绝，丢弃 {} 条（累计丢弃 {}）: {}",
                    inputs.size(), total, e.getMessage());
        }
    }

    
    public long droppedCount() {
        return dropped.get();
    }
}
