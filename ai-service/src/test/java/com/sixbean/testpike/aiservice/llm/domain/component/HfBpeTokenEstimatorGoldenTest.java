package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class HfBpeTokenEstimatorGoldenTest {

    private static final String GOLDEN_RESOURCE = "/tokenizer-golden/e11-golden-ids.json";
    private static final String TOKENIZER_RESOURCE = "/tokenizer-golden/qwen3-tokenizer.min.json";

    private static JsonNode golden;

    @BeforeAll
    static void loadGolden() throws Exception {
        ObjectMapper om = new ObjectMapper();
        try (InputStream in = HfBpeTokenEstimatorGoldenTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
            assertNotNull(in, "金标准夹具缺失：" + GOLDEN_RESOURCE + "（禁凭记忆改断言，重新生成）");
            golden = om.readTree(in);
        }
    }

    private static List<Integer> idsOf(String model, String sample) {
        List<Integer> out = new ArrayList<>();
        golden.get(model).get(sample).get("ids").forEach(n -> out.add(n.asInt()));
        return out;
    }

    @Test
    @DisplayName("G1 qwen3 词表加载：byte 面 vocab/merges 与官方一致（151,643+26 specials/151,387）")
    void loadQwenVocabMetadata() throws Exception {
        HfBpeTokenEstimator est = HfBpeTestSupport.qwenFull();
        assertEquals("hf-bpe-qwen3", est.engineId());
        assertEquals(151_643, est.vocabSize());
        assertEquals(151_387, est.mergeCount());
    }

    @Test
    @DisplayName("G2 产线文档逐位差分（中文+markdown+箭头，金标准 634 token）")
    void prodDocBitExact() {
        HfBpeTokenEstimator est = HfBpeTestSupport.qwenFull();
        String text = HfBpeTestSupport.sampleText("prod_doc");
        List<Integer> expect = idsOf("qwen3", "prod_doc");
        assertEquals(expect, est.encode(text), "prod_doc token 序列与金标准不一致");
        assertEquals(634, est.countTokens(text));
    }

    @Test
    @DisplayName("G3 纯中文重复串逐位（zh720=720 token，cl100k 同值但序列必须一致）")
    void zhRepeatBitExact() {
        HfBpeTokenEstimator est = HfBpeTestSupport.qwenFull();
        List<Integer> expect = idsOf("qwen3", "zh720");
        assertEquals(expect, est.encode(HfBpeTestSupport.sampleText("zh720")));
    }

    @Test
    @DisplayName("G4 摘要风格中文逐位（金标准 300，cl100k=444 高估 48%——E11 立项目的）")
    void summaryBitExact() {
        HfBpeTokenEstimator est = HfBpeTestSupport.qwenFull();
        assertEquals(idsOf("qwen3", "summary"), est.encode(HfBpeTestSupport.sampleText("summary")));
    }

    @Test
    @DisplayName("G5 JSON 混合逐位（两引擎本样本接近，序列不得跑偏）")
    void jsonMixBitExact() {
        HfBpeTokenEstimator est = HfBpeTestSupport.qwenFull();
        assertEquals(idsOf("qwen3", "json_mix"), est.encode(HfBpeTestSupport.sampleText("json_mix")));
    }

    @Test
    @DisplayName("G6 emoji+换行+制表符逐位（utf-8 四字节/代理对边界）")
    void emojiBoundaryBitExact() {
        HfBpeTokenEstimator est = HfBpeTestSupport.qwenFull();
        assertEquals(idsOf("qwen3", "emoji"), est.encode(HfBpeTestSupport.sampleText("emoji")));
    }

    @Test
    @DisplayName("G7 代码片段逐位（含中文标识符+注释，中英符号混排）")
    void codeBitExact() {
        HfBpeTokenEstimator est = HfBpeTestSupport.qwenFull();
        assertEquals(idsOf("qwen3", "code"), est.encode(HfBpeTestSupport.sampleText("code")));
    }

    @Test
    @DisplayName("G8 markdown 表格重复逐位（竖线/换行/空格组合）")
    void markdownTableBitExact() {
        HfBpeTokenEstimator est = HfBpeTestSupport.qwenFull();
        assertEquals(idsOf("qwen3", "markdown"), est.encode(HfBpeTestSupport.sampleText("markdown")));
    }

    @Test
    @DisplayName("G9 极短文本逐位（'好的'=1 token）")
    void shortTextBitExact() {
        HfBpeTokenEstimator est = HfBpeTestSupport.qwenFull();
        assertEquals(idsOf("qwen3", "short"), est.encode(HfBpeTestSupport.sampleText("short")));
        assertEquals(1, est.countTokens(HfBpeTestSupport.sampleText("short")));
    }

    @Test
    @DisplayName("G10 null/空安全 + 前导空格 Ġ 映射（GPT-2 byte 表，空格→'Ġ'）")
    void nullSafeAndSpaceMapping() {
        HfBpeTokenEstimator est = HfBpeTestSupport.qwenFull();
        assertEquals(0, est.countTokens(null));
        assertEquals(0, est.countTokens(""));
        assertTrue(est.encode("你好 世界").size() <= 4, "空格词边界合并失效（应 ≤4 token）");
    }

    @Test
    @DisplayName("G11 引擎选择：tokenizer_family=qwen 命中 hf-bpe-qwen3；=cl100k 命中默认")
    void registryRoutesByFamily() {
        TokenEstimator qwen = TokenEstimatorRegistry.resolve("qwen", "any-model");
        TokenEstimator fallback = TokenEstimatorRegistry.resolve("cl100k", "any-model");
        assertEquals("hf-bpe-qwen3", qwen.engineId());
        assertEquals("cl100k", fallback.engineId());
    }

    @Test
    @DisplayName("G12 auto 推断：provider 名含 qwen→hf-bpe-qwen3；deepseek→hf-bpe-deepseek-v3；未知→calibrated")
    void autoInferenceByProviderName() {
        assertEquals("hf-bpe-qwen3",
                TokenEstimatorRegistry.resolve("auto", "qwen3.8-flash").engineId());
        String ds = TokenEstimatorRegistry.resolve("auto", "deepseek-v4-flash").engineId();
        assertTrue("hf-bpe-deepseek-v3".equals(ds) || ds.startsWith("calibrated"),
                "deepseek auto 结果非法: " + ds);
        assertTrue(TokenEstimatorRegistry.resolve("auto", "glm-4").engineId().startsWith("calibrated"));
    }

    @Test
    @DisplayName("G13 未知 family 显式配置=快速失败（禁静默走错引擎，配置错误要响）")
    void unknownFamilyFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> TokenEstimatorRegistry.resolve("gpt9-family", "m"));
    }

    @Test
    @DisplayName("G14 词表文件缺失=启动快速失败（加载器读 tokenizer.json 资源不存在时抛，禁回退cl100k假绿）")
    void missingVocabFileFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> HfBpeTokenEstimator.fromResource("/tokenizer-golden/no-such.json", "hf-bpe-x"));
    }
}
