package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import java.util.List;

/**
 * Single-source case-seed wiring for the defect-family suites converted off
 * blanket TRUNCATE . Both consumers seeded the same minimal
 * case shape through the service path, so the seed lives here only (R40:
 * second consumer triggers extraction). Suites keep their own teardown wiring
 * and call {@link #create} from a one-line wrapper.
 */
public final class CaseFixtureRig {

    private CaseFixtureRig() {
    }

    /** Service-path case create with the shared seed shape; returns the id. */
    public static long create(CaseService caseService, long operatorId, long projectId, String title) {
        CaseInfo info = caseService.create(operatorId, new CaseCreateInput(projectId, title,
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }
}
