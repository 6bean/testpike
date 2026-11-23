package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * User-image reading prompt : asks the configured
 * vision model for a plain-text reading of one photo the user attached to a
 * dialog question. The dialog turn itself keeps running on the project text
 * router — only the model's OBSERVATION crosses over as text, never the raw
 * picture pretending a text model can see it (the ai.vision.model gate lives
 * in the resolver).
 * <p>Output contract = plain prose, visible-state only: the block rides the
 * system prompt next to the question, so speculation beyond what the picture
 * shows would be indistinguishable from what the user saw.
 */
@Component
public class ChatUserImagePromptTemplate {

    /** Observation discipline (system segment). */
    public static final String SYSTEM = """
            你是测试报障读图助手。用户提问时附上了一张自己拍摄的截图或照片，\
请只描述图片中肉眼可见的事实：当前在哪个页面/视图、有哪些弹窗或提示文案、按钮处于什么\
状态（可点/置灰）、是否有报错信息或空白区域。用不超过三句中文陈述，禁止推测代码原因、\
禁止编造图中不存在的内容。
            """;

    /**
     * Messages for one image read: system contract + user vision part naming
     * the image label as the reading anchor.
     *
     * @param systemHead assembled behaviour-kernel head
     * @param fileName display label of the attachment (already canonicalised)
     * @param imageUrl inlined {@code data:image/...;base64,...} url of the
     *                 image (the resolver downloads the presigned artifact
     *                 first — cloud providers cannot reach the deployment's
     *                 own storage host server-side)
     */
    public List<ChatMessage> messages(String systemHead, String fileName, String imageUrl) {
        String text = "用户附上的图片《" + fileName + "》，请描述这张图片的可见状态。";
        return List.of(new ChatMessage("system", systemHead),
                ChatMessage.userVision(text, imageUrl));
    }
}
