package com.sixbean.testpike.admin.cases.app.component;

import com.sixbean.testpike.admin.auth.infrastructure.remote.client.ConfigValueClient;
import com.sixbean.testpike.admin.cases.api.dto.SimilarityHintLevel;
import org.springframework.stereotype.Component;


@Component
public class SimilarityHintGrader {

 
    public static final String KEY_HINT = "case.similarity.hint-threshold";
 
    public static final String KEY_STRONG = "case.similarity.strong-hint-threshold";

    
    static final String DEFAULT_HINT = "0.80";
    static final String DEFAULT_STRONG = "0.90";

    private final ConfigValueClient configValueClient;

    public SimilarityHintGrader(ConfigValueClient configValueClient) {
        this.configValueClient = configValueClient;
    }

    
    public SimilarityHintLevel grade(double similarity) {
        double hint = threshold(KEY_HINT, DEFAULT_HINT);
        double strong = threshold(KEY_STRONG, DEFAULT_STRONG);
        if (strong < hint) {
            hint = parseOr(DEFAULT_HINT, 0.0);
            strong = parseOr(DEFAULT_STRONG, 0.0);
        }
        if (similarity >= strong) {
            return SimilarityHintLevel.STRONG;
        }
        if (similarity >= hint) {
            return SimilarityHintLevel.HINT;
        }
        return SimilarityHintLevel.NONE;
    }

    
    private double threshold(String key, String defaultLiteral) {
        String raw = configValueClient.getValue(key, defaultLiteral);
        double fromDefault = parseOr(defaultLiteral, 0.0);
        return parseOr(raw, fromDefault);
    }

    private static double parseOr(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (parsed < 0.0 || parsed > 1.0) {
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
