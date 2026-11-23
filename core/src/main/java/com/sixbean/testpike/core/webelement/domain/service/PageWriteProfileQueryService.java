package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.common.webelement.PageWriteProfilePageView;
import com.sixbean.testpike.common.webelement.PageWriteProfileView;
import com.sixbean.testpike.common.webelement.PageWriteRiskView;

import java.util.List;

/**
 * Read door over the per-page network write-risk ledger .
 * The browser screen and the kernel prompt annotation both consume this
 * query instead of re-deriving risk from survey runs: a page that writes on
 * input is a durable fact once booked, and listing it must never depend on
 * running a fresh probe.
 */
public interface PageWriteProfileQueryService {

    /**
 * Paged segment of one project's risk pages, newest activity first.
 *
 * @param projectId owning project (strict isolation, no cross-project leak)
 * @param page 1-based page number (values below 1 clamp to the first page)
 * @param pageSize rows per page (clamped to the door cap)
 * @param keyword optional case-insensitive substring filter on the page URL
 */
    PageWriteProfilePageView pageByProject(long projectId, int page, int pageSize, String keyword);

    /**
 * One risk row by id, inside the strict project segment. A foreign or
 * deleted row reads the same as absent — callers never learn which other
 * project owns the id (zero-leak posture shared with the paged door).
 *
 * @param projectId owning project the caller is allowed to see
 * @param profileId the ledger row id
 * @return the decoded row, or null when it does not exist in this segment
 */
    PageWriteProfileView findByIdInProject(long projectId, long profileId);

    /**
     * Narrow kernel door: pages of one project whose input
     * writes — the sticky snapshot-only downgrade or a booked write surface
     * — as pageKey+pageUrl pairs only, newest activity first and bounded by
     * the door cap. This is the single risk judgment the explore kernel
     * consumes; it never re-derives risk from a fresh probe.
     *
     * @param projectId owning project (strict isolation, no cross-project leak)
     */
    List<PageWriteRiskView> riskPagesForProject(long projectId);
}
