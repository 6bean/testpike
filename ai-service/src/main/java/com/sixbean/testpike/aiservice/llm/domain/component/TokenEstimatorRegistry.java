package com.sixbean.testpike.aiservice.llm.domain.component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class TokenEstimatorRegistry {

    
    static final String QWEN_RESOURCE = "/tokenizers/qwen3.json";
    static final String DEEPSEEK_RESOURCE = "/tokenizers/deepseek-v3.json";

    private static final Cl100kTokenEstimator DEFAULT_CL100K =
            new Cl100kTokenEstimator(new TokenCounter());

    
    private static final Map<String, Double> COEFFICIENTS = new ConcurrentHashMap<>();

    private static volatile HfBpeTokenEstimator qwen;
    private static volatile HfBpeTokenEstimator deepseek;

    private TokenEstimatorRegistry() {
    }

    
    public static void registerCoefficient(String providerModelName, double coefficient) {
        if (providerModelName == null || providerModelName.isBlank()) {
            return;
        }
        if (!(coefficient > 0) || !Double.isFinite(coefficient)) {
            throw new IllegalArgumentException("校准系数必须为正有限数: " + coefficient);
        }
        COEFFICIENTS.put(providerModelName.toLowerCase(Locale.ROOT), coefficient);
    }

    
    public static void clearCoefficientsForTest() {
        COEFFICIENTS.clear();
    }

    
    public static TokenEstimator resolve(String tokenizerFamily, String providerModelName) {
        String family = tokenizerFamily == null ? "auto" : tokenizerFamily.trim().toLowerCase(Locale.ROOT);
        switch (family) {
            case "cl100k" -> {
                return DEFAULT_CL100K;
            }
            case "qwen" -> {
                return qwenEngine(true);
            }
            case "deepseek" -> {
                return deepseekEngine(true, providerModelName);
            }
            case "auto" -> {
                return infer(providerModelName);
            }
            default -> throw new IllegalArgumentException(
                    "未知 tokenizer_family（合法=auto/qwen/deepseek/cl100k）: " + tokenizerFamily);
        }
    }

    
    private static TokenEstimator infer(String providerModelName) {
        String model = providerModelName == null ? "" : providerModelName.toLowerCase(Locale.ROOT);
        if (model.contains("qwen")) {
            return qwenEngine(false);
        }
        if (model.contains("deepseek")) {
            return deepseekEngine(false, providerModelName);
        }
        return calibrated(model);
    }

    private static TokenEstimator qwenEngine(boolean explicit) {
        HfBpeTokenEstimator local = qwen;
        if (local == null) {
            synchronized (TokenEstimatorRegistry.class) {
                local = qwen;
                if (local == null) {
                    try {
                        local = HfBpeTokenEstimator.fromResource(QWEN_RESOURCE, "hf-bpe-qwen3");
                        qwen = local;
                    } catch (RuntimeException e) {
                        if (explicit) {
                            throw e;
                        }
                        return new CalibratedDelegator(DEFAULT_CL100K, 1.0);
                    }
                }
            }
        }
        return local;
    }

    private static TokenEstimator deepseekEngine(boolean explicit, String providerModelName) {
        String model = providerModelName == null ? "" : providerModelName.toLowerCase(Locale.ROOT);
        return new CalibratedDelegator(DEFAULT_CL100K,
                COEFFICIENTS.getOrDefault(model, 1.0));
    }

    private static TokenEstimator calibrated(String lowerModel) {
        double coefficient = COEFFICIENTS.getOrDefault(lowerModel, 1.0);
        return new CalibratedDelegator(DEFAULT_CL100K, coefficient);
    }
}
