package com.sixbean.testpike.aiservice.upgradeadvisor.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.upgradeadvisor.api.dto.UpgradeAdvisorProfile;
import com.sixbean.testpike.aiservice.upgradeadvisor.api.dto.UpgradeAdvisorRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


@Component
public class UpgradeAdvisorPromptTemplate {

    
    public static final String SYSTEM = """
            你是移动端自动化测试平台的升级顾问。用户要给项目升级到新引擎版本，你会拿到两份材料：
            1) 目标版本的破坏性变更清单（逐条人话）；2) 该项目用例特征画像（平台程序化实算的统计数字，
            不是估计值）。请把两者对上：逐条判断破坏性变更是否命中画像里的用法，输出升级风险清单。
            只输出一个 JSON 对象，格式：{"risks":["...","..."],"summary":"一句话总体结论"}
            risks 每条必须是完整的人话句子，且引用画像里的实算数字说明影响面（如「你的项目里有 9 步在用它」）；
            没有命中的变更不要硬编风险；一条都不命中时 risks 给一条「未发现与你的用例特征直接冲突的变更」；
            summary 用一句话说清能不能升、建议先验证什么。
            禁止编造画像里没有的数字或动词；禁止输出 JSON 以外的任何文字。
            """;

    private final ObjectMapper objectMapper;

    public UpgradeAdvisorPromptTemplate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    
    public List<ChatMessage> messages(String systemHead, UpgradeAdvisorRequest request) {
        UpgradeAdvisorProfile profile = request.profile();
        StringBuilder user = new StringBuilder("目标升级版本：")
                .append(request.manifestVersion())
                .append("\n破坏性变更清单（逐条）：")
                .append(renderLines(request.breakingChanges()))
                .append("\n项目用例特征画像（平台实算）：")
                .append("现行脚本用例 ").append(profile.scriptCases()).append(" 个；")
                .append("app.* 动词使用计数 ").append(renderMap(profile.verbCounts())).append("；")
                .append("历史引擎使用计数 ").append(renderMap(profile.engineUsage())).append("；")
                .append("AI 定位样本用例 ").append(profile.aiSampleCases()).append(" 个；")
                .append("webview 混合落区步 ").append(profile.webviewSteps()).append(" 步")
                .append("\n请按上述契约输出升级风险清单 JSON。");
        return List.of(new ChatMessage("system", systemHead), new ChatMessage("user", user.toString()));
    }

    
    private String renderLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "（无）";
        }
        return String.join("\n", lines);
    }

    
    private String renderMap(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "（无）";
        }
        try {
            return objectMapper.writeValueAsString(counts);
        } catch (JsonProcessingException e) {
            return String.valueOf(counts);
        }
    }
}
