package com.sixbean.testpike.core.script.domain.component;

import com.sixbean.testpike.core.script.api.dto.Flowchart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class FlowchartMermaidAssemblerTest {

    private static String doc(String stepsJson) {
        return "{\"version\":\"v2\",\"caseId\":\"TC-C1\",\"title\":\"下单校验\","
                + "\"endType\":\"web\",\"steps\":[" + stepsJson + "]}";
    }

    private static final String GOTO = "{\"action\":\"goto\",\"target\":{\"url\":\"/login\"}}";
    private static final String CLICK = "{\"action\":\"click\",\"target\":{\"css\":\"#buy\"}}";
    private static final String EXPECT_OK = "{\"action\":\"expect\",\"target\":{\"text\":\"下单成功\"}}";

    private static String ifStep(String stepsInner, String elseInner) {
        return ifStep("promo.stackable == false", stepsInner, elseInner);
    }

    private static String ifStep(String condition, String stepsInner, String elseInner) {
        String s = "{\"action\":\"if\",\"condition\":\"" + condition + "\",\"steps\":["
                + stepsInner + "]";
        if (elseInner != null) {
            s += ",\"elseSteps\":[" + elseInner + "]";
        }
        return s + "}";
    }

    
    private static String nestedIf(int depth) {
        String inner = EXPECT_OK;
        for (int i = 0; i < depth; i++) {
            inner = "{\"action\":\"if\",\"condition\":\"c" + i + "\",\"steps\":[" + inner + "]}";
        }
        return inner;
    }

    @Test
    void 线性主链_节点按文档序链式连接() {
        Flowchart f = FlowchartMermaidAssembler.assemble(doc(GOTO + "," + CLICK + "," + EXPECT_OK));

        String m = f.mermaid();
        assertTrue(m.contains("flowchart"), "Mermaid flowchart 方言头（渲染库稳定语法）");
        assertTrue(m.contains("导航"), "goto 业务中文标签（TC-C1-01 非动作码）");
        assertTrue(m.contains("点击"), "click 业务中文标签");
        assertTrue(m.contains("断言"), "expect 业务中文标签");
        assertTrue(m.contains("下单成功"), "locator text 摘要进节点标签");
        assertTrue(m.contains("-->"), "节点链式连接箭头");
        assertEquals(3, f.stepCount(), "线性 3 步=3（全层级动作节点总数口径）");
    }

    @Test
    void if条件分支_条件节点与成立分支() {
        Flowchart f = FlowchartMermaidAssembler.assemble(
                doc(GOTO + "," + ifStep(EXPECT_OK, null)));

        String m = f.mermaid();
        assertTrue(m.contains("promo.stackable == false"), "condition 原文进判定节点（评审可见）");
        assertTrue(m.contains("成立"), "if 分支=alt 语义「成立」边标签");
        assertTrue(m.contains("下单成功"), "分支内子步骤节点仍渲染");
        assertEquals(3, f.stepCount(), "goto+if 容器+成立分支子步骤=全层级总数 3");
    }

    @Test
    void elseSteps_渲染不成立分支() {
        Flowchart f = FlowchartMermaidAssembler.assemble(
                doc(ifStep(EXPECT_OK, CLICK)));

        String m = f.mermaid();
        assertTrue(m.contains("不成立"), "elseSteps=「不成立」分支（alt/else 配对语义）");
        assertTrue(m.contains("点击"), "else 分支内动作节点渲染");
        assertEquals(3, f.stepCount(), "if 容器+分支内 2 子步骤=3");
    }

    @Test
    void loop动作_循环节点表达() {
        String loop = "{\"action\":\"loop\",\"count\":3,\"steps\":[" + CLICK + "]}";
        Flowchart f = FlowchartMermaidAssembler.assemble(doc(GOTO + "," + loop));

        String m = f.mermaid();
        assertTrue(m.contains("循环"), "loop 节点=「循环」业务表达（IT-FLOW-01）");
        assertTrue(m.contains("3"), "count 语义进标签");
        assertTrue(m.contains("-->|重复|"), "回边=「重复」（T244① 拆标签，勿再混「或退出」）");
        assertTrue(m.contains("循环结束"), "loop 出口=「循环结束」辅助节点（串行视觉锚）");
        assertTrue(m.contains("-->|完成|"), "出口边=「完成」指向循环结束节点");
        assertEquals(3, f.stepCount(), "goto+loop 容器+子步骤=3（循环结束=辅助节点不计）");
    }

    @Test
    void loop列表变体_items语义进标签() {
        String loop = "{\"action\":\"loop\",\"items\":[\"A\",\"B\"],\"steps\":[" + CLICK + "]}";
        Flowchart f = FlowchartMermaidAssembler.assemble(doc(loop));

        String m = f.mermaid();
        assertTrue(m.contains("循环"), "items 形态同为循环表达");
        assertTrue(m.contains("循环结束") && m.contains("-->|完成|"),
                "items 形态同锚出口节点（T244① 两形态统一）");
        assertEquals(2, f.stepCount(), "loop 容器+子步骤=2（辅助节点不计）");
    }

    
    @Test
    void 平级双loop_后继从循环结束节点串行下链() {
        String loopA = "{\"action\":\"loop\",\"count\":2,\"steps\":[" + CLICK + "]}";
        String loopB = "{\"action\":\"loop\",\"items\":[],\"steps\":[" + GOTO + "]}";
        Flowchart f = FlowchartMermaidAssembler.assemble(doc(loopA + "," + loopB));

        String m = f.mermaid();
        assertEquals(2, m.split("循环结束", -1).length - 1, "双 loop=两个「循环结束」出口节点");
        java.util.regex.Matcher def = java.util.regex.Pattern
                .compile("(s\\d+)\\[\"循环结束\"").matcher(m);
        assertTrue(def.find(), "循环结束节点定义行缺失");
        String firstEnd = def.group(1);
        assertTrue(m.contains(firstEnd + " --> s"),
                "后继兄弟步骤应从「" + firstEnd + "（循环结束）」往下链（串行视觉）: " + m);
        assertEquals(2, m.split("-->\\|完成\\|", -1).length - 1, "两 loop 各一条「完成」出口边");
        assertEquals(4, f.stepCount(), "loopA 容器+子步 + loopB 容器+子步=全层级 4（辅助不计）");
    }

    
    @Test
    void 循环壳空骨架_渲染链路零改出图() {
        String shell = "{\"action\":\"loop\",\"items\":[],\"steps\":[]}";
        Flowchart f = assertDoesNotThrow(() -> FlowchartMermaidAssembler.assemble(doc(GOTO + "," + shell)));

        String m = f.mermaid();
        assertTrue(m.contains("循环"), "loop 壳=「循环」节点（壳可直接进流程图）");
        assertTrue(m.contains("导航"), "壳前后主链照常连接");
        assertEquals(2, f.stepCount(), "goto+loop 壳（无子步骤）=2");
    }

    
    @Test
    void 分支壳双空分支_渲染链路零改出图() {
        String shell = "{\"action\":\"if\",\"condition\":\"\",\"steps\":[],\"elseSteps\":[]}";
        Flowchart f = assertDoesNotThrow(() -> FlowchartMermaidAssembler.assemble(doc(GOTO + "," + shell)));

        String m = f.mermaid();
        assertTrue(m.contains("成立"), "if 壳渲染 alt 分支语义");
        assertEquals(2, f.stepCount(), "goto+if 壳（双空分支）=2");
    }

    @Test
    void 嵌套条件_alt内alt_内外条件均可见() {
        String nested = ifStep(ifStep("c0", EXPECT_OK, null), CLICK);
        Flowchart f = FlowchartMermaidAssembler.assemble(doc(nested));

        String m = f.mermaid();
        assertTrue(m.contains("promo.stackable == false"), "外层条件可见");
        assertTrue(m.contains("c0"), "内层条件可见（嵌套分支不丢失）");
        long altTotal = m.split("成立", -1).length - 1;
        long negTotal = m.split("不成立", -1).length - 1;
        assertTrue(altTotal - negTotal >= 1 && altTotal >= 2,
                "嵌套两层各含成立边标签（altTotal=" + altTotal + ", negTotal=" + negTotal + "）");
        assertEquals(4, f.stepCount(), "外 if+内 if+内子步骤+else 子步骤=全层级 4");
    }

    @Test
    void 五十步长用例_组装性能留裕量() {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            if (i > 0) {
                steps.append(',');
            }
            steps.append("{\"action\":\"click\",\"target\":{\"css\":\"#el-").append(i).append("\"}}");
        }
        String json = doc(steps.toString());

        long start = System.nanoTime();
        Flowchart f = assertDoesNotThrow(() -> FlowchartMermaidAssembler.assemble(json));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(50, f.stepCount(), "50 步全层级总数");
        assertTrue(elapsedMs < 2000, "50 步组装 <2s（实测应远低于阈值），实际=" + elapsedMs + "ms");
    }

    @Test
    void 未知动作_标签回退原始动作码不抛错() {
        String unknown = "{\"action\":\"foo.bar\",\"target\":{\"css\":\"#x\"}}";
        Flowchart f = FlowchartMermaidAssembler.assemble(doc(unknown));

        assertTrue(f.mermaid().contains("foo.bar"), "命名空间/未知动作回退原始动作码");
        assertEquals(1, f.stepCount());
    }

    @Test
    void 嵌套深度超5_防御人话异常点名嵌套() {
        String tooDeep = doc(nestedIf(6));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FlowchartMermaidAssembler.assemble(tooDeep));
        assertTrue(e.getMessage().contains("嵌套"), "人话点名「嵌套」（消息=" + e.getMessage() + "）");
    }

    @Test
    void 空步骤_空图占位人话() {
        Flowchart f = FlowchartMermaidAssembler.assemble(doc(""));

        assertTrue(f.mermaid().contains("暂无操作步骤"), "空步骤=空图占位人话（IT-FLOW-01）");
        assertEquals(0, f.stepCount());
    }

    @Test
    void 无脚本文本_null与空白同为空图占位() {
        for (String input : new String[]{null, "", "   "}) {
            Flowchart f = FlowchartMermaidAssembler.assemble(input);
            assertTrue(f.mermaid().contains("暂无操作步骤"),
                    "input=[" + input + "] 应空图占位");
            assertEquals(0, f.stepCount(), "input=[" + input + "]");
        }
    }


    private static final String HTTP_REQ = "{\"action\":\"http.request\",\"method\":\"POST\","
            + "\"url\":\"http://api.internal/v1/orders\",\"expect\":{\"status\":200}}";
    private static final String NET_ASSERT = "{\"action\":\"net.assert\","
            + "\"match\":{\"urlPattern\":\"*/v1/orders*\"},\"expect\":{\"status\":200}}";
    private static final String CONSOLE_ASSERT = "{\"action\":\"console.assert\",\"mode\":\"fail\"}";
    private static final String SQL_ASSERT = "{\"action\":\"sql.assert\",\"conn\":\"{{ENV:DB_SIT}}\","
            + "\"sql\":\"SELECT count(*) FROM t_order\",\"expect\":{\"type\":\"row_count\"}}";
    private static final String SQL_QUERY = "{\"action\":\"sql.query\",\"conn\":\"{{ENV:DB_SIT}}\","
            + "\"sql\":\"SELECT id FROM t_order\",\"saveAs\":\"oid\"}";
    private static final String SQL_EXEC = "{\"action\":\"sql.exec\",\"conn\":\"{{ENV:DB_SIT}}\","
            + "\"sql\":\"INSERT INTO t_order(id) VALUES(:i)\",\"params\":{\"i\":1}}";

    @Test
    void B11六动作_中文动词标签不进动作码() {
        Flowchart f = FlowchartMermaidAssembler.assemble(
                doc(GOTO + "," + HTTP_REQ + "," + NET_ASSERT + "," + CONSOLE_ASSERT
                        + "," + SQL_ASSERT + "," + SQL_QUERY + "," + SQL_EXEC));

        String m = f.mermaid();
        assertTrue(m.contains("接口调用"), "http.request=接口调用");
        assertTrue(m.contains("网络观测"), "net.assert=网络观测");
        assertTrue(m.contains("数据库校验"), "sql.assert=数据库校验");
        assertTrue(m.contains("数据库取值"), "sql.query=数据库取值（04 §2.1.2 只读取值语义）");
        assertTrue(m.contains("数据操作"), "sql.exec=数据操作");
        assertTrue(m.contains("控制台断言"), "console.assert=控制台断言（TC-C1-01 业务描述非动作码）");
        for (String code : new String[]{"http.request", "net.assert", "console.assert",
                "sql.assert", "sql.query", "sql.exec"}) {
            assertTrue(!m.contains(code), "标签回退了动作码：" + code);
        }
        assertEquals(7, f.stepCount(), "goto+六动作=全层级 7");
    }

    @Test
    void B11非UI节点_虚线样式标注且UI节点不受影响() {
        Flowchart f = FlowchartMermaidAssembler.assemble(
                doc(GOTO + "," + HTTP_REQ + "," + SQL_EXEC));

        String m = f.mermaid();
        assertTrue(m.contains("classDef"), "虚线样式=classDef 定义行（渲染库稳定语法）");
        assertTrue(m.contains("stroke-dasharray"), "classDef 含虚线笔划属性");
        long dashedNodes = m.lines().filter(l -> l.contains(":::")).count();
        assertEquals(2, dashedNodes, "http.request+sql.exec 两非 UI 节点挂虚线类，UI 节点(goto)不挂");
        assertTrue(m.contains("导航") && !m.lines().anyMatch(
                l -> l.contains("导航") && l.contains(":::")), "goto=UI 节点零虚线（TC-C1-01 区分语义）");
    }

    @Test
    void 纯UI脚本_零虚线类定义不冗余() {
        Flowchart f = FlowchartMermaidAssembler.assemble(doc(GOTO + "," + CLICK + "," + EXPECT_OK));

        String m = f.mermaid();
        assertTrue(!m.contains("classDef"), "无 B11 步=不输出 classDef（存量渲染零变化，勿扰旧断言）");
        assertTrue(!m.contains(":::"), "无 B11 步=无节点挂类");
    }

    @Test
    void 损坏输入_人话异常不静默不500() {
        assertThrows(IllegalArgumentException.class,
                () -> FlowchartMermaidAssembler.assemble("not-json"));
        assertThrows(IllegalArgumentException.class,
                () -> FlowchartMermaidAssembler.assemble("123"));
        assertThrows(IllegalArgumentException.class,
                () -> FlowchartMermaidAssembler.assemble("[1,2]"));
    }

    /**
     * Node-to-step mapping contract (B3 UI Flow canvas: clicking a canvas node must jump to the
     * step it projects). Mapping covers action nodes only, in document pre-order; rendering
     * helper nodes (branch labels / join / loop-end) never appear. Format under test:
     * "nodeId:stepIndex" joined by commas.
     */
    private static String nodeMapping(Flowchart f) {
        return f.nodes().stream()
                .map(n -> n.id() + ":" + n.stepIndex())
                .collect(java.util.stream.Collectors.joining(","));
    }

    @Test
    void flatChain_mapsEveryNodeInDocumentOrder() {
        Flowchart f = FlowchartMermaidAssembler.assemble(doc(GOTO + "," + CLICK + "," + EXPECT_OK));

        assertEquals("s1:1,s2:2,s3:3", nodeMapping(f));
        assertEquals(f.stepCount(), f.nodes().size(), "mapping size must equal stepCount");
    }

    @Test
    void ifElseChain_mapsDecisionAndBranchSteps_excludesHelperNodes() {
        Flowchart f = FlowchartMermaidAssembler.assemble(
                doc(GOTO + "," + ifStep(CLICK, EXPECT_OK) + "," + CLICK));

        // creation order: s1 goto, s2 decision, s3 join(helper), s4 branch label(helper),
        // s5 then-step, s6 else-label(helper), s7 else-step, s8 trailing step
        assertEquals("s1:1,s2:2,s5:3,s7:4,s8:5", nodeMapping(f));
        assertEquals(f.stepCount(), f.nodes().size(), "mapping size must equal stepCount");
    }

    @Test
    void loopStep_mapsLoopContainerAndBody_excludesLoopEndHelper() {
        String loop = "{\"action\":\"loop\",\"count\":3,\"steps\":[" + CLICK + "]}";
        Flowchart f = FlowchartMermaidAssembler.assemble(doc(loop + "," + EXPECT_OK));

        // creation order: s1 loop container, s2 body step, s3 loop-end(helper), s4 trailing step
        assertEquals("s1:1,s2:2,s4:3", nodeMapping(f));
        assertEquals(f.stepCount(), f.nodes().size(), "mapping size must equal stepCount");
    }

    @Test
    void emptySteps_placeholderHasNoMapping() {
        Flowchart f = FlowchartMermaidAssembler.assemble(doc(""));

        assertEquals(0, f.nodes().size(), "placeholder text node is not an action step");
        assertEquals(0, f.stepCount());
    }
}
