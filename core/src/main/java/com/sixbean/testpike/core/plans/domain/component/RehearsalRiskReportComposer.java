package com.sixbean.testpike.core.plans.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.analysis.api.dto.CoverageSummaryView;
import com.sixbean.testpike.core.analysis.domain.service.SufficiencyAnalysisService;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.CiVerdictView;
import com.sixbean.testpike.core.batch.domain.component.CiGatePolicy;
import com.sixbean.testpike.common.defect.DefectLabels;
import com.sixbean.testpike.core.plans.api.dto.CaseRiskLine;
import com.sixbean.testpike.core.plans.api.dto.RehearsalRiskReportView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assembles one rehearsal risk report card, read-only .
 * <p>Everything the card shows comes from existing single computations: the
 * verdict is the pass-through of {@link CiGatePolicy} (same pass-rate
 * expression as the batch badge, plan exit-criteria override included), the
 * coverage is the pass-through of {@link SufficiencyAnalysisService} (never a
 * second denominator), and the ledger counts and failed-case lines are the
 * recorded tp_batch_case rows.
 * <p>Posture equals the draft composer: the project segment is carried by the
 * caller and re-checked against the batch row (a batch of another project is
 * refused by id, never answered with foreign facts), the batch id is a
 * ledger key, and nothing here writes, audits or flips a state machine.
 */
@Component
public class RehearsalRiskReportComposer {

    /** Worst-scale display rule: the card lists at most this many failed lines. */
    private static final int FAILED_CASE_DISPLAY_CAP = 30;

    /**
     * Caliber note shown on the card. User-facing copy: it names where a
     * threshold comes from in the user's own terms (the regression plan's
     * exit criteria, otherwise the gate pass-rate in system settings) and never
     * leaks the wire field name or the config key — an operator reading the card
     * must learn what to do, not how the DTO is shaped.
     */
    private static final String CALIBER_NOTE =
            "卡片口径：判定与通过率同批次徽标与 CI 门禁一致（准出阈值取回归计划设定的准出通过率，"
                    + "计划未设时取系统设置的门禁通过率）；覆盖率与覆盖分析页同一算法，"
                    + "降幅请对照本会话上一次演练卡的需求覆盖数字；未结束批次只报当前进度不硬判。";

    private final JdbcTemplate jdbcTemplate;
    private final CiGatePolicy ciGatePolicy;
    private final SufficiencyAnalysisService sufficiencyService;

    public RehearsalRiskReportComposer(JdbcTemplate jdbcTemplate, CiGatePolicy ciGatePolicy,
                                       SufficiencyAnalysisService sufficiencyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.ciGatePolicy = ciGatePolicy;
        this.sufficiencyService = sufficiencyService;
    }

    /**
 * Build the card for one rehearsal batch of the current conversation segment.
 *
 * @param projectId the server-carried segment (never model-fillable)
 * @param batchId batch id from the draft-confirmation or launch receipt
 */
    public RehearsalRiskReportView compose(long projectId, Long batchId) {
        if (batchId == null || batchId <= 0) {
            throw new ServiceException("取演练风险报告卡需要批次编号 batchId——"
                    + "批次编号来自草案确认回执或演练启动回执（形如 {\"batchId\": 512}）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<Map<String, Object>> batchRows = jdbcTemplate.queryForList(
                "SELECT project_id, name, status, plan_id FROM tp_exec_batch WHERE id = ?",
                batchId);
        if (batchRows.isEmpty()) {
            throw new ServiceException("批次 " + batchId + " 不存在或已被删除，未读取任何结果账——"
                    + "请使用草案确认回执中给出的批次编号", BizErrorCode.NOT_FOUND);
        }
        Map<String, Object> batch = batchRows.get(0);
        long batchProject = ((Number) batch.get("project_id")).longValue();
        if (batchProject != projectId) {
            throw new ServiceException("批次 " + batchId + " 不在当前项目内，未读取结果账——"
                    + "请在该批次所属项目下发起对话后重试", BizErrorCode.FORBIDDEN);
        }
        int status = ((Number) batch.get("status")).intValue();
        Long planId = positiveId(batch.get("plan_id"));

        long[] counts = readCounts(batchId);
        List<CaseRiskLine> failedLines = readFailedLines(batchId);

        CiVerdictView verdict = ciGatePolicy.evaluate(batchId, status, planId);
        CoverageSummaryView coverage = sufficiencyService.coverageSummary(projectId, null, null);
        return new RehearsalRiskReportView(batchId,
                batch.get("name") == null ? "批次" + batchId : String.valueOf(batch.get("name")),
                status, BatchInfo.statusLabel(status),
                verdict, verdictDisplay(verdict),
                counts[0], counts[1], counts[2], counts[3], counts[4],
                failedLines.size() > FAILED_CASE_DISPLAY_CAP
                        ? failedLines.subList(0, FAILED_CASE_DISPLAY_CAP) : failedLines,
                failedLines.size() > FAILED_CASE_DISPLAY_CAP,
                coverage, coverageDisplay(coverage), CALIBER_NOTE);
    }

    /**
     * One plain sentence over the pass-through verdict: rates as percents and
     * the threshold source named in the operator's own terms, so the answer
     * never quotes passRate/thresholdSource wire values. A pending verdict (or
     * any verdict without hard numbers) passes the human phrase through.
     */
    private static String verdictDisplay(CiVerdictView verdict) {
        if (verdict == null) {
            return "批次判定暂不可用";
        }
        if (verdict.passRate() == null || verdict.threshold() == null) {
            return verdict.verdictPhrase();
        }
        boolean passed = "pass".equals(verdict.verdict());
        return (passed ? "判定通过：通过率 " : "判定不通过：通过率 ")
                + percentOfOne(verdict.passRate())
                + (passed ? "，不低于准出阈值 " : "，低于准出阈值 ")
                + percentOfOne(verdict.threshold())
                + "（阈值来源：" + thresholdSourceLabel(verdict.thresholdSource()) + "）";
    }

    /** Threshold source code to the operator-facing name of the same setting. */
    private static String thresholdSourceLabel(String source) {
        return "plan".equals(source)
                ? "回归计划设定的准出通过率" : "系统设置的门禁通过率";
    }

    /**
     * Coverage as one on-screen sentence with a single caliber per side: the
     * requirement side reads covered/total (same fraction as the example the
     * dialog was taught), the contract side reads a measured percent or the
     * honest "gate off / no contract endpoints yet" wording — the -1 sentinel
     * never mixes into a fraction sentence.
     */
    private static String coverageDisplay(CoverageSummaryView coverage) {
        if (coverage == null) {
            return "覆盖率数据暂不可用";
        }
        String contractPart;
        if (!coverage.gateEnabled()) {
            contractPart = "契约覆盖准出门禁未启用";
        } else if (coverage.apitestCoveragePercent() < 0) {
            contractPart = "暂无契约接口，契约覆盖率无数字口径";
        } else {
            contractPart = "契约覆盖率 " + coverage.apitestCoveragePercent()
                    + "%（准出阈值 " + coverage.minApitestCoverage() + "%）";
        }
        return "需求覆盖 " + coverage.covered() + "/" + coverage.total()
                + "；" + contractPart + "。";
    }

    /** Rate 0~1 to one-decimal percent text (0.8200 → 82.0%). */
    private static String percentOfOne(java.math.BigDecimal rate) {
        return rate.multiply(java.math.BigDecimal.valueOf(100))
                .setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** One aggregate row of the result ledger; absent numbers read as zero. */
    private long[] readCounts(long batchId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT count(*) AS total,"
                        + " COALESCE(SUM(CASE WHEN result = 1 THEN 1 ELSE 0 END), 0) AS passed,"
                        + " COALESCE(SUM(CASE WHEN result = 2 THEN 1 ELSE 0 END), 0) AS failed,"
                        + " COALESCE(SUM(CASE WHEN result = 3 THEN 1 ELSE 0 END), 0) AS skipped,"
                        + " COALESCE(SUM(CASE WHEN result = 4 THEN 1 ELSE 0 END), 0) AS not_run"
                        + " FROM tp_batch_case WHERE batch_id = ?",
                batchId);
        if (rows.isEmpty()) {
            return new long[5];
        }
        Map<String, Object> row = rows.get(0);
        return new long[]{asLong(row.get("total")), asLong(row.get("passed")),
                asLong(row.get("failed")), asLong(row.get("skipped")), asLong(row.get("not_run"))};
    }

    /** Failed ledger rows newest-display-capped by one over the cap to detect truncation. */
    private List<CaseRiskLine> readFailedLines(long batchId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT bc.case_id, tc.title, bc.error_type, bc.flaky, bc.duration_ms"
                        + " FROM tp_batch_case bc LEFT JOIN tp_test_case tc ON tc.id = bc.case_id"
                        + " WHERE bc.batch_id = ? AND bc.result = 2 ORDER BY bc.case_id LIMIT "
                        + (FAILED_CASE_DISPLAY_CAP + 1),
                batchId);
        List<CaseRiskLine> lines = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String errorType = row.get("error_type") == null
                    ? null : String.valueOf(row.get("error_type"));
            boolean flaky = truthy(row.get("flaky"));
            lines.add(new CaseRiskLine(((Number) row.get("case_id")).longValue(),
                    row.get("title") == null ? null : String.valueOf(row.get("title")),
                    errorType, errorTypeLabel(errorType),
                    flaky, flaky ? "不稳定" : "稳定",
                    row.get("duration_ms") == null ? null
                            : ((Number) row.get("duration_ms")).longValue()));
        }
        return lines;
    }

    /**
     * Failure category to plain words: numeric codes map through the defects
     * single source (01 error_type enumeration), recorded free text keeps its
     * wording, and an absent category reads the unclassified label.
     */
    private static String errorTypeLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未分类";
        }
        try {
            return DefectLabels.errorTypeLabel(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException recordedAsText) {
            return raw.trim();
        }
    }

    private static Long positiveId(Object raw) {
        return raw instanceof Number number && number.longValue() > 0
                ? number.longValue() : null;
    }

    private static long asLong(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    /** Boolean and smallint both appear in ledger reads; anything else is false. */
    private static boolean truthy(Object raw) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return raw instanceof Number number && number.intValue() != 0;
    }
}
