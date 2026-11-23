package com.sixbean.testpike.core.ai.api.dto;

/**
 * AI batch generation cost estimation: display the estimated token cost and a confirmation prompt before generation to prevent accidental cost consumption.
 * <p> Pure calculation without persisting to database: estimated tokens = Σ(fixed prompt overhead + character count × tokens per character), cost = tokens/1000 × unit price.
 * The values are order-of-magnitude estimates; precise circuit breaker implementation is deferred, and this phase only shows a confirmation prompt.
 * <p> maxCount = current batch upper limit (the ai.batch.max-count key is dynamic, and the front-end confirmation dialog text updates accordingly).
 *
 */
public record AiBatchEstimate(long count, long estimatedTokens, double estimatedCostYuan, int maxCount,
                              Integer scenarioCount, Integer deliberationRounds) {
}
