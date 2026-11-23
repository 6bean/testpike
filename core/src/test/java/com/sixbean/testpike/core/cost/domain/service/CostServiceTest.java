package com.sixbean.testpike.core.cost.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cost.api.dto.BudgetInput;
import com.sixbean.testpike.core.cost.api.dto.BudgetView;
import com.sixbean.testpike.core.cost.api.dto.CostOverviewView;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CostServiceTest {

    @Autowired
    private CostService costService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String month;
    private String prevMonth;

    /** Run-unique tag on fixture project codes; leftover fixtures from an earlier
     *  run of the same suite must never collide with this run's buckets. */
    private static final String TAG = Long.toString(System.nanoTime() % 1_000_000_000L, 36);
    /** Per-method unique model tokens: rows from a previous test method are already
     *  swept, but tokens must still stay distinguishable for global-bucket anchors. */
    private final java.util.Set<String> modelTokens = new java.util.HashSet<>();
    private final List<Long> projectIds = new java.util.ArrayList<>();

    private String freshModel() {
        String token = "gpt-x-" + Long.toString(System.nanoTime() % 1_000_000_000L, 36);
        modelTokens.add(token);
        return token;
    }

    @BeforeEach
    void cleanAndAnchor() {
        month = jdbcTemplate.queryForObject("SELECT to_char(now(), 'YYYY-MM')", String.class);
        prevMonth = YearMonth.parse(month).minusMonths(1).toString();
    }

    @AfterEach
    void sweepRunOwnedRows() {
        // R37 shared-db hygiene: project sweep cascades tp_cost_budget through the FK
        // graph; tp_ai_task has no FK into tp_project so it goes through the extra list;
        // rows outside the project scope (orphan project_id NULL, prev-month boundary
        // row) are matched by this run's unique model tokens.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, List.of("tp_ai_task"), projectIds);
        for (String token : modelTokens) {
            jdbcTemplate.update("DELETE FROM tp_ai_call_log WHERE model = ?", token);
        }
    }

    private String code(String base) {
        return base + "-" + TAG;
    }

    private long createProject(String base) {
        String c = code(base);
        long id = projectService.create(1L, new ProjectCreateInput("项目" + c, c, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    /** Bucket amount for the dimension item whose value ends with {@code suffix};
     *  missing bucket = assertion failure (hard assertion, no conditional skip). */
    private static double dimAmount(List<com.sixbean.testpike.core.cost.api.dto.CostDimItem> items,
            String suffix) {
        return items.stream()
                .filter(d -> d.value().endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺桶: " + suffix))
                .amount()
                .doubleValue();
    }

    
    private void insertCallLog(Long projectId, Long operatorId, String model, double cost, int daysAgo) {
        jdbcTemplate.update("""
                INSERT INTO tp_ai_call_log (project_id, operator_id, provider, model, function,
                                            prompt_tokens, completion_tokens, cost, latency_ms, created_at)
                VALUES (?, ?, 'openai', ?, 'gen', 100, 200, ?, 1000,
                        greatest(now() - (? || ' days')::interval,
                                 date_trunc('month', now()) + interval '1 hour'))
                """, projectId, operatorId, model, cost, daysAgo);
    }

    @Test
    void overviewDimSumsEqualTotalAndBucketsCorrect() {
        long p1 = createProject("CST1");
        long p2 = createProject("CST2");
        String mA = freshModel();
        String mB = freshModel();
        insertCallLog(p1, 1L, mA, 1.5, 0);
        insertCallLog(p1, 2L, mB, 2.5, 1);
        insertCallLog(p2, 1L, mA, 4.0, 0);

        CostOverviewView view = costService.overview(month, null);

        java.math.BigDecimal total = view.total().amount();
        // Red-line same-source invariant: the four dimension partitions must each sum
        // to the grand total (semantics of the shared CostSql scope block).
        assertEquals(total.doubleValue(), sumAmount(view.byProject()), 0.0001, "项目维之和≠总计（口径漂移）");
        assertEquals(total.doubleValue(), sumAmount(view.byModel()), 0.0001, "模型维之和≠总计");
        assertEquals(total.doubleValue(), sumAmount(view.byCaller()), 0.0001, "发起人维之和≠总计");
        assertEquals(total.doubleValue(), sumAmount(view.byDay()), 0.0001, "日维之和≠总计");
        // Bucket exactness re-anchored on run-unique tokens: the shared dev db keeps
        // other rows, so the global grand total can no longer be pinned as an absolute.
        assertEquals(4.0, dimAmount(view.byProject(), code("CST1")), 0.0001, "CST1 项目桶应=4.0");
        assertEquals(4.0, dimAmount(view.byProject(), code("CST2")), 0.0001, "CST2 项目桶应=4.0");
        assertEquals(5.5, dimAmount(view.byModel(), mA), 0.0001, mA + " 模型桶应=5.5（两项目各一条）");
        assertEquals(2.5, dimAmount(view.byModel(), mB), 0.0001, mB + " 模型桶应=2.5");
        assertEquals(2L, view.byModel().stream().filter(d -> d.value().equals(mA))
                .findFirst().orElseThrow().calls(),
                mA + " 调用次数应=2（两项目各一条）");
    }

    private static double sumAmount(List<com.sixbean.testpike.core.cost.api.dto.CostDimItem> items) {
        return items.stream().map(com.sixbean.testpike.core.cost.api.dto.CostDimItem::amount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                .doubleValue();
    }

    @Test
    void overviewProjectScopedFilter() {
        long p1 = createProject("CST1");
        long p2 = createProject("CST2");
        String mA = freshModel();
        String mOrphan = freshModel();
        insertCallLog(p1, 1L, mA, 1.5, 0);
        insertCallLog(p2, 1L, mA, 4.0, 0);
        insertCallLog(null, 1L, mOrphan, 0.5, 0);

        CostOverviewView view = costService.overview(month, p1);
        // Project-scope totals only see this run's rows (live traffic never targets
        // a run-unique fixture project), so the absolute value stays exact.
        assertEquals(0, view.total().amount().compareTo(java.math.BigDecimal.valueOf(1.5)),
                "项目过滤后总计应=该项目成本");
        assertEquals(1, view.byProject().size(), "项目维应仅剩单桶");
        assertEquals(1.5, dimAmount(view.byModel(), mA), 0.0001, "项目过滤后模型桶只应剩本项目金额");
        assertFalse(view.byModel().stream().anyMatch(d -> d.value().equals(mOrphan)),
                "全局孤儿行不得混入项目过滤");
    }

    @Test
    void overviewEmptyMonthIsZeroNotNull() {
        CostOverviewView view = costService.overview("2000-01", null);
        assertEquals(0, view.total().amount().compareTo(java.math.BigDecimal.ZERO),
                "无数据月总计=0 非报错（IT-E3-03）");
        assertTrue(view.byProject().isEmpty() && view.byModel().isEmpty()
                && view.byCaller().isEmpty() && view.byDay().isEmpty(), "无数据维=空集");
    }

    @Test
    void overviewMonthBoundaryUtc8() {
        long p1 = createProject("CST1");
        String mA = freshModel();
        String mBoundary = freshModel();
        insertCallLog(p1, 1L, mA, 1.0, 0);
        jdbcTemplate.update("""
                INSERT INTO tp_ai_call_log (project_id, operator_id, provider, model, function,
                                            prompt_tokens, completion_tokens, cost, latency_ms, created_at)
                VALUES (?, 1, 'openai', ?, 'gen', 1, 1, 9.0, 1,
                        date_trunc('month', now()) - interval '1 hour')
                """, p1, mBoundary);
        // Global buckets filtered on run-unique model tokens: exact against live traffic
        // without wiping tp_ai_call_log. A wrong month filter would fold one token's
        // amount into the other month (or drop it), so both anchors keep the semantics.
        assertEquals(1.0, dimAmount(costService.overview(month, null).byModel(), mA), 0.0001,
                "本月口径应只含本月夹具行金额");
        assertFalse(costService.overview(month, null).byModel().stream()
                        .anyMatch(d -> d.value().equals(mBoundary)),
                "本月口径混入上月行");
        assertEquals(9.0, dimAmount(costService.overview(prevMonth, null).byModel(), mBoundary), 0.0001,
                "上月末归属错误（IT-E3-03）");
    }

    @Test
    void overviewExcludesArchivedProjectFromGlobalButVisibleWhenScoped() {
        long p1 = createProject("CST1");
        long p2 = createProject("CST2");
        insertCallLog(p1, 1L, freshModel(), 2.0, 0);
        insertCallLog(p2, 1L, freshModel(), 3.0, 0);
        projectService.archive(p2);

        CostOverviewView global = costService.overview(month, null);
        // Run-unique project codes make the bucket anchors collision-free: the global
        // view must still exclude the archived project and keep the active one exact.
        assertEquals(2.0, dimAmount(global.byProject(), code("CST1")), 0.0001,
                "在架项目桶应恰为本项目金额");
        assertTrue(global.byProject().stream().noneMatch(d -> d.value().endsWith(code("CST2"))),
                "软删项目仍计入全局桶（IT-E3-03 违反）");

        CostOverviewView scoped = costService.overview(month, p2);
        assertEquals(0, scoped.total().amount().compareTo(java.math.BigDecimal.valueOf(3.0)),
                "软删项目单查应可见（IT-E3-03）");
    }

    @Test
    void overviewRejectsIllegalMonthFormat() {
        for (String bad : List.of("2026/09", "2026-13", "202609", "")) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> costService.overview(bad, null), "非法月份应拒: " + bad);
            assertEquals(40001, ex.getCode(), bad + " 应为参数校验 40001");
            assertTrue(ex.getMessage().contains("yyyy-MM"), "人话应含合法格式: " + ex.getMessage());
        }
    }

    @Test
    void budgetFallsBackToGlobalDefaultsThenOverwritesSingleRow() {
        long p1 = createProject("CST1");
        BudgetView def = costService.getBudget(p1);
        assertEquals(0L, def.budgetCents(), "K8 默认 budget-default-cents=0（不限）");
        assertEquals(80, def.alertPct(), "K8 默认 alert-pct=80");
        assertFalse(def.overridden(), "无覆写行=非覆写态");

        costService.putBudget(1L, p1, new BudgetInput(5000L, 90));
        BudgetView after = costService.getBudget(p1);
        assertEquals(5000L, after.budgetCents(), "写后读回一致（IT-E3-01）");
        assertEquals(90, after.alertPct());
        assertTrue(after.overridden(), "覆写态标记");
        costService.putBudget(1L, p1, new BudgetInput(8000L, 85));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_cost_budget WHERE project_id = ?", Long.class, p1));
        assertEquals(8000L, costService.getBudget(p1).budgetCents());
    }

    @Test
    void budgetValidationBoundaries() {
        long p1 = createProject("CST1");
        assertBudgetRejected(new BudgetInput(-1L, 80), "0");
        assertBudgetRejected(new BudgetInput(Long.MAX_VALUE, 80), "100000000000");
        assertBudgetRejected(new BudgetInput(100L, 0), "1~100");
        assertBudgetRejected(new BudgetInput(100L, 101), "1~100");
        assertBudgetRejected(new BudgetInput(null, 80), "budgetCents|预算");
        assertBudgetRejected(new BudgetInput(100L, null), "alertPct|水位");
        costService.putBudget(1L, p1, new BudgetInput(0L, 100));
        assertEquals(0L, costService.getBudget(p1).budgetCents());
    }

    private void assertBudgetRejected(BudgetInput input, String messageRegex) {
        long p1 = createProject("REJ" + (System.nanoTime() % 1_000_000_000L));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> costService.putBudget(1L, p1, input));
        assertEquals(40001, ex.getCode(), "应为参数校验 40001，实际消息=" + ex.getMessage());
        assertTrue(ex.getMessage().matches("(?s).*(" + messageRegex + ").*"),
                "人话报错应含合法域 " + messageRegex + "，实际=" + ex.getMessage());
    }

    @Test
    void budgetGetRejectsUnknownProject() {
        assertThrows(ServiceException.class, () -> costService.getBudget(999999999L));
    }
}
