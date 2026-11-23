package com.sixbean.testpike.core.reportcenter.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportExportFile;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportListItem;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportQuery;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportView;


public interface ReportCenterService {

    
    ReportView generate(Long operatorId, ReportGenerateInput input);

    /**
 *
 * @param query list filter; a positive programId narrows through the program lens
 * @param operatorId caller whose visibility intersects the lens (null = trusted internal
 * call without a session user, same posture as the other lens domains)
 */
    PageResult<ReportListItem> list(ReportQuery query, Long operatorId);

    /** Pre-lens entry: no operator = lens narrowing (if any) runs un-intersected. */
    default PageResult<ReportListItem> list(ReportQuery query) {
        return list(query, null);
    }

    
    ReportView detail(long id);

    
    ReportExportFile export(Long operatorId, long id, String format, String theme);
}
