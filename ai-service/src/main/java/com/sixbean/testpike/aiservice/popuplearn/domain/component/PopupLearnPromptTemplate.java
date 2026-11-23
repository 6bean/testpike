package com.sixbean.testpike.aiservice.popuplearn.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ContentPart;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class PopupLearnPromptTemplate {

    
    public static final String SYSTEM = """
            你是移动端自动化测试的弹窗模式分析助手。给定同一个失败任务临近失败时刻的\
            若干张监看截图（按时间先后排列），请判断最后几张截图上是否出现了挡住目标操作的\
            弹窗（权限申请/隐私政策/升级提示/广告/崩溃对话框等），并归纳成一条可复用的弹窗处置规则。
            只输出一个 JSON 对象，不要输出其他文字，格式：\
            {"rule":{"detect":{"可判定的命中特征，如 textContains/控件选择器族"}},\
            "dismiss":{"action":"app. 开头的处置动作（如 app.tap / app.back）",\
            "target":{"处置动作的定位对象，如 {\\"text\\":\\"同意\\"}；无需定位时可空对象"}},\
            "title":"这条规则的一句话中文名（20 字内）"}
            截图中没有可归纳的弹窗时，把 detect 输出为空对象 {}，并在 title 说明原因。
            禁止猜测截图里不存在的按钮文字；处置动作必须给出真实可点的定位特征。
            """;

    
    /** {@code systemHead}=caller-assembled behaviour-kernel head (shared L0 assembly). */
    public List<ChatMessage> messages(int frameCount, List<String> imageUrls, String systemHead) {
        StringBuilder user = new StringBuilder("同一失败任务的监看帧共 ").append(frameCount)
                .append(" 张（时间先后排列，最后一张最接近失败时刻）。")
                .append("请按契约输出弹窗处置规则 JSON。");
        List<ContentPart> parts = new ArrayList<>();
        parts.add(ContentPart.text(user.toString()));
        parts.addAll(imageUrls.stream().map(ContentPart::imageUrl).toList());
        return List.of(new ChatMessage("system", systemHead),
                new ChatMessage("user", null, null, null, List.copyOf(parts)));
    }
}
