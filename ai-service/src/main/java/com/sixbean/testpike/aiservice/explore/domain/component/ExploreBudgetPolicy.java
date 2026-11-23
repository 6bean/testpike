package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


public record ExploreBudgetPolicy(int maxSteps, int maxPages, int noProgressLimit,
                                  int appautoMaxSteps, long appautoMaxTokens,
                                  long appautoMaxDurationSeconds) {

    
    public static final int OUTCOME_RUNNING = 0;
    
    public static final int OUTCOME_DONE = 2;
    
    public static final int OUTCOME_BUDGET_STOP = 3;
    
    public static final int OUTCOME_PARTIAL = 4;

    
    static final int DEFAULT_MAX_STEPS = 30;
    static final int DEFAULT_MAX_PAGES = 10;
    
    static final int DEFAULT_NO_PROGRESS_LIMIT = 3;
 
    static final int DEFAULT_APPAUTO_MAX_STEPS = 60;
    static final long DEFAULT_APPAUTO_MAX_TOKENS = 1_000_000L;
    static final long DEFAULT_APPAUTO_MAX_DURATION = 3600L;

    
    public ExploreBudgetPolicy(int maxSteps, int maxPages, int noProgressLimit) {
        this(maxSteps, maxPages, noProgressLimit,
                DEFAULT_APPAUTO_MAX_STEPS, DEFAULT_APPAUTO_MAX_TOKENS,
                DEFAULT_APPAUTO_MAX_DURATION);
    }

    
    public static ExploreBudgetPolicy parse(String budgetSnapshotJson, ObjectMapper mapper) {
        int maxSteps = DEFAULT_MAX_STEPS;
        int maxPages = DEFAULT_MAX_PAGES;
        int noProgressLimit = DEFAULT_NO_PROGRESS_LIMIT;
        int appautoMaxSteps = DEFAULT_APPAUTO_MAX_STEPS;
        long appautoMaxTokens = DEFAULT_APPAUTO_MAX_TOKENS;
        long appautoMaxDuration = DEFAULT_APPAUTO_MAX_DURATION;
        if (budgetSnapshotJson != null && !budgetSnapshotJson.isBlank()) {
            try {
                JsonNode node = mapper.readTree(budgetSnapshotJson);
                maxSteps = node.path("maxSteps").asInt(DEFAULT_MAX_STEPS);
                maxPages = node.path("maxPages").asInt(DEFAULT_MAX_PAGES);
                noProgressLimit = node.path("noProgressLimit").asInt(DEFAULT_NO_PROGRESS_LIMIT);
                JsonNode appauto = node.path("appauto");
                appautoMaxSteps = appauto.path("maxSteps").asInt(DEFAULT_APPAUTO_MAX_STEPS);
                appautoMaxTokens = appauto.path("maxTokens").asLong(DEFAULT_APPAUTO_MAX_TOKENS);
                appautoMaxDuration = appauto.path("maxDurationSeconds")
                        .asLong(DEFAULT_APPAUTO_MAX_DURATION);
            } catch (Exception ignored) {
            }
        }
        return new ExploreBudgetPolicy(maxSteps, maxPages, noProgressLimit,
                appautoMaxSteps, appautoMaxTokens, appautoMaxDuration);
    }

    
    public int classify(int stepsUsed, int pagesVisited, boolean goalReached,
                        int noProgressStreak, boolean noActionAvailable) {
        if (goalReached) {
            return OUTCOME_DONE;
        }
        if (stepsUsed >= maxSteps || pagesVisited >= maxPages) {
            return OUTCOME_BUDGET_STOP;
        }
        if (noProgressStreak >= noProgressLimit || noActionAvailable) {
            return OUTCOME_PARTIAL;
        }
        return OUTCOME_RUNNING;
    }

    
    public int classifyMobile(int stepsUsed, long tokensUsed, long elapsedSeconds,
                              boolean goalReached, int noProgressStreak,
                              boolean noActionAvailable) {
        if (goalReached) {
            return OUTCOME_DONE;
        }
        if (stepsUsed >= appautoMaxSteps || tokensUsed >= appautoMaxTokens
                || elapsedSeconds >= appautoMaxDurationSeconds) {
            return OUTCOME_BUDGET_STOP;
        }
        if (noProgressStreak >= noProgressLimit || noActionAvailable) {
            return OUTCOME_PARTIAL;
        }
        return OUTCOME_RUNNING;
    }
}
