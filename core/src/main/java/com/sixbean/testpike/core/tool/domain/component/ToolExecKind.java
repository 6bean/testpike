package com.sixbean.testpike.core.tool.domain.component;


public enum ToolExecKind {

    HTTP(1, "HTTP回调"),
    EXECUTOR(2, "EXECUTOR沙箱"),
    MCP_BRIDGE(3, "MCP_BRIDGE转发"),
    GENKIT(4, "GENKIT造数函数"),
    ANALYSIS(5, "只读分析函数");

    private final int code;
    private final String label;

    ToolExecKind(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    
    public static ToolExecKind of(int code) {
        for (ToolExecKind k : values()) {
            if (k.code == code) {
                return k;
            }
        }
        return null;
    }

    
    public static String labelOf(int code) {
        ToolExecKind k = of(code);
        return k == null ? String.valueOf(code) : k.label();
    }

    
    public static String allLabels() {
        StringBuilder sb = new StringBuilder();
        for (ToolExecKind k : values()) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(k.label());
        }
        return sb.toString();
    }
}
