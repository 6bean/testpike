package com.sixbean.testpike.common.util;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.List;


public final class CompareIdsValidator {

 
    public static final int MAX_PROJECTS = 20;

    private CompareIdsValidator() {
    }

    /**
 *
 * @throws ServiceException 40001
 */
    public static void validate(List<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            throw new ServiceException("请至少选择一个对比项目", BizErrorCode.VALIDATION_ERROR);
        }
        if (projectIds.size() > MAX_PROJECTS) {
            throw new ServiceException("对比项目数上限 " + MAX_PROJECTS + " 个（当前 "
                    + projectIds.size() + " 个）", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
