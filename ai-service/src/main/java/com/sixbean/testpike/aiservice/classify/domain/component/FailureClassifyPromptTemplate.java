package com.sixbean.testpike.aiservice.classify.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class FailureClassifyPromptTemplate {

    
    public static final String SYSTEM = """
            你是测试执行失败归因助手。根据执行器上报的失败详情，把失败归为以下三类之一：
            1=产品缺陷（被测系统本身功能异常：接口 5xx、数据不对、页面行为与预期不符）；
            2=AI定位错误（生成的脚本/定位器问题：元素定位失败、步骤缺失、断言写法错）；
            3=环境问题（执行环境异常：服务不可达、超时、网络、测试数据或账号失效）。
            只输出一个 JSON 对象，不要输出其他文字，格式：{"classification":数字,"note":"一句话中文说明"}
            """;

 
    public List<ChatMessage> messages(Integer executorErrorType, String errorDetail, String systemHead) {
        return messages(executorErrorType, errorDetail, null, systemHead);
    }

    
    public List<ChatMessage> messages(Integer executorErrorType, String errorDetail,
                                      String imageUrl, String systemHead) {
        String user = "执行器上报分类码：" + (executorErrorType == null ? "无" : executorErrorType)
                + "\n失败详情原文：\n" + errorDetail
                + "\n请归因并按契约输出 JSON。";
        ChatMessage userMsg = imageUrl == null || imageUrl.isBlank()
                ? new ChatMessage("user", user)
                : ChatMessage.userVision(user, imageUrl);
        return List.of(new ChatMessage("system", systemHead), userMsg);
    }
}
