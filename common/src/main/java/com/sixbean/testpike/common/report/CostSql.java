package com.sixbean.testpike.common.report;


public final class CostSql {

    
    public static final String SCOPE_FROM = """
            FROM tp_ai_call_log l
            LEFT JOIN tp_project p ON p.id = l.project_id
            LEFT JOIN tp_user u ON u.id = l.operator_id
            WHERE to_char(l.created_at, 'YYYY-MM') = :month
              AND (:projectId::bigint IS NULL OR l.project_id = :projectId)
              AND (:projectIds::bigint[] IS NULL OR l.project_id = ANY(:projectIds::bigint[]))
              AND (l.project_id IS NULL OR p.status = 1 OR l.project_id = :projectId\
                   OR (:projectIds::bigint[] IS NOT NULL AND l.project_id = ANY(:projectIds::bigint[])))\
            """;

    
    public static final String MEASURE_COLS =
            "COALESCE(sum(l.cost), 0) AS amount, count(*)::bigint AS calls";

    
    public static final String WINDOW_SCOPE_FROM = """
            FROM tp_ai_call_log l
            LEFT JOIN tp_project p ON p.id = l.project_id
            WHERE l.created_at >= :fromTime
              AND l.created_at < :toTime
              AND (:projectId::bigint IS NULL OR l.project_id = :projectId)
              AND (:projectIds::bigint[] IS NULL OR l.project_id = ANY(:projectIds::bigint[]))
              AND (l.project_id IS NULL OR p.status = 1 OR l.project_id = :projectId\
                   OR (:projectIds::bigint[] IS NOT NULL AND l.project_id = ANY(:projectIds::bigint[])))\
            """;

    private CostSql() {
    }
}
