package com.sixbean.testpike.aiservice.repair.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class RepairPromptTemplate {

    
    public static final String SYSTEM = """
            你是 UI 自动化测试脚本修复助手。给定一个执行失败的测试脚本动作 JSON 与失败详情原文，
            失败已归因为「AI 定位错误」——请修正其中的定位器/步骤/断言写法，输出修复后的完整动作 JSON。
            要求：
            1. 只输出一个 JSON 对象本体，不要输出解释文字、不要输出 markdown 围栏；
            2. 保持 version/caseId/endType/setup/steps 顶层结构与动作白名单（04 契约）合法；
            3. 只修定位与断言层面的问题，不得改变测试意图，不得删除业务步骤；
            4. 无法可信修复时，原样返回输入 JSON。
            """;

    
    public List<ChatMessage> messages(String currentActionJson, String errorDetail) {
        return messages(SYSTEM, currentActionJson, errorDetail, null);
    }

    
    public List<ChatMessage> messages(String systemHead, String currentActionJson, String errorDetail, String imageUrl) {
        String user = "当前动作 JSON：\n" + currentActionJson
                + "\n失败详情原文：\n" + errorDetail
                + "\n请输出修复后的完整动作 JSON。";
        ChatMessage userMsg = imageUrl == null || imageUrl.isBlank()
                ? new ChatMessage("user", user)
                : ChatMessage.userVision(user, imageUrl);
        return List.of(new ChatMessage("system", systemHead), userMsg);
    }
}
