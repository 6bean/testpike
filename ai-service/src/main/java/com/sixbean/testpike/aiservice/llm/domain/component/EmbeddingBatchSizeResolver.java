package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class EmbeddingBatchSizeResolver {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBatchSizeResolver.class);

    
    static final int MIN_BATCH = 1;
    static final int MAX_BATCH = 1024;

    
    public static final String KEY_BATCH_SIZE = "ai.embedding.batch-size";

    /**
     * K8 hot-tunable key for the provider-side per-request input cap
     * (defect ledger ㊾). Absent/invalid falls back to
     * llm.embedding.provider-batch-cap (conservative default 10).
     */
    public static final String KEY_PROVIDER_BATCH_CAP = "ai.embedding.provider-batch-cap";

    private final CoreGateway coreGateway;
    private final EmbeddingProperties properties;

    public EmbeddingBatchSizeResolver(CoreGateway coreGateway, EmbeddingProperties properties) {
        this.coreGateway = coreGateway;
        this.properties = properties;
    }

    
    public int resolve() {
        String raw = coreGateway == null ? null : coreGateway.getSysConfig(KEY_BATCH_SIZE);
        if (raw != null) {
            try {
                int size = Integer.parseInt(raw.trim());
                if (size >= MIN_BATCH && size <= MAX_BATCH) {
                    return size;
                }
                log.warn("K8 {}={} out of range (allowed {}-{}), falling back to config file value {}",
                        KEY_BATCH_SIZE, raw, MIN_BATCH, MAX_BATCH, properties.getBatchSize());
            } catch (NumberFormatException e) {
                log.warn("K8 {}={} is not a valid integer, falling back to config file value {}",
                        KEY_BATCH_SIZE, raw, properties.getBatchSize());
            }
        }
        return properties.getBatchSize();
    }

    /**
     * Effective per-request input cap enforced on outbound embedding calls
     * (K8 ai.embedding.provider-batch-cap → config llm.embedding.provider-batch-cap).
     * Same double-guard range as batch size: out-of-range K8 values never reach
     * the wire.
     */
    public int resolveProviderCap() {
        String raw = coreGateway == null ? null : coreGateway.getSysConfig(KEY_PROVIDER_BATCH_CAP);
        if (raw != null) {
            try {
                int cap = Integer.parseInt(raw.trim());
                if (cap >= MIN_BATCH && cap <= MAX_BATCH) {
                    return cap;
                }
                log.warn("K8 {}={} out of range (allowed {}-{}), falling back to config file value {}",
                        KEY_PROVIDER_BATCH_CAP, raw, MIN_BATCH, MAX_BATCH,
                        properties.getProviderBatchCap());
            } catch (NumberFormatException e) {
                log.warn("K8 {}={} is not a valid integer, falling back to config file value {}",
                        KEY_PROVIDER_BATCH_CAP, raw, properties.getProviderBatchCap());
            }
        }
        return properties.getProviderBatchCap();
    }
}
