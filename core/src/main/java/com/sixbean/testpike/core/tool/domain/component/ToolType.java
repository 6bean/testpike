package com.sixbean.testpike.core.tool.domain.component;


public enum ToolType {

    DOC(1, "文档类"),
    EXECUTABLE(2, "可执行");

    private final int code;
    private final String label;

    ToolType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    
    public static ToolType of(int code) {
        for (ToolType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }

    
    public static String labelOf(int code) {
        ToolType t = of(code);
        return t == null ? String.valueOf(code) : t.label();
    }

    
    public static String allLabels() {
        StringBuilder sb = new StringBuilder();
        for (ToolType t : values()) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(t.label());
        }
        return sb.toString();
    }
}
