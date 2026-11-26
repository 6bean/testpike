package com.sixbean.testpike.core.perf.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.perf.api.dto.PerfRunInsightRequest;
import com.sixbean.testpike.core.perf.domain.component.PerfAiSwitchGate;
import com.sixbean.testpike.core.perf.domain.component.PerfErrorClassCount;
import com.sixbean.testpike.core.perf.domain.service.PerfMetricService;
import com.sixbean.testpike.core.perf.domain.service.PerfRunInsightService;
import com.sixbean.testpike.core.perf.domain.service.PerfReportService;
import com.sixbean.testpike.core.perf.infrastructure.remote.client.AiServicePerfInsightClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI run-insight orchestration (B-11 .
 * <p>Flow: switch gate first (a closed point refuses in plain words naming the
 * exact key, with zero tokens spent) → the judge face {@code ciVerdict} doubles
 * as the ownership check (foreign/unknown run = NOT_FOUND human wording with no
 * existence leak, riding the {@code PerfRunService.view} single point — same
 * discipline as the report/CI doors) → a still-pending run answers honestly
 * with zero model tokens (nothing has been attributed yet, so there is nothing
 * to explain; the judge's own phrase names why) → only a terminal verdict pays
 * for the assembled facts block and one ai-service hop.
 * <p>Facts are copied, never computed: the SLA conclusion phrase comes from
 * the single judge head, the metric numbers from the report's metric card, the
 * error-class counts from the B-6 TopN query, the caliber wording from the
 * report footnote (red line 6: this leg introduces no second attribution
 * spec — the model only re-words what core already concluded). An empty TopN
 * is stated as an honest absence, never as a wall of per-class zeros.
 * <p>The reply is not re-judged here: the ai leg owns the three-shape format
 * refusal (AI_FORMAT_ERROR), and whatever insight comes back rides through to
 * the report card untranslated. Nothing is persisted — a read-out is transient.
 */
@Service
public class PerfRunInsightServiceImpl implements PerfRunInsightService {

    private static final Logger log = LoggerFactory.getLogger(PerfRunInsightServiceImpl.class);

    private final PerfAiSwitchGate switchGate;
    private final PerfReportService reportService;
    private final PerfMetricService metricService;
    private final AiServicePerfInsightClient insightClient;

    public PerfRunInsightServiceImpl(PerfAiSwitchGate switchGate,
                                     PerfReportService reportService,
                                     PerfMetricService metricService,
                                     AiServicePerfInsightClient insightClient) {
        this.switchGate = switchGate;
        this.reportService = reportService;
        this.metricService = metricService;
        this.insightClient = insightClient;
    }

    @Override
    public Map<String, Object> insight(long projectId, long runId) {
        switchGate.requireOpen(PerfAiSwitchGate.POINT_INSIGHT);
        if (runId <= 0) {
            throw new ServiceException("压测归因解读缺少有效的运行号，无法取数——请在「压测运行台账」点开一条运行行，"
                    + "从该行进入解读后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }

        // Ownership + judge in one hop (ciVerdict rides the run-view single point).
        Map<String, Object> verdict = reportService.ciVerdict(projectId, runId);
        String answer = String.valueOf(verdict.get("verdict"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("verdict", answer);
        out.put("verdictPhrase", verdict.get("verdictPhrase"));
        if (!"pass".equals(answer) && !"fail".equals(answer)) {
            // Pending shapes (in-flight / VOID): honest answer, zero model tokens,
            // and not even a report assembly is paid for — there is nothing to explain.
            out.put("insight", null);
            return out;
        }

        Map<String, Object> report = reportService.build(projectId, runId);
        String facts = factsBlock(verdict, report,
                metricService.errorClassTopN(projectId, runId));
        Map<String, Object> reply = insightClient.explain(
                new PerfRunInsightRequest(projectId, facts, runId));
        out.put("insight", reply == null ? null : reply.get("insight"));
        log.info("压测归因解读编排完成 projectId={} runId={} verdict={} factsChars={}",
                projectId, runId, answer, facts.length());
        return out;
    }

    /**
     * Plain-text facts block: conclusion, metric card, error TopN and the caliber
     * footnote — every line a verbatim copy of an existing single-source read
     * face, in the order a human reader would check them. Null numbers stay as
     * the honest "not recorded" wording instead of an invented 0.
     */
    private String factsBlock(Map<String, Object> verdict, Map<String, Object> report,
                              List<PerfErrorClassCount> errorTopN) {
        StringBuilder facts = new StringBuilder();
        facts.append("【判定结论】").append(text(verdict.get("verdictPhrase")));
        Object breached = verdict.get("breachedGates");
        if (breached instanceof List<?> gates && !gates.isEmpty()) {
            facts.append("；越线门禁：").append(String.join(" / ",
                    gates.stream().map(String::valueOf).toList()));
        }
        Object rawCard = report.get("card");
        if (rawCard instanceof Map<?, ?> card) {
            facts.append("\n【指标卡】采样秒数=").append(text(card.get("sampledSeconds")))
                    .append("；平均RPS=").append(text(card.get("avgRps")))
                    .append("；峰值RPS=").append(text(card.get("maxRps")))
                    .append("；最坏秒P95(ms)=").append(text(card.get("peakSecondP95Ms")))
                    .append("；平均错误率=").append(text(card.get("avgErrorRate")));
        }
        facts.append("\n【错误分类TopN】");
        if (errorTopN == null || errorTopN.isEmpty()) {
            facts.append("未记录到错误分类（该运行没有采集到分型错误，不是全为 0 的假象清单）");
        } else {
            facts.append(errorTopN.stream()
                    .map(row -> row.errorClass() + "=" + row.count())
                    .reduce((a, b) -> a + "、" + b).orElse(""));
        }
        facts.append("\n【口径注脚】").append(text(report.get("caliberFootnote")));
        return facts.toString();
    }

    private static String text(Object value) {
        return value == null ? "未记录" : String.valueOf(value);
    }
}
