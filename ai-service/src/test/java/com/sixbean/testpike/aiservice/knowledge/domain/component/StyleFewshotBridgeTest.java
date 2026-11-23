package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchRequest;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeSearchService;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class StyleFewshotBridgeTest {

    private static final long PROJECT_ID = 3L;
    private static final String SWITCH_KEY = "ai.kb.style-fewshot.enabled";

    private final CoreGateway coreGateway = mock(CoreGateway.class);

    private static KnowledgeSearchItem item(long caseId, String sourceType, String title,
                                            String content) {
        return new KnowledgeSearchItem("chunk-" + caseId, caseId, caseId, sourceType,
                title, content, 0.8, 0.9);
    }

    private static void switchOn(CoreGateway gateway) {
        when(gateway.getSysConfig(SWITCH_KEY)).thenReturn("true");
    }

    private StyleFewshotBridge bridge(KnowledgeSearchService search) {
        return new StyleFewshotBridge(search, coreGateway, new TokenCounter(), null);
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
    }

    @Test
    void 开关关闭返回空段且零检索调用() {
        AtomicInteger calls = new AtomicInteger();
        when(coreGateway.getSysConfig(SWITCH_KEY)).thenReturn("false");
        StyleFewshotBridge bridge = bridge(request -> {
            calls.incrementAndGet();
            return List.of(item(1L, "TEST_CASE", "t", "正文"));
        });

        String segment = bridge.fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        assertEquals("", segment, "开关关闭必须零注入");
        assertEquals(0, calls.get(), "开关关闭不得触发检索（禁耗预算）");
    }

    @Test
    void 开关开启命中三条注入段含全部正文与按需措辞() {
        switchOn(coreGateway);
        StyleFewshotBridge bridge = bridge(request -> List.of(
                item(11L, "TEST_CASE", "登录用例", "步骤A正文"),
                item(12L, "TEST_CASE", "退款用例", "步骤B正文"),
                item(13L, "TEST_CASE", "导出用例", "步骤C正文")));

        String segment = bridge.fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        assertTrue(segment.contains("步骤A正文") && segment.contains("步骤B正文")
                && segment.contains("步骤C正文"), "三条命中正文须全量注入：\n" + segment);
        assertTrue(segment.contains("参考按需") || segment.contains("按需参考"),
                "注入段标题须带「参考按需」措辞（非强制遵循语义）：\n" + segment);
    }

    @Test
    void 非TEST_CASE来源被滤除非用例不进气泡() {
        switchOn(coreGateway);
        StyleFewshotBridge bridge = bridge(request -> List.of(
                item(21L, "TEST_CASE", "用例", "用例正文KEEP"),
                item(22L, "TEST_SPEC", "规范", "规范正文DROP"),
                item(23L, "STYLE", "风格", "风格正文DROP")));

        String segment = bridge.fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        assertTrue(segment.contains("用例正文KEEP"));
        assertTrue(!segment.contains("规范正文DROP"), "TEST_SPEC 不得进 A4 few-shot 段（14 §2 用例主数据源）");
        assertTrue(!segment.contains("风格正文DROP"), "STYLE 不得进 A4 few-shot 段");
    }

    @Test
    void 命中超五条截断为前五条() {
        switchOn(coreGateway);
        StyleFewshotBridge bridge = bridge(request -> List.of(
                item(31L, "TEST_CASE", "t1", "正文31"),
                item(32L, "TEST_CASE", "t2", "正文32"),
                item(33L, "TEST_CASE", "t3", "正文33"),
                item(34L, "TEST_CASE", "t4", "正文34"),
                item(35L, "TEST_CASE", "t5", "正文35"),
                item(36L, "TEST_CASE", "t6", "正文66DROP"),
                item(37L, "TEST_CASE", "t7", "正文77DROP")));

        String segment = bridge.fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        for (long id = 31; id <= 35; id++) {
            assertTrue(segment.contains("正文" + id), "top5 应保留 正文" + id);
        }
        assertTrue(!segment.contains("正文66DROP") && !segment.contains("正文77DROP"),
                "第 6 条起须截断（14 §5.2 注入 3~8，A4 定案 top5）");
    }

    @Test
    void 检索异常failsoft返回空段不炸生成链() {
        switchOn(coreGateway);
        StyleFewshotBridge bridge = bridge((KnowledgeSearchRequest request) -> {
            throw new IllegalStateException("向量库不可达");
        });

        String segment = bridge.fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        assertEquals("", segment, "检索异常=空段（宁缺勿断铁律，McpKnowledgeBridge 同基径）");
    }

    // ---- piece 3b: revision sample pool consumption (BA ruling 7) ----

    private static RevisionSample corrected(String ai, String human) {
        return new RevisionSample("corrected", ai, human);
    }

    private static RevisionSample deprecated(String ai) {
        return new RevisionSample("deprecated", ai, null);
    }

    private StyleFewshotBridge poolBridge(KnowledgeSearchService search, RevisionSampleReader reader) {
        return new StyleFewshotBridge(search, coreGateway, new TokenCounter(), reader);
    }

    @SuppressWarnings("unchecked")
    private static RevisionSampleReader readerReturning(List<RevisionSample> samples) {
        RevisionSampleReader reader = mock(RevisionSampleReader.class);
        when(reader.recent(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(samples);
        return reader;
    }

    @Test
    void 检索零命中但池有样本时段仍出带被修正标注() {
        switchOn(coreGateway);
        StyleFewshotBridge bridge = poolBridge(request -> List.of(),
                readerReturning(List.of(corrected("AI原稿甲", "人工改后乙"))));

        String segment = bridge.fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        assertTrue(!segment.isEmpty(), "检索命中为空但池有样本时段仍须出（双空才退）");
        assertTrue(segment.contains("被修正"), "段题须带「被修正」标注（BA 裁决 7 负样本标注）：\n" + segment);
        assertTrue(segment.contains("AI原稿甲") && segment.contains("人工改后乙"),
                "修正样本前后两侧均须呈现：\n" + segment);
    }

    @Test
    void 入池新样本后注入段内容变化实测() {
        switchOn(coreGateway);
        KnowledgeSearchService search = request -> List.of(
                item(41L, "TEST_CASE", "登录用例", "风格正文K"));
        String before = poolBridge(search, readerReturning(List.of()))
                .fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());
        String after = poolBridge(search,
                readerReturning(List.of(corrected("池样本入池后新增文本", "人工修订结果"))))
                .fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        assertTrue(!before.contains("池样本入池后新增文本"), "入池前段内不得含池文本");
        assertTrue(after.contains("池样本入池后新增文本"), "入池后段内容须变化（命中变化实测）");
        assertTrue(after.contains("风格正文K"), "样式参考与池样本同段并存，检索命中零丢失");
    }

    @Test
    void 删除形态只显AI侧并带弃用注记() {
        switchOn(coreGateway);
        StyleFewshotBridge bridge = poolBridge(request -> List.of(),
                readerReturning(List.of(deprecated("被弃用的AI产出丙"))));

        String segment = bridge.fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        assertTrue(segment.contains("被弃用的AI产出丙"), "删除形态仍显 AI 侧：\n" + segment);
        assertTrue(segment.contains("弃用"), "删除形态须带弃用注记：\n" + segment);
        assertTrue(!segment.contains("人工修改后"), "删除形态无 human_text，禁出现修改后侧标签：\n" + segment);
    }

    @Test
    void 开关关闭时池零读取() {
        when(coreGateway.getSysConfig(SWITCH_KEY)).thenReturn("false");
        RevisionSampleReader reader = mock(RevisionSampleReader.class);
        StyleFewshotBridge bridge = poolBridge(request -> List.of(), reader);

        String segment = bridge.fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        assertEquals("", segment, "开关关闭必须零注入");
        org.mockito.Mockito.verifyNoInteractions(reader);
    }

    @Test
    void 读取件为null保持旧行为零池段() {
        switchOn(coreGateway);
        StyleFewshotBridge bridge = new StyleFewshotBridge(
                request -> List.of(item(42L, "TEST_CASE", "t", "旧路径正文")),
                coreGateway, new TokenCounter(), null);

        String segment = bridge.fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        assertTrue(segment.contains("旧路径正文"), "null 读取件=legacy 形态照常出样式段");
        assertTrue(!segment.contains("被修正"), "null 读取件不得凭空造池段");
    }

    @Test
    void 池读取故障时样式段照常failsoft() {
        switchOn(coreGateway);
        RevisionSampleReader failing = mock(RevisionSampleReader.class);
        when(failing.recent(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("库不可达"));
        StyleFewshotBridge bridge = poolBridge(
                request -> List.of(item(43L, "TEST_CASE", "t", "样式正文丁")), failing);

        String segment = bridge.fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        assertTrue(segment.contains("样式正文丁"), "池故障不炸样式注入（fail-soft 同基径）：\n" + segment);
    }

    @Test
    void 池样本与样式命中共用同一预算环超预算池段先弃() {
        switchOn(coreGateway);
        KnowledgeSearchService search = request -> List.of(
                item(44L, "TEST_CASE", "t", "样式优先保留戊"));
        List<RevisionSample> pool = List.of(corrected(
                "超预算池文本己己己己己己己己己己己己", "超预算池修订文本庚庚庚庚庚庚庚庚庚庚"));
        TokenCounter counter = new TokenCounter();
        when(coreGateway.getSysConfig(com.sixbean.testpike.aiservice.doc.domain.component
                .ContextBudgetPlanner.BUDGET_CONFIG_KEY)).thenReturn("1000000");
        // Probe the real cost of each family with an unbounded budget first:
        // the BPE token counts then give a deterministic mid-point budget.
        String styleOnly = new StyleFewshotBridge(search, coreGateway, counter,
                readerReturning(List.of())).fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());
        String both = new StyleFewshotBridge(search, coreGateway, counter,
                readerReturning(pool)).fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());
        int styleTokens = counter.countTokens(styleOnly);
        int poolCost = counter.countTokens(both) - styleTokens;
        assertTrue(poolCost > 2, "池样本须有可测增量预算成本（夹具形态前提）");
        when(coreGateway.getSysConfig(com.sixbean.testpike.aiservice.doc.domain.component
                .ContextBudgetPlanner.BUDGET_CONFIG_KEY))
                .thenReturn(String.valueOf(styleTokens + Math.max(1, poolCost / 2)));

        String segment = new StyleFewshotBridge(search, coreGateway, counter,
                readerReturning(pool)).fewshotSegment(null, PROJECT_ID, "登录需求", endpoint());

        assertTrue(segment.contains("样式优先保留戊"), "样式命中排前，预算内先注入：\n" + segment);
        assertTrue(!segment.contains("超预算池文本己"), "同环计数下池样本超预算整项丢弃：\n" + segment);
    }
}
