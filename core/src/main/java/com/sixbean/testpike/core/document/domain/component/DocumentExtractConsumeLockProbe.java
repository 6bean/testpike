package com.sixbean.testpike.core.document.domain.component;

import com.sixbean.testpike.common.idempotent.ExtractDedupKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * ②: tells the core extract entry whether a live ai-service consumer of
 * a previous extraction generation is still working this document.
 * <p>The proof of liveness is the consume-lock heartbeat stamped by
 * {@code MqIdempotentConsumeSupport} ( segment: TTL = lease/3, refreshed while
 * the holder runs). Any {@code :hb} key matching this document's heartbeat
 * pattern means an old generation is still burning the LLM chain — the entry
 * uses that to name the preemption choice instead of a blind "please wait".
 * <p>Read failure (Redis blip) is treated as "no live generation" — a
 * conservative pass that keeps today's retry flow working; the WARN names it
 * honestly so the ledger can see the fallback fire (no silent zero-call
 * exemption).
 */
@Component
public class DocumentExtractConsumeLockProbe {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractConsumeLockProbe.class);

    private final StringRedisTemplate redis;

    public DocumentExtractConsumeLockProbe(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** True = at least one previous-generation consume heartbeat is warm for this document. */
    public boolean liveGenerationRunning(long documentId) {
        try {
            Set<String> warm = redis.keys(ExtractDedupKeys.consumeHeartbeatPattern(documentId));
            return warm != null && !warm.isEmpty();
        } catch (Exception e) {
            log.warn("F2 抢占探针读取消费心跳失败（按无在跑轮次保守放行）: documentId={} err={}",
                    documentId, e.toString());
            return false;
        }
    }
}
