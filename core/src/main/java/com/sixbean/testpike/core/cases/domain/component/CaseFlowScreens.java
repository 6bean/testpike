package com.sixbean.testpike.core.cases.domain.component;

/**
 * Single source for "where a use-case machine event is performed by a human".
 * <p>The route path feeds the flow-guide dialog (the front end whitelist-validates it
 * against the menu structure); the screen label feeds refusal guidance so a message
 * names the words the user actually sees in the navigation menu instead of guessing.
 * <p>Both views are derived from the same switch so the route and the label can never
 * drift apart (a guidance message pointing at a screen that does not run the action is
 * a defect, not a hint).
 */
public final class CaseFlowScreens {

    /** Review-workbench route (review verdicts and entering edit mode happen there). */
    public static final String ROUTE_REVIEW = "/review";
    /** Case list route (edit-save back, restore and the archive family are list/detail actions). */
    public static final String ROUTE_CASES = "/cases";
    /** Execution route (exec-side events land there). */
    public static final String ROUTE_EXECUTION = "/execution";

    /** Navigation labels rendered for the three routes above. */
    private static final String SCREEN_REVIEW = "「评审工作台」";
    private static final String SCREEN_CASES = "「用例管理」";
    private static final String SCREEN_EXECUTION = "「执行与试运行」";

    private CaseFlowScreens() {
    }

    /** Route path where a human performs the event (every machine event is covered). */
    public static String routeOf(CaseEvent event) {
        return switch (event) {
            case PASS, NEED_CHANGE, REJECT, EDIT_BEGIN -> ROUTE_REVIEW;
            case EXEC_START, EXEC_SUCCESS, EXEC_FAIL, RETRY_ENV_OK -> ROUTE_EXECUTION;
            case EDIT_SAVE, RESTORE, ARCHIVE, UNARCHIVE, DEFECT_FIXED -> ROUTE_CASES;
        };
    }

    /** Navigation label of the route that runs the event, quoted for direct message embedding. */
    public static String screenOf(CaseEvent event) {
        return screenOfRoute(routeOf(event));
    }

    /** Navigation label for a route path; unknown routes fall back to the case list screen. */
    public static String screenOfRoute(String route) {
        return switch (route == null ? "" : route) {
            case ROUTE_REVIEW -> SCREEN_REVIEW;
            case ROUTE_EXECUTION -> SCREEN_EXECUTION;
            default -> SCREEN_CASES;
        };
    }
}
