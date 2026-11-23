package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * RFC4180 CSV reader for perf dataset uploads .
 * <p>One parser for the whole feature: upload validation and read-back preview
 * both go through here so a file accepted once can never fail to be previewed
 * later (single source, no second dialect). Handles UTF-8 BOM, LF/CRLF/CR
 * terminators, quoted cells with embedded commas/newlines and doubled-quote
 * escaping.
 * <p>Domain guards applied at parse time with human-readable refusals: header
 * present with safe identifier column names (they double as {{VAR}} names in
 * scenario steps), no duplicated columns case-insensitively, every data row
 * exactly as wide as the header, at least one data row, and a live row budget
 * passed in by the caller (K8 hot-updatable — the parser hardcodes no limit).
 */
@Component
public class PerfCsvParser {

    /** Column names reused as variable slots: identifier-shaped, ASCII-safe. */
    private static final Pattern COLUMN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    /**
     * Parse and validate one CSV document.
     *
     * @param content raw file bytes (UTF-8, BOM tolerated)
     * @param maxRows row budget for data rows (&lt;1 clamps to 1; pass
     *                Integer.MAX_VALUE on read-back paths where the budget was
     *                already enforced at upload time)
     * @return validated header + data rows
     * @throws ServiceException naming the offending column/line so the uploader can fix it
     */
    public PerfCsvData parse(byte[] content, int maxRows) {
        List<List<String>> lines = readLines(new String(
                stripBomBytes(content), StandardCharsets.UTF_8));
        if (lines.isEmpty()) {
            throw new ServiceException("CSV 文件缺少表头行：第一行必须是列名", BizErrorCode.VALIDATION_ERROR);
        }
        List<String> columns = validateHeader(lines.get(0));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> row = lines.get(i);
            if (row.size() != columns.size()) {
                throw new ServiceException("CSV 数据第 " + i + " 行有 " + row.size()
                        + " 列，与表头 " + columns.size() + " 列不符（文件第 " + (i + 1)
                        + " 行），请核对引号与逗号", BizErrorCode.VALIDATION_ERROR);
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new ServiceException("CSV 只有表头没有数据行：至少需要一行测试数据",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int budget = Math.max(maxRows, 1);
        if (rows.size() > budget) {
            throw new ServiceException("CSV 数据行数 " + rows.size() + " 超过数据集行数上限 " + budget
                    + " 行，请拆分文件或调整系统配置 perf.dataset.max-rows",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new PerfCsvData(columns, rows);
    }

    /** Header shape check: non-empty cells, safe identifiers, case-insensitive uniqueness. */
    private List<String> validateHeader(List<String> rawHeader) {
        List<String> columns = new ArrayList<>(rawHeader.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String cell : rawHeader) {
            String name = cell.trim();
            if (name.isEmpty()) {
                throw new ServiceException("CSV 表头存在空列名，请为每一列填写名称（列名将用作步骤变量名）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (!COLUMN_NAME.matcher(name).matches()) {
                throw new ServiceException("CSV 列名「" + name + "」不合法：只允许字母/数字/下划线，"
                        + "且不能以数字开头（列名将用作步骤变量名）", BizErrorCode.VALIDATION_ERROR);
            }
            if (!seen.add(name.toLowerCase())) {
                throw new ServiceException("CSV 表头列名「" + name + "」重复，数据集内列名必须唯一",
                        BizErrorCode.VALIDATION_ERROR);
            }
            columns.add(name);
        }
        return columns;
    }

    /**
     * Single-pass RFC4180 state machine producing physical rows. Newlines and
     * commas inside quotes belong to the cell; a doubled quote is a literal
     * quote. Trailing blank lines are dropped so a final newline never mints
     * a ghost row; an unclosed quote is refused with its line number.
     */
    private List<List<String>> readLines(String text) {
        List<List<String>> result = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasContent = false;
        int lineNo = 1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                if (c == '\n') {
                    lineNo++;
                }
                cell.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                rowHasContent = true;
                i++;
                continue;
            }
            if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                rowHasContent = true;
                i++;
                continue;
            }
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (rowHasContent || cell.length() > 0 || !row.isEmpty()) {
                    row.add(cell.toString());
                    result.add(new ArrayList<>(row));
                }
                row = new ArrayList<>();
                cell.setLength(0);
                rowHasContent = false;
                lineNo++;
                i++;
                continue;
            }
            cell.append(c);
            rowHasContent = true;
            i++;
        }
        if (inQuotes) {
            throw new ServiceException("CSV 第 " + lineNo + " 行附近有未闭合的双引号，请检查引号配对",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (rowHasContent || cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            result.add(new ArrayList<>(row));
        }
        return result;
    }

    /** BOM removal before UTF-8 decode (Excel re-saves CSVs with a leading BOM). */
    private byte[] stripBomBytes(byte[] content) {
        if (content == null) {
            throw new ServiceException("CSV 文件内容不能为空", BizErrorCode.VALIDATION_ERROR);
        }
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            byte[] rest = new byte[content.length - 3];
            System.arraycopy(content, 3, rest, 0, rest.length);
            return rest;
        }
        return content;
    }
}
