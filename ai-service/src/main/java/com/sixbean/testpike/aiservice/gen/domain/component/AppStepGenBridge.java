package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.util.UiActionContract;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;


@Component
public class AppStepGenBridge {

 
    static final String KEY_GEN_STEPS = "ai.appauto.gen-steps";

 
    private static final String TARGET_APP = "app";
    private static final String TARGET_MP = "mp";
    private static final String TARGET_HARMONY = "harmony";

    private final CoreGateway coreGateway;

    public AppStepGenBridge(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    
    public String dispatchSegment(long caseId) {
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_GEN_STEPS))) {
            return "";
        }
        Map<String, Object> caseInfo;
        try {
            caseInfo = coreGateway.getCase(caseId);
        } catch (Exception e) {
            return "";
        }
        String target = textOf(caseInfo, "automationTarget");
        if (!TARGET_APP.equals(target) && !TARGET_MP.equals(target) && !TARGET_HARMONY.equals(target)) {
            return "";
        }
        Map<String, Object> engine = resolveEngine(textOf(caseInfo, "appautoEngine"));
        if (engine == null) {
            return "";
        }
        Map<String, Boolean> caps = capabilitiesOf(engine);
        List<String> allowed = UiActionContract.prunedVerbs(caps);
        if (allowed.isEmpty()) {
            return "";
        }
        return render(target, engine, caps, allowed);
    }

    
    private Map<String, Object> resolveEngine(String engineCode) {
        List<Map<String, Object>> engines;
        try {
            engines = coreGateway.listAppautoEngines();
        } catch (Exception e) {
            return null;
        }
        if (engines == null || engines.isEmpty()) {
            return null;
        }
        if (engineCode == null || engineCode.isBlank()) {
            return engines.get(0);
        }
        String wanted = engineCode.trim().toUpperCase(Locale.ROOT);
        return engines.stream()
                .filter(e -> wanted.equals(textOf(e, "code")))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> capabilitiesOf(Map<String, Object> engine) {
        Object caps = engine.get("capabilities");
        return caps instanceof Map<?, ?> ? (Map<String, Boolean>) caps : Map.of();
    }

    private String render(String target, Map<String, Object> engine,
                          Map<String, Boolean> caps, List<String> allowed) {
        List<String> flags = new ArrayList<>();
        for (String flag : UiActionContract.CAPABILITY_FLAGS) {
            if (Boolean.TRUE.equals(caps.get(flag))) {
                flags.add(flag);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n【APP 自动化步骤分流（本用例自动化目标=").append(target)
                .append("，目标引擎=").append(textOf(engine, "name"))
                .append("（").append(textOf(engine, "code")).append("））】\n");
        sb.append("本用例步骤动作只允许使用 app. 前缀的抽象动作 IR（引擎无关，换引擎零改），")
                .append("且只允许「本次允许动词清单」内的动词（清单=IR 基线∩目标引擎能力位图裁剪，")
                .append("清单外动词的产物一律被校验拒绝）：")
                .append(String.join(" / ", allowed.stream().map(v -> "app." + v).toList())).append("。\n");
        sb.append("前文 goto/click/input/expect 等 Web 动作清单对本用例不适用——")
                .append("本用例所有交互步一律用上述允许动词清单里的 app. 动词表达；")
                .append("清单里没有的动作就是目标引擎做不到的动作，禁用。\n");
        if (!flags.isEmpty()) {
            sb.append("目标引擎能力特性旗（仅这些位可用，未列出的特性禁依赖）：")
                    .append(String.join(" / ", flags)).append("。\n");
        }
        sb.append("定位器规约：target 必须是对象形，优先 resource-id 命名规约 ")
                .append("{\"resourceId\":\"<应用包名>:id/<控件id>\",\"chain\":[\"resourceId\"]}")
                .append("（包名:id/控件名三段拼接）；resource-id 不可得时退可见文本 ")
                .append("{\"text\":\"…\",\"chain\":[\"text\"]}；确定性定位链=resource-id→可见文本，")
                .append("禁编造描述里没出现过的文案与坐标。\n");
        return sb.toString();
    }

    private static String textOf(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
