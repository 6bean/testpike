package com.sixbean.testpike.aiservice.explore.infrastructure.remote;


public record ExploreSessionBrief(
        Long id,
        Long projectId,
        String goalText,
        String startUrl,
        Integer status,
        Integer stepsUsed,
        Integer pagesVisited,
        Long costCents,
        String reportMd,
        Long createdBy,
        String budgetSnapshot,
        Long envId,
        String credRef) {

    
    public static final int STATUS_RUNNING = 1;

    
    public ExploreSessionBrief(Long id, Long projectId, String goalText, String startUrl,
                               Integer status, Integer stepsUsed, Integer pagesVisited,
                               Long costCents, String reportMd, Long createdBy,
                               String budgetSnapshot) {
        this(id, projectId, goalText, startUrl, status, stepsUsed, pagesVisited,
                costCents, reportMd, createdBy, budgetSnapshot, null, null);
    }

    
    public boolean isTerminal() {
        return status != null && status != STATUS_RUNNING;
    }
}
