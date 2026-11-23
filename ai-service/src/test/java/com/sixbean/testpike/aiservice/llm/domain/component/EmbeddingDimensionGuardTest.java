package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class EmbeddingDimensionGuardTest {

    private static LlmEmbeddingResult result(int... dims) {
        List<float[]> vectors = new java.util.ArrayList<>();
        for (int d : dims) {
            vectors.add(new float[d]);
        }
        return new LlmEmbeddingResult(vectors, "bailian", "text-embedding-v3", 0, 0);
    }

    @Test
    void 维度一致_放行() {
        assertDoesNotThrow(() -> new EmbeddingDimensionGuard().check(result(4, 4), 4));
    }

    @Test
    void 维度不符_人话拒绝含期望与实际() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> new EmbeddingDimensionGuard().check(result(4, 3), 4));
        assertTrue(e.getMessage().contains("4"), "必须点名期望维度：" + e.getMessage());
        assertTrue(e.getMessage().contains("3"), "必须点名实际维度：" + e.getMessage());
        assertTrue(e.getMessage().contains("重建"), "必须提示换模型需重建向量表：" + e.getMessage());
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
    }

    @Test
    void 期望维度为null_跳过校验_旧清单兼容() {
        assertDoesNotThrow(() -> new EmbeddingDimensionGuard().check(result(7), null));
    }

    @Test
    void 空结果_放行_由调用方判数量() {
        LlmEmbeddingResult empty = new LlmEmbeddingResult(List.of(), "bailian", "m", 0, 0);
        assertDoesNotThrow(() -> new EmbeddingDimensionGuard().check(empty, 4));
    }
}
