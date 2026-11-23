package com.sixbean.testpike.aiservice.wxmock.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.wxmock.api.dto.WxMockRequest;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class WxMockPromptTemplate {

    
    public static final String SYSTEM = """
            你是微信小程序自动化测试的接口 mock 数据生成助手。按给定的接口契约样例，生成 wx.request 的 mock 响应数据集。
            只输出一个 JSON 对象，格式：{"mocks":[{"scenario":"normal","response":{...}},
            {"scenario":"boundary","response":{...}},{"scenario":"empty","response":{...}}]}
            scenario 固定三个：normal=按契约类型生成合理业务值；boundary=每个字段取边界值
            （数字取 0 或极大值、字符串取空串或超长串）；empty=响应体为空对象 {}。
            response 字段必须与契约 response 定义的字段一一对应，类型匹配，禁止编造契约外的字段。
            证件与号码类字段（身份证/银行卡/统一社会信用代码/IBAN/手机号，按字段名语义识别）
            一律写成模板占位符 ${gen.函数名(seed=整数)}，函数名只能从「本次可用生成函数清单」里选，
            禁止直接编造号码字面量（编造的号码校验位必错，会被下游校验闸剪除）。
            其余字段直接给字面值；数字保持数字型，布尔保持 true/false，不套引号。
            不要输出任何 JSON 以外的文字。
            """;

    private final ObjectMapper objectMapper;

    public WxMockPromptTemplate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    
    public List<ChatMessage> messages(String systemHead, WxMockRequest request) {
        StringBuilder user = new StringBuilder("mock 目标接口：")
                .append(request.method() == null ? "GET" : request.method())
                .append(" ").append(request.apiPath())
                .append("\n接口契约样例（JSON，request=请求体字段类型，response=响应字段类型）：")
                .append(renderContract(request.contract()))
                .append("\n本次可用生成函数清单（占位符函数名只能从这些里选）：")
                .append(renderFunctions(request.genFunctions()))
                .append("\n请按契约输出 mock 数据集 JSON。");
        return List.of(new ChatMessage("system", systemHead), new ChatMessage("user", user.toString()));
    }

    
    private String renderContract(Object contract) {
        if (contract == null) {
            return "（空）";
        }
        try {
            return objectMapper.writeValueAsString(contract);
        } catch (JsonProcessingException e) {
            return String.valueOf(contract);
        }
    }

    
    private String renderFunctions(List<String> functions) {
        if (functions == null || functions.isEmpty()) {
            return "（空）";
        }
        try {
            return objectMapper.writeValueAsString(functions);
        } catch (JsonProcessingException e) {
            return String.valueOf(functions);
        }
    }
}
