package com.sixbean.testpike.aiservice.llm.domain.component;


public final class Cl100kTokenEstimator implements TokenEstimator {

    private final TokenCounter delegate;

    public Cl100kTokenEstimator(TokenCounter delegate) {
        this.delegate = delegate;
    }

    @Override
    public String engineId() {
        return "cl100k";
    }

    @Override
    public int countTokens(String text) {
        return delegate.countTokens(text);
    }
}
