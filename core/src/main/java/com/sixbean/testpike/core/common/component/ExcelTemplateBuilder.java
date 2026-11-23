package com.sixbean.testpike.core.common.component;

import com.alibaba.excel.EasyExcel;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel import template builder shared by legacy import and user import with a strict prohibition on duplicate logic.
 * <p>Empty template generation includes the header and one example row, with the first column showing "Example: column name" and the remaining columns empty for direct filling after download.
 *
 */
public final class ExcelTemplateBuilder {

    private ExcelTemplateBuilder() {
    }

    /** Empty template generation includes the header and one example row, with columns matching the export template as specified in PRD 4.2.5. */
    public static byte[] build(List<String> headers, String sheetName) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        List<List<String>> head = headers.stream().map(List::of).toList();
        List<List<Object>> example = new ArrayList<>();
        List<Object> exampleRow = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            exampleRow.add(i == 0 ? "示例：" + headers.get(i) : "");
        }
        example.add(exampleRow);
        EasyExcel.write(baos).head(head).sheet(sheetName).doWrite(example);
        return baos.toByteArray();
    }
}
