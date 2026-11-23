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


class DdlDictBridgeTest {

    private static final long PROJECT_ID = 3L;
    private static final String GATE_KEY = "ai.sql-gen.enabled";
    private static final String TRUTH_KEY = "ai.gen.truth-source.ddl-dict";

    /**
     * Truth gate pinned OFF so each legacy test below asserts only the
     * ai.sql-gen.enabled behaviour (pre-T542d semantics stay individually
     * testable; the truth gate's own suite is DdlTruthSourceInjectionTest).
     */
    private void truthGateOff() {
        when(coreGateway.getSysConfig(TRUTH_KEY)).thenReturn("false");
    }

    private final CoreGateway coreGateway = mock(CoreGateway.class);

    private static KnowledgeSearchItem item(long docId, String sourceType, String title,
                                            String content) {
        return new KnowledgeSearchItem("chunk-" + docId, docId, 0L, sourceType,
                title, content, 0.8, 0.9);
    }

    private DdlDictBridge bridge(KnowledgeSearchService search) {
        return new DdlDictBridge(search, coreGateway, new TokenCounter());
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
    }

    @Test
    void 闸关返回空段且零检索调用() {
        AtomicInteger calls = new AtomicInteger();
        truthGateOff();
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("0");
        DdlDictBridge bridge = bridge(request -> {
            calls.incrementAndGet();
            return List.of(item(1L, "DDL_DICT", "t_order", "CREATE TABLE t_order (id bigint)"));
        });

        String segment = bridge.ddlSegment(null, PROJECT_ID, "下单后落库校验", endpoint());

        assertEquals("", segment, "闸关必须零注入（TC-B11-05 人工模板通道态）");
        assertEquals(0, calls.get(), "闸关禁发起检索");
    }

    @Test
    void 两闸面值均不可读时默认关闭零注入() {
        // Legacy gate absent = off (V71 default-safe int face) AND the truth gate
        // explicitly off (the absent-truth factory default itself is tested in
        // DdlTruthSourceInjectionTest — here the pair must stay closed).
        truthGateOff();
        DdlDictBridge bridge = bridge(request ->
                List.of(item(1L, "DDL_DICT", "t_order", "CREATE TABLE t_order (id bigint)")));

        String segment = bridge.ddlSegment(null, PROJECT_ID, "下单后落库校验", endpoint());

        assertEquals("", segment, "双闸读不到值按关处理（默认安全），零注入");
    }

    @Test
    void 真值闸缺行出厂开旧闸关仍注入表结构段() {
        // an absent truth-source row keeps the gate ON, so the
        // dictionary is retrieved even while the legacy sql-gen gate is off.
        DdlDictBridge bridge = bridge(request ->
                List.of(item(1L, "DDL_DICT", "t_order", "CREATE TABLE t_order (id bigint)")));

        String segment = bridge.ddlSegment(null, PROJECT_ID, "下单后落库校验", endpoint());

        assertTrue(segment.contains("表结构"), "出厂开须注入: " + segment);
        assertTrue(segment.contains("t_order"), "须含命中表名: " + segment);
    }

    @Test
    void 闸开注入表结构参考段含命中内容() {
        truthGateOff();
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("1");
        DdlDictBridge bridge = bridge(request ->
                List.of(item(7L, "DDL_DICT", "t_order",
                        "CREATE TABLE t_order (id bigint PRIMARY KEY, status varchar(16))")));

        String segment = bridge.ddlSegment(null, PROJECT_ID, "下单后 t_order 落库 PENDING", endpoint());

        assertTrue(segment.contains("表结构"), "段标题须为人话表结构参考: " + segment);
        assertTrue(segment.contains("t_order"), "须含命中表名标题: " + segment);
        assertTrue(segment.contains("CREATE TABLE"), "须含 DDL 正文: " + segment);
    }

    @Test
    void 非DDL来源命中被滤除() {
        truthGateOff();
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("1");
        DdlDictBridge bridge = bridge(request -> List.of(
                item(1L, "TEST_CASE", "优秀用例", "步骤若干"),
                item(2L, "DDL_DICT", "t_pay", "CREATE TABLE t_pay (id bigint)")));

        String segment = bridge.ddlSegment(null, PROJECT_ID, "支付落库", endpoint());

        assertTrue(segment.contains("t_pay"), "DDL_DICT 命中须注入: " + segment);
        assertTrue(!segment.contains("优秀用例"), "非 DDL_DICT 来源禁注入: " + segment);
    }

    @Test
    void 命中超上限按分截断() {
        truthGateOff();
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("1");
        List<KnowledgeSearchItem> many = new java.util.ArrayList<>();
        for (int i = 0; i < DdlDictBridge.MAX_ITEMS + 3; i++) {
            many.add(item(i, "DDL_DICT", "t_" + i, "CREATE TABLE t_" + i + " (id bigint)"));
        }
        DdlDictBridge bridge = bridge(request -> many);

        String segment = bridge.ddlSegment(null, PROJECT_ID, "多表落库", endpoint());

        assertTrue(!segment.contains("t_" + DdlDictBridge.MAX_ITEMS),
                "超出 MAX_ITEMS 的队尾项须丢弃: " + segment);
    }

    @Test
    void 检索异常failSoft空段不炸() {
        truthGateOff();
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("1");
        DdlDictBridge bridge = bridge(request -> {
            throw new IllegalStateException("向量库不可达");
        });

        assertEquals("", bridge.ddlSegment(null, PROJECT_ID, "落库校验", endpoint()),
                "检索故障=空段，绝不阻断生成主链");
    }

    @Test
    void 检索请求携带BUSINESS场景与拉宽topK() {
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("1");
        KnowledgeSearchRequest[] captured = new KnowledgeSearchRequest[1];
        DdlDictBridge bridge = bridge(request -> {
            captured[0] = request;
            return List.of(item(1L, "DDL_DICT", "t_order", "CREATE TABLE t_order (id bigint)"));
        });

        bridge.ddlSegment(null, PROJECT_ID, "下单后落库 PENDING", endpoint());

        assertEquals(KnowledgeSearchScene.BUSINESS.name(), captured[0].scene(),
                "14 §5.3 冻结五场景无 DDL 场景=复用 BUSINESS（tick81 定案）");
        assertEquals(PROJECT_ID, captured[0].projectId(), "项目隔离键必须透传");
        assertEquals("下单后落库 PENDING", captured[0].query(), "查询文本=用例聚合文本");
        assertTrue(captured[0].topK() != null && captured[0].topK() > DdlDictBridge.MAX_ITEMS,
                "候选须拉宽后本地截断: " + captured[0].topK());
    }

    @Test
    void 查询文本空白时零检索调用() {
        when(coreGateway.getSysConfig(GATE_KEY)).thenReturn("1");
        AtomicInteger calls = new AtomicInteger();
        DdlDictBridge bridge = bridge(request -> {
            calls.incrementAndGet();
            return List.of();
        });

        assertEquals("", bridge.ddlSegment(null, PROJECT_ID, "  ", endpoint()));
        assertEquals(0, calls.get(), "空白查询禁打检索");
    }
}
