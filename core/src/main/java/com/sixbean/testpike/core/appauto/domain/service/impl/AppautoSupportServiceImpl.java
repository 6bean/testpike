package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoHealthGroupView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoSupportView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.AppautoHealthSamples;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoSupportService;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.api.dto.ComponentVerdict;
import com.sixbean.testpike.core.cluster.domain.component.ComponentSupportRules;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


@Service
public class AppautoSupportServiceImpl implements AppautoSupportService {

    
    private static final double RATE_SCALE = 10000.0;

    
    private static final List<String> STRICT_FIRST = List.of(
            ComponentVerdict.RED, ComponentVerdict.UNKNOWN,
            ComponentVerdict.YELLOW, ComponentVerdict.GREEN);

    
    private static final String UNKNOWN_ENGINE_NAME = "未知引擎";

    
    private static final String UNKNOWN_KEY = "";

    private final AppautoEngineService engineService;
    private final AppAutoDispatchSupport appAutoDispatchSupport;
    private final ClusterComponentService componentService;
    private final AppautoConfigReader appautoConfigReader;
    private final JdbcTemplate jdbcTemplate;

    public AppautoSupportServiceImpl(AppautoEngineService engineService,
                                     AppAutoDispatchSupport appAutoDispatchSupport,
                                     ClusterComponentService componentService,
                                     AppautoConfigReader appautoConfigReader,
                                     JdbcTemplate jdbcTemplate) {
        this.engineService = engineService;
        this.appAutoDispatchSupport = appAutoDispatchSupport;
        this.componentService = componentService;
        this.appautoConfigReader = appautoConfigReader;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AppautoSupportView> support(long projectId) {
        int windowDays = appautoConfigReader.supportWindowDays();
        double minRate = appautoConfigReader.supportPassRateMin() / 100.0;
        Map<String, Map<String, AppautoHealthSamples>> healthByEngine =
                aggregateHealth(projectId, windowDays);
        List<ClusterComponentInfo> ledger = componentService.listAll();

        List<AppautoSupportView> out = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (AppautoEngineInfo engine : engineService.listEngines(true)) {
            String key = engine.code().toUpperCase(Locale.ROOT);
            out.add(viewFor(engine.code(), engine.name(), engine.status() == 1,
                    versionDim(engine, ledger),
                    healthByEngine.getOrDefault(key, Map.of()), windowDays, minRate));
            emitted.add(key);
        }
        for (Map.Entry<String, Map<String, AppautoHealthSamples>> entry : healthByEngine.entrySet()) {
            if (UNKNOWN_KEY.equals(entry.getKey()) || emitted.contains(entry.getKey())) {
                continue;
            }
            out.add(viewFor(entry.getKey(), entry.getKey(), false,
                    gray("引擎「" + entry.getKey() + "」不在描述符目录，无版本基准可依"
                            + "——历史样本仍按信封如实计入健康维"),
                    entry.getValue(), windowDays, minRate));
        }
        if (healthByEngine.containsKey(UNKNOWN_KEY)) {
            out.add(viewFor(null, UNKNOWN_ENGINE_NAME, false,
                    gray("该组任务信封缺少引擎描述（engine.code），版本维无从判定——"
                            + "诚实归「未知引擎」组，禁按任务类型猜引擎"),
                    healthByEngine.get(UNKNOWN_KEY), windowDays, minRate));
        }
        return out;
    }


    
    private Map<String, Map<String, AppautoHealthSamples>> aggregateHealth(long projectId,
                                                                           int windowDays) {
        Map<String, Map<String, AppautoHealthSamples>> byEngine = new LinkedHashMap<>();
        for (Map<String, Object> row : terminalRows(projectId, windowDays)) {
            String actionJson = (String) row.get("action_json");
            String engineCode = appAutoDispatchSupport.envelopeEngineCode(actionJson);
            String engineKey = engineCode == null || engineCode.isBlank()
                    ? UNKNOWN_KEY : engineCode.trim().toUpperCase(Locale.ROOT);
            String platform = appAutoDispatchSupport.envelopePlatformCode(actionJson);
            String platformKey = platform == null ? UNKNOWN_KEY : platform;
            byEngine.computeIfAbsent(engineKey, k -> new LinkedHashMap<>())
                    .computeIfAbsent(platformKey, k -> new AppautoHealthSamples())
                    .accept(((Number) row.get("status")).intValue(),
                            (Integer) row.get("error_type"),
                            (Integer) row.get("ai_failure_class"));
        }
        return byEngine;
    }

    
    private List<Map<String, Object>> terminalRows(long projectId, int windowDays) {
        return jdbcTemplate.queryForList("""
                SELECT status, error_type, ai_failure_class, action_json FROM tp_exec_task
                WHERE task_type = 6 AND status IN (3, 4, 5) AND project_id = ?
                  AND COALESCE(finished_at, started_at, created_at)
                      >= CURRENT_TIMESTAMP - (? || ' days')::interval
                """, projectId, String.valueOf(windowDays));
    }


    
    private ComponentVerdict versionDim(AppautoEngineInfo engine, List<ClusterComponentInfo> ledger) {
        Set<String> families = ComponentSupportRules.familiesForRuntimeReq(engine.runtimeVersionReq());
        if (families.isEmpty()) {
            return gray("引擎「" + engine.code() + "」描述符未声明可纳管的运行时版本要求"
                    + "（runtime_version_req 空或键未登记部件族），无版本基准可依");
        }
        ComponentVerdict strictest = null;
        for (ClusterComponentInfo row : ledger) {
            if (!families.contains(row.componentFamily())) {
                continue;
            }
            ComponentVerdict verdict = ComponentSupportRules.judge(row.componentFamily(),
                    row.currentVersion(), row.requirement(), row.targetVersion());
            strictest = tighter(strictest, verdict, row, engine);
        }
        if (strictest == null) {
            return gray("该引擎运行时族（" + String.join("/", families)
                    + "）暂无节点台账上报，版本支持态诚实置灰——心跳扩报或基准扫描回写后自动重判");
        }
        return strictest;
    }

    
    private ComponentVerdict tighter(ComponentVerdict current, ComponentVerdict candidate,
                                     ClusterComponentInfo row, AppautoEngineInfo engine) {
        if (current == null) {
            return new ComponentVerdict(candidate.supportState(),
                    "节点 " + row.nodeId() + "·" + row.componentFamily() + "："
                            + candidate.stateReason());
        }
        boolean worse = STRICT_FIRST.indexOf(candidate.supportState())
                < STRICT_FIRST.indexOf(current.supportState());
        if (!worse) {
            return current;
        }
        return new ComponentVerdict(candidate.supportState(),
                "节点 " + row.nodeId() + "·" + row.componentFamily() + "（全节点最严，引擎 "
                        + engine.code() + "）：" + candidate.stateReason());
    }


    private AppautoSupportView viewFor(String code, String name, boolean enabled,
                                       ComponentVerdict version,
                                       Map<String, AppautoHealthSamples> platforms,
                                       int windowDays, double minRate) {
        List<AppautoHealthGroupView> groups = new ArrayList<>(platforms.size());
        int samples = 0;
        int passes = 0;
        for (Map.Entry<String, AppautoHealthSamples> entry : new TreeMap<>(platforms).entrySet()) {
            AppautoHealthSamples gs = entry.getValue();
            samples += gs.total();
            passes += gs.passCount();
            groups.add(new AppautoHealthGroupView(
                    UNKNOWN_KEY.equals(entry.getKey()) ? null : entry.getKey(),
                    gs.total(), gs.passCount(), rate(gs.passCount(), gs.total()),
                    Map.copyOf(gs.errorDist()), Map.copyOf(gs.aiDist())));
        }
        double rate = rate(passes, samples);
        String healthState;
        if (samples == 0) {
            healthState = ComponentVerdict.UNKNOWN;
        } else if (rate < minRate) {
            healthState = ComponentVerdict.YELLOW;
        } else {
            healthState = ComponentVerdict.GREEN;
        }
        return new AppautoSupportView(code, name, enabled,
                version.supportState(), version.stateReason(),
                healthState, samples, rate, List.copyOf(groups),
                conclude(name, version.supportState(), healthState, samples, rate, windowDays),
                caliber(windowDays));
    }

 
    private String conclude(String name, String versionState, String healthState,
                            int samples, double rate, int windowDays) {
        int pct = (int) Math.round(rate * 100);
        String head = name + "：版本维 " + stateWord(versionState);
        if (samples == 0) {
            return head + "；近 " + windowDays + " 天无执行样本，健康维诚实未知——"
                    + "跑过任务后自动出数，禁拿零样本当绿灯";
        }
        if (ComponentVerdict.YELLOW.equals(healthState)) {
            return head + "；近 " + windowDays + " 天通过率 " + pct
                    + "%，跌破健康线——建议先看归因再升级";
        }
        return head + "；近 " + windowDays + " 天通过率 " + pct + "%，双维健康";
    }

    private String stateWord(String state) {
        return switch (state) {
            case ComponentVerdict.GREEN -> "绿（支持矩阵内）";
            case ComponentVerdict.YELLOW -> "黄（可升级/超出背书）";
            case ComponentVerdict.RED -> "红（失守）";
            default -> "灰（证据不足）";
        };
    }

    
    private String caliber(int windowDays) {
        return "口径：数据源=APP 执行历史（task_type=6）近 " + windowDays + " 天"
                + "（天数 K8 键 appauto.support-health-window-days 可配热更）；"
                + "通过率=通过÷终态（3 成功/4 失败/5 超时，与全仓统计单谱 PassRateSql 同语义）；"
                + "引擎/平台维=任务信封 engine.code/engine.platform 投影，缺位诚实归「未知」组禁猜；"
                + "归因分布只计失败侧行；版本维=部件台账三色判定谱现算（禁第二套）。";
    }

    private static ComponentVerdict gray(String reason) {
        return new ComponentVerdict(ComponentVerdict.UNKNOWN, reason);
    }

    private static double rate(int pass, int total) {
        return total == 0 ? 0 : Math.round((double) pass / total * RATE_SCALE) / RATE_SCALE;
    }
}
