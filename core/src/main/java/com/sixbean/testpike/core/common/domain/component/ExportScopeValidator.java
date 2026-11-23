package com.sixbean.testpike.core.common.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.List;

/**
 * Common component for batch export scope validation (extracted from CaseServiceImpl.export because the same logic appears at least twice).
 * Mandatory common-component extraction rule: case export and defect export share the same ids/filter mutually exclusive contract.
 * The three 400 user-facing messages and thresholds are maintained centrally, and duplication by each domain is prohibited.
 *
 */
public final class ExportScopeValidator {

 /** Per-request limit for selected export (reject when more than 5,000; guide users to use filtered export). */
    public static final int MAX_EXPORT_IDS = 5000;

    /** Synchronous/asynchronous boundary (contract: more than 2,000 records use an asynchronous export queue reusing tp_export_task). */
    public static final int ASYNC_THRESHOLD = 2000;

    private ExportScopeValidator() {
    }

    /**
     * Validate the export scope and return the mode: either selected export ids or filtered export conditions.
     *
     * @param ids selected ID list (null or empty means not provided).
     * @param hasFilter whether any filter condition is present.
     * @return "ids" (selected mode) or "filter" (filtered mode).
     * @throws ServiceException with 400 when both are empty ("Please select an export scope"), when both are provided ("Choose one of the two"), or when ids exceeds 5,000.
     */
    public static String validate(List<Long> ids, boolean hasFilter) {
        boolean hasIds = ids != null && !ids.isEmpty();
        if (!hasIds && !hasFilter) {
            throw new ServiceException("请选择导出范围（勾选导出或按筛选条件导出，二选一）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (hasIds && hasFilter) {
            throw new ServiceException("勾选导出与筛选导出二选一，不能同时提交", BizErrorCode.VALIDATION_ERROR);
        }
        if (hasIds && ids.size() > MAX_EXPORT_IDS) {
            throw new ServiceException("勾选导出单次上限 " + MAX_EXPORT_IDS + " 条，请改用筛选导出",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return hasIds ? "ids" : "filter";
    }

    /** Export file name (common component: Chinese prefix_yyyyMMdd.xlsx, using file-name sanitization fallback). */
    public static String exportFileName(String base) {
        return ExcelExportSupport.sanitizeFileName(base + "_"
                + new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date()) + ".xlsx");
    }
}
