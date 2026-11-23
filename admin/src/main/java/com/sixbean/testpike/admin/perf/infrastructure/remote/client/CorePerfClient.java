package com.sixbean.testpike.admin.perf.infrastructure.remote.client;

import com.sixbean.testpike.admin.perf.api.dto.PerfApprovalApplyRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfGateCheckRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfGateDecisionView;
import com.sixbean.testpike.admin.perf.api.dto.PerfRunPageView;
import com.sixbean.testpike.admin.perf.api.dto.PerfScenarioDraftRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfScenarioPageView;
import com.sixbean.testpike.admin.perf.api.dto.PerfScenarioSaveRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfTemplateDto;
import com.sixbean.testpike.admin.perf.api.dto.PerfWhitelistSaveRequest;

import java.util.List;
import java.util.Map;


public interface CorePerfClient {

    
    long createWhitelist(PerfWhitelistSaveRequest request);

    
    void deleteWhitelist(long projectId, long id);

    
    List<Map<String, Object>> listWhitelist(long projectId);

    
    long applyApproval(PerfApprovalApplyRequest request);

    
    void approveApproval(long id, long approverId);

    
    void rejectApproval(long id, long approverId, String rejectReason);

    
    List<Map<String, Object>> listApprovals(long projectId);

    
    PerfGateDecisionView precheck(PerfGateCheckRequest request);

    
    int stopRun(long projectId, long runId);

    /** Mark a finished run GOLDEN/CANDIDATE/VOID (B-7 golden-run marking; core single source). */
    void markRun(long projectId, long runId, String mark);

    
    int stopAllRuns(long projectId);

    
    int sendReportCard(long projectId, long runId);

 
    List<Map<String, Object>> listScenarios(long projectId);

    /**
     * Shipped scenario-template catalogue (CF-4, wizard picker data; read-only
     * and project-independent — the core catalogue owns the single read path
     * and the honest drop of broken rows, admin bridges verbatim).
     */
    List<PerfTemplateDto> listTemplates();

    /**
     * Engine catalogue for the wizard engine option (D3 disclosure face, W-d):
     * one row per declared engine carrying its rate-caliber caveat, values
     * single-sourced from the dispatch value domain and wording from the core
     * disclosure component. Read-only and project-independent — the bridge
     * passes rows through verbatim and owns no filtering or re-wording.
     */
    List<Map<String, Object>> engines();

    /**
     * Resource pre-flight readout (CF-5 + CF-7): human wording about executor
     * pool/capacity/queue outlook and dataset demand-vs-capacity for one
     * scenario's next launch. Read-only, non-blocking — core owns all
     * judgements (single online/capability + queue + tier sources), the bridge
     * passes the projection through verbatim.
     */
    Map<String, Object> resourcePreflight(long projectId, long scenarioId);

    /**
 * One paged window of the project's scenarios the
 * scenario ledger page consumes this door; clamp/whitelist order/truthful
 * total are core semantics carried through untouched).
 */
    PerfScenarioPageView pageScenarios(long projectId, int page, int pageSize,
                                       String sortBy, String sortDir, long programId);

    
    Map<String, Object> viewScenario(long projectId, long id);

    
    long createScenario(PerfScenarioSaveRequest request);

    
    void updateScenario(long projectId, long id, PerfScenarioSaveRequest request);

    /** Delete-impact ledger for the confirmation card (A-13; counts decided in core). */
    Map<String, Object> scenarioDeleteImpact(long projectId, long id);

    /** Delete a scenario (A-13: in-flight run refuses, finished runs stay decoupled — core decides). */
    void deleteScenario(long projectId, long id);

    /**
     * Launch a perf run (three-layer gate + precheck single-point in the core launch
     * chain; operator via X-Operator-Id; returns the run id). GB-1: envId overrides
     * the scenario-bound environment for this run (null = scenario default).
     */
    long launchRun(long projectId, long scenarioId, Long envId);

    
    List<Map<String, Object>> listRuns(long projectId, long scenarioId);

    /** One paged window of a scenario's runs the run
 * drawer door; clamp and the truthful total are core semantics and the
 * bridge never re-paginates). */
    PerfRunPageView pageRuns(long projectId, long scenarioId, int page, int pageSize);

    /**
 * Project-wide run ledger window : every run of the project
 * segment including A-13 decoupled history; core owns clamp/total/sort.
 * The optional status/mark filters pass straight through — narrowing and
 * the narrowed total stay core semantics, the bridge never re-filters.
 */
    PerfRunPageView pageProjectRuns(long projectId, int page, int pageSize,
                                    String sortBy, String sortDir,
                                    Integer status, String mark);

    
    Map<String, Object> viewRun(long projectId, long runId);

    
    Map<String, Object> buildReport(long projectId, long runId);

    /** Run-report export : raw pdf/html bytes from the same core
 * single-source assembly; refusals ride core wording untranslated. */
    byte[] exportReport(long projectId, long runId, String format);

    
    Map<String, Object> compareReports(long projectId, long runA, long runB);

    /** Scenario trend rows : ascending terminal-run rows with
 * whole-run percentiles; ownership check and the window live in core. */
    List<Map<String, Object>> scenarioTrend(long projectId, long scenarioId);

    
    List<Map<String, Object>> listFrames(long projectId, long runId);

    /** Live-curve incremental face : cursor append + server-side downsample. */
    Map<String, Object> liveCurve(long projectId, long runId, Integer sinceOffset);

    
    List<Map<String, Object>> listEvents(long projectId, long runId);

    /** Error-class TopN (B-6 error drawer: {errorClass, count} rows, descending). */
    List<Map<String, Object>> errorClassTopN(long projectId, long runId);

    /**
 * AI scenario draft for wizard prefill (B-11 : switch refusal and
 * step trimming are core orchestration semantics — the bridge forwards the
 * intent verbatim and passes {steps, droppedCount, droppedReasons} back
 * with human wording untranslated (red line 6, no second validation here).
 */
    Map<String, Object> draftScenario(long projectId, String description);

    /** One AI run-insight hop through core orchestration (B-11 . */
    Map<String, Object> runInsight(long projectId, long runId);
}
