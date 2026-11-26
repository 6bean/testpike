package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;


@Component
public class PerfRunDispatcher {

    private final GatlingAdapter gatlingAdapter;
    private final K6Adapter k6Adapter;
    private final JmeterAdapter jmeterAdapter;
    private final PerfConfigReader perfConfigReader;
    private final PerfEngineResolver perfEngineResolver;
    private final ExecTaskService execTaskService;
    private final ObjectMapper objectMapper;
    /**
     * Engine routing table (W-d): engine key → adapter. Replaces the former
     * ternary that silently routed every non-K6 value to Gatling. A value-range
     * engine without a registered adapter is rejected honestly by
     * {@link #adapterFor} — never substituted (D1 no-silent-engine-switch).
     * Keys here must stay a subset of
     * {@link PerfRunDispatchSupport#ENGINE_VALUES}; the JMeter adapter package
     * registers its row when it lands.
     */
    private final Map<String, EngineAdapter> routingTable;

    public PerfRunDispatcher(GatlingAdapter gatlingAdapter, K6Adapter k6Adapter,
                             JmeterAdapter jmeterAdapter,
                             PerfConfigReader perfConfigReader,
                             PerfEngineResolver perfEngineResolver,
                             @Lazy ExecTaskService execTaskService,
                             ObjectMapper objectMapper) {
        this.gatlingAdapter = gatlingAdapter;
        this.k6Adapter = k6Adapter;
        this.jmeterAdapter = jmeterAdapter;
        this.perfConfigReader = perfConfigReader;
        this.perfEngineResolver = perfEngineResolver;
        this.execTaskService = execTaskService;
        this.objectMapper = objectMapper;
        // Ordered table: the reject wording enumerates supported engines in a stable sequence.
        Map<String, EngineAdapter> table = new LinkedHashMap<>();
        table.put("K6", k6Adapter);
        table.put("GATLING", gatlingAdapter);
        table.put("JMETER", jmeterAdapter);
        this.routingTable = table;
    }

    /**
     * Routing-table lookup with honest rejection: unknown or not-yet-adapted
     * engine = human wording naming the engine and the supported set.
     */
    public EngineAdapter adapterFor(String engine) {
        EngineAdapter adapter = routingTable.get(engine);
        if (adapter == null) {
            throw new ServiceException("压测引擎暂无可用适配器（" + (engine == null || engine.isEmpty()
                    ? "空" : engine) + "），当前支持=" + String.join("/", routingTable.keySet())
                    + "——请在「压测工作台」打开该场景编辑，把「压测引擎」改成上述取值之一并保存后重新起压",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return adapter;
    }

    
    public void dispatch(long projectId, long runId, Map<String, Object> scenarioView) {
        // W-d scenario-level engine binding resolved through the single door
        // (PerfEngineResolver) — the launch snapshot pins the same truth here.
        String engine = perfEngineResolver.resolve(scenarioView.get("engine"));
        EngineAdapter adapter = adapterFor(engine);
        String artifact;
        try {
            artifact = adapter.compile(scenarioView);
        } catch (ServiceException compileReject) {
            throw new ServiceException("压测运行启动失败：" + compileReject.getMessage()
                    + "——请回到「压测工作台」的场景编辑按上述原因修正步骤，保存后重新起压",
                    compileReject.getCode() == null ? BizErrorCode.VALIDATION_ERROR
                            : compileReject.getCode());
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", "perf-run");
        envelope.put("runId", runId);
        envelope.put("engine", engine);
        envelope.put("artifact", artifact);
        // A-14 budget self-healing: the envelope carries a duration-derived budget so
        // the executor watchdog and the server-side sweep judge this run by its own
        // horizon (duration + hot-updatable margin) instead of the global 10-minute
        // case timeout, which cut long runs down mid-flight. Duration unresolvable
        // (unknown model/legacy config) = key absent = consumers fall back (zero drift).
        long budgetMs = budgetOf(scenarioView);
        if (budgetMs > 0) {
            envelope.put("budgetMs", budgetMs);
        }
        try {
            execTaskService.createPerfRunTask(projectId, objectMapper.writeValueAsString(envelope));
        } catch (JsonProcessingException jsonFailure) {
            throw new ServiceException("压测运行启动失败：任务参数组织异常（"
                    + jsonFailure.getMessage() + "），请重新发起压测；反复失败请到「压测场景」检查该场景配置",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Scenario view (launch-time row: pressure_model + pressure_config json text)
     * → budget ms via {@link PerfBudgetPolicy} single point; margin from the K8 key
     * read at dispatch time (hot-update takes effect for the next launch).
     */
    private long budgetOf(Map<String, Object> scenarioView) {
        double durationSeconds;
        try {
            JsonNode config = objectMapper.readTree(
                    String.valueOf(scenarioView.get("pressure_config")));
            durationSeconds = PerfBudgetPolicy.durationSecondsOf(
                    String.valueOf(scenarioView.get("pressure_model")), config);
        } catch (Exception unparsable) {
            return 0L;   // dirty config = no budget key; consumers fall back, dispatch chain never breaks
        }
        return PerfBudgetPolicy.budgetMs(durationSeconds, perfConfigReader.budgetMarginMinutes());
    }
}
