package com.sixbean.testpike.core.common.domain.component;

import com.alibaba.excel.EasyExcel;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel export shared component: defect export must reuse the shared component within the batch, and copying is prohibited.
 * <p>Responsibilities: 1) generate EasyExcel bytes (header plus data rows, with per-cell formula escaping); 2) escape formula injection.
 * CSV/Excel formula injection protection applies to text cells starting with {@code =}, {@code +}, {@code -}, {@code @}, tab, or carriage return.
 * Prepend a single quote to text cells starting with those characters to force literal text and prevent execution as formulas when opened; 3) filename sanitization.
 * Strip path separators and control characters to prevent Content-Disposition filename injection.
 *
 */
public final class ExcelExportSupport {

    /** Formula injection trigger prefixes: text starting with these characters may be parsed as formulas by Excel/CSV. */
    private static final char[] FORMULA_PREFIXES = {'=', '+', '-', '@', '\t', '\r'};

    private ExcelExportSupport() {
    }

    /**
     * Generate xlsx bytes (header row plus data rows, with automatic per-cell formula escaping of cell values).
     *
     * @param sheetName sheet name, internally sanitized for Excel illegal characters `[]:*?/\`.
     * @param headers column headers.
     * @param rows data rows (cells normalized by {@link #cellText}: null to empty string, formula injection characters escaped).
     * @return xlsx file bytes.
     */
    public static byte[] toExcel(String sheetName, List<String> headers, List<List<Object>> rows) {
        List<List<String>> head = new ArrayList<>();
        for (String h : headers) {
            head.add(List.of(h));
        }
        List<List<Object>> data = new ArrayList<>();
        for (List<Object> row : rows) {
            List<Object> escaped = new ArrayList<>(row.size());
            for (Object cell : row) {
                escaped.add(cellText(cell));
            }
            data.add(escaped);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out).head(head).sheet(sanitizeSheetName(sheetName)).doWrite(data);
        return out.toByteArray();
    }

    /**
     * Normalize cell values to escaped text: null becomes an empty string; other values are converted with toString and passed through {@link #escapeFormula}.
     * Write numbers, dates, and other non-strings uniformly as text to keep export column display consistent and avoid Excel automatic formatting ambiguity.
     */
    public static String cellText(Object value) {
        return value == null ? "" : escapeFormula(String.valueOf(value));
    }

    /**
 * Escape formula injection: text starting with =, +, -, @, tab, or carriage return is prefixed with a single quote to force literal text.
     *
     * @param value original cell text.
     * @return escaped text (returned unchanged when escaping is not needed).
     */
    public static String escapeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        for (char prefix : FORMULA_PREFIXES) {
            if (first == prefix) {
                return "'" + value;
            }
        }
        return value;
    }

    /**
     * Sanitize filenames by stripping directory separators and control characters, retaining only safe characters, and replacing the rest with underscores.
 * Prevent Content-Disposition header injection and path traversal by sanitizing filename injection characters.
     *
     * @param fileName original filename.
     * @return safe filename.
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "export.xlsx";
        }
        // Use only the last path component by stripping any directory prefix, then replace illegal or control characters with underscores.
        String base = fileName.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c < 0x20 || c == 0x7f || "\"<>|?*:;@=&".indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Sanitize sheet names: Excel forbids `[]:*?/\` and limits length to 31 characters. */
    private static String sanitizeSheetName(String sheetName) {
        if (sheetName == null || sheetName.isBlank()) {
            return "Sheet1";
        }
        String cleaned = sheetName.replaceAll("[\\[\\]:*?/\\\\]", "_");
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }
}
