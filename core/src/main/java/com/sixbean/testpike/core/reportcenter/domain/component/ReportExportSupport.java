package com.sixbean.testpike.core.reportcenter.domain.component;

import com.sixbean.testpike.core.common.domain.component.ExcelExportSupport;
import com.sixbean.testpike.core.common.domain.component.PdfHtmlRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public final class ReportExportSupport {

    
    private static final String CSS_LIGHT = """
            body { font-family: "Noto Sans SC", sans-serif; font-size: 12px; color: #1f2d3d; margin: 24px; background: #ffffff; }
            h1 { font-size: 20px; text-align: center; }
            h2 { font-size: 14px; border-left: 4px solid #3370ff; padding-left: 8px; margin-top: 20px; }
            table { border-collapse: collapse; width: 100%; margin-top: 8px; }
            th, td { border: 1px solid #c0c4cc; padding: 6px 10px; text-align: left; }
            th { background: #eef2ff; }
            .meta { color: #606266; font-size: 11px; text-align: center; }
            """;

    
    private static final String CSS_DARK = """
            body { font-family: "Noto Sans SC", sans-serif; font-size: 12px; color: #e5eaf3; margin: 24px; background: #141a26; }
            h1 { font-size: 20px; text-align: center; color: #ffffff; }
            h2 { font-size: 14px; border-left: 4px solid #4c8dff; padding-left: 8px; margin-top: 20px; }
            table { border-collapse: collapse; width: 100%; margin-top: 8px; }
            th, td { border: 1px solid #33415e; padding: 6px 10px; text-align: left; }
            th { background: #1d2739; color: #9db4dd; }
            .meta { color: #8a94a6; font-size: 11px; text-align: center; }
            """;

    private ReportExportSupport() {
    }

    
    public static List<List<Object>> csvRows(List<Object> meta,
                                             List<List<Object>> scalars,
                                             List<List<Object>> dists) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("── 报表元信息 ──"));
        rows.add(meta);
        rows.add(List.of());
        rows.add(List.of("── 指标（标量口径）──"));
        rows.add(List.of("口径键", "指标", "口径版本", "值"));
        rows.addAll(scalars);
        if (!dists.isEmpty()) {
            rows.add(List.of());
            rows.add(List.of("── 分布明细 ──"));
            rows.add(List.of("口径键", "指标", "口径版本", "分类", "计数"));
            rows.addAll(dists);
        }
        return rows;
    }

    
    public static String csvFileName(String base) {
        return ExcelExportSupport.sanitizeFileName(base + ".csv");
    }

    
    public static String pdfFileName(String base) {
        return ExcelExportSupport.sanitizeFileName(base + ".pdf");
    }

    
    public static byte[] toPdf(String title, Map<String, String> meta,
                               List<List<Object>> scalars,
                               Map<String, List<List<Object>>> dists, boolean dark) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"UTF-8\"/><style>")
                .append(dark ? CSS_DARK : CSS_LIGHT)
                .append("</style></head><body>");
        sb.append("<h1>").append(esc(title)).append("</h1>");
        sb.append("<table>");
        for (Map.Entry<String, String> e : meta.entrySet()) {
            sb.append("<tr><th>").append(esc(e.getKey())).append("</th><td>")
                    .append(esc(e.getValue())).append("</td></tr>");
        }
        sb.append("</table><h2>指标（口径注册表快照）</h2>");
        sb.append("<table><tr><th>指标</th><th>口径版本</th><th>值</th></tr>");
        for (List<Object> row : scalars) {
            sb.append("<tr>");
            for (Object cell : row) {
                sb.append("<td>").append(esc(String.valueOf(cell))).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        for (Map.Entry<String, List<List<Object>>> e : dists.entrySet()) {
            sb.append("<h2>").append(esc(e.getKey())).append("</h2>");
            sb.append("<table><tr><th>分类</th><th>计数</th></tr>");
            for (List<Object> row : e.getValue()) {
                sb.append("<tr><td>").append(esc(String.valueOf(row.get(0))))
                        .append("</td><td>").append(esc(String.valueOf(row.get(1))))
                        .append("</td></tr>");
            }
            sb.append("</table>");
        }
        sb.append("</body></html>");
        return PdfHtmlRenderer.render(sb.toString());
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
