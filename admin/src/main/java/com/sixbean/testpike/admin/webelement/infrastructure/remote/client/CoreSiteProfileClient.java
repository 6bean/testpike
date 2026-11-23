package com.sixbean.testpike.admin.webelement.infrastructure.remote.client;

import com.sixbean.testpike.admin.webelement.api.dto.SiteProfileInput;
import com.sixbean.testpike.admin.webelement.api.dto.SiteProfileView;
import com.sixbean.testpike.common.webelement.SiteSurveyRunView;

import java.util.List;

/**
 * core site-profile ledger client. Write doors forward the revision token so
 * the optimistic-lock refusal reaches the browser with its original semantics
 * (shared posture with the element ledger family, no second protocol).
 */
public interface CoreSiteProfileClient {

    SiteProfileView create(SiteProfileInput input);

    SiteProfileView get(long id);

    List<SiteProfileView> list(long projectId);

    SiteProfileView update(long id, SiteProfileInput input);

    void delete(long id);

    /** Survey-run ledger rows of one project, newest first (core caps the segment). */
    List<SiteSurveyRunView> listRuns(long projectId, int limit);

    /**
     * Dispatch a whole-site survey crawl for one profile through the core
     * dispatch door (production guard and rule freeze live core-side). The
     * caller must pass the project resolved by re-reading the row; the
     * operator id rides X-Operator-Id via the shared call wrapper.
     */
    long dispatchSurvey(long projectId, long profileId);
}
