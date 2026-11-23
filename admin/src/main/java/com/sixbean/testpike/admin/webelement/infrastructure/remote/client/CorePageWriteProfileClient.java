package com.sixbean.testpike.admin.webelement.infrastructure.remote.client;

import com.sixbean.testpike.common.webelement.PageWriteProfilePageView;

/**
 * core page write-risk ledger client. segment shape and caps are core-side;
 * the bridge only forwards the project resolved from the session context
 * and the paging parameters (shared posture with the site-profile client it
 * sits beside, no second protocol). The conversion call files a pending
 * draft behind core's single service door — it never edits a risk row.
 */
public interface CorePageWriteProfileClient {

    PageWriteProfilePageView pageProfiles(long projectId, int page, int pageSize, String keyword);

    long caseDraftFromFailure(long projectId, long profileId, int failureIndex);
}
