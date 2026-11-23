package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.cases.api.dto.CaseVersionCompareInfo;

/**
 * Dialog compare card read face . Turns
 * "compare vA and vB of this case" into the single-line answer the chat
 * shows. Deliberately a SEPARATE service from the field-level diff door:
 * the diff DTO keeps its page-facing shape (raw before/after values), while
 * this face owns the card wording and the validation of the model-supplied
 * anchors. The diff itself stays one source — CaseService.diffVersion.
 */
public interface CaseVersionCompareService {

    /**
     * @param caseId      mandatory, >= 1
     * @param fromVersion optional baseline; null = the case's latest version
     *                    (same default as the GET diff door)
     * @param toVersion   mandatory, >= 1
     * @throws com.sixbean.testpike.common.exception.ServiceException when an
     *         anchor is missing/invalid, when the case is not found (40401)
     *         or when a snapshot is corrupted (40001) — all in the shared
     *         door's own human wording, never re-wrapped here.
     */
    CaseVersionCompareInfo compare(Long caseId, Integer fromVersion, Integer toVersion);
}
