package com.sixbean.testpike.common.report;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p> program dimension: scoped calibers carry a conjunctive pair of range
 * parameters — {@code :projectId} (single project) and {@code :projectIds}
 * (PostgreSQL bigint[] set literal, see {@link CaliberService#projectSetParam}).
 * Null means "unrestricted" in each dimension, so single-project and global
 * consumers keep byte-identical behavior while program snapshots pass a set.
 */
public enum CaliberDefinition {

    
    PASS_RATE("pass-rate", "通过率", "v1.0",
            "指定范围内执行任务的通过率=成功(status=3)÷终态(3/4/5)任务数；临时快速执行批不计入",
            """
            SELECT %1$s AS value
            FROM tp_exec_task t
            WHERE (:projectId::bigint IS NULL OR t.project_id = :projectId)
              AND (:projectIds::bigint[] IS NULL OR t.project_id = ANY(:projectIds::bigint[]))
              AND (:fromTime::timestamp IS NULL OR t.created_at >= :fromTime)
              AND (:toTime::timestamp IS NULL OR t.created_at < :toTime)
              AND %2$s
            """.formatted(PassRateSql.RATE_EXPR, PassRateSql.TEMP_BATCH_EXCLUDE)),

    
    DEFECT_DENSITY("defect-density", "缺陷密度", "v1.0",
            "未关闭缺陷数(status<>5 已关闭)÷项目有效用例数(deleted=0 且未归档 status<>9)",
            """
            SELECT (SELECT count(*) FROM tp_defect d
                     WHERE (:projectId::bigint IS NULL OR d.project_id = :projectId)
                       AND (:projectIds::bigint[] IS NULL OR d.project_id = ANY(:projectIds::bigint[])))::numeric
                   / NULLIF((SELECT count(*) FROM tp_test_case c
                              WHERE c.deleted = 0 AND c.status <> 9
                                AND (:projectId::bigint IS NULL OR c.project_id = :projectId)
                                AND (:projectIds::bigint[] IS NULL OR c.project_id = ANY(:projectIds::bigint[]))), 0)
                   AS value
            """),

 
    REQ_COVERAGE("req-coverage", "需求覆盖率", "v1.0",
            "已覆盖需求点数÷项目需求点总数；停用需求点(status=3)不计入分子分母，归档用例(status=9)与软删用例不计入覆盖",
            """
            SELECT (SELECT count(DISTINCT cr.requirement_id)
                      FROM tp_case_requirement cr
                      JOIN tp_requirement r ON r.id = cr.requirement_id
                      JOIN tp_test_case c ON c.id = cr.case_id AND c.deleted = 0 AND c.status <> 9
                     WHERE r.status <> 3
                       AND (:projectId::bigint IS NULL OR r.project_id = :projectId)
                       AND (:projectIds::bigint[] IS NULL OR r.project_id = ANY(:projectIds::bigint[])))::numeric
                   / NULLIF((SELECT count(*) FROM tp_requirement rq
                              WHERE rq.status <> 3
                                AND (:projectId::bigint IS NULL OR rq.project_id = :projectId)
                                AND (:projectIds::bigint[] IS NULL OR rq.project_id = ANY(:projectIds::bigint[]))), 0)
                   AS value
            """),

    
    AI_ACCEPT_RATE("ai-accept-rate", "AI 采纳率", "v1.0",
            "AI 生成用例(source=1)中首次评审即通过的用例数÷已评审 AI 用例总数（同一用例多次评审按最早一条计）",
            """
            WITH reviewed AS (
                SELECT DISTINCT r.case_id
                FROM tp_review_log r
                JOIN tp_test_case c ON c.id = r.case_id AND c.deleted = 0 AND c.source = 1
                WHERE (:projectId::bigint IS NULL OR c.project_id = :projectId)
                  AND (:projectIds::bigint[] IS NULL OR c.project_id = ANY(:projectIds::bigint[]))),
            first_action AS (
                SELECT DISTINCT ON (r.case_id) r.case_id, r.action
                FROM tp_review_log r
                JOIN reviewed rv ON rv.case_id = r.case_id
                ORDER BY r.case_id, r.created_at)
            SELECT (SELECT count(*) FROM first_action WHERE action = '通过')::numeric
                   / NULLIF((SELECT count(*) FROM reviewed), 0) AS value
            """),

    
    EXEC_P95("exec-p95", "执行时长 P95", "v1.0",
            "成功执行任务(status=3)的 duration_ms 95 分位（percentile_cont，毫秒）；空集返回 NULL",
            """
            SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY t.duration_ms) AS value
            FROM tp_exec_task t
            WHERE t.status = 3 AND t.duration_ms IS NOT NULL
              AND (:projectId::bigint IS NULL OR t.project_id = :projectId)
              AND (:projectIds::bigint[] IS NULL OR t.project_id = ANY(:projectIds::bigint[]))
              AND (:fromTime::timestamp IS NULL OR t.created_at >= :fromTime)
              AND (:toTime::timestamp IS NULL OR t.created_at < :toTime)
            """),

    
    TOKEN_COST_MONTH("token-cost-month", "月度 Token 成本", "v1.0",
            "指定自然月（yyyy-MM，UTC+8 月切）tp_ai_call_log 计费成本合计（含 E8 校准后 cost 列）；"
                    + "项目/模型/发起人/日四维分项与本总计共用同一作用域块（CostSql），分项之和恒等于总计",
            """
            SELECT COALESCE(sum(l.cost), 0) AS value, %2$s
            %1$s
            """.formatted(CostSql.SCOPE_FROM, CostSql.MEASURE_COLS)),

 
    TOKEN_COST_WINDOW("token-cost-window", "期间内 Token 成本", "v1.0",
            "期间 [起,止) 内 tp_ai_call_log 计费成本合计（含 E8 校准后 cost 列）；"
                    + "作用域与 token-cost-month 同规则（CostSql.WINDOW_SCOPE_FROM 共享块，软删项目语义一致）；"
                    + "周报/月报快照 payload 的期间成本取数口（自然月=token-cost-month 等值，周期=周窗口语义）",
            """
            SELECT COALESCE(sum(l.cost), 0) AS value, %2$s
            %1$s
            """.formatted(CostSql.WINDOW_SCOPE_FROM, CostSql.MEASURE_COLS)),

 
    TOKEN_COST_PROJECT("token-cost-project", "月度 Token 成本（按项目）", "v1.0",
            "token-cost-month 同作用域（自然月 UTC+8 月切+可选项目过滤）按 project_id 分组：项目成本合计+调用次数",
            """
            SELECT COALESCE(p.name, '未知项目') AS value, %2$s
            %1$s
            GROUP BY l.project_id, p.name
            ORDER BY amount DESC
            """.formatted(CostSql.SCOPE_FROM, CostSql.MEASURE_COLS)),

 
    TOKEN_COST_MODEL("token-cost-model", "月度 Token 成本（按模型）", "v1.0",
            "token-cost-month 同作用域按 model 分组：模型成本合计+调用次数（成本看板 TopN 消耗者模型链路）",
            """
            SELECT l.model AS value, %2$s
            %1$s
            GROUP BY l.model
            ORDER BY amount DESC
            """.formatted(CostSql.SCOPE_FROM, CostSql.MEASURE_COLS)),

 
    TOKEN_COST_CALLER("token-cost-caller", "月度 Token 成本（按发起人）", "v1.0",
            "token-cost-month 同作用域按 operator_id 分组：发起人（显示名，缺显名回退登录名）成本合计+调用次数",
            """
            SELECT COALESCE(u.display_name, u.username, '未知发起人') AS value, %2$s
            %1$s
            GROUP BY l.operator_id, u.display_name, u.username
            ORDER BY amount DESC
            """.formatted(CostSql.SCOPE_FROM, CostSql.MEASURE_COLS)),

 
    TOKEN_COST_DAY("token-cost-day", "月度 Token 成本（按日）", "v1.0",
            "token-cost-month 同作用域按调用日期分组：逐日成本合计+调用次数（成本看板日趋势链路）",
            """
            SELECT to_char(l.created_at, 'YYYY-MM-DD') AS value, %2$s
            %1$s
            GROUP BY 1
            ORDER BY 1
            """.formatted(CostSql.SCOPE_FROM, CostSql.MEASURE_COLS)),

    
    FEEDBACK_REJECT_TYPE("feedback-reject-type", "反馈问题类型分布", "v1.1",
            "评审拒绝/需修改动作按 feedback_types（D1 六类中文标签，unnest 展开一行一类）聚合计数；"
                    + "空列存量历史行归「未分类」兜底桶；D6 问题类型帕累托数据源",
            """
            SELECT t.type_label AS value,
                   count(*)::text AS extra
            FROM tp_review_log r
            JOIN tp_test_case c ON c.id = r.case_id AND c.deleted = 0
            CROSS JOIN LATERAL unnest(
                COALESCE(r.feedback_types, ARRAY['未分类']::text[])) AS t(type_label)
            WHERE r.action IN ('拒绝', '需修改')
              AND (:projectId::bigint IS NULL OR c.project_id = :projectId)
              AND (:projectIds::bigint[] IS NULL OR c.project_id = ANY(:projectIds::bigint[]))
            GROUP BY 1 ORDER BY count(*) DESC
            """),

    
    FIX_ADOPT_RATE("fix-adopt-rate", "修复采纳率", "v1.0",
            "评审中发生『需修改』的用例中，最终评审通过(status=3)的占比；D6 修复采纳率数据源",
            """
            WITH need_change AS (
                SELECT DISTINCT r.case_id
                FROM tp_review_log r
                JOIN tp_test_case c ON c.id = r.case_id AND c.deleted = 0
                WHERE r.action = '需修改'
                  AND (:projectId::bigint IS NULL OR c.project_id = :projectId)
                  AND (:projectIds::bigint[] IS NULL OR c.project_id = ANY(:projectIds::bigint[])))
            SELECT (SELECT count(*) FROM need_change nc
                     JOIN tp_test_case c2 ON c2.id = nc.case_id AND c2.status = 3)::numeric
                   / NULLIF((SELECT count(*) FROM need_change), 0) AS value
            """),


 /** Full-run P95 v2.0: merged histogram nearest-rank (falls back to worst-second p95 max when buckets absent). */
    PERF_P95("perf-p95", "压测响应时间 P95", "v2.0",
            "压测 run 全程 P95（毫秒）=各秒直方图桶（tp_perf_metric.hist，桶界与 core PerfHistogram "
                    + "镜像一致）合并后最近秩 ceil(0.95N) 取桶上界；桶缺席（旧帧）回退各秒 p95_ms "
                    + "最大值（秒窗谱如实降级）；SLA 门禁 perf-p95 判定与报告注脚同一算法（单处定义，"
                    + "禁引擎自带统计）。v1.0=各秒 p95 取 max，全程分位数学不成立已升版",
            """
            WITH ranked AS (
                SELECT (e.ord - 1) AS bucket,
                       sum(sum((e.cnt #>> '{}')::bigint)) OVER (ORDER BY e.ord - 1) AS cum,
                       sum(sum((e.cnt #>> '{}')::bigint)) OVER () AS total
                FROM tp_perf_metric m,
                     jsonb_array_elements(m.hist)
                       WITH ORDINALITY AS e(cnt, ord)
                WHERE m.run_id = :runId AND m.hist IS NOT NULL
                GROUP BY 1)
            SELECT COALESCE(
                (SELECT (ARRAY[5,10,15,20,25,30,40,50,75,100,125,150,175,200,250,300,
                               400,500,750,1000,1500,2000,3000,5000,10000,30000,60000])
                         [min(bucket) + 1]
                   FROM ranked WHERE cum * 20 >= total * 19),
                (SELECT max(m.p95_ms) FROM tp_perf_metric m WHERE m.run_id = :runId)) AS value
            """),

    /** Weighted error rate v2.0: sum(fail)/sum(req) (equal-weight second mean only as fallback). */
    PERF_ERROR_RATE("perf-error-rate", "压测错误率", "v2.0",
            "压测 run 全程错误率（0-1）=Σfail_count÷Σreq_count（按请求数加权，修正低载秒高错误率被"
                    + "等权放大）；桶缺席（旧帧）回退 error_rate 等权均值；SLA 门禁 perf-error-rate "
                    + "判定与报告注脚同源（单处定义）。v1.0=等权秒均值已升版",
            """
            SELECT COALESCE(
                (SELECT sum(m.fail_count)::numeric / NULLIF(sum(m.req_count), 0)
                   FROM tp_perf_metric m
                   WHERE m.run_id = :runId AND m.req_count IS NOT NULL),
                (SELECT avg(m.error_rate) FROM tp_perf_metric m WHERE m.run_id = :runId)) AS value
            """),

    /** Sustained throughput v2.0: sum(req)/sampled seconds (equal-weight rps mean only as fallback). */
    PERF_THROUGHPUT("perf-throughput", "压测持续吞吐", "v2.0",
            "压测 run 持续吞吐（请求/秒）=Σreq_count÷采样秒数（去重 second_offset）；桶缺席回退 "
                    + "rps 等权均值=SLA 门禁 perf-throughput 对照承诺 minRps 判定（单处定义）。"
                    + "v1.0=各秒 rps 等权均值已升版",
            """
            SELECT COALESCE(
                (SELECT sum(m.req_count)::numeric
                        / NULLIF((SELECT count(DISTINCT m2.second_offset) FROM tp_perf_metric m2
                                   WHERE m2.run_id = :runId), 0)
                   FROM tp_perf_metric m
                   WHERE m.run_id = :runId AND m.req_count IS NOT NULL),
                (SELECT avg(m.rps) FROM tp_perf_metric m WHERE m.run_id = :runId)) AS value
            """);

    
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<![:\\w]):([a-zA-Z_][a-zA-Z0-9_]*)");

    private final String code;
    private final String displayName;
    private final String version;
    private final String description;
    private final String sqlTemplate;

    CaliberDefinition(String code, String displayName, String version,
                      String description, String sqlTemplate) {
        this.code = code;
        this.displayName = displayName;
        this.version = version;
        this.description = description;
        this.sqlTemplate = sqlTemplate;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public String version() {
        return version;
    }

    public String description() {
        return description;
    }

    public String sqlTemplate() {
        return sqlTemplate;
    }

 
    public String hoverText() {
        return displayName + " " + version + "=" + description;
    }

    
    public List<String> namedParams() {
        List<String> names = new ArrayList<>();
        Matcher m = NAMED_PARAM.matcher(sqlTemplate);
        while (m.find()) {
            if (!names.contains(m.group(1))) {
                names.add(m.group(1));
            }
        }
        return names;
    }
}
