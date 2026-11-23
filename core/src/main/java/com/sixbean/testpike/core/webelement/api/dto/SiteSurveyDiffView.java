package com.sixbean.testpike.core.webelement.api.dto;

import com.sixbean.testpike.core.webelement.domain.component.SiteSurveyDiffPageDelta;

import java.util.List;

/**
 * Re-survey review proposal (D6): the page-level differences between one
 * whole-site survey run and the newest finished earlier run of the same
 * profile. Purely descriptive — vanished pages are listed, never demoted;
 * a human approves every element-ledger change through the existing gates.
 */
public class SiteSurveyDiffView {

    private final long currentRunId;
    private final Long baselineRunId;
    private final boolean firstSurvey;
    private final List<String> newPages;
    private final List<String> vanishedPages;
    private final List<SiteSurveyDiffPageDelta> changedPages;

    public SiteSurveyDiffView(long currentRunId, Long baselineRunId, boolean firstSurvey,
                              List<String> newPages, List<String> vanishedPages,
                              List<SiteSurveyDiffPageDelta> changedPages) {
        this.currentRunId = currentRunId;
        this.baselineRunId = baselineRunId;
        this.firstSurvey = firstSurvey;
        this.newPages = newPages;
        this.vanishedPages = vanishedPages;
        this.changedPages = changedPages;
    }

    public long getCurrentRunId() {
        return currentRunId;
    }

    public Long getBaselineRunId() {
        return baselineRunId;
    }

    public boolean isFirstSurvey() {
        return firstSurvey;
    }

    public List<String> getNewPages() {
        return newPages;
    }

    public List<String> getVanishedPages() {
        return vanishedPages;
    }

    public List<SiteSurveyDiffPageDelta> getChangedPages() {
        return changedPages;
    }
}
