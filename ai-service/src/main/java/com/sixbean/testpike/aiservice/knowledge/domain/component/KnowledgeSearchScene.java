package com.sixbean.testpike.aiservice.knowledge.domain.component;


public enum KnowledgeSearchScene {

    
    CASE_GEN(5, 0.65),

 
    SIMILARITY(3, 0.80),

    
    LOCATOR(3, 0.75),

    
    BUSINESS(5, 0.70),

    
    FALLBACK(3, 0.70);

    private final int defaultTopK;
    private final double defaultThreshold;

    KnowledgeSearchScene(int defaultTopK, double defaultThreshold) {
        this.defaultTopK = defaultTopK;
        this.defaultThreshold = defaultThreshold;
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    public double getDefaultThreshold() {
        return defaultThreshold;
    }
}
