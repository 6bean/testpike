package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * <ol>
 * </ol>
 *
 */
public class PromptSizeGuard {

    private static final Logger log = LoggerFactory.getLogger(PromptSizeGuard.class);

    
    public enum Decision {
        
        PASS,
        
        COMPRESS
    }

    
    public record Verdict(Decision decision, int estTokens, int limitTokens) {
        public boolean overLimit() {
            return decision == Decision.COMPRESS;
        }
    }

    private final TokenEstimator tokenCounter;

    public PromptSizeGuard(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    
    public PromptSizeGuard(TokenEstimator estimator) {
        this.tokenCounter = estimator;
    }

    
    public int estimate(List<ChatMessage> messages) {
        return CalibrationSample.estimateOf(tokenCounter, messages);
    }

    
    public Verdict check(List<ChatMessage> messages, int limitTokens, boolean compressible) {
        return check(messages, limitTokens, compressible, null);
    }

    
    public Verdict check(List<ChatMessage> messages, int limitTokens, boolean compressible,
                         Integer modelLimitTokens) {
        int effective = effectiveLimit(limitTokens, modelLimitTokens);
        int est = estimate(messages);
        if (est <= effective) {
            return new Verdict(Decision.PASS, est, effective);
        }
        if (compressible) {
            log.info("PromptSizeGuard 超限判压缩 est={} limit={}（文档链重走 F5 压缩）", est, effective);
            return new Verdict(Decision.COMPRESS, est, effective);
        }
        throw new ServiceException("提示词超出模型上限（实际 " + est + " tokens > 上限 "
                + effective + "），请缩减输入内容或更换更大上下文模型",
                com.sixbean.testpike.common.exception.BizErrorCode.AI_PROMPT_TOO_LARGE);
    }

    
    public Verdict check(List<ChatMessage> messages, int limitTokens, boolean compressible,
                         Integer modelLimitTokens, TokenEstimator estimator) {
        if (estimator == null) {
            return check(messages, limitTokens, compressible, modelLimitTokens);
        }
        int effective = effectiveLimit(limitTokens, modelLimitTokens);
        int est = estimator.countTexts(messages.stream().map(ChatMessage::textContent).toList());
        if (est <= effective) {
            return new Verdict(Decision.PASS, est, effective);
        }
        if (compressible) {
            log.info("PromptSizeGuard 超限判压缩 est={} limit={} engine={}（文档链重走 F5 压缩）",
                    est, effective, estimator.engineId());
            return new Verdict(Decision.COMPRESS, est, effective);
        }
        throw new ServiceException("提示词超出模型上限（实际 " + est + " tokens > 上限 "
                + effective + "），请缩减输入内容或更换更大上下文模型",
                BizErrorCode.AI_PROMPT_TOO_LARGE);
    }

    
    public static int effectiveLimit(int docBudget, Integer modelLimitTokens) {
        if (modelLimitTokens == null || modelLimitTokens <= 0) {
            return docBudget;
        }
        return Math.min(docBudget, modelLimitTokens);
    }
}
