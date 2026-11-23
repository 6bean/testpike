package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingProperties;
import com.sixbean.testpike.aiservice.knowledge.domain.component.EmbeddingModelAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;


class EmbeddingModelAdapterTest {

    private final List<EmbeddingCompletionRequest> capturedRequests = new ArrayList<>();
    private final List<Long> capturedProjectIds = new ArrayList<>();

    private EmbeddingFallbackChain stubChain(List<float[]> vectors) {
        return new EmbeddingFallbackChain(null, null, null, null, null) {
            @Override
            public LlmEmbeddingResult embed(EmbeddingCompletionRequest request, long projectId) {
                capturedRequests.add(request);
                capturedProjectIds.add(projectId);
                return new LlmEmbeddingResult(vectors, "fake", request.model(), 0, 0L);
            }
        };
    }

    private EmbeddingProperties props() {
        EmbeddingProperties p = new EmbeddingProperties();
        p.setModel("text-embedding-v3");
        p.setDimension(1536);
        return p;
    }

    @Test
    void 单条embed_委托降级链且model与全局域projectId透传() {
        float[] v = {0.1f, 0.2f, 0.3f};
        EmbeddingModelAdapter adapter = new EmbeddingModelAdapter(stubChain(List.of(v)), props());

        float[] out = adapter.embed("alpha");

        assertArrayEquals(v, out, "单条=链结果首向量原样透传");
        assertEquals(1, capturedRequests.size());
        assertEquals(List.of("alpha"), capturedRequests.get(0).input());
        assertEquals("text-embedding-v3", capturedRequests.get(0).model(),
                "model 锚=llm.embedding.model（链首位候选端点保留调用方 model 语义）");
        assertEquals(0L, capturedProjectIds.get(0), "向量基座=全局域（projectId 0，14 §2 通用规范域）");
    }

    @Test
    void 批量call_返回序与index均锚输入序() {
        List<float[]> vectors = List.of(new float[]{1f}, new float[]{2f}, new float[]{3f});
        EmbeddingModelAdapter adapter = new EmbeddingModelAdapter(stubChain(vectors), props());

        EmbeddingResponse response = adapter.call(
                new EmbeddingRequest(List.of("a", "b", "c"), null));

        assertEquals(3, response.getResults().size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, response.getResults().get(i).getIndex(),
                    "index 序=输入序（PgVectorStore 批量对齐契约）");
            assertArrayEquals(vectors.get(i), response.getResults().get(i).getOutput(),
                    "保序：第 i 向量=链返回第 i 向量，适配层禁重排");
        }
        assertEquals(List.of("a", "b", "c"), capturedRequests.get(0).input(),
                "出站 input 序=instructions 序（乱序还原在 OpenAiCompatClient 层已测）");
    }

    @Test
    void dimensions_经属性透传非模型实测() {
        EmbeddingProperties p = props();
        p.setDimension(777);
        EmbeddingModelAdapter adapter = new EmbeddingModelAdapter(stubChain(List.of()), p);

        assertEquals(777, adapter.dimensions(),
                "维度锚单源=llm.embedding.dimension（KnowledgeVectorStoreConfig fail-fast 输入）");
    }
}
