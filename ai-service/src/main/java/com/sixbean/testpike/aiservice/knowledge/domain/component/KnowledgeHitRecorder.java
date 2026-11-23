package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.KnowledgeHitRemoteInput;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


@Component
public class KnowledgeHitRecorder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeHitRecorder.class);

    
    static final int QUEUE_CAPACITY = 256;

    private final CoreGateway coreGateway;
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor executor;

    public KnowledgeHitRecorder(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
        this.executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "knowledge-hit");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    
    public void record(Long taskId, String scene, List<KnowledgeSearchItem> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        Map<Long, List<String>> byDoc = new LinkedHashMap<>();
        for (KnowledgeSearchItem hit : hits) {
            if (hit.docId() == null) {
                continue;
            }
            byDoc.computeIfAbsent(hit.docId(), k -> new ArrayList<>()).add(hit.chunkId());
        }
        if (byDoc.isEmpty()) {
            return;
        }
        List<KnowledgeHitRemoteInput> inputs = new ArrayList<>(byDoc.size());
        for (Map.Entry<Long, List<String>> entry : byDoc.entrySet()) {
            inputs.add(new KnowledgeHitRemoteInput(entry.getKey(), taskId, scene,
                    entry.getValue()));
        }
        try {
            executor.execute(() -> {
                try {
                    coreGateway.recordKnowledgeHits(inputs);
                } catch (Exception e) {
                    log.warn("知识检索留痕写失败（观测面不阻断）: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            long total = dropped.incrementAndGet();
            log.warn("知识留痕队列满/拒绝，丢弃 {} 行（累计丢弃 {}）: {}",
                    inputs.size(), total, e.getMessage());
        }
    }

    
    public long droppedCount() {
        return dropped.get();
    }
}
