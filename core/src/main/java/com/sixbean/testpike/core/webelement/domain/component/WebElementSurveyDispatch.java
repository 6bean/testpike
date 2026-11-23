package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Survey task dispatch entry (WE-S4 segment P2b): turns one "survey this page"
 * request into an explore session through the SAME
 * {@link ExploreService#create} chain the explore page dialog uses — no
 * second INSERT path (shared-logic single source). The goal text carries the
 * survey marker shared with {@link WebElementSurveyCollector#isSurveyGoal}
 * plus the declared viewport classes read through
 * {@link SurveyEnvelopeTiers#declared} (single source shared with the
 * envelope attach point); an unset key honestly declares no tiers (default
 * viewport only) instead of guessing. The machine-side tier list reaches the
 * executor on the step envelope (viewportClasses), attached at
 * {@code ExploreServiceImpl.submitAct}. Login-state injection for the
 * explore channel has no storage_state path yet (only task_type=1 claims
 * carry it) — wired through the existing SessionStateSupport single source
 * once that channel lands, no second implementation here.
 */
@Component
public class WebElementSurveyDispatch {

    /** Install key literal lives on the shared tiers component (R40 single source). */
    static final String VIEWPORT_CLASSES_KEY = SurveyEnvelopeTiers.VIEWPORT_CLASSES_KEY;

    private final ExploreService exploreService;
    private final SurveyEnvelopeTiers surveyEnvelopeTiers;

    public WebElementSurveyDispatch(ExploreService exploreService,
                                    SurveyEnvelopeTiers surveyEnvelopeTiers) {
        this.exploreService = exploreService;
        this.surveyEnvelopeTiers = surveyEnvelopeTiers;
    }

    /**
     * Create one survey explore session for the given page.
     *
     * @param operatorId acting user id (session audit trail)
     * @param projectId  project whose page ledger receives the survey
     * @param startUrl   page url to survey; validated by the shared create chain
     * @return the new explore session id
     */
    public long dispatch(long operatorId, long projectId, String startUrl) {
        String url = startUrl == null ? "" : startUrl.strip();
        String goal = WebElementSurveyCollector.SURVEY_GOAL_PREFIX + url + viewportDeclaration();
        return exploreService.create(operatorId, projectId, new ExploreCreateInput(goal, url));
    }

    /** Declared tiers appended to the goal so the loop and panels read intent. */
    private String viewportDeclaration() {
        List<String> classes = surveyEnvelopeTiers.declared();
        return classes.isEmpty() ? "" : "（视口档：" + String.join(",", classes) + "）";
    }
}
