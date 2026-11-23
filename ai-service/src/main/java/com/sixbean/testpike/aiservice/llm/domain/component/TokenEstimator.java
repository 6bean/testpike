package com.sixbean.testpike.aiservice.llm.domain.component;

import java.util.Collection;

/**
 * <ul>
 * </ul>
 *
 */
public interface TokenEstimator {

    
    String engineId();

    
    int countTokens(String text);

    
    default int countTexts(Collection<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String part : parts) {
            total += countTokens(part);
        }
        return total;
    }
}
