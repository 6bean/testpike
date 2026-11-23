package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class SqlStatementContract {

    
    public static final Set<String> READ_ONLY_FIRST_WORDS = Set.of("SELECT");

    
    public static final Set<String> EXEC_FIRST_WORDS = Set.of("INSERT", "UPDATE", "DELETE");

    
    public static final Set<String> EXEC_FORBIDDEN_TOKENS =
            Set.of("DROP", "TRUNCATE", "ALTER", "GRANT", "REVOKE", "CREATE");

    private static final String EXEC_ACTION = "sql.exec";

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Pattern NAMED_PARAM = Pattern.compile("(?<!:):([A-Za-z_]\\w*)");

    private static final Pattern FORBIDDEN_TOKEN = Pattern.compile(
            "\\b(" + String.join("|", EXEC_FORBIDDEN_TOKENS) + ")\\b");

    private static final Pattern WHERE_CLAUSE = Pattern.compile("\\bWHERE\\b");

    private SqlStatementContract() {
    }

    /**
 *
 * @param action sql.assert / sql.query / sql.exec
 */
    public static String violation(String action, String sql, JsonNode params) {
        if (sql == null || sql.isBlank()) {
            return action + " 必须给定 sql（SQL 文本不得为空）";
        }
        String concatViolation = noInterpolationViolation(sql);
        if (concatViolation != null) {
            return concatViolation;
        }
        String stripped = stripLiteralsAndComments(sql);
        List<String> statements = splitStatements(stripped);
        if (statements.isEmpty()) {
            return action + " 的 sql 无有效语句（仅注释/空白）";
        }
        if (statements.size() > 1) {
            return "一次 sql 只允许一条语句（检测到 " + statements.size()
                    + " 条，分号拆句）——多语句可绕过黑名单词法闸，请拆成多步";
        }
        String statement = statements.get(0);
        String firstViolation = firstWordViolation(action, statement);
        if (firstViolation != null) {
            return firstViolation;
        }
        if (EXEC_ACTION.equals(action)) {
            String execViolation = execOnlyViolation(statement);
            if (execViolation != null) {
                return execViolation;
            }
        }
        return bindingViolation(statement, params);
    }

    
    public static String noInterpolationViolation(String sql) {
        if (sql.contains("{{")) {
            return "SQL 文本禁内插占位符（检测到 {{）——取值请用 params 具名绑定 :name，"
                    + "连接名占位符只写在 conn 字段";
        }
        if (sql.contains("${")) {
            return "SQL 文本禁内插占位符（检测到 ${）——取值请用 params 具名绑定 :name";
        }
        return null;
    }

    

    
    public static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i) == quote) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? sql.length() : eol;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    
    private static List<String> splitStatements(String stripped) {
        List<String> result = new ArrayList<>();
        for (String piece : stripped.split(";")) {
            if (!piece.isBlank()) {
                result.add(piece.trim());
            }
        }
        return result;
    }

    private static String firstWordViolation(String action, String statement) {
        Set<String> allowed = EXEC_ACTION.equals(action) ? EXEC_FIRST_WORDS : READ_ONLY_FIRST_WORDS;
        Matcher matcher = WORD.matcher(statement);
        String first = matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : "";
        if (allowed.contains(first)) {
            return null;
        }
        return action + " 的 SQL 首关键字仅支持 " + String.join(" / ", allowed.stream().sorted().toList())
                + "，当前为「" + (first.isEmpty() ? "(无关键字)" : first) + "」";
    }

    private static String execOnlyViolation(String statement) {
        String upper = statement.toUpperCase(Locale.ROOT);
        Matcher forbidden = FORBIDDEN_TOKEN.matcher(upper);
        if (forbidden.find()) {
            return "sql.exec 禁用 " + forbidden.group(1)
                    + " 等 DDL/权限语句（永久黑名单，仅允许 INSERT / UPDATE / DELETE 受控 DML）";
        }
        String firstWord = firstWordOf(statement);
        if (("UPDATE".equals(firstWord) || "DELETE".equals(firstWord))
                && !WHERE_CLAUSE.matcher(upper).find()) {
            return firstWord + " 语句必须带 WHERE 条件（无 WHERE 的全表变更已拒——防误清生产/测试数据）";
        }
        return null;
    }

    private static String firstWordOf(String statement) {
        Matcher matcher = WORD.matcher(statement);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : "";
    }

    private static String bindingViolation(String statement, JsonNode params) {
        Matcher matcher = NAMED_PARAM.matcher(statement);
        List<String> missing = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            boolean bound = params != null && params.isObject() && params.has(name);
            if (!bound && !missing.contains(name)) {
                missing.add(name);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        return "SQL 中的具名参数 :" + String.join(" / :", missing)
                + " 在 params 中缺少绑定值（所有 :name 必须在 params 给出，禁字面量拼接）";
    }
}
