package com.sixbean.testpike.core.cases.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionCompareInfo;
import com.sixbean.testpike.core.cases.domain.component.CaseVersionCompareSummarizer;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.cases.domain.service.CaseVersionCompareService;
import org.springframework.stereotype.Service;

/**
 * Compare-card service . Validates the model's
 * anchors, delegates the field-level diff to the SHARED version door
 * (CaseService.diffVersion — the exact method behind
 * GET /cases/{id}/versions/{v}/diff, so 404/40001/span-truncation semantics
 * stay one source, R40), then renders the one-line card. No DB access here:
 * everything comes through the shared service.
 */
@Service
public class CaseVersionCompareServiceImpl implements CaseVersionCompareService {

    private final CaseService caseService;

    public CaseVersionCompareServiceImpl(CaseService caseService) {
        this.caseService = caseService;
    }

    @Override
    public CaseVersionCompareInfo compare(Long caseId, Integer fromVersion, Integer toVersion) {
        if (caseId == null || caseId < 1 || toVersion == null || toVersion < 1
                || (fromVersion != null && fromVersion < 1)) {
            throw new ServiceException("对比用例版本需要有效的用例 id 与目标版本号（均须 ≥1，基准版本可缺省=当前最新版本；当前入参 caseId="
                    + caseId + "，fromVersion=" + fromVersion + "，toVersion=" + toVersion + "）"
                    + "——请在「用例管理」打开该用例的「版本历史」，从列表中选取基准版本与目标版本号后再发起对比（示例：用例 1024 从版本 2 对比到版本 3）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return CaseVersionCompareSummarizer.summarize(
                caseService.diffVersion(caseId, toVersion, fromVersion));
    }
}
