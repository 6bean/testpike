package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class KnowledgeSearchConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchConfigResolver.class);

    
    public static final String KEY_REWEIGHT_EXCELLENT = "kb.reweight-excellent";
    public static final String KEY_REWEIGHT_SPEC = "kb.reweight-spec";
    public static final String KEY_REWEIGHT_FEEDBACK = "kb.reweight-feedback";
    public static final String KEY_LANG_BONUS = "kb.lang-bonus";
    public static final String KEY_FUSION_MODE = "kb.fusion-mode";
    public static final String KEY_GLOBAL_WEIGHT = "kb.global-weight";

    
    public static final String MODE_RRF = "rrf";
    public static final String MODE_WEIGHTED = "weighted";

    private final CoreGateway coreGateway;
    private final KnowledgeSearchProperties properties;

    public KnowledgeSearchConfigResolver(CoreGateway coreGateway, KnowledgeSearchProperties properties) {
        this.coreGateway = coreGateway;
        this.properties = properties;
    }

    
    public double reweightExcellent() {
        return resolveDouble(KEY_REWEIGHT_EXCELLENT, 1.0, 2.0, properties.getReweightExcellent());
    }

    
    public double reweightSpec() {
        return resolveDouble(KEY_REWEIGHT_SPEC, 1.0, 2.0, properties.getReweightSpec());
    }

    
    public double reweightFeedback() {
        return resolveDouble(KEY_REWEIGHT_FEEDBACK, 0.0, 1.0, properties.getReweightFeedback());
    }

    
    public double langBonus() {
        return resolveDouble(KEY_LANG_BONUS, 0.0, 0.5, properties.getLangBonus());
    }

    
    public double globalWeight() {
        return resolveDouble(KEY_GLOBAL_WEIGHT, 0.0, 0.5, properties.getGlobalWeight());
    }

    
    public String fusionMode() {
        String raw = coreGateway == null ? null : coreGateway.getSysConfig(KEY_FUSION_MODE);
        if (raw != null) {
            String mode = raw.trim().toLowerCase();
            if (MODE_RRF.equals(mode) || MODE_WEIGHTED.equals(mode)) {
                return mode;
            }
            log.warn("K8 {}={} 非法（允许 rrf|weighted），回退配置文件 {}",
                    KEY_FUSION_MODE, raw, properties.getFusionMode());
        }
        String fallback = properties.getFusionMode() == null
                ? MODE_RRF : properties.getFusionMode().trim().toLowerCase();
        return MODE_WEIGHTED.equals(fallback) ? MODE_WEIGHTED : MODE_RRF;
    }

    
    public int rrfK() {
        return properties.getRrfK();
    }

    
    public boolean weightedMode() {
        return MODE_WEIGHTED.equals(fusionMode());
    }

    private double resolveDouble(String key, double min, double max, double fallback) {
        String raw = coreGateway == null ? null : coreGateway.getSysConfig(key);
        if (raw != null) {
            try {
                double value = Double.parseDouble(raw.trim());
                if (value >= min && value <= max) {
                    return value;
                }
                log.warn("K8 {}={} 越界（允许 {}~{}），回退配置文件 {}", key, raw, min, max, fallback);
            } catch (NumberFormatException e) {
                log.warn("K8 {}={} 非法小数，回退配置文件 {}", key, raw, fallback);
            }
        }
        return fallback;
    }
}
