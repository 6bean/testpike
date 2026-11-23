package com.sixbean.testpike.core.document.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class DocDimensionSuggesterTest {

    private final DocDimensionSuggester suggester = new DocDimensionSuggester();

    @Test
    void emptyPreview_honestlySuggestsAllThreeDimensions() {
        assertEquals(List.of(DocDimensionSuggester.EXCEPTION_FLOW,
                DocDimensionSuggester.BOUNDARY,
                DocDimensionSuggester.NON_FUNCTIONAL),
                suggester.suggest(List.of()));
    }

    @Test
    void blankItemTexts_treatedAsNothingPresented() {
        assertEquals(3, suggester.suggest(java.util.Arrays.asList("", "   ", null)).size(),
                "空白/null 文本不得把维度误判为已呈现");
    }

    @Test
    void exceptionFlowKeywordHit_removesOnlyExceptionDimension() {
        List<String> missing = suggester.suggest(
                List.of("支付失败重试提示|连续三次失败进入错误页并给出人话原因"));
        assertEquals(List.of(DocDimensionSuggester.BOUNDARY,
                DocDimensionSuggester.NON_FUNCTIONAL), missing);
    }

    @Test
    void boundaryKeywordHit_removesOnlyBoundaryDimension() {
        List<String> missing = suggester.suggest(
                List.of("备注长度上限|备注最多输入500字符，超长禁止提交"));
        assertEquals(List.of(DocDimensionSuggester.EXCEPTION_FLOW,
                DocDimensionSuggester.NON_FUNCTIONAL), missing);
    }

    @Test
    void nonFunctionalKeywordHit_removesOnlyNonFunctionalDimension() {
        List<String> missing = suggester.suggest(
                List.of("列表加载性能|首屏加载响应时间不超过2秒"));
        assertEquals(List.of(DocDimensionSuggester.EXCEPTION_FLOW,
                DocDimensionSuggester.BOUNDARY), missing);
    }

    @Test
    void allDimensionsPresented_returnsEmptyNoNoise() {
        List<String> missing = suggester.suggest(List.of(
                "支付失败重试提示|连续三次失败进入错误页并给出人话原因",
                "备注长度上限|备注最多输入500字符，超长禁止提交",
                "列表响应时间|首屏加载响应时间不超过2秒"));
        assertTrue(missing.isEmpty(), "三型全覆盖时不得再建议（缺才提示）");
    }

    @Test
    void multiItemUnion_coversDimensionAcrossItems() {
        List<String> missing = suggester.suggest(List.of(
                "下单异常处理|库存不足抛出异常并提示",
                "金额精度|金额输入最多两位小数，超出四舍五入"));
        assertEquals(List.of(DocDimensionSuggester.NON_FUNCTIONAL), missing);
    }
}
