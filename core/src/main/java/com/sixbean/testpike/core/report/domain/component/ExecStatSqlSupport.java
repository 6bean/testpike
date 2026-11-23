package com.sixbean.testpike.core.report.domain.component;

import com.sixbean.testpike.common.report.PassRateSql;
import org.springframework.stereotype.Component;


@Component
public class ExecStatSqlSupport {

    
    public String excludeTempBatch() {
        return PassRateSql.TEMP_BATCH_EXCLUDE;
    }
}
