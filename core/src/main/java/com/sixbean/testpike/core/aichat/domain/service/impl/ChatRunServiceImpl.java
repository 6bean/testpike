package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatImageAttachments;
import com.sixbean.testpike.common.aichat.ChatObjectRef;
import com.sixbean.testpike.common.aichat.ChatObjectRefs;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import com.sixbean.testpike.core.aichat.api.dto.ChatRunInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.aichat.domain.service.ChatFrameService;
import com.sixbean.testpike.core.aichat.domain.service.ChatRunPublisher;
import com.sixbean.testpike.core.aichat.domain.service.ChatRunService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Dialog run opening . Composition, not a second
 * state machine: session ownership through {@link ChatSessionService}, the two
 * turn rows through {@link ChatFrameService} (the user echo is sealed with the
 * existing close rule, the assistant placeholder with the existing open rule),
 * the dispatch through {@link ChatRunPublisher}.
 * <p>Row order matters for reconnect honesty: the user echo is opened and
 * closed inside the same transaction, so a client that reconnects mid-run
 * replays exactly the streaming assistant block — finished turns are served
 * from the message body, never re-faked as live.
 * <p>Knife 4b: the @-mention chips are canonicalised here — the ONE validation
 * point of the chain (admin mirrors the wire shape, ai-service consumes the
 * canonical list) — before they ride the run message. A refusal (bad kind,
 * blank title, cap overrun) happens BEFORE any ledger row lands, so a refused
 * run leaves no half-written turn behind.
 * <p>Knife 1 : the failure-badge deep link stores its execution pointer
 * in the session whitelist context (already project-slice checked at the
 * session door); opening a run folds that id into the broker message so the
 * ai-service side can fetch the diagnosis. Extraction lives here — the one
 * door that reads the context — never re-parsed by any other consumer.
 */
@Service
public class ChatRunServiceImpl implements ChatRunService {

    private final ChatSessionService sessionService;
    private final ChatFrameService frameService;
    private final ChatRunPublisher runPublisher;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public ChatRunServiceImpl(ChatSessionService sessionService,
                              ChatFrameService frameService,
                              ChatRunPublisher runPublisher,
                              ConfigService configService,
                              ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.frameService = frameService;
        this.runPublisher = runPublisher;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ChatRunInfo openRun(long sessionId, long userId, String prompt,
                               List<ChatObjectRef> refObjects,
                               List<ChatImageAttachment> attachments,
                               boolean systemManager, String questionContextJson) {
        if (prompt == null || prompt.isBlank()) {
            throw new ServiceException("对话问题内容不能为空——请在「AI 对话」输入框输入问题后发送", BizErrorCode.VALIDATION_ERROR);
        }
        List<ChatObjectRef> canonicalRefs = ChatObjectRefs.canonicalize(
                refObjects, maxRefsPerQuestion());
        List<ChatImageAttachment> canonicalImages = ChatImageAttachments.canonicalize(
                attachments, maxImagesPerQuestion());
        ChatSessionInfo session = sessionService.requireOwned(sessionId, userId);
        // H1 at the run door : a chat-image key carries its
        // issuing project segment — a key minted under another project is a
        // horizontal reach, refused by name before any ledger row lands.
        for (ChatImageAttachment attachment : canonicalImages) {
            long issuerProject = ChatImageAttachments.projectIdOf(attachment.objectKey());
            if (issuerProject != session.projectId()) {
                throw new ServiceException("图片附件不属于当前项目（存储键："
                        + attachment.objectKey() + "）——请在「AI 对话」重新选择并上传当前项目内的图片后发送",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        // the per-question page anchor is checked at the SAME
        // whitelist door the session shell owns (one reconciliation point,
        // never a second validator) — an unknown key, a nested value or a
        // foreign project segment is refused here, BEFORE any ledger row lands.
        String questionContext = questionContextJson == null || questionContextJson.isBlank()
                ? null
                : sessionService.normalizeContextWhitelist(questionContextJson, session.projectId());
        long userMessageId = questionContext == null
                ? frameService.openMessage(sessionId, "user", prompt.strip())
                : frameService.openMessage(sessionId, "user", prompt.strip(), questionContext);
        // (write face): a question that carries user images books
        // ONE state frame on its own user turn BEFORE the seal — the durable
        // ledger of which objectKeys shipped with the question, so a later
        // turn can replay the pictures across messages instead of asking the
        // user to paste again. Display posture is untouched: the dock reducer
        // gives user rows no process area and history reload rebuilds bodies
        // for assistant rows only, so this frame never renders (red line:
        // zero visual drift, frame-granularity replay semantics unchanged).
        if (!canonicalImages.isEmpty()) {
            frameService.appendFrame(sessionId, userMessageId, ChatFrameType.STATE,
                    "本问附带图片 " + canonicalImages.size() + " 枚",
                    imageLedgerMetaJson(canonicalImages));
        }
        frameService.closeMessage(userMessageId, "done");
        long assistantMessageId = frameService.openMessage(sessionId, "assistant", null);
        // the session context follows the anchor the user just
        // asked from — folded AFTER the turn rows landed (a refused question
        // never reaches here), and silent when nothing actually moves (R28).
        if (questionContext != null) {
            sessionService.applyQuestionContext(sessionId, questionContext);
        }
        // The run pointer folds this question's executionId ahead of the
        // session-level deep link: the anchor the user just sent from wins.
        Long executionPointer = questionContext == null ? null : executionPointerOf(questionContext);
        if (executionPointer == null) {
            executionPointer = executionPointerOf(session.contextJson());
        }
        runPublisher.publishRun(new AiChatRunMessage(sessionId, assistantMessageId,
                session.projectId(), userId, prompt.strip(), canonicalRefs,
                executionPointer, canonicalImages, systemManager));
        return new ChatRunInfo(userMessageId, assistantMessageId);
    }

    /**
 * Structured value of the user-image ledger frame : the
 * canonical (objectKey, fileName) pairs in canonicalisation order, under
 * the {@code chatImages} meta key — the dataMoment precedent of a typed
 * meta_json value that the replay scanner reads without re-parsing any
 * prose. Built with Jackson so a user-chosen file name can never break
 * out of the JSON.
 */
    private String imageLedgerMetaJson(List<ChatImageAttachment> images) {
        var root = objectMapper.createObjectNode();
        var booked = root.putArray("chatImages");
        for (ChatImageAttachment image : images) {
            booked.addObject()
                    .put("objectKey", image.objectKey())
                    .put("fileName", image.fileName());
        }
        return root.toString();
    }

    /**
     * Read the whitelist context's executionId, if the session was opened from
     * a failure badge. Malformed context is treated as no pointer rather than
     * a refusal: the row was already validated at the session door, and a
     * broken legacy value must not block the user from asking a question.
     */
    private Long executionPointerOf(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return null;
        }
        try {
            JsonNode pointer = objectMapper.readTree(contextJson).get("executionId");
            if (pointer == null || pointer.isNull()) {
                return null;
            }
            long id = pointer.isNumber() ? pointer.asLong()
                    : Long.parseLong(pointer.asText().trim());
            return id > 0 ? id : null;
        } catch (Exception malformed) {
            return null;
        }
    }

    /** Hot-readable chip cap (K8); unreadable value falls back to the seeded default. */
    private int maxRefsPerQuestion() {
        try {
            return Math.max(1, Integer.parseInt(configService
                    .getValue(ChatObjectRefs.KEY_MAX_REFS,
                            String.valueOf(ChatObjectRefs.DEFAULT_MAX_REFS)).trim()));
        } catch (NumberFormatException brokenValue) {
            return ChatObjectRefs.DEFAULT_MAX_REFS;
        }
    }

    /** Hot-readable image cap (K8, P4 ; same fallback posture as the chip cap. */
    private int maxImagesPerQuestion() {
        try {
            return Math.max(1, Integer.parseInt(configService
                    .getValue(ChatImageAttachments.KEY_MAX_IMAGES,
                            String.valueOf(ChatImageAttachments.DEFAULT_MAX_IMAGES)).trim()));
        } catch (NumberFormatException brokenValue) {
            return ChatImageAttachments.DEFAULT_MAX_IMAGES;
        }
    }
}
