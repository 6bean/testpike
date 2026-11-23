package com.sixbean.testpike.core.reportcenter.domain.component;

import com.sixbean.testpike.core.common.domain.component.ExcelExportSupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;


public final class ReportCsvWriter {

    
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private ReportCsvWriter() {
    }

    
    public static byte[] toCsv(List<List<Object>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(UTF8_BOM);
            for (List<Object> row : rows) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) {
                        line.append(',');
                    }
                    line.append(quoteCell(ExcelExportSupport.cellText(row.get(i))));
                }
                out.write(line.toString().getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("CSV 生成失败", e);
        }
        return out.toByteArray();
    }

    
    private static String quoteCell(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
