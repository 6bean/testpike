package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.core.aichat.api.dto.ChatExportView;
import com.sixbean.testpike.core.aichat.api.dto.ChatSedimentView;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.service.ChatSedimentService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionExportService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.domain.service.DocumentService;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Lands a dialog session as a knowledge document .
 * <p>The content is the export single source re-rendered in memory
 * ({@link ChatSessionExportService#renderMarkdown}: masking applied, system
 * plumbing dropped, unsealed turns excluded), then it enters the document
 * world through the shared {@link DocumentService#upload} pipeline — original
 * bytes to S3, display name, chunk parsing, dedup and audit all prescribed
 * exactly once. The default TEST_SPEC category applies: REFERENCE belongs to a
 * later batch's value domain and is deliberately not written here. A landed
 * document is finally queued on the same ai.kb.ingest routing key as every
 * other ingest trigger, so ai-service vectorizes it like any document.
 * <p>No {@code @Transactional} by design: {@code upload} itself runs without
 * one (its afterCommit publish fires immediately — same precedent), and the
 * document row is already committed before the ingest event goes out, so a
 * consumer never races an invisible row.
 */
@Service
public class ChatSedimentServiceImpl implements ChatSedimentService {

    private final ChatSessionService sessionService;
    private final ChatSessionExportService exportService;
    private final DocumentService documentService;
    private final AiGenPublisher aiGenPublisher;

    public ChatSedimentServiceImpl(ChatSessionService sessionService,
                                   ChatSessionExportService exportService,
                                   DocumentService documentService,
                                   AiGenPublisher aiGenPublisher) {
        this.sessionService = sessionService;
        this.exportService = exportService;
        this.documentService = documentService;
        this.aiGenPublisher = aiGenPublisher;
    }

    @Override
    public ChatSedimentView landAsKnowledge(long userId, long sessionId) {
        // One ownership gate, two callers: requireOwned answers 403 before the
        // export render re-checks through the same guard.
        ChatSessionInfo session = sessionService.requireOwned(sessionId, userId);
        ChatExportView export = exportService.renderMarkdown(userId, sessionId);

        // An empty dialog must not become a header-only shell document.
        if (export.turnCount() == 0) {
            throw new ServiceException("这段对话还没有问答内容，请先在下方输入框发出至少一问并收到回答，"
                    + "再点击「把本对话存为知识文档，供检索与生成参考」重试",
                    BizErrorCode.VALIDATION_ERROR);
        }

        DocumentInfo doc;
        try {
            doc = documentService.upload(userId, session.projectId(), export.filename(),
                    export.markdown().getBytes(StandardCharsets.UTF_8), null);
        } catch (ServiceException e) {
            // Same-minute re-save hits the sha256 dedup: the raw upload refusal
            // speaks in file terms, which would confuse a dialog user, so the
            // 409 is re-phrased with the dialog context before it rides out.
            if (Integer.valueOf(BizErrorCode.RESOURCE_CONFLICT).equals(e.getCode())) {
                throw new ServiceException("这段对话刚才已经存过知识了（内容相同：「"
                        + displayOf(export.filename()) + "」），请继续聊出新内容后"
                        + "再点击「把本对话存为知识文档，供检索与生成参考」重新保存",
                        BizErrorCode.RESOURCE_CONFLICT);
            }
            throw e;
        }

        // Parsing failed inside the sync pipeline (status=2): the document row
        // exists but is unsearchable — tell the user why instead of claiming success.
        if (doc.status() != 1) {
            throw new ServiceException("对话内容未能解析成知识片段："
                    + (doc.errorMsg() == null ? "未记录具体原因" : doc.errorMsg())
                    + "；请按该原因调整对话内容后，再点击「把本对话存为知识文档，供检索与生成参考」"
                    + "重新保存（本次失败文档「" + doc.name() + "」可在「需求提取」页查看处置）",
                    BizErrorCode.VALIDATION_ERROR);
        }

        // Trigger source of its own: TEST_SPEC rows normally wait for the F2
        // extraction path, dialog sediment queues ingest right away (null
        // category = consumer keeps the TEST_SPEC source fallback).
        aiGenPublisher.publishKbIngest(new AiKbIngestMessage(
                doc.id(), session.projectId(), userId, System.currentTimeMillis(), null));

        return new ChatSedimentView(sessionId, doc.id(), doc.name(), doc.chunkCount());
    }

    /** Display name the document list shows (upload strips the extension). */
    private static String displayOf(String filename) {
        return filename.toLowerCase().endsWith(".md")
                ? filename.substring(0, filename.length() - 3) : filename;
    }
}
