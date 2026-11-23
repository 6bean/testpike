package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineHealthInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEnvDoctorService;
import com.sixbean.testpike.core.common.domain.component.VersionCompare;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
public class AppautoEnvDoctorServiceImpl implements AppautoEnvDoctorService {

 
    static final String KEY_ENV_DOCTOR = "ai.appauto.env-doctor";

    
    private static final Map<String, String> RUNTIME_KEY_ALIASES = Map.of("jdk", "java");

    
    private static final Map<String, String> ENV_VAR_TOOL_KEYS = Map.of(
            "WX_DEVTOOLS_CLI_PATH", "wx_devtools_cli");

    
    private static final Pattern PROBE_ENDPOINT = Pattern.compile("//([\\w.]+:\\d+)");

    
    private static final Set<String> RUNTIME_PROBE_KEYS = Set.of("node", "java", "python3");

    private final AppautoEngineService engineService;
    private final ExecutorService executorService;
    private final ConfigService configService;

    public AppautoEnvDoctorServiceImpl(AppautoEngineService engineService,
                                       ExecutorService executorService,
                                       ConfigService configService) {
        this.engineService = engineService;
        this.executorService = executorService;
        this.configService = configService;
    }

    @Override
    public List<AppautoEngineHealthInfo> health(boolean includeDisabled) {
        List<AppautoEngineInfo> engines = engineService.listEngines(includeDisabled);
        List<Map<String, Object>> onlineCaps = executorService.onlineCapabilities();
        boolean aiEnabled = "1".equals(configService.getValue(KEY_ENV_DOCTOR, "0").trim());
        List<AppautoEngineHealthInfo> rows = new ArrayList<>(engines.size());
        for (AppautoEngineInfo engine : engines) {
            rows.add(reconcile(engine, onlineCaps, aiEnabled));
        }
        return rows;
    }

    private AppautoEngineHealthInfo reconcile(AppautoEngineInfo engine,
                                              List<Map<String, Object>> onlineCaps,
                                              boolean aiEnabled) {
        if (engine.status() != 1) {
            return new AppautoEngineHealthInfo(engine.code(), engine.name(), false,
                    onlineCaps.size(), 0, List.of(
                    "引擎「" + engine.name() + "」已停用/下架，环境体检不适用——"
                            + "如需启用请到引擎中心（管理）恢复上架"), aiEnabled);
        }
        if (onlineCaps.isEmpty()) {
            return new AppautoEngineHealthInfo(engine.code(), engine.name(), true, 0, 0,
                    List.of("当前无在线执行器——体检待执行器注册心跳后自动刷新"), aiEnabled);
        }
        Set<String> blocking = new LinkedHashSet<>();
        int ready = 0;
        for (Map<String, Object> caps : onlineCaps) {
            List<String> gaps = new ArrayList<>();
            checkRuntimeRequirements(engine, caps, gaps);
            checkEnvProbeSpec(engine, caps, gaps);
            if (gaps.isEmpty()) {
                ready++;
            } else {
                blocking.addAll(gaps);
            }
        }
        return new AppautoEngineHealthInfo(engine.code(), engine.name(), true,
                onlineCaps.size(), ready, List.copyOf(blocking), aiEnabled);
    }

    
    private void checkRuntimeRequirements(AppautoEngineInfo engine, Map<String, Object> caps,
                                          List<String> gaps) {
        for (Map.Entry<String, String> req : engine.runtimeVersionReq().entrySet()) {
            String reportedKey = RUNTIME_KEY_ALIASES.getOrDefault(req.getKey(), req.getKey());
            String actual = asText(capsMap(caps, "runtimes").get(reportedKey));
            if (actual == null) {
                gaps.add("引擎「" + engine.name() + "」要求 " + req.getKey() + " " + req.getValue()
                        + "，执行器上报面无该运行时版本实测——请在执行机核实版本并重新注册");
                continue;
            }
            if (!VersionCompare.satisfies(actual, req.getValue())) {
                gaps.add("引擎「" + engine.name() + "」要求 " + req.getKey() + " " + req.getValue()
                        + "，执行器实测 " + reportedKey + " " + actual + "——请升级后重新注册执行器");
            }
        }
    }

    
    private void checkEnvProbeSpec(AppautoEngineInfo engine, Map<String, Object> caps,
                                   List<String> gaps) {
        Map<String, Object> tools = capsMap(caps, "tools");
        List<String> reportedEngines = capsList(caps, "engines");
        for (Map<String, String> probe : engine.envCheckSpec()) {
            String cmd = probe.getOrDefault("cmd", "").trim();
            if (cmd.isEmpty()) {
                continue;
            }
            if (cmd.startsWith("curl")) {
                gaps.add("体检覆盖缺口：core 无法远程实证 " + endpointOf(cmd)
                        + " 服务端口存活（探测配方「" + cmd + "」需在执行机实测）"
                        + "——引擎「" + engine.name() + "」请按装机指引自查");
            } else if (cmd.contains("import ")) {
                String module = cmd.substring(cmd.indexOf("import ") + 7).trim();
                if (!reportedEngines.contains(engine.code())) {
                    gaps.add("引擎「" + engine.name() + "」依赖的 Python 组件 " + module
                            + " 未在执行器装载面（缺件归因=未安装或版本不兼容）——"
                            + "pip 安装后重新注册执行器（testPike 不随包分发第三方组件）");
                }
            } else if (cmd.startsWith("test -n")) {
                String toolKey = ENV_VAR_TOOL_KEYS.get(envVarNameOf(cmd));
                if (toolKey == null || !Boolean.TRUE.equals(tools.get(toolKey))) {
                    gaps.add("引擎「" + engine.name() + "」需配置环境变量 " + envVarNameOf(cmd)
                            + "（指向安装路径）并重新注册执行器——路径配置见装机指引");
                }
            } else {
                String exe = cmd.split("\\s+")[0];
                if (RUNTIME_PROBE_KEYS.contains(exe)) {
                    checkRuntimeProbe(engine, exe, probe.get("expect"),
                            capsMap(caps, "runtimes"), gaps);
                } else if (!Boolean.TRUE.equals(tools.get(exe))) {
                    gaps.add("引擎「" + engine.name() + "」依赖的命令行工具 " + exe
                            + " 不在执行器 PATH（需在执行机自行安装并加入 PATH，"
                            + "testPike 不随包分发；装机指引见引擎详情安装文档）");
                }
            }
        }
    }

    
    private void checkRuntimeProbe(AppautoEngineInfo engine, String exe, String expect,
                                   Map<String, Object> runtimes, List<String> gaps) {
        String key = "python3".equals(exe) ? "python" : exe;
        String actual = asText(runtimes.get(key));
        if (actual == null) {
            gaps.add("引擎「" + engine.name() + "」需 " + key + " 运行时，执行器上报面未实测到版本"
                    + "——请在执行机安装后重新注册执行器");
            return;
        }
        if (expect != null && !expect.isBlank() && !VersionCompare.satisfies(actual, expect)) {
            gaps.add("引擎「" + engine.name() + "」要求 " + key + " " + expect
                    + "，执行器实测 " + actual + "——请升级后重新注册执行器");
        }
    }

    private String endpointOf(String cmd) {
        Matcher m = PROBE_ENDPOINT.matcher(cmd);
        return m.find() ? m.group(1) : "服务端口";
    }

    private String envVarNameOf(String cmd) {
        Matcher m = Pattern.compile("\\$\\{?(\\w+)}?").matcher(cmd);
        return m.find() ? m.group(1) : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capsMap(Map<String, Object> caps, String key) {
        Object face = caps.get(key);
        return face instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> capsList(Map<String, Object> caps, String key) {
        Object face = caps.get(key);
        return face instanceof List<?> l ? (List<String>) l : List.of();
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() || "null".equals(s) ? null : s;
    }
}
