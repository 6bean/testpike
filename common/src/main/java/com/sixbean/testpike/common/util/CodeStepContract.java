package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;


public final class CodeStepContract {

    
    public static final String LANGUAGE_PYTHON = "python";

    
    public static final long TIMEOUT_MIN_MS = 1;

    
    public static final long TIMEOUT_MAX_MS = 30_000;

    private CodeStepContract() {
    }

    
    public static String violation(JsonNode step) {
        String languageViolation = languageViolation(step);
        if (languageViolation != null) {
            return languageViolation;
        }
        String code = step.path("code").asText("");
        String sandboxViolation = sandboxViolation(code);
        if (sandboxViolation != null) {
            return sandboxViolation;
        }
        return timeoutViolation(step.get("timeoutMs"));
    }

    
    private static String languageViolation(JsonNode step) {
        JsonNode language = step.get("language");
        if (language == null || language.isNull() || language.asText().isBlank()) {
            return "代码步骤还差语言选项：请在步骤表单选择语言（当前仅支持 " + LANGUAGE_PYTHON + "）";
        }
        String value = language.asText().trim();
        if (LANGUAGE_PYTHON.equalsIgnoreCase(value)) {
            return null;
        }
        if ("js".equalsIgnoreCase(value) || "javascript".equalsIgnoreCase(value)) {
            return "code.step 语言「" + value + "」沙箱暂未开放（B8 定版口径=受限 Python 片段，"
                    + "js 片段请改用既有动作原语表达）";
        }
        return "code.step 语言「" + value + "」不在支持列表（当前仅 " + LANGUAGE_PYTHON + "）";
    }

    
    private static String sandboxViolation(String code) {
        if (code.isBlank()) {
            return "代码步骤还没有代码正文：请在步骤编辑器的代码框填写内容";
        }
        for (CodeStepForbiddenCategory category : FORBIDDEN_CATEGORIES) {
            String hit = category.firstHit(code);
            if (hit != null) {
                return "code.step 代码命中沙箱禁制「" + category.label() + "」（命中 " + hit
                        + "）——自定义代码步骤默认禁网/禁子进程/禁文件访问（IT-B8-01 三禁默认），"
                        + "请改用既有动作原语（http.request/sql.query/expect）表达";
            }
        }
        return null;
    }

    
    private static String timeoutViolation(JsonNode timeoutMs) {
        if (timeoutMs == null || timeoutMs.isNull()) {
            return null;
        }
        if (!timeoutMs.isNumber() || timeoutMs.asLong() < TIMEOUT_MIN_MS
                || timeoutMs.asLong() > TIMEOUT_MAX_MS) {
            return "code.step 的 timeoutMs 非法（" + timeoutMs.asText() + "）——须为 "
                    + TIMEOUT_MIN_MS + "-" + TIMEOUT_MAX_MS + " 毫秒（与工具试跑沙箱的时长上限一致）";
        }
        return null;
    }

    
    private static final List<CodeStepForbiddenCategory> FORBIDDEN_CATEGORIES = List.of(
            new CodeStepForbiddenCategory("网络访问", "socket", "requests", "urllib", "urllib2",
                    "http\\.client", "ftplib", "smtplib", "telnetlib"),
            new CodeStepForbiddenCategory("子进程派生", "subprocess", "multiprocessing",
                    "os\\.system", "os\\.popen", "os\\.fork", "os\\.execv", "Popen"),
            new CodeStepForbiddenCategory("文件访问", "shutil", "pathlib", "os\\.remove", "os\\.mkdir",
                    "os\\.rename", "os\\.unlink", "\\bopen\\s*\\("),
            new CodeStepForbiddenCategory("沙箱逃逸", "eval", "exec", "compile", "__import__",
                    "importlib", "builtins", "globals", "locals"));

}
