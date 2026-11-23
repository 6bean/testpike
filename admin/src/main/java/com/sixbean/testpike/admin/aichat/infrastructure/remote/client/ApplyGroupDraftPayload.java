package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

import java.util.List;

/**
 * Wire payload of the core group draft-apply call (AM batch piece 2b;
 * core ApplyGroupDraftRequest). Ticket + checked ids only, mirroring the
 * single-card ApplyDraftPayload posture.
 */
record ApplyGroupDraftPayload(String confirmToken, List<Long> draftIds) {
}
