package com.sixbean.testpike.common.report;


public final class PassRateSql {

    
    public static final String PASS_COUNT = "count(*) FILTER (WHERE t.status = 3)";

    
    public static final String TERMINAL_COUNT = "count(*) FILTER (WHERE t.status IN (3, 4, 5))";

    
    public static final String FAIL_COUNT = "count(*) FILTER (WHERE t.status = 4)";

    
    public static final String TIMEOUT_COUNT = "count(*) FILTER (WHERE t.status = 5)";

    
    public static final String RATE_EXPR =
            PASS_COUNT + "::numeric / NULLIF(" + TERMINAL_COUNT + ", 0)";

 
    public static final String TEMP_BATCH_EXCLUDE =
            "(t.batch_id IS NULL OR NOT EXISTS (SELECT 1 FROM tp_exec_batch tb"
                    + " WHERE tb.id = t.batch_id AND tb.type = 2))";

    private PassRateSql() {
    }
}
