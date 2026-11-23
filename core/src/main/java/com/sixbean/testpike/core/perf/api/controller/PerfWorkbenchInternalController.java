package com.sixbean.testpike.core.perf.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.perf.api.dto.PerfLiveCurveView;
import com.sixbean.testpike.core.perf.api.dto.PerfRunPageView;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioDraftRequest;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioPageView;
import com.sixbean.testpike.core.perf.api.dto.PerfTemplateInfo;
import com.sixbean.testpike.core.perf.domain.component.PerfScenarioTemplateCatalog;
import com.sixbean.testpike.core.perf.domain.component.PerfResourcePreflight;
import com.sixbean.testpike.core.perf.domain.component.PerfErrorClassCount;
import com.sixbean.testpike.core.perf.domain.service.PerfMetricService;
import com.sixbean.testpike.core.perf.domain.service.PerfReportService;
import com.sixbean.testpike.core.perf.domain.service.PerfRunInsightService;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioDraftService;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal/perf")
public class PerfWorkbenchInternalController {

    private final PerfScenarioService scenarioService;
    private final PerfRunService runService;
    private final PerfReportService reportService;
    private final PerfMetricService metricService;
    private final PerfScenarioTemplateCatalog templateCatalog;
    private final PerfResourcePreflight perfResourcePreflight;
    private final PerfScenarioDraftService scenarioDraftService;
    private final PerfRunInsightService runInsightService;

    public PerfWorkbenchInternalController(PerfScenarioService scenarioService,
                                           PerfRunService runService,
                                           PerfReportService reportService,
                                           PerfMetricService metricService,
                                           PerfScenarioTemplateCatalog templateCatalog,
                                           PerfResourcePreflight perfResourcePreflight,
                                           PerfScenarioDraftService scenarioDraftService,
                                           PerfRunInsightService runInsightService) {
        this.scenarioService = scenarioService;
        this.runService = runService;
        this.reportService = reportService;
        this.metricService = metricService;
        this.templateCatalog = templateCatalog;
        this.perfResourcePreflight = perfResourcePreflight;
        this.scenarioDraftService = scenarioDraftService;
        this.runInsightService = runInsightService;
    }

    /**
     * Shipped scenario-template catalogue (CF-4): config rows under the
     * perf.template. prefix, served as wizard picker data. Read-only and
     * project-independent (templates carry placeholders and example paths,
     * never credentials), same posture as the signature auth presets.
     */
    @GetMapping("/templates")
    public Response<List<PerfTemplateInfo>> listTemplates() {
        return Response.builderSuccess(templateCatalog.templates());
    }

    /**
     * D3 disclosure face: engine catalogue with rate-caliber caveats (W-d).
     * Read-only, project-independent; values come from the dispatch value
     * domain single source and the wording from PerfEngineDisclosure — the
     * wizard annotation and the report footnote reuse this same source, and
     * no AI-family configuration keys leave this door.
     */
    @GetMapping("/engines")
    public Response<List<Map<String, Object>>> listEngines() {
        return Response.builderSuccess(
                com.sixbean.testpike.core.perf.domain.component
                        .PerfEngineDisclosure.catalogue());
    }

    /**
     * Resource pre-flight readout (CF-5 + CF-7): the pool/capacity/queue and
     * dataset-demand wording for one scenario's next launch, read-only and
     * non-blocking (rejection stays owned by the launch transaction gates).
     * All judgements come from the dispatch path's single sources (online+
     * capability face, shared queue count, pressure tier, K8 default engine);
     * this door only projects them into human wording.
     */
    @GetMapping("/resource-preflight")
    public Response<Map<String, Object>> resourcePreflight(@RequestParam long projectId,
                                                            @RequestParam long scenarioId) {
        return Response.builderSuccess(perfResourcePreflight.preflight(projectId, scenarioId));
    }

    
    @GetMapping("/scenarios")
    public Response<List<Map<String, Object>>> listScenarios(@RequestParam long projectId) {
        return Response.builderSuccess(scenarioService.list(projectId));
    }

    /**
 * AI scenario draft for wizard prefill (B-11 : switch gate + step
 * re-validation/trimming have their single point in PerfScenarioDraftService;
 * nothing is persisted here — saving stays behind the human-confirmed
 * scenario door above. Exact-path mapping wins over /scenarios/{id} reads.
 */
    @PostMapping("/scenarios/draft")
    public Response<Map<String, Object>> draftScenario(
            @RequestBody PerfScenarioDraftRequest request) {
        return Response.builderSuccess(scenarioDraftService.draft(request));
    }

    /**
 * AI run-insight read-out (B-11 : switch gate, ownership/judge and
 * facts assembly have their single point in PerfRunInsightService — this
 * door is a thin wrapper (red line 6: no second spec here). Transient by
 * contract: nothing is persisted, the read-out rides back for the report card.
 */
    @PostMapping("/runs/{runId}/insight")
    public Response<Map<String, Object>> runInsight(@PathVariable long runId,
                                                    @RequestParam long projectId) {
        return Response.builderSuccess(runInsightService.insight(projectId, runId));
    }

    /**
 * One paged window of the project's scenarios display-scale
 * + sort contract: /api/v1/perf/scenarios/page consumes this door; clamp,
 * whitelist order and the truthful total are core semantics. The unbounded
 * list above stays for internal consumers like the wizard home prefetch).
 */
    @GetMapping("/scenarios/page")
    public Response<PerfScenarioPageView> pageScenarios(@RequestParam long projectId,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int pageSize,
                                                        @RequestParam(required = false) String sortBy,
                                                        @RequestParam(required = false) String sortDir,
                                                        @RequestParam(defaultValue = "0") long programId) {
        return Response.builderSuccess(
                scenarioService.listPaged(OperatorContext.currentOperatorId(), programId,
                        projectId, page, pageSize, sortBy, sortDir));
    }

    
    @GetMapping("/scenarios/{id}")
    public Response<Map<String, Object>> viewScenario(@PathVariable long id,
                                                      @RequestParam long projectId) {
        return Response.builderSuccess(scenarioService.view(projectId, id));
    }

    
    @PostMapping("/scenarios")
    public Response<Long> createScenario(@RequestParam long projectId,
                                         @RequestBody PerfScenarioInput input) {
        return Response.builderSuccess(scenarioService.create(projectId, input));
    }

    
    @PutMapping("/scenarios/{id}")
    public Response<Void> updateScenario(@PathVariable long id, @RequestParam long projectId,
                                         @RequestBody PerfScenarioInput input) {
        scenarioService.update(projectId, id, input);
        return Response.builderSuccess(null);
    }

    /**
     * Delete-impact ledger (A-13): the confirmation card reads runs-in-flight /
     * finished-runs-kept / approval links before the operator commits, so the
     * wording names real counts instead of a generic warning.
     */
    @GetMapping("/scenarios/{id}/delete-impact")
    public Response<Map<String, Object>> scenarioDeleteImpact(@PathVariable long id,
                                                              @RequestParam long projectId) {
        return Response.builderSuccess(scenarioService.deleteImpact(projectId, id));
    }

    /** Delete a scenario (A-13: in-flight run refuses; finished runs stay decoupled — core decides). */
    @DeleteMapping("/scenarios/{id}")
    public Response<Void> deleteScenario(@PathVariable long id, @RequestParam long projectId) {
        scenarioService.delete(projectId, id);
        return Response.builderSuccess(null);
    }

    /**
     * Launch a perf run (gate + precheck enforced inside the core launch chain;
     * operator = X-Operator-Id login context). GB-1: the optional envId overrides the
     * scenario-bound environment for this run — gate tier, environment lock and the
     * pinned snapshot all decide on the override; absent = scenario default.
     */
    @PostMapping("/scenarios/{id}/runs")
    public Response<Long> launchRun(@PathVariable long id, @RequestParam long projectId,
                                    @RequestParam(required = false) Long envId) {
        Long operator = OperatorContext.currentOperatorId();
        return Response.builderSuccess(runService.launch(projectId, id, envId,
                operator == null ? 0L : operator));
    }

    
    @GetMapping("/runs")
    public Response<List<Map<String, Object>>> listRuns(@RequestParam long projectId,
                                                        @RequestParam long scenarioId) {
        return Response.builderSuccess(runService.listByScenario(projectId, scenarioId));
    }

    /**
 * Project-wide run ledger window : no scenario key, so runs
 * whose scenario was deleted (A-13 history) stay addressable; clamp,
 * truthful total and the whitelisted sort all stay core semantics and the
 * bridge never re-paginates.
 */
    @GetMapping("/runs/project-page")
    public Response<PerfRunPageView> pageProjectRuns(@RequestParam long projectId,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int pageSize,
                                                     @RequestParam(required = false) String sortBy,
                                                     @RequestParam(required = false) String sortDir,
                                                     @RequestParam(required = false) Integer status,
                                                     @RequestParam(required = false) String mark) {
        return Response.builderSuccess(runService.listProjectPaged(projectId, page, pageSize,
                sortBy, sortDir, status, mark));
    }

    /**
 * One paged window of a scenario's runs the run drawer
 * consumes /api/v1/perf/runs/page over this door; clamp and the truthful
 * total are core semantics, order stays newest-first because the drawer
 * has no sortable headers, sort-whitelist exemption recorded there. The
 * unbounded list above stays for internal consumers like the run picker
 * on the version-compare page).
 */
    @GetMapping("/runs/page")
    public Response<PerfRunPageView> pageRuns(@RequestParam long projectId,
                                              @RequestParam long scenarioId,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        return Response.builderSuccess(runService.listByScenarioPaged(projectId, scenarioId,
                page, pageSize));
    }

    
    @GetMapping("/runs/{runId}")
    public Response<Map<String, Object>> viewRun(@PathVariable long runId,
                                                 @RequestParam long projectId) {
        return Response.builderSuccess(runService.view(projectId, runId));
    }

    /**
     * Mark a finished run (B-7): mark ∈ GOLDEN / CANDIDATE / VOID. Ownership and
     * state gates live in the core service (single source); promoting a golden
     * atomically demotes the scenario's incumbent golden to CANDIDATE.
     */
    @PostMapping("/runs/{runId}/mark")
    public Response<Void> markRun(@PathVariable long runId, @RequestParam long projectId,
                                  @RequestParam String mark) {
        runService.mark(projectId, runId, mark, OperatorContext.currentOperatorId());
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/runs/{runId}/report")
    public Response<Map<String, Object>> buildReport(@PathVariable long runId,
                                                     @RequestParam long projectId) {
        return Response.builderSuccess(reportService.build(projectId, runId));
    }

    /**
 * Run-report export : raw bytes, format=pdf|html — the same
 * build face serialized into a downloadable document (single source, never
 * a second computation head). Refusals (unknown format / foreign run) ride
 * the service wording untranslated.
 */
    @GetMapping(value = "/runs/{runId}/report-export", produces = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] exportReport(@PathVariable long runId, @RequestParam long projectId,
                               @RequestParam String format) {
        return reportService.export(projectId, runId, format);
    }

    
    @GetMapping("/runs/compare")
    public Response<Map<String, Object>> compareReports(@RequestParam long projectId,
                                                        @RequestParam long runA,
                                                        @RequestParam long runB) {
        return Response.builderSuccess(reportService.compare(projectId, runA, runB));
    }

    /**
 * Scenario trend rows : ascending terminal-run rows
 * with whole-run percentiles; ownership check + window truncation live in core
 * (thin wrapper here, same discipline as every other door).
 */
    @GetMapping("/scenarios/{id}/trend")
    public Response<List<Map<String, Object>>> scenarioTrend(@PathVariable long id,
                                                             @RequestParam long projectId) {
        return Response.builderSuccess(reportService.trend(projectId, id));
    }

    /**
 * CI gate verdict of one run :
 * pass/fail/pending riding the same single judge head as the report verdict
 * row; all pending shapes and ownership errors are decided in core (thin
 * wrapper here, same discipline as every other door).
 */
    @GetMapping("/runs/{runId}/ci-verdict")
    public Response<Map<String, Object>> ciVerdict(@PathVariable long runId,
                                                   @RequestParam long projectId) {
        return Response.builderSuccess(reportService.ciVerdict(projectId, runId));
    }

    
    @GetMapping("/runs/{runId}/frames")
    public Response<List<Map<String, Object>>> listFrames(@PathVariable long runId,
                                                          @RequestParam long projectId) {
        return Response.builderSuccess(metricService.listFrames(projectId, runId));
    }

    /**
 * Live-curve incremental read face : seconds after the cursor only,
 * adaptively bucketed when the window still exceeds the point ceiling. The running
 * page polls this instead of /frames — a 4h soak costs one append per poll, never a
 * 14400-row re-ship. Report pages keep the full unsampled caliber elsewhere.
 */
    @GetMapping("/runs/{runId}/live-curve")
    public Response<PerfLiveCurveView> liveCurve(@PathVariable long runId,
                                                 @RequestParam long projectId,
                                                 @RequestParam(required = false) Integer sinceOffset) {
        return Response.builderSuccess(
                metricService.liveCurve(projectId, runId, sinceOffset, null));
    }

    
    @GetMapping("/runs/{runId}/events")
    public Response<List<Map<String, Object>>> listEvents(@PathVariable long runId,
                                                          @RequestParam long projectId) {
        return Response.builderSuccess(metricService.listEvents(projectId, runId));
    }

    /** Error-class TopN of the run (B-6 error drawer read face: aggregate-caliber sums,
     *  descending; never-collected classes stay absent instead of a fabricated zero wall). */
    @GetMapping("/runs/{runId}/error-topn")
    public Response<List<PerfErrorClassCount>> errorClassTopN(@PathVariable long runId,
                                                              @RequestParam long projectId) {
        return Response.builderSuccess(metricService.errorClassTopN(projectId, runId));
    }
}
