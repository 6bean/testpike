package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Failure-scene description prompt : asks the configured
 * vision model for a plain-text reading of the failure screenshot — the dialog
 * turn itself keeps running on the project text router, so only the model's
 * OBSERVATION crosses over as text, never the raw picture pretending a text
 * model can see it (the ai.vision.model gate lives in the resolver).
 * <p>Output contract = plain prose, visible-state only: the block rides the
 * system prompt next to the diagnosis, so speculation beyond what the picture
 * shows would be indistinguishable from evidence.
 */
@Component
public class FailureScenePromptTemplate {

    /** Observation discipline (system segment). */
    public static final String SYSTEM = """
            你是测试失败现场读图助手。给你一张 UI 自动化失败时刻的截图，请只描述截图中\
肉眼可见的事实：当前在哪个页面/视图、有哪些弹窗或提示文案、按钮处于什么状态（可点/置灰）、\
是否有报错信息或空白区域。用不超过三句中文陈述，禁止推测代码原因、禁止编造图中不存在的内容。
            """;

    /**
     * Messages for one scene read: system contract + user vision part with the
     * failure detail as reading context.
     *
     * @param systemHead assembled behaviour-kernel head
     * @param errorDetail failure detail excerpt (may be blank)
     * @param sceneImage  inlined {@code data:image/png;base64,...} url of the
     *                    screenshot (the resolver downloads the presigned
     *                    artifact first — cloud providers cannot reach the
     *                    deployment's own storage host server-side)
     */
    public List<ChatMessage> messages(String systemHead, String errorDetail, String sceneImage) {
        String text = "失败详情（读图时对照，可能不完整）："
                + (errorDetail == null || errorDetail.isBlank() ? "（无）" : errorDetail.strip())
                + "\n请描述这张失败现场截图的可见状态。";
        return List.of(new ChatMessage("system", systemHead),
                ChatMessage.userVision(text, sceneImage));
    }
}
