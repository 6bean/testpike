package com.sixbean.testpike.core.webelement.domain.component;

/**
 * One page's candidate-count change between two whole-site survey runs:
 * how many ledger writes the baseline run recorded versus this run, under
 * the same folded page key. Pure value object for the review proposal —
 * listing this never mutates the element ledger (D6 one-way door).
 */
public class SiteSurveyDiffPageDelta {

    private final String pageKey;
    private final int before;
    private final int after;

    public SiteSurveyDiffPageDelta(String pageKey, int before, int after) {
        this.pageKey = pageKey;
        this.before = before;
        this.after = after;
    }

    public String getPageKey() {
        return pageKey;
    }

    public int getBefore() {
        return before;
    }

    public int getAfter() {
        return after;
    }
}
