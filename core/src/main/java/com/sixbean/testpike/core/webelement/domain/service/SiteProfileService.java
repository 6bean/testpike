package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.core.webelement.api.dto.SiteProfileInput;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileView;

import java.util.List;

/**
 * Site profile ledger (whole-site survey ): one profile per project and
 * per in-site path scope. The service derives the scope prefix from the start
 * URL, enforces name uniqueness inside a project, keeps an unchanged save
 * write-free (revision and timestamps frozen) and refuses stale-revision
 * writes. Deleting a profile keeps its survey runs and collected elements.
 */
public interface SiteProfileService {

    /** Create a profile; scope is derived server-side from startUrl. */
    SiteProfileView create(SiteProfileInput input);

    /** Update with optimistic lock; a no-change save writes nothing at all. */
    SiteProfileView update(long id, SiteProfileInput input);

    /** Load one profile (NOT_FOUND when the row does not exist). */
    SiteProfileView get(long id);

    /** Profiles of one project, newest activity first. */
    List<SiteProfileView> list(long projectId);

    /** Delete the profile row only; run ledger and elements stay (D7). */
    void delete(long id);
}
