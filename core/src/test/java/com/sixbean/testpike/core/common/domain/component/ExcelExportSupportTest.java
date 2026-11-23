package com.sixbean.testpike.core.common.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ExcelExportSupportTest {


    @Test
    void escapeFormula_危险前缀全部转义为字面文本() {
        assertEquals("'=1+1", ExcelExportSupport.escapeFormula("=1+1"));
        assertEquals("'+SUM(A1)", ExcelExportSupport.escapeFormula("+SUM(A1)"));
        assertEquals("'-cmd", ExcelExportSupport.escapeFormula("-cmd"));
        assertEquals("'@SUM", ExcelExportSupport.escapeFormula("@SUM"));
        assertEquals("'\tTAB", ExcelExportSupport.escapeFormula("\tTAB"));
        assertEquals("'\rCR", ExcelExportSupport.escapeFormula("\rCR"));
    }

    @Test
    void escapeFormula_正常文本与空值原样返回() {
        assertEquals("登录成功", ExcelExportSupport.escapeFormula("登录成功"));
        assertEquals("中间等号不转义", ExcelExportSupport.escapeFormula("中间等号不转义"));
        assertEquals("", ExcelExportSupport.escapeFormula(""));
        assertEquals(null, ExcelExportSupport.escapeFormula(null));
    }

    @Test
    void cellText_null归一空串且逐格转义() {
        assertEquals("", ExcelExportSupport.cellText(null));
        assertEquals("'=evil", ExcelExportSupport.cellText("=evil"));
        assertEquals("3", ExcelExportSupport.cellText(3));
    }


    @Test
    void sanitizeFileName_剥离目录与控制字符防头注入() {
        assertEquals("evil.xlsx", ExcelExportSupport.sanitizeFileName("../../etc/evil.xlsx"));
        assertEquals("evil.xlsx", ExcelExportSupport.sanitizeFileName("..\\..\\win\\evil.xlsx"));
        String cleaned = ExcelExportSupport.sanitizeFileName("a\";b.xlsx");
        assertEquals("a__b.xlsx", cleaned, "引号/分号必须替换: " + cleaned);
        assertEquals("a_b.xlsx", ExcelExportSupport.sanitizeFileName("a\nb.xlsx"));
        assertEquals("export.xlsx", ExcelExportSupport.sanitizeFileName(null));
        assertEquals("export.xlsx", ExcelExportSupport.sanitizeFileName("  "));
        assertEquals("用例导出_20260904.xlsx", ExcelExportSupport.sanitizeFileName("用例导出_20260904.xlsx"));
    }


    @Test
    void toExcel_写出读回单元格含转义前缀() {
        byte[] bytes = ExcelExportSupport.toExcel("测试[表]*名", List.of("列A", "列B"),
                List.<List<Object>>of(List.<Object>of("=HYPERLINK(\"x\")", "正常值")));
        assertTrue(bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K', "xlsx PK 魔数");
        List<java.util.Map<Integer, String>> rows = com.alibaba.excel.EasyExcel
                .read(new java.io.ByteArrayInputStream(bytes)).headRowNumber(0).sheet().doReadSync();
        assertEquals(2, rows.size(), "表头+数据行");
        assertEquals("'=HYPERLINK(\"x\")", String.valueOf(rows.get(1).get(0)), "读回必须含转义前缀");
        assertEquals("正常值", String.valueOf(rows.get(1).get(1)), "正常值原样写出");
    }
}
