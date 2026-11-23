package com.sixbean.testpike.core.webelement.api.dto;

/**
 * One element's health reading for the Page Element Library ledger list
 * (WE S7 8.3 step-health account). Pure derived view over the daily rollup
 * table — nothing here is stored a second time.
 *
 * @param elementId       ledger element the reading belongs to
 * @param reached         true when the current window has any anchor attempt
 *                        outcome at all; unreached elements are never counted
 *                        and never alerted (silent pages must not look sick)
 * @param missRatePercent rounded miss share of the current window,
 *                        -1 when the element was not reached
 * @param badge           health column tier: green / yellow / red, or
 *                        {@code none} when unreached
 * @param aboutToBreak    true when the miss rate climbed across two consecutive
 *                        windows and the current window is not a total failure
 *                        (the "about to break" observation badge)
 */
public record WebElementHealthView(long elementId, boolean reached, int missRatePercent,
                                   String badge, boolean aboutToBreak) {

    /** Health column tier values (the ledger list maps these to colors). */
    public static final String BADGE_GREEN = "green";
    public static final String BADGE_YELLOW = "yellow";
    public static final String BADGE_RED = "red";
    public static final String BADGE_NONE = "none";

    /** Honest reading for an element with no outcome in any window. */
    public static WebElementHealthView unreached(long elementId) {
        return new WebElementHealthView(elementId, false, -1, BADGE_NONE, false);
    }
}
