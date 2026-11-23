package com.sixbean.testpike.core.spec.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.config.FactoryOnSwitch;
import com.sixbean.testpike.common.mq.AiSpecExtractMessage;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.domain.service.DocumentService;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.spec.api.dto.SpecExtractEstimate;
import com.sixbean.testpike.core.spec.domain.service.SpecExtractDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * fix: the over-count rejection is conditional on the grouped-intake
 * door (ai.spec.grouped-intake.enabled, factory on, same tri-state source as
 * the consumer) — while the door is on, over-count sets are dispatched and
 * the consumer seals them into token-bounded groups instead of rejecting.
 */
@Service
public class SpecExtractDispatchServiceImpl implements SpecExtractDispatchService {

    private static final Logger log = LoggerFactory.getLogger(SpecExtractDispatchServiceImpl.class);

    
    private static final int STATUS_PARSE_OK = 1;
    
    private static final int STATUS_EXTRACTED = 4;

    
    @Value("${ai.batch.fixed-token-overhead:800}")
    private double fixedTokenOverhead;

    
    @Value("${ai.batch.estimate-token-per-char:2.0}")
    private double tokenPerChar;

    private final DocumentService documentService;
    private final AiGenPublisher aiGenPublisher;
    private final SystemConfigReader configReader;

    public SpecExtractDispatchServiceImpl(DocumentService documentService,
                                          AiGenPublisher aiGenPublisher,
                                          SystemConfigReader configReader) {
        this.documentService = documentService;
        this.aiGenPublisher = aiGenPublisher;
        this.configReader = configReader;
    }

    @Override
    public SpecExtractEstimate dispatch(long operatorId, long documentId, String modelHint) {
        DocumentInfo doc = documentService.get(documentId);
        int status = doc.status();
        if (status != STATUS_PARSE_OK && status != STATUS_EXTRACTED) {
            throw new ServiceException(
                    status == 0 ? "文档正在解析中，请在「文档列表」查看该行解析状态，解析完成后再发起接口清单抽取"
                            : status == 3 ? "该文档的需求点提取正在进行中，请在「文档列表」查看该行状态，提取完成后再发起接口清单抽取"
                            : "文档解析未成功，无法抽取接口清单；请修复源文件后在「文档列表」重新上传",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        List<Map<String, Object>> chunks = documentService.chunks(documentId);
        int maxChunks = currentMaxChunks();
        if (chunks.size() > maxChunks && !groupedIntakeOpen()) {
            throw new ServiceException("文档分块数 " + chunks.size() + " 超过单次抽取上限 "
                    + maxChunks + "——可在「系统设置」把 apitest.spec-extract.max-chunks 上调后重新发起，"
                    + "或先把文档拆分为多篇再上传抽取", BizErrorCode.VALIDATION_ERROR);
        }
        long estimatedTokens = estimateTokens(chunks);
        try {
            aiGenPublisher.publishSpecExtract(new AiSpecExtractMessage(
                    documentId, doc.projectId(), operatorId,
                    modelHint == null || modelHint.isBlank() ? null : modelHint.trim(),
                    UUID.randomUUID().toString()));
        } catch (RuntimeException e) {
            log.warn("通道 B 抽取任务派发失败 documentId={} err={}", documentId, e.toString());
            throw new ServiceException("接口清单抽取任务提交失败（消息服务暂不可达）——"
                            + "请稍后在「文档列表」重新发起抽取；持续失败请联系管理员检查消息服务（RabbitMQ）状态",
                    BizErrorCode.STATE_MACHINE_REJECT, e);
        }
        return new SpecExtractEstimate(chunks.size(), estimatedTokens, maxChunks);
    }

    
    private int currentMaxChunks() {
        int configured = configReader.getInt(AiSpecExtractMessage.KEY_MAX_CHUNKS,
                AiSpecExtractMessage.DEFAULT_MAX_CHUNKS);
        return configured <= 0 ? AiSpecExtractMessage.DEFAULT_MAX_CHUNKS : configured;
    }

    /**
     * Grouped-intake door state (K8, factory on, fresh read per dispatch):
     * resolved through the shared tri-state semantics the ai-service
     * consumer door uses (R40 — the missing-row=on rule lives in exactly
     * one place). While the door is on, an over-count chunk set is not
     * rejected here: the consumer seals the chunks into token-bounded
     * groups and owns the final say, so the pre-check must not shadow it.
     */
    private boolean groupedIntakeOpen() {
        return FactoryOnSwitch.on(
                configReader.getRaw(AiSpecExtractMessage.KEY_GROUPED_INTAKE_ENABLED));
    }

    
    private long estimateTokens(List<Map<String, Object>> chunks) {
        long tokens = 0;
        for (Map<String, Object> chunk : chunks) {
            String content = String.valueOf(chunk.get("content"));
            tokens += Math.round(fixedTokenOverhead + content.length() * tokenPerChar);
        }
        return tokens;
    }
}
