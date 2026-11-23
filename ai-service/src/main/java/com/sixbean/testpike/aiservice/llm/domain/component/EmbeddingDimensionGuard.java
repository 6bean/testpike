package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class EmbeddingDimensionGuard {

    
    public void check(LlmEmbeddingResult result, Integer expectedDimension) {
        if (expectedDimension == null || result == null || result.embeddings().isEmpty()) {
            return;
        }
        List<float[]> vectors = result.embeddings();
        for (int i = 0; i < vectors.size(); i++) {
            int actual = vectors.get(i) == null ? 0 : vectors.get(i).length;
            if (actual != expectedDimension) {
                throw new ServiceException(
                        "embedding 模型返回向量维度 " + actual + " 与配置维度 " + expectedDimension
                                + " 不符（模型=" + result.model() + " 第" + (i + 1) + "条）。"
                                + "向量维度与向量表绑定：更换 embedding 模型必须重建向量表后重新入库，"
                                + "请先核对大模型清单的向量维度配置",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
    }
}
