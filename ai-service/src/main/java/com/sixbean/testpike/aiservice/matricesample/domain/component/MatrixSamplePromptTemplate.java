package com.sixbean.testpike.aiservice.matricesample.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.matricesample.api.dto.MatrixSampleCandidate;
import com.sixbean.testpike.aiservice.matricesample.api.dto.MatrixSampleProfile;
import com.sixbean.testpike.aiservice.matricesample.api.dto.MatrixSampleRequest;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class MatrixSamplePromptTemplate {

    /** Sampling contract head (system slot = conservative fallback head; the
     *  published template row replaces this text through the L0 kernel assembly). */
    public static final String SYSTEM = """
            你是移动端自动化测试平台的机型矩阵抽样顾问。兼容性测试的「引擎×平台×机型」组合会爆炸，\
            平台已程序化实算出全部候选组合面与画像数字（不是估计值），你的任务=从中挑出最值得先跑的
            前 N 个组合：优先覆盖不同机型轴与高通过率引擎、可寻址设备的组合优先（addressable=false
            的仅代表当前项目没有该类设备档案，若机型覆盖缺口需要它也可以推荐并在理由里说明）、
            成本上控制总机时。
            只输出一个 JSON 对象，格式：{"recommendations":[{"engineCode":"...","platformCode":"...",\
            "deviceKindCode":"..."}],"summary":"一句话推荐理由"}
            recommendations 数组每项的三个字段值必须逐字来自候选清单（禁止自造引擎/平台/设备类，
            禁止输出 JSON 以外的任何文字），条数不超过要求且按推荐优先级排序。
            禁止编造画像里没有的数字。
            """;

    private final ObjectMapper objectMapper;

    public MatrixSamplePromptTemplate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Assemble the dialogue for one sampling call; the system turn comes
     *  from the behaviour-kernel head supplied by the caller (published row or
     *  the SYSTEM contract fallback, always kernel-extended). */
    public List<ChatMessage> messages(MatrixSampleRequest request, String systemHead) {
        MatrixSampleProfile profile = request.profile();
        StringBuilder user = new StringBuilder("候选组合清单（平台实算，逐行）：\n");
        for (MatrixSampleCandidate c : request.candidates()) {
            user.append("- ").append(c.engineCode())
                    .append(" × ").append(c.platformCode())
                    .append(" × ").append(c.deviceKindCode())
                    .append("（可寻址设备档案=").append(c.addressable() ? "有" : "无").append("）\n");
        }
        user.append("画像（平台实算）：候选组合 ").append(profile.candidateCount())
                .append(" 个；项目机型现装面 ").append(profile.installedModelAxes())
                .append(" 轴（品名×OS 版本）；近窗口引擎终态通过率 ")
                .append(renderMap(profile.engineRecentPassRate()))
                .append("；每任务估时 ").append(profile.minutesPerTask())
                .append(" 分钟；单用例扇出上限 ").append(profile.fanoutMax())
                .append("。\n请推荐前 ").append(request.topN()).append(" 个组合并给出理由。");
        return List.of(new ChatMessage("system", systemHead), new ChatMessage("user", user.toString()));
    }

    
    private String renderMap(java.util.Map<String, Double> rates) {
        if (rates == null || rates.isEmpty()) {
            return "（暂无终态样本）";
        }
        try {
            return objectMapper.writeValueAsString(rates);
        } catch (JsonProcessingException e) {
            return String.valueOf(rates);
        }
    }
}
