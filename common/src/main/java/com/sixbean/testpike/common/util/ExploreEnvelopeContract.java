package com.sixbean.testpike.common.util;

import java.util.List;
import java.util.Set;


public final class ExploreEnvelopeContract {

    
    public static final String APP_URL_PREFIX = "app://";

    
    public static final String EXPLORE_ACT_KIND = "explore-act";

    
    public static final String TARGET_MOBILE = "mobile";

 
    public static final List<String> MOBILE_ROAM_ACTIONS =
            List.of("tree-snapshot", "tap", "back", "swipe", "home");

    
    private static final Set<String> MOBILE_ROAM_ACTION_SET = Set.copyOf(MOBILE_ROAM_ACTIONS);

    
    public static final String TREE_ACTION = "tree-snapshot";

    
    public static final String TREE_CARRIER_STEP = "app.screenshot";

    
    public static boolean isAppStartUrl(String startUrl) {
        return startUrl != null && startUrl.strip().startsWith(APP_URL_PREFIX);
    }

    
    public static boolean isMobileEnvelope(String kind, String target) {
        return EXPLORE_ACT_KIND.equals(kind) && TARGET_MOBILE.equals(target);
    }

    
    public static String mobileRoamViolation(String action) {
        return action != null && MOBILE_ROAM_ACTION_SET.contains(action)
                ? null
                : "移动探索动作非法（漫游白名单：" + String.join("、", MOBILE_ROAM_ACTIONS)
                        + "；卸载/清库/安装类动词探索模式一律禁生成）: " + action;
    }

    
    public static String roamToIrStepAction(String action) {
        if (action == null) {
            return null;
        }
        return TREE_ACTION.equals(action) ? TREE_CARRIER_STEP : "app." + action;
    }

    
    public static boolean isMobileRoamAction(String action) {
        return action != null && MOBILE_ROAM_ACTION_SET.contains(action);
    }

    // ------------------------------------------------ whole-site survey dialect

    /** Whole-site survey goal dialect prefix (one word list, both services). */
    public static final String SITE_SURVEY_GOAL_PREFIX = "整站测绘：";

    /** Deterministic crawl envelope action (executor consumes the siteSurvey face). */
    public static final String SITE_SURVEY_ACTION = "site-survey";

    /** Envelope field carrying the dispatch-time frozen rules snapshot. */
    public static final String SITE_SURVEY_RULES_FIELD = "siteSurvey";

    /** Page journal frame action, one per crawled page (relay + replay pin). */
    public static final String SITE_PAGE_FRAME_ACTION = "site-survey-page";

    /** True when the goal carries the whole-site marker (core collector and ai-service kernel share this one test). */
    public static boolean isSiteSurveyGoal(String goalText) {
        return goalText != null && goalText.trim().startsWith(SITE_SURVEY_GOAL_PREFIX);
    }

    private ExploreEnvelopeContract() {
    }
}
