package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.apitest.domain.service.ApiRunService;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


@Component
public class ApiRunResultFinalizer {

    private static final Logger log = LoggerFactory.getLogger(ApiRunResultFinalizer.class);

    private final ApiRunService apiRunService;
    private final ApiVarPoolSupport apiVarPoolSupport;
    private final ApiRunSetAdvancer apiRunSetAdvancer;
    private final ObjectMapper objectMapper;

    public ApiRunResultFinalizer(ApiRunService apiRunService,
                                 ApiVarPoolSupport apiVarPoolSupport,
                                 ApiRunSetAdvancer apiRunSetAdvancer,
                                 ObjectMapper objectMapper) {
        this.apiRunService = apiRunService;
        this.apiVarPoolSupport = apiVarPoolSupport;
        this.apiRunSetAdvancer = apiRunSetAdvancer;
        this.objectMapper = objectMapper;
    }

    
    public void onResultSubmitted(ExecTaskInfo task, ExecResultInput input) {
        if (task.taskType() != 4) {
            return;
        }
        long runId = extractRunId(task.actionJson());
        if (runId <= 0) {
            log.warn("API 运行任务 {} 信封缺 runId（脏单）——运行记录不回写，任务收口不受影响", task.id());
            return;
        }
        int status = mapStatus(input);
        String storedVarsJson = apiVarPoolSupport.toStoredJson(input.vars());
        try {
            apiRunService.recordResult(runId, status,
                    input.durationMs() == null ? null : input.durationMs().longValue(),
                    assertionDetailJson(input.protocolSnapshots()), "", errorMsg(input, status),
                    storedVarsJson);
        } catch (ServiceException e) {
            log.warn("API 运行 {} 结果回写被拒（{}）——任务 {} 收口不受影响", runId, e.getMessage(), task.id());
        }
        // collection-run tail hook: a finished child hop drives the advancer
        // (next hop / suppression / close). Standalone runs are a no-op inside;
        // an advance failure never breaks the result chain — the ledger stays
        // honest and can be re-driven later.
        try {
            apiRunSetAdvancer.onChildFinished(runId);
        } catch (Exception e) {
            log.error("API 运行 {} 所属集合推进异常——任务 {} 收口不受影响", runId, task.id(), e);
        }
    }

 
    private int mapStatus(ExecResultInput input) {
        if ("pass".equals(input.result())) {
            return 1;
        }
        // an unsatisfied precondition is an honest skip, not a
        // verdict — it lands on run status 4 (same ledger state the collection
        // advancer writes for suppressed hops), never 2/3 fake failures.
        if ("manual_skip".equals(input.result())) {
            return 4;
        }
        if ("fail".equals(input.result()) && Integer.valueOf(1).equals(input.errorType())) {
            return 2;
        }
        return 3;
    }

    
    private String errorMsg(ExecResultInput input, int status) {
        if (status == 1) {
            return "";
        }
        return input.errorDetail();
    }

    
    private long extractRunId(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return 0;
        }
        try {
            return objectMapper.readTree(actionJson).path("runId").asLong(0);
        } catch (Exception e) {
            return 0;
        }
    }

    
    private String assertionDetailJson(Object protocolSnapshots) {
        if (!(protocolSnapshots instanceof List<?> list) || list.isEmpty()) {
            return "[]";
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> element)) {
            return "[]";
        }
        Object rows = element.get("assertions");
        if (rows == null) {
            return "[]";
        }
        try {
            JsonNode node = rows instanceof String s
                    ? objectMapper.readTree(s)
                    : objectMapper.valueToTree(rows);
            return node.isArray() ? node.toString() : "[]";
        } catch (Exception e) {
            return "[]";
        }
    }
}
