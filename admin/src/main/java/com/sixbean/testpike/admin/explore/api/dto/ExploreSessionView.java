package com.sixbean.testpike.admin.explore.api.dto;

/**
 * envId/credRef carry the mobile-shape environment and vault account reference;
 * loginStateName carries the web-shape login-state ledger handle (a namespace
 * separate from credRef). Unused fields per shape are null.
 *
 */
public record ExploreSessionView(Long id, Long projectId, String goalText, String startUrl,
                                 Integer status, Integer stepsUsed, Integer pagesVisited,
                                 Long costCents, String reportMd, Long createdBy,
                                 String budgetSnapshot, Long envId, String credRef,
                                 String loginStateName) {

    /** Thirteen-arg delegate (pre- shape stays compilable, loginStateName=null). */
    public ExploreSessionView(Long id, Long projectId, String goalText, String startUrl,
                              Integer status, Integer stepsUsed, Integer pagesVisited,
                              Long costCents, String reportMd, Long createdBy,
                              String budgetSnapshot, Long envId, String credRef) {
        this(id, projectId, goalText, startUrl, status, stepsUsed, pagesVisited,
                costCents, reportMd, createdBy, budgetSnapshot, envId, credRef, null);
    }

    /** Eleven-arg delegate (legacy web shape stays compilable, all extras null). */
    public ExploreSessionView(Long id, Long projectId, String goalText, String startUrl,
                              Integer status, Integer stepsUsed, Integer pagesVisited,
                              Long costCents, String reportMd, Long createdBy, String budgetSnapshot) {
        this(id, projectId, goalText, startUrl, status, stepsUsed, pagesVisited,
                costCents, reportMd, createdBy, budgetSnapshot, null, null, null);
    }
}
